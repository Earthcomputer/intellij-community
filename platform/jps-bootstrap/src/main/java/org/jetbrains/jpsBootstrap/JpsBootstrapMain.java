// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.jpsBootstrap;

import com.google.common.hash.Hashing;
import com.intellij.execution.CommandLineWrapperUtil;
import com.intellij.openapi.diagnostic.IdeaLogRecordFormatter;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.Strings;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.containers.ContainerUtil;
import jetbrains.buildServer.messages.serviceMessages.MessageWithAttributes;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes;
import org.apache.commons.cli.*;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.intellij.build.dependencies.BuildDependenciesCommunityRoot;
import org.jetbrains.intellij.build.dependencies.BuildDependenciesLogging;
import org.jetbrains.intellij.build.dependencies.JdkDownloader;
import org.jetbrains.intellij.build.dependencies.TeamCityHelper;
import org.jetbrains.jps.incremental.storage.ProjectStamps;
import org.jetbrains.jps.model.JpsModel;
import org.jetbrains.jps.model.module.JpsModule;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.stream.Collectors;

import static org.jetbrains.intellij.build.dependencies.BuildDependenciesLogging.*;
import static org.jetbrains.jpsBootstrap.JpsBootstrapUtil.getJpsArtifactsResolutionRetryProperties;
import static org.jetbrains.jpsBootstrap.JpsBootstrapUtil.getTeamCitySystemProperties;
import static org.jetbrains.jpsBootstrap.JpsBootstrapUtil.toBooleanChecked;

@SuppressWarnings({"SameParameterValue"})
public class JpsBootstrapMain {
  private static final String DEFAULT_BUILD_SCRIPT_XMX = "4g";

  private static final String COMMUNITY_HOME_ENV = "JPS_BOOTSTRAP_COMMUNITY_HOME";
  private static final String JPS_BOOTSTRAP_VERBOSE = "JPS_BOOTSTRAP_VERBOSE";

  private static final Option OPT_HELP = Option.builder("h").longOpt("help").build();
  private static final Option OPT_VERBOSE = Option.builder("v").longOpt("verbose").desc("Show more logging from jps-bootstrap and the building process").build();
  private static final Option OPT_SYSTEM_PROPERTY = Option.builder("D").hasArgs().valueSeparator('=').desc("Pass system property to the build script").build();
  private static final Option OPT_PROPERTIES_FILE = Option.builder().longOpt("properties-file").hasArg().desc("Pass system properties to the build script from specified properties file https://en.wikipedia.org/wiki/.properties").build();
  private static final Option OPT_BUILD_TARGET_XMX = Option.builder().longOpt("build-target-xmx").hasArg().desc("Specify Xmx to run build script. default: " + DEFAULT_BUILD_SCRIPT_XMX).build();
  private static final Option OPT_JAVA_ARGFILE_TARGET = Option.builder().longOpt("java-argfile-target").hasArg().desc("Write java argfile to this file").build();
  private static final Option OPT_ONLY_DOWNLOAD_JDK = Option.builder().longOpt("download-jdk").desc("Download project JDK and exit").build();
  private static final List<Option> ALL_OPTIONS =
    Arrays.asList(OPT_HELP, OPT_VERBOSE, OPT_SYSTEM_PROPERTY, OPT_PROPERTIES_FILE, OPT_JAVA_ARGFILE_TARGET, OPT_BUILD_TARGET_XMX, OPT_ONLY_DOWNLOAD_JDK);

  static final boolean underTeamCity = TeamCityHelper.INSTANCE.isUnderTeamCity();

  private static Options createCliOptions() {
    Options opts = new Options();

    for (Option option : ALL_OPTIONS) {
      opts.addOption(option);
    }

    return opts;
  }

  public static void main(String[] args) {
    Path jpsBootstrapWorkDir = null;

    try {
      JpsBootstrapMain mainInstance = new JpsBootstrapMain(args);
      jpsBootstrapWorkDir = mainInstance.jpsBootstrapWorkDir;
      mainInstance.main();
      System.exit(0);
    }
    catch (Throwable t) {
      fatal(ExceptionUtil.getThrowableText(t));

      // Better diagnostics for local users
      if (!TeamCityHelper.INSTANCE.isUnderTeamCity()) {
        System.err.println("\n###### ERROR EXIT due to FATAL error: " + t.getMessage() + "\n");
        String work = jpsBootstrapWorkDir == null ? "PROJECT_HOME/build/jps-bootstrap-work" : jpsBootstrapWorkDir.toString();
        System.err.println("###### You may try to delete caches at " + work + " and retry");
      }

      System.exit(1);
    }
  }

