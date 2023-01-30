// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.ui

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.plugins.gitlab.api.GitLabProjectConnectionManager
import org.jetbrains.plugins.gitlab.api.GitLabProjectCoordinates
import org.jetbrains.plugins.gitlab.mergerequest.data.GitLabMergeRequestId

internal class GitLabReviewTabsController(private val project: Project) {
  // TODO: make it more safe, so repository should be passed to places properly where needed
  val currentRepository: GitLabProjectCoordinates?
    get() = project.service<GitLabProjectConnectionManager>().connectionState.value?.repo?.repository

  private val _openReviewTabRequest = MutableSharedFlow<GitLabReviewTab>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val openReviewTabRequest: Flow<GitLabReviewTab> = _openReviewTabRequest

  fun openReviewDetails(reviewId: GitLabMergeRequestId) {
    _openReviewTabRequest.tryEmit(GitLabReviewTab.ReviewSelected(reviewId))
  }
}


internal sealed class GitLabReviewTab {
  object ReviewList : GitLabReviewTab()
  data class ReviewSelected(val reviewId: GitLabMergeRequestId) : GitLabReviewTab()
}