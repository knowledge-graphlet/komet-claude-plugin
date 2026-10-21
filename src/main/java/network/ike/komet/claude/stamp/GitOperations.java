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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The repository operations of the Stamped Document card, every one through the person's own
 * {@code git} exactly as {@link DocumentCommit} and the annotation commit run it (ike-lab-documents
 * topic {@code dev-stamped-document-surface}, phase 4; IKE-Network/ike-issues#1307): their
 * identity, their signing, their credentials. Nothing that authenticates goes through JGit, and
 * nothing here sets an environment variable or a path for git — Komet started from the Dock
 * inherits no shell, so authentication comes from git's own configuration (the credential helper,
 * the SSH config, the agent socket as git finds it), and when it fails the card says so with
 * git's message. Every operation reports git's stdout and stderr, and refuses rather than
 * retries.
 */
public final class GitOperations {

    private static final int TIMEOUT_SECONDS = 300;

    private GitOperations() { }

    /**
     * One run of git.
     *
     * @param exit   the exit status, or {@code -1} when git could not run to completion
     * @param output stdout and stderr together, as git wrote them
     */
    public record Run(int exit, String output) {

        /** @return whether git exited 0 */
        public boolean ok() {
            return exit == 0;
        }

        /** @return the output, or a line naming the exit status when git said nothing */
        public String report(String verb) {
            return output.isBlank() ? "git " + verb + " exited " + exit : output;
        }
    }

    /**
     * A remote as {@code git remote -v} lists it.
     *
     * @param name the remote's name
     * @param url  its fetch URL
     */
    public record Remote(String name, String url) { }

    /**
     * What the Repository panel shows.
     *
     * @param root     the working tree
     * @param remotes  the remotes, in git's order
     * @param branch   the current branch, or {@code HEAD} when detached
     * @param headSha  HEAD's short SHA
     * @param headText HEAD's subject
     * @param upstream the branch's upstream, or empty when it has none
     * @param ahead    commits on the branch not on the upstream, or -1 without an upstream
     * @param behind   commits on the upstream not on the branch, or -1 without an upstream
     * @param status   the open file's status in the working tree: {@code clean}, {@code modified}, {@code untracked}, {@code staged}, or empty without a file
     */
    public record RepositoryState(Path root, List<Remote> remotes, String branch, String headSha, String headText,
                                  Optional<String> upstream, int ahead, int behind, String status) { }

    /**
     * One commit as the History view lists it.
     *
     * @param sha     the full SHA
     * @param author  the author's name
     * @param date    the author date, as a day
     * @param summary the subject
     */
    public record Commit(String sha, String author, String date, String summary) {

        /** @return the short SHA */
        public String shortSha() {
            return sha.substring(0, Math.min(7, sha.length()));
        }
    }

