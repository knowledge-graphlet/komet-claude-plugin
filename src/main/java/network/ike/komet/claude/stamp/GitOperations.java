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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                                  Optional<String> upstream, int ahead, int behind, String status) {

        /**
         * What a closed Repository panel says: the folder, the branch, the file, and whatever is
         * not yet where it belongs.
         *
         * @param file    the document's file, or {@code null} when there is none
         * @param pending whether the document carries an edit pending in Komet
         * @return the parts that apply, joined
         */
        public String summary(Path file, boolean pending) {
            List<String> parts = new java.util.ArrayList<>();
            parts.add(root.getFileName() == null ? root.toString() : root.getFileName().toString());
            if (!branch.isEmpty()) {
                parts.add(branch);
            }
            if (file != null) {
                parts.add(file.toString());
            }
            if (status.equals("modified") || status.equals("staged") || status.equals("untracked")) {
                parts.add(status);
            }
            if (pending) {
                parts.add("edit pending");
            }
            if (ahead > 0) {
                parts.add(ahead + " ahead");
            }
            if (behind > 0) {
                parts.add(behind + " behind");
            }
            return String.join(" \u00b7 ", parts);
        }

        /** @return the remote a first push goes to: {@code origin} when there is one, else the first */
        public Optional<Remote> pushTarget() {
            return remotes.stream().filter(remote -> remote.name().equals("origin")).findFirst()
                    .or(() -> remotes.stream().findFirst());
        }
    }

    /**
     * The branches a working tree can switch to.
     *
     * @param current the current branch, or empty when HEAD is detached
     * @param local   the local branches, in git's order
     * @param remote  the remotes' branches with no local branch of the name, each as {@code origin/name}
     */
    public record Branches(String current, List<String> local, List<String> remote) { }

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
        // symbolic-ref names the branch of a repository with no commits yet, which rev-parse cannot;
        // rev-parse answers for a detached HEAD, which is not a symbolic ref.
        Run branch = git(root, "symbolic-ref", "--quiet", "--short", "HEAD");
        if (!branch.ok()) {
            branch = git(root, "rev-parse", "--abbrev-ref", "HEAD");
        }
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
     * Says why a new repository may not be made in a folder. A repository holds everything under
     * it, so the home folder and the file system's root are refused. A folder a repository already
     * holds is refused, and so is a {@code .git} entry of any kind, which in a synced tree may be
     * the empty husk Syncthing left. A folder in a Syncthing tree is refused because {@code .git}
     * never syncs: the other machines would get the files with no history, and no origin for
     * materialization to fetch one from.
     *
     * @param folder the folder the repository would be made in, which need not exist yet
     * @return the reason, or empty when the folder may hold a new repository
     */
    public static Optional<String> initRefusal(Path folder) {
        Path target = folder.toAbsolutePath().normalize();
        if (target.getParent() == null || target.equals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize())) {
            return Optional.of("A repository in " + target + " would hold everything under it. Choose a folder for the documents.");
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            return Optional.of(target + " is a file, not a folder.");
        }
        for (Path dir = target; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return Optional.of(target + " is already in the repository at " + dir + ".");
            }
        }
        return syncedFolder(target).map(synced -> target + " is in the synced folder " + synced
                + ", where .git never syncs: other machines would get the files with no history. Choose a folder outside it.");
    }

    /**
     * Finds the Syncthing folder holding a path, by the marker Syncthing keeps at a folder's root.
     *
     * @param path the path, which need not exist yet
     * @return the synced folder's root, or empty when the path is in none
     */
    public static Optional<Path> syncedFolder(Path path) {
        for (Path dir = path.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".stfolder"))) {
                return Optional.of(dir);
            }
        }
        return Optional.empty();
    }

    /**
     * Makes a new repository, creating the folder when needed. The first branch is named by the
     * person's own {@code init.defaultBranch}. Nothing is committed.
     *
     * @param folder the folder to make the repository in
     * @return what git did, or the refusal of {@link #initRefusal}
     * @throws IOException if the folder cannot be made or git cannot be run
     */
    public static Run init(Path folder) throws IOException {
        Optional<String> refusal = initRefusal(folder);
        if (refusal.isPresent()) {
            return new Run(-1, refusal.get());
        }
        Path target = folder.toAbsolutePath().normalize();
        Files.createDirectories(target);
        return git(target, "init", "--quiet");
    }

    /**
     * Adds a remote. It contacts nothing: the first {@link #pushSettingUpstream push} does, and
     * the repository must already exist there.
     *
     * @param root the working tree
     * @param name the remote's name
     * @param url  the repository URL, as git takes it
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run addRemote(Path root, String name, String url) throws IOException {
        if (name == null || name.isBlank() || url == null || url.isBlank()) {
            return new Run(-1, "A remote needs a name and a URL.");
        }
        return git(root, "remote", "add", "--", name.strip(), url.strip());
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
     * Pushes a branch that has no upstream yet and makes the remote's branch its upstream, so
     * the plain {@link #push} and {@link #pullFastForwardOnly pull} work from then on.
     *
     * @param root   the working tree
     * @param remote the remote's name
     * @param branch the branch
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run pushSettingUpstream(Path root, String remote, String branch) throws IOException {
        return git(root, "push", "--set-upstream", remote, branch);
    }

    /**
     * Whether HEAD holds a file, which a document needs: it is stored from its history.
     *
     * @param root the working tree
     * @param file the file, relative to the root
     * @return {@code false} when the file is uncommitted or the repository has no commits yet
     * @throws IOException if git cannot be run
     */
    public static boolean committed(Path root, Path file) throws IOException {
        return git(root, "cat-file", "-e", "HEAD:" + file.toString().replace('\\', '/')).ok();
    }

    /**
     * The files among those named that differ from HEAD in the working tree or the index, or are
     * untracked: what an edit made outside Komet looks like. A file deleted from the working tree
     * is left out, since a document stored from it would have nothing left to read.
     *
     * @param root  the working tree
     * @param files the files, relative to the root
     * @return those that changed, in the order named; empty when the repository has no commits
     * @throws IOException if git cannot be run
     */
    public static List<String> changedFiles(Path root, List<String> files) throws IOException {
        if (files.isEmpty()) {
            return List.of();
        }
        // Names only, NUL-separated: git quotes neither, and no status column can strip away.
        Set<String> found = new HashSet<>();
        for (Run run : List.of(pathsRun(root, files, "diff", "--name-only", "-z", "HEAD"),
                pathsRun(root, files, "ls-files", "--others", "--exclude-standard", "-z"))) {
            if (run.ok() && !run.output().isEmpty()) {
                found.addAll(List.of(run.output().split(String.valueOf((char) 0))));
            }
        }
        return files.stream().filter(found::contains).filter(file -> Files.isRegularFile(root.resolve(file))).toList();
    }

    /** Runs git over named files, the names read literally: a {@code *} in one is that character. */
    private static Run pathsRun(Path root, List<String> files, String... args) throws IOException {
        List<String> command = new ArrayList<>(List.of("--literal-pathspecs"));
        command.addAll(List.of(args));
        command.add("--");
        command.addAll(files);
        return git(root, command.toArray(String[]::new));
    }

    /**
     * Lists the branches. A remote's own HEAD is not a branch, and a remote's branch that has a
     * local branch of the name is reached by that one.
     *
     * @param root the working tree
     * @return the branches
     * @throws IOException if git cannot be run
     */
    public static Branches branches(Path root) throws IOException {
        Run refs = git(root, "for-each-ref", "--format=%(refname)%09%(symref)", "refs/heads", "refs/remotes");
        List<String> local = new ArrayList<>();
        List<String> remote = new ArrayList<>();
        if (refs.ok()) {
            for (String line : refs.output().lines().toList()) {
                String[] parts = line.split("\t", -1);
                if (parts[0].startsWith("refs/heads/")) {
                    local.add(parts[0].substring("refs/heads/".length()));
                } else if (parts.length < 2 || parts[1].isEmpty()) {
                    remote.add(parts[0].substring("refs/remotes/".length()));
                }
            }
        }
        remote.removeIf(name -> local.contains(name.substring(name.indexOf('/') + 1)));
        Run current = git(root, "symbolic-ref", "--quiet", "--short", "HEAD");
        return new Branches(current.ok() ? current.output().strip() : "", List.copyOf(local), List.copyOf(remote));
    }

    /**
     * Switches to a local branch. Uncommitted changes git can carry come along; when the switch
     * would overwrite one, git refuses and nothing moves.
     *
     * @param root   the working tree
     * @param branch the local branch
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run switchBranch(Path root, String branch) throws IOException {
        return git(root, "switch", branch);
    }

    /**
     * Makes a local branch of a remote's and switches to it, as {@link #switchBranch} does.
     *
     * @param root   the working tree
     * @param branch the remote's branch, as {@code origin/name}
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run trackBranch(Path root, String branch) throws IOException {
        return git(root, "switch", "--track", branch);
    }

    /**
     * Starts a new branch at HEAD and switches to it. No file changes, and in a repository with
     * no commits yet it names the branch the first commit will start.
     *
     * @param root the working tree
     * @param name the new branch's name
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Run createBranch(Path root, String name) throws IOException {
        if (name == null || name.isBlank()) {
            return new Run(-1, "A branch needs a name.");
        }
        return git(root, "switch", "-c", name.strip());
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
