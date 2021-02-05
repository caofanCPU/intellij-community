// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package git4idea.index

import com.intellij.openapi.project.Project
import com.intellij.vcs.commit.AbstractCommitMessagePolicy

class GitStageCommitMessagePolicy(project: Project) : AbstractCommitMessagePolicy(project) {
  fun getCommitMessage(): String? =
    if (vcsConfiguration.CLEAR_INITIAL_COMMIT_MESSAGE) null else vcsConfiguration.LAST_COMMIT_MESSAGE

  fun save(commitMessage: String, saveToHistory: Boolean) {
    if (saveToHistory) vcsConfiguration.saveCommitMessage(commitMessage)
  }
}