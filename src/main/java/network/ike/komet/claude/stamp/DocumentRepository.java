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