  private final Path projectHome;
  private final BuildDependenciesCommunityRoot communityHome;
  private final String moduleNameToRun;
  private final String classNameToRun;
  private final String buildTargetXmx;
  private final Path jpsBootstrapWorkDir;
  private final Path javaArgsFileTarget;
  private final List<String> mainArgsToRun;
  private final Properties additionalSystemProperties;
  private final Properties additionalSystemPropertiesFromPropertiesFile;
  private final boolean onlyDownloadJdk;

  public JpsBootstrapMain(String[] args) throws IOException {
    initLogging();

    CommandLine cmdline;
    try {
      cmdline = (new DefaultParser()).parse(createCliOptions(), args, true);
    }
    catch (ParseException e) {
      e.printStackTrace();
      showUsagesAndExit();
      throw new IllegalStateException("NOT_REACHED");
    }

    final List<String> freeArgs = Arrays.asList(cmdline.getArgs());
    if (cmdline.hasOption(OPT_HELP) || freeArgs.size() < 1) {
      showUsagesAndExit();
    }

    projectHome = Path.of(freeArgs.get(0)).normalize();
    onlyDownloadJdk = cmdline.hasOption(OPT_ONLY_DOWNLOAD_JDK);
    if (onlyDownloadJdk) {
      moduleNameToRun = null;
      classNameToRun = null;
      mainArgsToRun = Collections.emptyList();
      javaArgsFileTarget = null;
    }
    else {
      moduleNameToRun = freeArgs.get(1);
      classNameToRun = freeArgs.get(2);
      mainArgsToRun = freeArgs.subList(3, freeArgs.size());
      javaArgsFileTarget = Path.of(cmdline.getOptionValue(OPT_JAVA_ARGFILE_TARGET));
    }

    additionalSystemProperties = cmdline.getOptionProperties("D");

    additionalSystemPropertiesFromPropertiesFile = new Properties();
    if (cmdline.hasOption(OPT_PROPERTIES_FILE)) {
      Path propertiesFile = Path.of(cmdline.getOptionValue(OPT_PROPERTIES_FILE));
      try (Reader reader = Files.newBufferedReader(propertiesFile)) {
        info("Loading properties from " + propertiesFile);
        additionalSystemPropertiesFromPropertiesFile.load(reader);
      }
    }

    String verboseEnv = System.getenv(JPS_BOOTSTRAP_VERBOSE);
    BuildDependenciesLogging.setVerboseEnabled(cmdline.hasOption(OPT_VERBOSE) || (verboseEnv != null && toBooleanChecked(verboseEnv)));

    String communityHomeString = System.getenv(COMMUNITY_HOME_ENV);
    if (communityHomeString == null) {
      throw new IllegalStateException("Please set " + COMMUNITY_HOME_ENV + " environment variable");
    }

    communityHome = new BuildDependenciesCommunityRoot(Path.of(communityHomeString));
    jpsBootstrapWorkDir = projectHome.resolve("build").resolve("jps-bootstrap-work");

    info("Working directory: " + jpsBootstrapWorkDir);
    Files.createDirectories(jpsBootstrapWorkDir);

    buildTargetXmx = cmdline.hasOption(OPT_BUILD_TARGET_XMX) ? cmdline.getOptionValue(OPT_BUILD_TARGET_XMX) : DEFAULT_BUILD_SCRIPT_XMX;
  }

  private Path downloadJdk() {
    Path jdkHome;
    if (underTeamCity) {
      jdkHome = JdkDownloader.getJdkHome(communityHome);
      SetParameterServiceMessage setParameterServiceMessage = new SetParameterServiceMessage(
        "jps.bootstrap.java.home", jdkHome.toString()
      );
      System.out.println(setParameterServiceMessage.asString());
      setParameterServiceMessage = new SetParameterServiceMessage(
        "jps.bootstrap.java.executable", JdkDownloader.INSTANCE.getJavaExecutable(jdkHome).toString());
      System.out.println(setParameterServiceMessage.asString());
    }
    else {
      // On local run JDK was already downloaded via jps-bootstrap.{sh,cmd}
      jdkHome = Path.of(System.getProperty("java.home"));
    }
    return jdkHome;
  }

