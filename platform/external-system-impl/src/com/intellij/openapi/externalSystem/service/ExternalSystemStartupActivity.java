// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.service;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.ExternalSystemManager;
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder;
import com.intellij.openapi.externalSystem.model.ExternalSystemDataKeys;
import com.intellij.openapi.externalSystem.service.project.ProjectRenameAware;
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsManagerImpl;
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

final class ExternalSystemStartupActivity implements StartupActivity.DumbAware {
  @Override
  public void runActivity(@NotNull Project project) {
    ExternalProjectsManagerImpl.getInstance(project).init();

    ApplicationManager.getApplication().invokeLater(() -> {
      ExternalSystemManager.EP_NAME.forEachExtensionSafe(manager -> {
        if (manager instanceof StartupActivity) {
          ((StartupActivity)manager).runActivity(project);
        }
      });

      boolean isNewlyImportedProject = project.getUserData(ExternalSystemDataKeys.NEWLY_IMPORTED_PROJECT) == Boolean.TRUE;
      boolean isNewlyCreatedProject = project.getUserData(ExternalSystemDataKeys.NEWLY_CREATED_PROJECT) == Boolean.TRUE;
      if (!isNewlyImportedProject && isNewlyCreatedProject) {
        ExternalSystemManager.EP_NAME.forEachExtensionSafe(manager -> {
          ExternalSystemUtil.refreshProjects(new ImportSpecBuilder(project, manager.getSystemId()).createDirectoriesForEmptyContentRoots());
        });
      }
      ProjectRenameAware.beAware(project);
    }, project.getDisposed());
  }
}
