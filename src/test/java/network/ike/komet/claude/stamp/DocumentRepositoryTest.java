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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A file that is in no repository gets a home at the person's location: real files, the git on
 * PATH and its configured identity, as {@link GitOperationsTest} runs them.
 */
class DocumentRepositoryTest {

    @TempDir
    Path root;

    /** A repository made here has only the runner's global identity to commit with. */
    @BeforeEach
    void aGitIdentityIsConfigured() throws Exception {
        assumeTrue(GitOperations.git(root, "config", "--global", "user.email").ok(), "no global git identity");
    }

    private Path document(String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "= A Document\n\nIts first paragraph.\n");
        return file;
    }

    @Test
    void theFilesOwnFolderBecomesARepositoryAndTheFileItsFirstCommit() throws Exception {
        Path file = document("plain/doc.adoc");
        Path other = document("plain/other.adoc");

        DocumentRepository.Placed placed = DocumentRepository.place(file, file.getParent());

        assertTrue(placed.ok(), placed.report());
        assertEquals(file.getParent(), placed.root());
        assertEquals(file, placed.file());
        assertTrue(GitOperations.committed(placed.root(), Path.of("doc.adoc")));
        assertFalse(GitOperations.committed(placed.root(), Path.of("other.adoc")), "only the one file is committed");
        assertTrue(Files.exists(other));
        assertTrue(placed.report().contains("nothing pushed"));
        assertEquals(1, GitOperations.log(placed.root(), null, 10).size());
    }

    @Test
    void anAncestorFolderKeepsTheFilesPlaceInsideIt() throws Exception {
        Path file = document("plain/sub/doc.adoc");

        DocumentRepository.Placed placed = DocumentRepository.place(file, root.resolve("plain"));

        assertTrue(placed.ok(), placed.report());
        assertEquals(file, placed.file());
        assertTrue(GitOperations.committed(placed.root(), Path.of("sub/doc.adoc")));
    }

    @Test
    void aFolderElsewhereTakesACopyAndTheOriginalStays() throws Exception {
        Path file = document("downloads/doc.adoc");
        Path location = root.resolve("documents");

        DocumentRepository.Placed placed = DocumentRepository.place(file, location);

        assertTrue(placed.ok(), placed.report());
        assertEquals(location.resolve("doc.adoc"), placed.file());
        assertTrue(Files.exists(file), "the file the person had stays where it was");
        assertTrue(GitOperations.committed(location, Path.of("doc.adoc")));
        assertTrue(placed.report().contains("copied"));
    }

    @Test
    void aFolderInAnExistingRepositoryJoinsIt() throws Exception {
        Path repository = root.resolve("repository");
        assertTrue(GitOperations.init(repository).ok());
        Path file = document("downloads/doc.adoc");

        DocumentRepository.Placed placed = DocumentRepository.place(file, repository.resolve("topics"));

        assertTrue(placed.ok(), placed.report());
        assertEquals(repository, placed.root());
        assertTrue(GitOperations.committed(repository, Path.of("topics/doc.adoc")));
        assertTrue(placed.report().startsWith("Used the repository"));
    }

    @Test
    void anExistingFileOfThatNameIsNeverOverwritten() throws Exception {
        Path file = document("downloads/doc.adoc");
        Path taken = document("documents/doc.adoc");
        Files.writeString(taken, "Someone else's.\n");

        DocumentRepository.Placed placed = DocumentRepository.place(file, taken.getParent());

        assertFalse(placed.ok());
        assertEquals("Someone else's.\n", Files.readString(taken));
        assertFalse(Files.exists(taken.getParent().resolve(".git")), "refused before anything was made");
    }

    @Test
    void aSyncedFolderIsRefusedAndAFolderOutsideItIsTheWayOut() throws Exception {
        Files.createDirectories(root.resolve("synced/.stfolder"));
        Path file = document("synced/notes/doc.adoc");

        assertTrue(DocumentRepository.refusal(file, file.getParent()).orElseThrow().contains("synced folder"));
        DocumentRepository.Placed refused = DocumentRepository.place(file, file.getParent());
        assertFalse(refused.ok());
        assertFalse(Files.exists(file.getParent().resolve(".git")), "no .git enters the synced tree");

        DocumentRepository.Placed placed = DocumentRepository.place(file, root.resolve("outside"));
        assertTrue(placed.ok(), placed.report());
        assertTrue(Files.exists(file));
    }

    @Test
    void aFailedCommitLeavesTheRepositoryAndSaysWhatStands() throws Exception {
        Path repository = root.resolve("repository");
        assertTrue(GitOperations.init(repository).ok());
        Files.writeString(repository.resolve("staged.adoc"), "x\n");
        assertTrue(GitOperations.git(repository, "add", "staged.adoc").ok());
        Path file = document("downloads/doc.adoc");

        DocumentRepository.Placed placed = DocumentRepository.place(file, repository);

        assertFalse(placed.ok(), "other staged changes refuse the first commit");
        assertEquals(repository, placed.root());
        assertTrue(placed.report().contains("not committed"), placed.report());
        assertTrue(Files.exists(repository.resolve("doc.adoc")));
    }
}