  private void main() throws Throwable {
    Path jdkHome = downloadJdk();
    if (onlyDownloadJdk) {
      return;
    }

    /*
     * Enable dependencies resolution retries while building buildscript.
     * Don't override settings properties if they're already present in System.properties(), in additionalSystemProperties or
     * in additionalSystemPropertiesFromPropertiesFile.
     */
    Properties resolverRetrySettingsProperties = getJpsArtifactsResolutionRetryProperties(
      additionalSystemPropertiesFromPropertiesFile,
      additionalSystemProperties,
      System.getProperties()
    );
    resolverRetrySettingsProperties.forEach((k, v) -> System.setProperty((String) k, (String) v));

    Path kotlincHome = KotlinCompiler.downloadAndExtractKotlinCompiler(communityHome);

    JpsModel model = JpsProjectUtils.loadJpsProject(projectHome, jdkHome, kotlincHome);
    JpsModule module = JpsProjectUtils.getModuleByName(model, moduleNameToRun);

    downloadOrBuildClasses(module, model, kotlincHome);

    List<File> moduleRuntimeClasspath = JpsProjectUtils.getModuleRuntimeClasspath(module);
    verbose("Module " + module.getName() + " classpath:\n  " + moduleRuntimeClasspath.stream().map(JpsBootstrapMain::fileDebugInfo).collect(Collectors.joining("\n  ")));

    writeJavaArgfile(moduleRuntimeClasspath);
  }

  private void removeOpenedPackage(List<String> openedPackages, String openedPackage, List<String> unknownPackages) {
    if (!openedPackages.remove(openedPackage)) {
      unknownPackages.add(openedPackage);
    }
  }

  private List<String> getOpenedPackages() throws Exception {
    Path openedPackagesPath = communityHome.communityRoot.resolve("plugins/devkit/devkit-core/src/run/OpenedPackages.txt");
    List<String> openedPackages = ContainerUtil.filter(Files.readAllLines(openedPackagesPath), it -> !it.isBlank());
    List<String> unknownPackages = new ArrayList<>();

    if (!SystemInfo.isWindows) {
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/sun.awt.windows=ALL-UNNAMED", unknownPackages);
    }
    if (!SystemInfo.isMac) {
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED", unknownPackages);
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED", unknownPackages);
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED", unknownPackages);
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED", unknownPackages);
    }
    if (!SystemInfo.isLinux) {
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED", unknownPackages);
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED", unknownPackages);
      removeOpenedPackage(openedPackages,"--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", unknownPackages);
    }
    if (!unknownPackages.isEmpty()) {
      throw new Exception(String.format("OS specific opened packages: ['%s'] not found in '%s'. " +
          "Probably you need to clean up OS-specific package names in org.jetbrains.jpsBootstrap.JpsBootstrapMain",
        String.join("','", unknownPackages), openedPackagesPath));
    }
    return openedPackages;
  }

  private void writeJavaArgfile(List<File> moduleRuntimeClasspath) throws Exception {
    Properties systemProperties = new Properties();

    if (underTeamCity) {
      systemProperties.putAll(getTeamCitySystemProperties());
    }
    systemProperties.putAll(additionalSystemPropertiesFromPropertiesFile);
    systemProperties.putAll(additionalSystemProperties);

    systemProperties.putIfAbsent("file.encoding", "UTF-8"); // just in case
    systemProperties.putIfAbsent("java.awt.headless", "true");

    /*
     * Add dependencies resolution retries properties to argfile.
     * Don't override them if they're already present in additionalSystemProperties or in additionalSystemPropertiesFromPropertiesFile.
     */
    Properties resolverRetrySettingsProperties = getJpsArtifactsResolutionRetryProperties(
      additionalSystemPropertiesFromPropertiesFile,
      additionalSystemProperties
    );
    systemProperties.putAll(resolverRetrySettingsProperties);

    List<String> args = new ArrayList<>();
    args.add("-ea");
    args.add("-Xmx" + buildTargetXmx);

    args.addAll(getOpenedPackages());

    args.addAll(convertPropertiesToCommandLineArgs(systemProperties));

    args.add("-classpath");
    args.add(Strings.join(moduleRuntimeClasspath, File.pathSeparator));

    args.add("-Dbuild.script.launcher.main.class=" + classNameToRun);
    args.add("org.jetbrains.intellij.build.impl.BuildScriptLauncher");

    args.addAll(mainArgsToRun);

    CommandLineWrapperUtil.writeArgumentsFile(
      javaArgsFileTarget.toFile(),
      args,
      StandardCharsets.UTF_8
    );

    info("java argfile:\n" + Files.readString(javaArgsFileTarget));
  }

