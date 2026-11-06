/*
 * Copyright © 2026 Knowledge Graphlet / IKE Network
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.stamp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Repository panel's operations against a scratch bare remote, through the git on PATH as
 * the core's own tests run it: state (remotes, branch, HEAD, ahead and behind, file status),
 * clone refusals, fetch and fast-forward-only pull, push, and the log. The scratch repositories
 * run no hooks: a hook from the runner's global core.hooksPath (one that rewrites short commit
 * messages, on the kec build agent) would otherwise change what the test asserts.
 */
class GitOperationsTest {

    @TempDir
    Path root;

    @Test
    void aClosedPanelSaysTheFolderBranchFileAndWhatIsOutstanding() {
        GitOperations.RepositoryState clean = new GitOperations.RepositoryState(Path.of("/work/ike-lab-documents"), List.of(), "main", "abc1234",
                "subject", java.util.Optional.of("origin/main"), 0, 0, "clean");
        assertEquals("ike-lab-documents \u00b7 main \u00b7 topics/a.adoc", clean.summary(Path.of("topics/a.adoc"), false));

        GitOperations.RepositoryState ahead = new GitOperations.RepositoryState(Path.of("/work/repo"), List.of(), "", "", "",
                java.util.Optional.empty(), 2, 1, "modified");
        assertEquals("repo \u00b7 modified \u00b7 edit pending \u00b7 2 ahead \u00b7 1 behind", ahead.summary(null, true));
    }

    @Test
    void stateReadsRemotesBranchHeadAndCounts() throws Exception {
        Path bare = bare("origin.git");
        Path work = clone(bare, "work");
        Files.writeString(work.resolve("doc.adoc"), "one\n");
        git(work, "add", "doc.adoc");
        commit(work, "Add doc");
        git(work, "push", "-u", "origin", "main");

        GitOperations.RepositoryState state = GitOperations.state(work, Path.of("doc.adoc"));

        assertEquals(work.toRealPath(), state.root().toRealPath());
        assertEquals(List.of(new GitOperations.Remote("origin", bare.toString())), state.remotes());
        assertEquals("main", state.branch());
        assertEquals("Add doc", state.headText());
        assertEquals(7, state.headSha().length());
        assertEquals("origin/main", state.upstream().orElseThrow());
        assertEquals(0, state.ahead());
        assertEquals(0, state.behind());
        assertEquals("clean", state.status());

        Files.writeString(work.resolve("doc.adoc"), "one\ntwo\n");
        assertEquals("modified", GitOperations.state(work, Path.of("doc.adoc")).status());
        git(work, "add", "doc.adoc");
        assertEquals("staged", GitOperations.state(work, Path.of("doc.adoc")).status());
        commit(work, "Add a line");
        assertEquals(1, GitOperations.state(work, Path.of("doc.adoc")).ahead(), "one commit not yet pushed");
        Files.writeString(work.resolve("new.adoc"), "new\n");
        assertEquals("untracked", GitOperations.state(work, Path.of("new.adoc")).status(), "a file git does not know");
    }

    @Test
    void cloneRefusesAnExistingNonEmptyFolderAndNeverAnExistingTree() throws Exception {
        Path bare = bare("origin.git");
        Path occupied = Files.createDirectories(root.resolve("occupied"));
        Files.writeString(occupied.resolve("keep.txt"), "mine\n");

        GitOperations.Run refused = GitOperations.clone(bare.toString(), occupied);

        assertFalse(refused.ok());
        assertTrue(refused.output().contains("not empty"), refused.output());
        assertEquals("mine\n", Files.readString(occupied.resolve("keep.txt")), "nothing was touched");
        assertFalse(GitOperations.clone("", root.resolve("fresh")).ok(), "a clone needs a URL");

        Path empty = Files.createDirectories(root.resolve("empty"));
        GitOperations.Run ok = GitOperations.clone(bare.toString(), empty);
        assertTrue(ok.ok(), ok.output());
        assertTrue(Files.isDirectory(empty.resolve(".git")), "an empty folder is cloned into");
        GitOperations.Run fresh = GitOperations.clone(bare.toString(), root.resolve("fresh"));
        assertTrue(fresh.ok(), fresh.output());
        assertTrue(Files.isDirectory(root.resolve("fresh").resolve(".git")), "a new folder is created");
    }

