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

import design.knowledge.stamp.adoc.git.GitRepo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Gives a file that is in no repository a home: a document is stored from its git history, so a
 * file with none has to be committed somewhere before it can be opened. The location is the
 * person's choice. A location already in a repository takes the file there; any other gets a new
 * repository. A file that lies outside the location is copied in and left where it was, so
 * nothing the person had moves. Only the one file is committed, locally, and nothing is pushed.
 */
final class DocumentRepository {

    private DocumentRepository() { }

    /**
     * What placing a file did.
     *
     * @param ok     whether the file is committed and can be opened
     * @param root   the repository root, or {@code null} when none was reached
     * @param file   the file's path in that repository, or {@code null} when none was reached
     * @param report what happened, for the status line
     */
    record Placed(boolean ok, Path root, Path file, String report) { }

    /**
     * Says why a file cannot be placed at a location.
     *
     * @param file     the file
     * @param location the folder for it
     * @return the reason, or empty when {@link #place} may proceed
     */
    static Optional<String> refusal(Path file, Path location) {
        Path source = file.toAbsolutePath().normalize();
        Path folder = location.toAbsolutePath().normalize();
        Path target = target(source, folder);
        if (!target.equals(source) && Files.exists(target)) {
            return Optional.of(target + " already exists.");
        }
        return GitRepo.findRoot(folder).isPresent() ? Optional.empty() : GitOperations.initRefusal(folder);
    }

    /** What choosing a folder does with a document whose file no known working tree holds. */
    enum Placement {
        /** The folder's repository holds the file, so the commit goes there. */
        USE,
        /** A repository that does not hold the file takes a copy. */
        COPY,
        /** A folder in no repository gets one, and a copy. */
        CREATE,
        /** The folder will not do. */
        REFUSED
    }

    /**
     * What choosing a folder would do, in words the panel can show before the person commits to it.
     *
     * @param placement what happens
     * @param folder    the folder it happens in: for {@link Placement#USE} the repository's top
     * @param action    the action button's label
     * @param note      what will happen, or why it will not
     */
    record Plan(Placement placement, Path folder, String action, String note) { }

    /**
     * Works out what choosing a folder would do. The folder decides, so one panel serves both
     * finding the repository that holds a file and making a home for a copy: anywhere inside the
     * repository that holds it is that repository; otherwise a copy goes to the folder, in the
     * repository there or in a new one.
     *
     * @param folder the folder, which need not exist yet
     * @param path   the file's repository-relative path
     * @param copy   whether a copy is an answer at all; when not, only the repository that holds the file will do
     * @return the plan
     */
    static Plan plan(Path folder, String path, boolean copy) {
        Path target = folder.toAbsolutePath().normalize();
        String name = Path.of(path).getFileName().toString();
        Optional<Path> root = GitRepo.findRoot(target);
        if (root.isPresent() && Files.isRegularFile(root.get().resolve(path))) {
            return new Plan(Placement.USE, root.get(), "Use This Repository", name + " is in this repository, so the commit goes here.");
        }
        if (!copy) {
            return refused(target, workingTreeRefusal(target, path).orElse(target + " does not hold " + path + "."));
        }
        if (Files.exists(target) && !Files.isDirectory(target)) {
            return refused(target, target + " is not a folder.");
        }
        Optional<String> refused = copyRefusal(name, target);
        if (refused.isPresent()) {
            return refused(target, refused.get());
        }
        String copied = "A copy of the committed text becomes a new document here, with its own history, and you select the lines again. "
                + "This document stays as it is.";
        return root.isPresent()
                ? new Plan(Placement.COPY, target, "Copy Here", "This repository does not hold " + name + ". " + copied)
                : new Plan(Placement.CREATE, target, "Create Repository Here",
                (Files.exists(target) ? "This folder is in no repository, so one is made here. " : target + " does not exist yet, so it is made, with a repository. ") + copied);
    }

