// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewnotes;

import java.io.IOException;

import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

class RefUpdateListener implements GitReferenceUpdatedListener {

  private static final Logger log =
      LoggerFactory.getLogger(RefUpdateListener.class);

  private final CreateReviewNotes.Factory reviewNotesFactory;
  private final SchemaFactory<ReviewDb> schema;
  private final GitRepositoryManager repoManager;

  @Inject
  RefUpdateListener(final CreateReviewNotes.Factory reviewNotesFactory,
      final SchemaFactory<ReviewDb> schema,
      final GitRepositoryManager repoManager) {
    this.reviewNotesFactory = reviewNotesFactory;
    this.schema = schema;
    this.repoManager = repoManager;
  }

  @Override
  public void onGitReferenceUpdated(Event e) {
    Project.NameKey projectName = new Project.NameKey(e.getProjectName());
    Repository git;
    try {
      git = repoManager.openRepository(projectName);
    } catch (RepositoryNotFoundException x) {
      log.error(x.getMessage(), x);
      return;
    } catch (IOException x) {
      log.error(x.getMessage(), x);
      return;
    }

    ReviewDb reviewDb;
    try {

      try {
        reviewDb = schema.open();
      } catch (OrmException x) {
        log.error(x.getMessage(), x);
        return;
      }

      try {
        CreateReviewNotes crn = reviewNotesFactory.create(
            reviewDb, projectName, git);
        for (Update u : e.getUpdates()) {
          if (!u.getRefName().startsWith("refs/heads/")) {
            continue;
          }
          crn.createNotes(u.getRefName(),
              ObjectId.fromString(u.getOldObjectId()),
              ObjectId.fromString(u.getNewObjectId()),
              null);
        }
        crn.commitNotes();
      } catch (OrmException x) {
        log.error(x.getMessage(), x);
      } catch (IOException x) {
        log.error(x.getMessage(), x);
      } catch (ConcurrentRefUpdateException x) {
        log.error(x.getMessage(), x);
      } finally {
        reviewDb.close();
      }

    } finally {
      git.close();
    }

  }
}
