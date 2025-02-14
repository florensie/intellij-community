// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.platform;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Configures various subsystems (facets etc) when user opens folder with code but without of ".idea" folder.
 * <br/>
 * Example: to support some framework you need to enable and configure facet. User opens folder with code for the first time.
 * This class scans code and detects framework heuristically. It then configures facet without user action.
 */
public interface DirectoryProjectConfigurator {

  /**
   * @return if code must be called or EDT or not.
   * If {@link #configureProject(Project, VirtualFile, Ref, boolean)} is slow (heavy computations, network access etc) return "false" here.
   */
  default boolean isEdtRequired() {
    return true;
  }

  /**
   * @param isProjectCreatedWithWizard if true then new project created with wizard, existing folder opened otherwise
   */
  void configureProject(@NotNull Project project,
                        @NotNull VirtualFile baseDir,
                        @NotNull Ref<Module> moduleRef,
                        boolean isProjectCreatedWithWizard);
}