    /**
     * Reads what the panel shows; the operations that fail (no upstream, no HEAD yet) leave
     * their field empty rather than failing the read.
     *
     * @param root the working tree
     * @param file the open file, relative to the root, or {@code null}
     * @return the state
     * @throws IOException if git cannot be run
     */
    public static RepositoryState state(Path root, Path file) throws IOException {
        List<Remote> remotes = new ArrayList<>();
        Run remote = git(root, "remote", "-v");
        if (remote.ok()) {
            for (String line : remote.output().lines().toList()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 3 && parts[2].equals("(fetch)")) {
                    remotes.add(new Remote(parts[0], parts[1]));
                }
            }
        }
        Run branch = git(root, "rev-parse", "--abbrev-ref", "HEAD");
        Run head = git(root, "log", "-1", "--format=%h%x09%s");
        String headSha = "";
        String headText = "";
        if (head.ok() && head.output().contains("\t")) {
            headSha = head.output().substring(0, head.output().indexOf('\t'));
            headText = head.output().substring(head.output().indexOf('\t') + 1).strip();
        }
        Run upstream = git(root, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}");
        int ahead = -1;
        int behind = -1;
        if (upstream.ok()) {
            Run counts = git(root, "rev-list", "--left-right", "--count", "HEAD...@{upstream}");
            if (counts.ok()) {
                String[] parts = counts.output().trim().split("\\s+");
                if (parts.length == 2) {
                    ahead = Integer.parseInt(parts[0]);
                    behind = Integer.parseInt(parts[1]);
                }
            }
        }
        String status = "";
        if (file != null) {
            // Asked of git's exit statuses rather than parsed from porcelain output, whose leading
            // column is a space that strips away.
            if (!git(root, "ls-files", "--error-unmatch", "--", file.toString()).ok()) {
                status = "untracked";
            } else if (!git(root, "diff", "--quiet", "--", file.toString()).ok()) {
                status = "modified";
            } else if (!git(root, "diff", "--cached", "--quiet", "--", file.toString()).ok()) {
                status = "staged";
            } else {
                status = "clean";
            }
        }
        return new RepositoryState(root, List.copyOf(remotes), branch.ok() ? branch.output().strip() : "", headSha, headText,
                upstream.ok() ? Optional.of(upstream.output().strip()) : Optional.empty(), ahead, behind, status);
    }

    /**
     * Clones a repository into a folder that does not exist or is empty: never into an existing
     * tree, which a synced folder would propagate and a second clone would corrupt.
     *
     * @param url    the repository URL, as git takes it
     * @param target the folder to create the clone in
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run clone(String url, Path target) throws IOException {
        if (url == null || url.isBlank()) {
            return new Run(-1, "A clone needs a repository URL.");
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                return new Run(-1, target + " exists and is not a directory.");
            }
            try (var entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    return new Run(-1, target + " exists and is not empty; a clone never goes into an existing tree. Choose an empty or new folder.");
                }
            }
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return new Run(-1, "The parent folder of " + target + " does not exist.");
        }
        return git(parent, "clone", "--", url, target.toAbsolutePath().toString());
    }

    /**
     * Fetches from the branch's remote (or origin).
     *
     * @param root the working tree
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run fetch(Path root) throws IOException {
        return git(root, "fetch", "--verbose");
    }

    /**
     * Pulls, fast-forward only: a pull that would merge is refused by git and reported as such.
     *
     * @param root the working tree
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run pullFastForwardOnly(Path root) throws IOException {
        return git(root, "pull", "--ff-only");
    }

    /**
     * Pushes the current branch to its upstream.
     *
     * @param root the working tree
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run push(Path root) throws IOException {
        return git(root, "push");
    }

    /**
     * The commits that touched the file, or the repository's, newest first.
     *
     * @param root  the working tree
     * @param file  the file, relative to the root, or {@code null} for the whole repository
     * @param limit how many at most
     * @return the commits
     * @throws IOException if git cannot be run
     */
    public static List<Commit> log(Path root, Path file, int limit) throws IOException {
        List<String> args = new ArrayList<>(List.of("log", "--max-count=" + limit, "--date=short", "--format=%H%x09%an%x09%ad%x09%s"));
        if (file != null) {
            args.add("--");
            args.add(file.toString());
        }
        Run run = git(root, args.toArray(String[]::new));
        List<Commit> commits = new ArrayList<>();
        if (!run.ok()) {
            return commits;
        }
        for (String line : run.output().lines().toList()) {
            String[] parts = line.split("\t", 4);
            if (parts.length == 4) {
                commits.add(new Commit(parts[0], parts[1], parts[2], parts[3]));
            }
        }
        return commits;
    }

    /** @return HEAD's full SHA, or empty when the repository has no commits */
    public static Optional<String> head(Path root) throws IOException {
        Run run = git(root, "rev-parse", "HEAD");
        return run.ok() ? Optional.of(run.output().strip()) : Optional.empty();
    }

    /**
     * Runs the person's git in a working tree, its environment untouched, stdout and stderr
     * together, bounded by a timeout.
     *
     * @param dir  the directory to run in
     * @param args the arguments after {@code git}
     * @return what git did
     * @throws IOException if git cannot be started
     */
    static Run git(Path dir, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "git " + args[0] + " timed out after " + TIMEOUT_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "interrupted");
        }
        return new Run(process.exitValue(), output);
    }
}
