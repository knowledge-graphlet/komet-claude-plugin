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