    @Test
    void fetchPullFastForwardOnlyAndPushAgainstAScratchRemote() throws Exception {
        Path bare = bare("origin.git");
        Path alice = clone(bare, "alice");
        Files.writeString(alice.resolve("doc.adoc"), "one\n");
        git(alice, "add", "doc.adoc");
        commit(alice, "Add doc");
        git(alice, "push", "-u", "origin", "main");
        Path bob = clone(bare, "bob");

        // Alice moves the remote on; Bob sees it after Fetch and takes it with a fast-forward Pull.
        Files.writeString(alice.resolve("doc.adoc"), "one\ntwo\n");
        git(alice, "add", "doc.adoc");
        commit(alice, "Add two");
        git(alice, "push");
        assertEquals(0, GitOperations.state(bob, Path.of("doc.adoc")).behind(), "not fetched yet");
        assertTrue(GitOperations.fetch(bob).ok());
        assertEquals(1, GitOperations.state(bob, Path.of("doc.adoc")).behind(), "fetched: one behind");
        String before = GitOperations.head(bob).orElseThrow();
        GitOperations.Run pulled = GitOperations.pullFastForwardOnly(bob);
        assertTrue(pulled.ok(), pulled.output());
        assertFalse(before.equals(GitOperations.head(bob).orElseThrow()), "HEAD moved");
        assertEquals("one\ntwo\n", Files.readString(bob.resolve("doc.adoc")));

        // Both commit: Bob's pull would merge, so it is refused and reported; his push is rejected too.
        Files.writeString(alice.resolve("doc.adoc"), "one\ntwo\nthree\n");
        git(alice, "add", "doc.adoc");
        commit(alice, "Add three");
        git(alice, "push");
        Files.writeString(bob.resolve("other.txt"), "bob\n");
        git(bob, "add", "other.txt");
        commit(bob, "Bob's file");
        assertTrue(GitOperations.fetch(bob).ok());
        GitOperations.RepositoryState diverged = GitOperations.state(bob, Path.of("doc.adoc"));
        assertEquals(1, diverged.ahead());
        assertEquals(1, diverged.behind());
        GitOperations.Run refused = GitOperations.pullFastForwardOnly(bob);
        assertFalse(refused.ok(), "a pull that would merge is refused");
        assertFalse(refused.report("pull").isBlank(), "and git's reason is reported");
        assertTrue(Files.exists(bob.resolve("other.txt")) && !Files.readString(bob.resolve("doc.adoc")).contains("three"), "nothing merged");
        assertFalse(GitOperations.push(bob).ok(), "the push is rejected, as git says");

        // Alice, up to date, pushes an empty annotation-style commit: the scratch remote takes it.
        git(alice, "commit", "-q", "--allow-empty", "--no-gpg-sign", "-m", "Affirm doc.adoc 1-2");
        assertEquals(1, GitOperations.state(alice, null).ahead());
        GitOperations.Run pushed = GitOperations.push(alice);
        assertTrue(pushed.ok(), pushed.output());
        assertEquals(0, GitOperations.state(alice, null).ahead());
        List<GitOperations.Commit> log = GitOperations.log(alice, Path.of("doc.adoc"), 10);
        assertEquals(List.of("Add three", "Add two", "Add doc"), log.stream().map(GitOperations.Commit::summary).toList(), "the file's commits, newest first");
        assertEquals("Affirm doc.adoc 1-2", GitOperations.log(alice, null, 10).getFirst().summary(), "the repository's log holds the empty commit too");
        assertEquals("alice", log.getFirst().author());
        assertEquals(40, log.getFirst().sha().length());
    }

    @Test
    void initMakesARepositoryWhoseBranchIsReadBeforeItHasAnyCommit() throws Exception {
        Path folder = root.resolve("new/documents");
        assertTrue(GitOperations.init(folder).ok(), "the folder is created and the repository made");
        GitOperations.RepositoryState state = GitOperations.state(folder, null);
        assertFalse(state.branch().isEmpty(), "an unborn branch still has a name");
        assertEquals("", state.headSha());
        assertTrue(state.remotes().isEmpty());
    }

