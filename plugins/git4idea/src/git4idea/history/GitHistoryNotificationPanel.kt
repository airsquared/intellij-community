// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.history

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InplaceButton
import com.intellij.ui.LightColors
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.data.index.VcsLogBigRepositoriesList
import com.intellij.vcs.log.data.index.VcsLogModifiableIndex
import com.intellij.vcs.log.history.isNewHistoryEnabled
import com.intellij.vcs.log.impl.VcsLogSharedSettings
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.util.VcsLogUtil
import git4idea.i18n.GitBundle
import java.awt.BorderLayout

private const val INDEXING_NOTIFICATION_DISMISSED_KEY = "git.history.resume.index.dismissed"

internal object GitHistoryNotificationPanel {
  @JvmStatic
  fun create(project: Project, session: VcsHistorySession): EditorNotificationPanel? {
    val filePath = (session as? GitHistoryProvider.GitHistorySession)?.filePath ?: return null
    if (PropertiesComponent.getInstance(project).getBoolean(INDEXING_NOTIFICATION_DISMISSED_KEY)) return null
    if (!isNewHistoryEnabled()) return null
    if (!VcsLogSharedSettings.isIndexSwitchedOn(project)) return null

    val root = VcsLogUtil.getActualRoot(project, filePath) ?: return null
    if (!VcsLogBigRepositoriesList.getInstance().isBig(root) && VcsLogData.isIndexSwitchedOnInRegistry()) {
      return null
    }

    return EditorNotificationPanel(LightColors.YELLOW, EditorNotificationPanel.Status.Warning).apply {
      text = GitBundle.message("history.indexing.disabled.notification.text")
      createActionLabel(GitBundle.message("history.indexing.disabled.notification.resume.link")) {
        VcsLogBigRepositoriesList.getInstance().removeRepository(root)
        if (!VcsLogData.isIndexSwitchedOnInRegistry()) {
          VcsLogData.getIndexingRegistryValue().setValue(true)
        }
        else {
          (VcsProjectLog.getInstance(project).dataManager?.index as? VcsLogModifiableIndex)?.scheduleIndex(false)
        }
        this.parent?.remove(this)
      }
      add(InplaceButton(IconButton(GitBundle.message("history.indexing.disabled.notification.dismiss.link"),
                                   AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) {
        PropertiesComponent.getInstance(project).setValue(INDEXING_NOTIFICATION_DISMISSED_KEY, true)
        this.parent?.remove(this)
      }, BorderLayout.EAST)
    }
  }
}