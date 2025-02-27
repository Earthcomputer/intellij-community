// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.importing

import com.intellij.openapi.externalSystem.project.ArtifactExternalDependenciesImporter
import com.intellij.openapi.externalSystem.project.PackagingModel
import com.intellij.openapi.externalSystem.service.project.ArtifactExternalDependenciesImporterImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.packaging.artifacts.ArtifactModel
import com.intellij.packaging.artifacts.ModifiableArtifactModel
import com.intellij.packaging.elements.PackagingElementResolvingContext
import com.intellij.packaging.impl.artifacts.DefaultPackagingElementResolvingContext
import com.intellij.workspaceModel.storage.MutableEntityStorage
import com.intellij.workspaceModel.storage.bridgeEntities.ModuleEntity
import org.jetbrains.idea.maven.importing.MavenWorkspaceConfigurator.*
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsProcessorTask
import org.jetbrains.idea.maven.project.MavenProjectsTree

val FACET_DETECTION_DISABLED_KEY = Key.create<MutableMap<MavenWorkspaceFacetConfigurator, Boolean>>("FACET_DETECTION_DISABLED_KEY")

interface MavenWorkspaceFacetConfigurator : MavenWorkspaceConfigurator {
  fun isApplicable(mavenProject: MavenProject): Boolean
  fun isFacetDetectionDisabled(project: Project): Boolean

  fun preProcess(storage: MutableEntityStorage,
                 module: ModuleEntity,
                 project: Project,
                 mavenProject: MavenProject,
                 artifactModel: ModifiableArtifactModel) = preProcess(storage, module)

  fun preProcess(storage: MutableEntityStorage, module: ModuleEntity) {
  }

  fun process(storage: MutableEntityStorage,
              module: ModuleEntity,
              project: Project,
              mavenProject: MavenProject,
              mavenTree: MavenProjectsTree,
              mavenProjectToModuleName: Map<MavenProject, String>,
              packagingModel: PackagingModel,
              postTasks: MutableList<MavenProjectsProcessorTask>) {
  }

  fun isFacetDetectionDisabled(context: Context<*>): Boolean {
    if (null == context.getUserData(FACET_DETECTION_DISABLED_KEY)) {
      context.putUserData(FACET_DETECTION_DISABLED_KEY, mutableMapOf())
    }
    val facetDetectionDisabledMap = context.getUserData(FACET_DETECTION_DISABLED_KEY)!!
    if (!facetDetectionDisabledMap.containsKey(this)) {
      facetDetectionDisabledMap[this] = isFacetDetectionDisabled(context.project)
    }
    return facetDetectionDisabledMap[this]!!
  }

  override fun configureMavenProject(context: MutableMavenProjectContext) {
    val project = context.project
    if (isFacetDetectionDisabled(project)) return

    val mavenProjectWithModules = context.mavenProjectWithModules
    val mavenProject = mavenProjectWithModules.mavenProject
    if (!isApplicable(mavenProject)) return

    val modules = mavenProjectWithModules.modules
    for (moduleWithType in modules) {
      val moduleType = moduleWithType.type
      if (moduleType.containsCode) {
        val module = moduleWithType.module
        preProcess(context.storage, module, project, mavenProject, context.artifactModel)
      }
    }
  }

  override fun beforeModelApplied(context: MutableModelContext) {
    val project = context.project
    if (isFacetDetectionDisabled(project)) return

    val artifactModel = context.artifactModel
    val resolvingContext = object : DefaultPackagingElementResolvingContext(project) {
      override fun getArtifactModel(): ArtifactModel {
        return artifactModel
      }
    }
    val packagingModel: PackagingModel = FacetPackagingModel(artifactModel, resolvingContext)
    val mavenProjectsWithModules = context.mavenProjectsWithModules
    val mavenProjectToModuleName = mavenProjectsWithModules.associateBy({ it.mavenProject }, { it.modules.first().module.name })
    val storage = context.storage
    val mavenTree = context.mavenProjectsTree
    val postTasks = mutableListOf<MavenProjectsProcessorTask>()

    for (mavenProjectWithModules in mavenProjectsWithModules) {
      val mavenProject = mavenProjectWithModules.mavenProject
      if (!isApplicable(mavenProject)) continue

      val modules = mavenProjectWithModules.modules

      for (moduleWithType in modules) {
        val moduleType = moduleWithType.type
        if (moduleType.containsCode) {
          val module = moduleWithType.module
          process(storage,
                  module,
                  project,
                  mavenProject,
                  mavenTree,
                  mavenProjectToModuleName,
                  packagingModel,
                  postTasks)
        }
      }
    }

    if (null == context.getUserData(POST_TASKS_KEY)) {
      context.putUserData(POST_TASKS_KEY, mutableListOf())
    }
    postTasks.forEach { context.getUserData(POST_TASKS_KEY)!!.add(it as MavenPostTask) }
  }

  class FacetPackagingModel(private val myArtifactModel: ModifiableArtifactModel,
                            private val myResolvingContext: PackagingElementResolvingContext) : PackagingModel {
    private val myDependenciesImporter: ArtifactExternalDependenciesImporter

    init {
      myDependenciesImporter = ArtifactExternalDependenciesImporterImpl()
    }

    override fun getModifiableArtifactModel(): ModifiableArtifactModel {
      return myArtifactModel
    }

    override fun getPackagingElementResolvingContext(): PackagingElementResolvingContext {
      return myResolvingContext
    }

    override fun getArtifactExternalDependenciesImporter(): ArtifactExternalDependenciesImporter {
      return myDependenciesImporter
    }
  }
}