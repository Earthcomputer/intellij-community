// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform

import com.intellij.CommonBundle
import com.intellij.configurationStore.runInAutoSaveDisabledMode
import com.intellij.configurationStore.saveSettings
import com.intellij.featureStatistics.fusCollectors.LifecycleUsageTriggerCollector
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.runUnderModalProgressIfIsEdt
import com.intellij.lang.LangBundle
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.PrimaryModuleManager
import com.intellij.openapi.module.impl.ModuleManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.project.modifyModules
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.ModuleAttachProcessor.Companion.getPrimaryModule
import com.intellij.projectImport.ProjectAttachProcessor
import com.intellij.projectImport.ProjectOpenedCallback
import com.intellij.util.io.directoryStreamIfExists
import com.intellij.util.io.systemIndependentPath
import java.nio.file.Path
import kotlin.io.path.exists

private val LOG = logger<ModuleAttachProcessor>()

class ModuleAttachProcessor : ProjectAttachProcessor() {
  companion object {
    @JvmStatic
    fun getPrimaryModule(project: Project) = if (canAttachToProject()) PrimaryModuleManager.findPrimaryModule(project) else null

    @JvmStatic
    fun getSortedModules(project: Project): List<Module> {
      val primaryModule = getPrimaryModule(project)
      val result = ArrayList<Module>()
      ModuleManager.getInstance(project).modules.filterTo(result) { it !== primaryModule}
      result.sortBy(Module::getName)
      primaryModule?.let {
        result.add(0, it)
      }
      return result
    }

    /**
     * @param project the project
     * @return `null` if either multi-projects are not enabled or the project has only one module
     */
    @JvmStatic
    @NlsSafe
    fun getMultiProjectDisplayName(project: Project): String? {
      if (!canAttachToProject()) {
        return null
      }

      val modules = ModuleManager.getInstance(project).modules
      if (modules.size <= 1) {
        return null
      }

      val primaryModule = getPrimaryModule(project) ?: modules.first()
      val result = StringBuilder(primaryModule.name)
        .append(", ")
        .append(modules.asSequence().filter { it !== primaryModule }.first().name)
      if (modules.size > 2) {
        result.append("...")
      }
      return result.toString()
    }
  }

  override fun attachToProject(project: Project, projectDir: Path, callback: ProjectOpenedCallback?): Boolean {
    LOG.info(String.format("Attaching directory: \"%s\"", projectDir))
    val dotIdeaDir = projectDir.resolve(Project.DIRECTORY_STORE_FOLDER)
    if (!dotIdeaDir.exists()) {
      val options = OpenProjectTask(useDefaultProjectAsTemplate = true, isNewProject = true)
      val newProject = ProjectManagerEx.getInstanceEx().newProject(projectDir, options) ?: return false
      runUnderModalProgressIfIsEdt {
        PlatformProjectOpenProcessor.runDirectoryProjectConfigurators(baseDir = projectDir,
                                                                      project = newProject,
                                                                      newProject = true)
        runInAutoSaveDisabledMode {
          saveSettings(newProject)
        }
      }
      runWriteAction { Disposer.dispose(newProject) }
    }

    val newModule = try {
      findMainModule(project, dotIdeaDir) ?: findMainModule(project, projectDir)
    }
    catch (e: Exception) {
      LOG.info(e)
      Messages.showErrorDialog(project,
                               LangBundle.message("module.attach.dialog.message.cannot.attach.project", e.message),
                               CommonBundle.getErrorTitle())
      return false
    }

    LifecycleUsageTriggerCollector.onProjectModuleAttached(project)

    if (newModule != null) {
      callback?.projectOpened(project, newModule)
      return true
    }

    return Messages.showYesNoDialog(project,
                                    LangBundle.message("module.attach.dialog.message.project.uses.non.standard.layout", projectDir),
                                    LangBundle.message("module.attach.dialog.title.open.project"),
                                    Messages.getQuestionIcon()) != Messages.YES
  }

  override fun beforeDetach(module: Module) {
   module.project.messageBus.syncPublisher(ModuleAttachListener.TOPIC).beforeDetach(module)
  }
}

private fun findMainModule(project: Project, projectDir: Path): Module? {
  projectDir.directoryStreamIfExists({ path -> path.fileName.toString().endsWith(ModuleManagerEx.IML_EXTENSION) }) { directoryStream ->
    for (file in directoryStream) {
      return attachModule(project, file)
    }
  }
  return null
}

private fun attachModule(project: Project, imlFile: Path): Module {
  val module = project.modifyModules {
    loadModule(imlFile.systemIndependentPath)
  }

  val newModule = ModuleManager.getInstance(project).findModuleByName(module.name)!!
  val primaryModule = addPrimaryModuleDependency(project, newModule)
  module.project.messageBus.syncPublisher(ModuleAttachListener.TOPIC).afterAttach(newModule, primaryModule, imlFile)
  return newModule
}

private fun addPrimaryModuleDependency(project: Project, newModule: Module): Module? {
  val module = getPrimaryModule(project)
  if (module != null && module !== newModule) {
    ModuleRootModificationUtil.addDependency(module, newModule)
    return module
  }
  return null
}