    /**
     * Works out what choosing a folder would do with a file that is in no repository, in the
     * words {@link #place} will act on: the file's own folder or one above it becomes a
     * repository around it; any other folder takes a copy, in the repository there or a new one.
     *
     * @param file     the file
     * @param location the folder for it, which need not exist yet
     * @return the plan
     */
    static Plan placing(Path file, Path location) {
        Path source = file.toAbsolutePath().normalize();
        Path folder = location.toAbsolutePath().normalize();
        String name = source.getFileName().toString();
        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            return refused(folder, folder + " is not a folder.");
        }
        Optional<String> reason = refusal(source, folder);
        if (reason.isPresent()) {
            return refused(folder, reason.get());
        }
        String committed = " Only this file is committed, locally, and nothing is pushed.";
        if (GitRepo.findRoot(folder).isPresent()) {
            return new Plan(Placement.COPY, folder, "Copy Here", name + " is copied into this repository. The original stays where it is." + committed);
        }
        if (source.startsWith(folder)) {
            return new Plan(Placement.CREATE, folder, "Create Repository Here", "This folder becomes a repository, and " + name + " stays where it is." + committed);
        }
        return new Plan(Placement.CREATE, folder, "Create Repository Here",
                (Files.exists(folder) ? "This folder is in no repository, so one is made here. " : folder + " does not exist yet, so it is made, with a repository. ")
                        + name + " is copied in, and the original stays where it is." + committed);
    }

    /**
     * Works out what choosing a folder for a new, empty repository would do.
     *
     * @param folder the folder, which need not exist yet
     * @return the plan
     */
    static Plan making(Path folder) {
        Path target = folder.toAbsolutePath().normalize();
        Optional<String> reason = GitOperations.initRefusal(target);
        return reason.map(why -> refused(target, why)).orElseGet(() -> new Plan(Placement.CREATE, target, "Create Repository Here",
                (Files.exists(target) ? "A repository is made in this folder." : target + " does not exist yet, so it is made, with a repository.")
                        + " Nothing is committed yet, and it stays on this machine until you add a remote and push."));
    }

    /**
     * A plan that goes nowhere.
     *
     * @param folder the folder refused
     * @param reason why
     * @return the plan
     */
    static Plan refused(Path folder, String reason) {
        return new Plan(Placement.REFUSED, folder, "Choose", reason);
    }

    /**
     * Says why a stored document's text cannot be copied to a location.
     *
     * @param name     the file name the copy would have
     * @param location the folder for it
     * @return the reason, or empty when {@link #placeCopy} may proceed
     */
    static Optional<String> copyRefusal(String name, Path location) {
        Path target = location.toAbsolutePath().normalize().resolve(name);
        return Files.exists(target) ? Optional.of(target + " already exists.") : refusal(target, location);
    }

    /**
     * Says why a folder is not the working tree of a document's file. Annotations and commits go
     * to the repository that holds the file, and its paths are relative to the repository's top,
     * so only that folder will do.
     *
     * @param folder the folder the person gave
     * @param path   the file's repository-relative path
     * @return the reason, or empty when the folder is the file's working tree
     */
    static Optional<String> workingTreeRefusal(Path folder, String path) {
        Path target = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            return Optional.of(target + (Files.exists(target) ? " is not a folder." : " does not exist."));
        }
        Optional<Path> root = GitRepo.findRoot(target);
        if (root.isEmpty()) {
            return Optional.of(target + " is not in a git repository.");
        }
        if (!root.get().equals(target)) {
            return Optional.of(target + " is inside the repository at " + root.get() + ". Give that folder.");
        }
        return Files.isRegularFile(target.resolve(path)) ? Optional.empty() : Optional.of(target + " does not hold " + path + ".");
    }

    /**
     * Places a document's text, read from the store, as {@link #place} places a file. No file of
     * it need exist anywhere: this is how a document whose repository is out of reach gets one.
     *
     * @param name     the file name the copy has
     * @param text     the document's text
     * @param location the folder for it
     * @return what happened
     * @throws IOException if the text cannot be written or git cannot be run
     */
    static Placed placeCopy(String name, String text, Path location) throws IOException {
        Path folder = Files.createTempDirectory("komet-document");
        Path copy = folder.resolve(name);
        try {
            Files.writeString(copy, text);
            return place(copy, location);
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(folder);
        }
    }

    /**
     * Places a file: makes the repository the location needs, copies the file in when it lies
     * outside, and commits it. A failure leaves what was already done, and says so: a repository
     * is never removed, since a {@code .git} deleted in a synced tree propagates.
     *
     * @param file     the file
     * @param location the folder for it
     * @return what happened
     * @throws IOException if the file cannot be copied or git cannot be run
     */
    static Placed place(Path file, Path location) throws IOException {
        Optional<String> refused = refusal(file, location);
        if (refused.isPresent()) {
            return new Placed(false, null, null, refused.get());
        }
        Path source = file.toAbsolutePath().normalize();
        Path folder = location.toAbsolutePath().normalize();
        Optional<Path> existing = GitRepo.findRoot(folder);
        if (existing.isEmpty()) {
            GitOperations.Run init = GitOperations.init(folder);
            if (!init.ok()) {
                return new Placed(false, null, null, init.report("init"));
            }
        }
        Path root = existing.orElse(folder);
        Path target = target(source, folder);
        boolean copied = !target.equals(source);
        if (copied) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target);
        }
        String relative = root.relativize(target).toString().replace('\\', '/');
        DocumentCommit.Result committed = DocumentCommit.first(root, relative, "Add " + target.getFileName() + "\n");
        String where = (existing.isEmpty() ? "Made a repository at " : "Used the repository at ") + root
                + (copied ? " and copied the file in" : "");
        if (!committed.ok()) {
            return new Placed(false, root, target, where + ", but the file is not committed: " + committed.output());
        }
        return new Placed(true, root, target, where + "; first commit " + committed.sha().substring(0, 7) + ", nothing pushed.");
    }

    /** The file's place at the location: where it is when the location holds it, else directly in the location. */
    private static Path target(Path source, Path folder) {
        return source.startsWith(folder) ? source : folder.resolve(source.getFileName());
    }
}
