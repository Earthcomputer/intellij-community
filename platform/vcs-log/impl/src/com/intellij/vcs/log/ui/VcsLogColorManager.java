// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.ui;

import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.Collection;

/**
 * Managers colors used for paths in the vcs log.
 *
 * @author Kirill Likhodedov
 */
public interface VcsLogColorManager {

  /**
   * Returns the color assigned to the given repository root.
   */
  default @NotNull Color getRootColor(@NotNull VirtualFile root) {
    return getPathColor(VcsUtil.getFilePath(root));
  }

  /**
   * Returns the color assigned to the given file path.
   */
  @NotNull
  Color getPathColor(@NotNull FilePath path);

  /**
   * Tells if there are several paths currently shown in the log.
   */
  default boolean hasMultiplePaths() {
    return getPaths().size() > 1;
  }

  /**
   * Returns paths managed by this manager.
   */
  @NotNull
  Collection<FilePath> getPaths();

  /**
   * Returns long name for this file path (to be shown in the tooltip, etc.).
   */
  default @NotNull @NlsSafe String getLongName(@NotNull FilePath path) {
    return path.getPresentableUrl();
  }
}
