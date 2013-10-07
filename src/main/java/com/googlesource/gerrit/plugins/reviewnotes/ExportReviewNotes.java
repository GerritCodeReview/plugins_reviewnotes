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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TextProgressMonitor;
import org.eclipse.jgit.lib.ThreadSafeProgressMonitor;
import org.eclipse.jgit.util.BlockList;
import org.kohsuke.args4j.Option;

import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.sshd.SshCommand;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.SchemaFactory;
import com.google.inject.Inject;

/** Export review notes for all submitted changes in all projects. */
public class ExportReviewNotes extends SshCommand {
  @Option(name = "--threads", usage = "Number of concurrent threads to run")
  private int threads = 2;

  @Inject
  private GitRepositoryManager gitManager;

  @Inject
  private SchemaFactory<ReviewDb> database;

  @Inject
  private CreateReviewNotes.Factory reviewNotesFactory;

  private Map<Project.NameKey, List<Change>> changes;
  private ThreadSafeProgressMonitor monitor;

  @Override
  protected void run() throws Failure, InterruptedException {
    if (threads <= 0) {
      threads = 1;
    }

    List<Change> allChangeList = allChanges();
    monitor = new ThreadSafeProgressMonitor(new TextProgressMonitor(stdout));
    monitor.beginTask("Scanning changes", allChangeList.size());
    changes = cluster(allChangeList);
    allChangeList = null;

    monitor.startWorkers(threads);
    for (int tid = 0; tid < threads; tid++) {
      new Worker().start();
    }
    monitor.waitForCompletion();
    monitor.endTask();
  }

  private List<Change> allChanges() {
    ReviewDb db = null;
    try {
      db = database.open();
      return db.changes().all().toList();
    } catch (OrmException e) {
      stderr.print("Cannot read changes from database " + e.getMessage());
      return Collections.emptyList();
    } finally {
      if (db != null) {
        db.close();
      }
    }
  }

  private Map<Project.NameKey, List<Change>> cluster(List<Change> changes) {
    HashMap<Project.NameKey, List<Change>> m =
        new HashMap<Project.NameKey, List<Change>>();
    for (Change change : changes) {
      if (change.getStatus() == Change.Status.MERGED) {
        List<Change> l = m.get(change.getProject());
        if (l == null) {
          l = new BlockList<Change>();
          m.put(change.getProject(), l);
        }
        l.add(change);
      } else {
        monitor.update(1);
      }
    }
    return m;
  }

  private void export(ReviewDb db, Project.NameKey project, List<Change> changes)
      throws IOException, OrmException, InterruptedException {
    final Repository git;
    try {
      git = gitManager.openRepository(project);
    } catch (RepositoryNotFoundException e) {
      return;
    }
    try {
      CreateReviewNotes crn = reviewNotesFactory.create(db, project, git);
      crn.createNotes(changes, monitor);
      crn.commitNotes();
    } catch (ConcurrentRefUpdateException e) {
      stderr.print(e.getMessage());
    } finally {
      git.close();
    }
  }

  private Map.Entry<Project.NameKey, List<Change>> next() {
    synchronized (changes) {
      if (changes.isEmpty()) {
        return null;
      }

      final Project.NameKey name = changes.keySet().iterator().next();
      final List<Change> list = changes.remove(name);
      return new Map.Entry<Project.NameKey, List<Change>>() {
        @Override
        public Project.NameKey getKey() {
          return name;
        }

        @Override
        public List<Change> getValue() {
          return list;
        }

        @Override
        public List<Change> setValue(List<Change> value) {
          throw new UnsupportedOperationException();
        }
      };
    }
  }

  private class Worker extends Thread {
    @Override
    public void run() {
      ReviewDb db;
      try {
        db = database.open();
      } catch (OrmException e) {
        stderr.print(e.getMessage());
        return;
      }
      try {
        for (;;) {
          Entry<Project.NameKey, List<Change>> next = next();
          if (next != null) {
            try {
              export(db, next.getKey(), next.getValue());
            } catch (IOException e) {
              stderr.print(e.getMessage());
            } catch (OrmException e) {
              stderr.print(e.getMessage());
            } catch (InterruptedException e) {
              stderr.print(e.getMessage());
            }
          } else {
            break;
          }
        }
      } finally {
        monitor.endWorker();
        db.close();
      }
    }
  }
}