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

    @Test
    void aStoredDocumentsTextGetsARepositoryWithNoFileToStartFrom() throws Exception {
        Path library = root.resolve("komet/docs");

        DocumentRepository.Placed placed = DocumentRepository.placeCopy("doc.adoc", "= A Document\n", library);

        assertTrue(placed.ok(), placed.report());
        assertEquals(library, placed.root());
        assertEquals("= A Document\n", Files.readString(library.resolve("doc.adoc")));
        assertTrue(GitOperations.committed(library, Path.of("doc.adoc")));

        DocumentRepository.Placed second = DocumentRepository.placeCopy("other.adoc", "= Another\n", library);

        assertTrue(second.ok(), second.report());
        assertTrue(second.report().startsWith("Used the repository"), "the library is one repository");
    }

    @Test
    void aCopyNeverOverwritesADocumentOfThatName() throws Exception {
        Path taken = document("documents/doc.adoc");
        Path library = taken.getParent();

        assertTrue(DocumentRepository.copyRefusal("doc.adoc", library).orElseThrow().contains("already exists"));
        assertFalse(DocumentRepository.placeCopy("doc.adoc", "Mine.\n", library).ok());
        assertEquals("= A Document\n\nIts first paragraph.\n", Files.readString(taken));
        assertTrue(DocumentRepository.copyRefusal("free.adoc", library).isEmpty());
    }

    @Test
    void theFolderChosenDecidesWhatHappens() throws Exception {
        Path holding = root.resolve("holding");
        assertTrue(GitOperations.init(holding).ok());
        document("holding/topics/doc.adoc");
        Path other = root.resolve("other");
        assertTrue(GitOperations.init(other).ok());
        Path plain = document("plain/note.adoc").getParent();
        Path missing = root.resolve("komet/docs");

        assertEquals(DocumentRepository.Placement.USE, DocumentRepository.plan(holding, "topics/doc.adoc", true).placement());
        DocumentRepository.Plan inside = DocumentRepository.plan(holding.resolve("topics"), "topics/doc.adoc", true);
        assertEquals(DocumentRepository.Placement.USE, inside.placement(), "anywhere in the repository that holds it is that repository");
        assertEquals(holding, inside.folder());
        assertEquals(DocumentRepository.Placement.COPY, DocumentRepository.plan(other, "topics/doc.adoc", true).placement());
        assertEquals(DocumentRepository.Placement.CREATE, DocumentRepository.plan(plain, "topics/doc.adoc", true).placement());
        DocumentRepository.Plan toCome = DocumentRepository.plan(missing, "topics/doc.adoc", true);
        assertEquals(DocumentRepository.Placement.CREATE, toCome.placement());
        assertTrue(toCome.note().contains("does not exist yet"));
        assertFalse(Files.exists(missing), "planning makes nothing");
    }

    @Test
    void aFileInNoRepositoryIsPlacedAsTheFolderSays() throws Exception {
        Path file = document("downloads/doc.adoc");
        Path repository = root.resolve("repository");
        assertTrue(GitOperations.init(repository).ok());
        Path library = root.resolve("komet/docs");

        DocumentRepository.Plan own = DocumentRepository.placing(file, file.getParent());
        assertEquals(DocumentRepository.Placement.CREATE, own.placement());
        assertTrue(own.note().contains("stays where it is"), own.note());
        assertEquals(DocumentRepository.Placement.COPY, DocumentRepository.placing(file, repository).placement());
        DocumentRepository.Plan toCome = DocumentRepository.placing(file, library);
        assertEquals(DocumentRepository.Placement.CREATE, toCome.placement());
        assertTrue(toCome.note().contains("does not exist yet"), toCome.note());
        assertFalse(Files.exists(library), "planning makes nothing");
    }

    @Test
    void aFolderThatCannotTakeTheFileIsRefusedAsPlaceRefusesIt() throws Exception {
        Path file = document("downloads/doc.adoc");
        Path taken = document("documents/doc.adoc").getParent();
        Files.createDirectories(root.resolve("synced/.stfolder"));

        DocumentRepository.Plan name = DocumentRepository.placing(file, taken);
        assertEquals(DocumentRepository.Placement.REFUSED, name.placement());
        assertTrue(name.note().contains("already exists"), name.note());
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.placing(file, root.resolve("synced/notes")).placement());
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.placing(file, taken.resolve("doc.adoc")).placement());
        assertFalse(DocumentRepository.place(file, taken).ok(), "the plan and the placing agree");
    }

    @Test
    void aNewRepositoryGoesInAFolderNoRepositoryHolds() throws Exception {
        Path existing = root.resolve("repository");
        assertTrue(GitOperations.init(existing).ok());
        Path plain = document("plain/note.adoc").getParent();
        Path missing = root.resolve("komet/docs");

        assertEquals(DocumentRepository.Placement.CREATE, DocumentRepository.making(plain).placement());
        DocumentRepository.Plan toCome = DocumentRepository.making(missing);
        assertEquals(DocumentRepository.Placement.CREATE, toCome.placement());
        assertTrue(toCome.note().contains("does not exist yet"), toCome.note());
        assertFalse(Files.exists(missing), "planning makes nothing");
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.making(existing).placement());
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.making(existing.resolve("topics")).placement());
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.making(Path.of(System.getProperty("user.home"))).placement());
    }

    @Test
    void aFolderThatCannotTakeTheCopyIsRefusedWithItsReason() throws Exception {
        Path taken = document("documents/doc.adoc").getParent();
        Path other = root.resolve("other");
        assertTrue(GitOperations.init(other).ok());

        DocumentRepository.Plan name = DocumentRepository.plan(taken, "topics/doc.adoc", true);
        assertEquals(DocumentRepository.Placement.REFUSED, name.placement());
        assertTrue(name.note().contains("already exists"));
        DocumentRepository.Plan home = DocumentRepository.plan(Path.of(System.getProperty("user.home")), "topics/doc.adoc", true);
        assertEquals(DocumentRepository.Placement.REFUSED, home.placement(), home.note());
        assertEquals(DocumentRepository.Placement.REFUSED, DocumentRepository.plan(taken.resolve("doc.adoc"), "topics/doc.adoc", true).placement());
    }

    @Test
    void whenACopyIsNoAnswerOnlyTheRepositoryThatHoldsTheFileWillDo() throws Exception {
        Path holding = root.resolve("holding");
        assertTrue(GitOperations.init(holding).ok());
        document("holding/doc.adoc");
        Path other = root.resolve("other");
        assertTrue(GitOperations.init(other).ok());

        assertEquals(DocumentRepository.Placement.USE, DocumentRepository.plan(holding, "doc.adoc", false).placement());
        DocumentRepository.Plan refused = DocumentRepository.plan(other, "doc.adoc", false);
        assertEquals(DocumentRepository.Placement.REFUSED, refused.placement());
        assertTrue(refused.note().contains("does not hold"));
    }

    @Test
    void theWorkingTreeIsTheTopOfTheRepositoryThatHoldsTheFile() throws Exception {
        Path repository = root.resolve("repository");
        assertTrue(GitOperations.init(repository).ok());
        document("repository/topics/doc.adoc");
        Path outside = document("elsewhere/doc.adoc").getParent();

        assertTrue(DocumentRepository.workingTreeRefusal(repository, "topics/doc.adoc").isEmpty());
        assertTrue(DocumentRepository.workingTreeRefusal(repository, "doc.adoc").orElseThrow().contains("does not hold"));
        assertTrue(DocumentRepository.workingTreeRefusal(repository.resolve("topics"), "doc.adoc").orElseThrow().contains("inside the repository"));
        assertTrue(DocumentRepository.workingTreeRefusal(outside, "doc.adoc").orElseThrow().contains("not in a git repository"));
        assertTrue(DocumentRepository.workingTreeRefusal(root.resolve("missing"), "doc.adoc").orElseThrow().contains("does not exist"));
        assertTrue(DocumentRepository.workingTreeRefusal(outside.resolve("doc.adoc"), "doc.adoc").orElseThrow().contains("not a folder"));
    }
}