  private void downloadOrBuildClasses(JpsModule module, JpsModel model, Path kotlincHome) throws Throwable {
    String fromJpsBuildEnvValue = System.getenv(JpsBuild.CLASSES_FROM_JPS_BUILD_ENV_NAME);
    boolean runJpsBuild = fromJpsBuildEnvValue != null && JpsBootstrapUtil.toBooleanChecked(fromJpsBuildEnvValue) || ProjectStamps.PORTABLE_CACHES;

    String manifestJsonUrl = System.getenv(ClassesFromCompileInc.MANIFEST_JSON_URL_ENV_NAME);
    if (manifestJsonUrl != null && manifestJsonUrl.isBlank()) {
      manifestJsonUrl = null;
    }

    if (runJpsBuild && manifestJsonUrl != null) {
      throw new IllegalStateException("Both env. variables are set, choose only one: " +
        JpsBuild.CLASSES_FROM_JPS_BUILD_ENV_NAME + " " +
        ClassesFromCompileInc.MANIFEST_JSON_URL_ENV_NAME);
    }

    if (!runJpsBuild && manifestJsonUrl == null) {
      // Nothing specified. It's ok locally, but on buildserver we must be sure
      if (underTeamCity) {
        throw new IllegalStateException("On buildserver one of the following env. variables must be set: " +
          JpsBuild.CLASSES_FROM_JPS_BUILD_ENV_NAME + " " +
          ClassesFromCompileInc.MANIFEST_JSON_URL_ENV_NAME);
      }
    }

    Set<JpsModule> modulesSubset = JpsProjectUtils.getRuntimeModulesClasspath(module);

    JpsBuild jpsBuild = new JpsBuild(communityHome, model, jpsBootstrapWorkDir, kotlincHome);

    // Some workarounds like 'kotlinx.kotlinx-serialization-compiler-plugin-for-compilation' library (used as Kotlin compiler plugin)
    // require that the corresponding library was downloaded. It's unclear from modules structure which libraries exactly required
    // so download them all
    //
    // In case of running from read-to-use classes we need all dependent libraries as well
    // Instead of calculating what libraries are exactly required, download them all
    jpsBuild.resolveProjectDependencies();

    if (manifestJsonUrl != null) {
      info("Downloading project classes from " + manifestJsonUrl);
      ClassesFromCompileInc.downloadProjectClasses(model.getProject(), communityHome, modulesSubset);
    } else {
      jpsBuild.buildModules(modulesSubset);
    }
  }

  private static String fileDebugInfo(File file) {
    try {
      if (file.exists()) {
        BasicFileAttributes attributes = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        if (attributes.isDirectory()) {
          return file + " directory";
        }
        else {
          long length = attributes.size();
          String sha256 = Hashing.sha256().hashBytes(Files.readAllBytes(file.toPath())).toString();
          return file + " file length " + length + " sha256 " + sha256;
        }
      }
      else {
        return file + " missing file";
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static List<String> convertPropertiesToCommandLineArgs(Properties properties) {
    List<String> result = new ArrayList<>();
    for (String propertyName : properties.stringPropertyNames().stream().sorted().collect(Collectors.toList())) {
      String value = properties.getProperty(propertyName);

      result.add("-D" + propertyName + "=" + value);
    }
    return result;
  }

  @Contract("->fail")
  private static void showUsagesAndExit() {
    HelpFormatter formatter = new HelpFormatter();
    formatter.setWidth(1000);
    formatter.printHelp("./jps-bootstrap.sh [jps-bootstrap options] MODULE_NAME CLASS_NAME [arguments_passed_to_CLASS_NAME's_main]", createCliOptions());
    System.exit(1);
  }

  private static void initLogging() {
    java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");

    for (Handler handler : rootLogger.getHandlers()) {
      rootLogger.removeHandler(handler);
    }
    IdeaLogRecordFormatter layout = new IdeaLogRecordFormatter();
    ConsoleHandler consoleHandler = new ConsoleHandler();
    consoleHandler.setFormatter(new IdeaLogRecordFormatter(false, layout));
    consoleHandler.setLevel(java.util.logging.Level.WARNING);
    rootLogger.addHandler(consoleHandler);
  }

  private static class SetParameterServiceMessage extends MessageWithAttributes {
    public SetParameterServiceMessage(@NotNull String name, @NotNull String value) {
      super(ServiceMessageTypes.BUILD_SET_PARAMETER, Map.of("name", name, "value", value));
    }
  }
}