    @Test
    void initRefusesAnyFolderARepositoryWouldNotBeRightIn() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        assertTrue(GitOperations.initRefusal(home).isPresent(), "the home folder");
        assertTrue(GitOperations.initRefusal(home.getRoot()).isPresent(), "the file system's root");

        Path repository = root.resolve("repository");
        assertTrue(GitOperations.init(repository).ok());
        assertTrue(GitOperations.initRefusal(repository).orElseThrow().contains("already"), "a repository");
        assertTrue(GitOperations.initRefusal(repository.resolve("nested")).isPresent(), "a folder a repository holds");

        Path husk = root.resolve("husk");
        Files.createDirectories(husk.resolve(".git"));
        assertTrue(GitOperations.initRefusal(husk).isPresent(), "an empty .git entry, as Syncthing leaves one");
        assertFalse(GitOperations.init(husk).ok());
        assertFalse(Files.exists(husk.resolve(".git/HEAD")), "refused, not initialized");

        Path file = root.resolve("a-file");
        Files.writeString(file, "x\n");
        assertTrue(GitOperations.initRefusal(file).orElseThrow().contains("not a folder"));
        assertFalse(GitOperations.init(file).ok());

        assertTrue(GitOperations.initRefusal(root.resolve("fine")).isEmpty(), "an ordinary new folder");
    }

    @Test
    void aFolderInASyncedTreeIsRefusedBecauseGitNeverSyncs() throws Exception {
        Path synced = root.resolve("synced");
        Files.createDirectories(synced.resolve(".stfolder"));
        Path notes = synced.resolve("notes/deep");
        assertEquals(synced, GitOperations.syncedFolder(notes).orElseThrow());
        assertTrue(GitOperations.initRefusal(notes).orElseThrow().contains("synced folder"));
        assertFalse(GitOperations.init(notes).ok());
        assertFalse(Files.exists(notes), "a refused init leaves nothing behind");
        assertTrue(GitOperations.syncedFolder(root.resolve("elsewhere")).isEmpty());
    }

    @Test
    void aNewRepositoryGetsARemoteAndItsFirstPushSetsTheUpstream() throws Exception {
        Path bare = bare("origin.git");
        Path folder = root.resolve("new");
        assertTrue(GitOperations.init(folder).ok());
        git(folder, "config", "user.name", "new");
        git(folder, "config", "user.email", "new@example.test");
        git(folder, "config", "core.hooksPath", NO_HOOKS);
        assertFalse(GitOperations.addRemote(folder, "origin", " ").ok(), "a remote needs a URL");
        assertTrue(GitOperations.addRemote(folder, "origin", bare.toString()).ok());

        Files.writeString(folder.resolve("doc.adoc"), "one\n");
        assertFalse(GitOperations.committed(folder, Path.of("doc.adoc")), "no commits yet");
        git(folder, "add", "doc.adoc");
        assertFalse(GitOperations.committed(folder, Path.of("doc.adoc")), "staged is not committed");
        commit(folder, "Add doc");
        assertTrue(GitOperations.committed(folder, Path.of("doc.adoc")));

        GitOperations.RepositoryState before = GitOperations.state(folder, Path.of("doc.adoc"));
        assertTrue(before.upstream().isEmpty());
        assertFalse(GitOperations.push(folder).ok(), "git refuses a plain push with no upstream");
        GitOperations.Remote target = before.pushTarget().orElseThrow();
        assertEquals("origin", target.name());
        assertTrue(GitOperations.pushSettingUpstream(folder, target.name(), before.branch()).ok());

        GitOperations.RepositoryState after = GitOperations.state(folder, Path.of("doc.adoc"));
        assertEquals("origin/" + before.branch(), after.upstream().orElseThrow());
        assertEquals(0, after.ahead());
        assertTrue(GitOperations.push(folder).ok(), "the plain push works from then on");
    }

    @Test
    void aFirstPushPrefersOriginAndNeedsARemote() {
        GitOperations.Remote origin = new GitOperations.Remote("origin", "o");
        GitOperations.Remote fork = new GitOperations.Remote("fork", "f");
        assertEquals(origin, state(List.of(fork, origin)).pushTarget().orElseThrow());
        assertEquals(fork, state(List.of(fork)).pushTarget().orElseThrow());
        assertTrue(state(List.of()).pushTarget().isEmpty());
    }

    @Test
    void branchesListLocalsAndTheRemotesOwnOnly() throws Exception {
        Path bare = bare("branches.git");
        Path other = clone(bare, "other");
        Files.writeString(other.resolve("doc.adoc"), "one\n");
        git(other, "add", "doc.adoc");
        commit(other, "Add doc");
        git(other, "push", "-q", "-u", "origin", "main");
        git(other, "switch", "-q", "-c", "theirs");
        git(other, "push", "-q", "-u", "origin", "theirs");
        Path work = clone(bare, "branches-work");
        git(work, "checkout", "-q", "main");

        GitOperations.Branches branches = GitOperations.branches(work);

        assertEquals("main", branches.current());
        assertEquals(List.of("main"), branches.local());
        assertEquals(List.of("origin/theirs"), branches.remote(), "origin/HEAD is no branch, and origin/main is main's");

        assertTrue(GitOperations.trackBranch(work, "origin/theirs").ok());
        assertEquals("theirs", GitOperations.branches(work).current());
        assertEquals("origin/theirs", GitOperations.state(work, null).upstream().orElseThrow());
    }

    @Test
    void aNewBranchStartsAtHeadAndASwitchCarriesTheFiles() throws Exception {
        Path work = clone(bare("switch.git"), "switch-work");
        Files.writeString(work.resolve("doc.adoc"), "one\n");
        git(work, "add", "doc.adoc");
        commit(work, "Add doc");
        String head = GitOperations.head(work).orElseThrow();

        assertTrue(GitOperations.createBranch(work, "topic").ok());
        assertEquals(head, GitOperations.head(work).orElseThrow());
        assertEquals("topic", GitOperations.branches(work).current());

        Files.writeString(work.resolve("doc.adoc"), "two\n");
        git(work, "add", "doc.adoc");
        commit(work, "Change doc");
        assertTrue(GitOperations.switchBranch(work, "main").ok());
        assertEquals("one\n", Files.readString(work.resolve("doc.adoc")));

        assertFalse(GitOperations.createBranch(work, "  ").ok());
        assertFalse(GitOperations.createBranch(work, "not a name").ok(), "git's own refusal");
        assertFalse(GitOperations.createBranch(work, "topic").ok(), "a name in use");
    }

    @Test
    void aSwitchThatWouldOverwriteAnEditIsRefusedAndNothingMoves() throws Exception {
        Path work = clone(bare("refuse.git"), "refuse-work");
        Files.writeString(work.resolve("doc.adoc"), "one\n");
        git(work, "add", "doc.adoc");
        commit(work, "Add doc");
        git(work, "switch", "-q", "-c", "topic");
        Files.writeString(work.resolve("doc.adoc"), "two\n");
        git(work, "add", "doc.adoc");
        commit(work, "Change doc");
        git(work, "switch", "-q", "main");
        Files.writeString(work.resolve("doc.adoc"), "mine\n");

        GitOperations.Run run = GitOperations.switchBranch(work, "topic");

        assertFalse(run.ok());
        assertTrue(run.output().contains("doc.adoc"), run.output());
        assertEquals("main", GitOperations.branches(work).current());
        assertEquals("mine\n", Files.readString(work.resolve("doc.adoc")));
    }

    @Test
    void changedFilesAreThoseThatDifferFromHead() throws Exception {
        Path work = clone(bare("changed.git"), "changed-work");
        assertEquals(List.of(), GitOperations.changedFiles(work, List.of("doc.adoc")), "no commits yet");
        Files.createDirectories(work.resolve("topics"));
        for (String file : List.of("clean.adoc", "edited.adoc", "staged.adoc", "gone.adoc", "topics/a [b]*.adoc")) {
            Files.writeString(work.resolve(file), "one\n");
            git(work, "add", "--", ":(literal)" + file);
        }
        commit(work, "Add files");
        Files.writeString(work.resolve("edited.adoc"), "two\n");
        Files.writeString(work.resolve("staged.adoc"), "two\n");
        git(work, "add", "staged.adoc");
        Files.delete(work.resolve("gone.adoc"));
        Files.writeString(work.resolve("topics/a [b]*.adoc"), "two\n");
        Files.writeString(work.resolve("new.adoc"), "one\n");
        Files.writeString(work.resolve("unnamed.adoc"), "one\n");

        List<String> named = List.of("clean.adoc", "new.adoc", "edited.adoc", "staged.adoc", "gone.adoc", "topics/a [b]*.adoc");

        assertEquals(List.of("new.adoc", "edited.adoc", "staged.adoc", "topics/a [b]*.adoc"), GitOperations.changedFiles(work, named));
        assertEquals(List.of(), GitOperations.changedFiles(work, List.of()));
    }

    @Test
    void aFileChangedOutsideKometCommitsAloneAsItStands() throws Exception {
        Path work = clone(bare("working.git"), "working-work");
        Files.writeString(work.resolve("doc.adoc"), "one\n");
        Files.writeString(work.resolve("other.adoc"), "one\n");
        git(work, "add", "doc.adoc", "other.adoc");
        commit(work, "Add files");
        Files.writeString(work.resolve("doc.adoc"), "two\n");
        Files.writeString(work.resolve("other.adoc"), "two\n");

        DocumentCommit.Result tracked = DocumentCommit.workingFile(work, "doc.adoc", "Change doc\n", false);

        assertTrue(tracked.ok(), tracked.output());
        assertEquals(List.of("other.adoc"), GitOperations.changedFiles(work, List.of("doc.adoc", "other.adoc")));

        Files.writeString(work.resolve("new.adoc"), "one\n");
        assertTrue(DocumentCommit.workingFile(work, "new.adoc", "Add new\n", false).ok(), "an untracked file is added");
        assertTrue(GitOperations.committed(work, Path.of("new.adoc")));

        Files.writeString(work.resolve("doc.adoc"), "three\n");
        git(work, "add", "other.adoc");
        DocumentCommit.Result refused = DocumentCommit.workingFile(work, "doc.adoc", "Change doc\n", false);
        assertFalse(refused.ok());
        assertTrue(refused.output().contains("other.adoc"), refused.output());
    }

    @Test
    void theDraftSaysWhereTheEditWasMade() {
        String draft = DocumentCommit.draftWorkingFileMessage("topics/doc.adoc", List.of("one"), List.of("one", "two"));

        assertTrue(draft.startsWith("Edit doc.adoc\n\nEdited outside Komet: 1 line(s) written, 0 line(s) replaced or removed."), draft);
    }

    private static GitOperations.RepositoryState state(List<GitOperations.Remote> remotes) {
        return new GitOperations.RepositoryState(Path.of("r"), remotes, "main", "", "", java.util.Optional.empty(), -1, -1, "");
    }

    private Path bare(String name) throws Exception {
        Path bare = root.resolve(name);
        git(root, "init", "-q", "--bare", "-b", "main", bare.toString());
        git(bare, "config", "core.hooksPath", NO_HOOKS);
        return bare;
    }

    private Path clone(Path bare, String name) throws Exception {
        Path work = root.resolve(name);
        git(root, "clone", "-q", bare.toString(), work.toString());
        git(work, "config", "user.name", name);
        git(work, "config", "user.email", name + "@example.test");
        git(work, "config", "commit.gpgsign", "false");
        // GitOperations runs git untouched, so the repository itself turns hooks off for it.
        git(work, "config", "core.hooksPath", NO_HOOKS);
        git(work, "checkout", "-q", "-B", "main");
        return work;
    }

    private void commit(Path dir, String message) throws Exception {
        git(dir, "commit", "-q", "--no-gpg-sign", "-m", message);
    }

    /** A hooks directory that holds no hooks. */
    private static final String NO_HOOKS = "/dev/null";

    private static void git(Path dir, String... args) throws Exception {
        // Hooks off for the test's own git too, from before a repository has its own config.
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=" + NO_HOOKS));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
