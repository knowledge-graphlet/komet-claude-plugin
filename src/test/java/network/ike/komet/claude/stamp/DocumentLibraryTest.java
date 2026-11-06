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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The library's folder, listing and details, on real files with the git on PATH. */
class DocumentLibraryTest {

    @TempDir
    Path scratch;

    private Path library;

    @BeforeEach
    void aLibraryOfItsOwn() throws Exception {
        assumeTrue(GitOperations.git(scratch, "config", "--global", "user.email").ok(), "no global git identity");
        library = scratch.resolve("komet/docs");
        System.setProperty(DocumentLibrary.LOCATION_PROPERTY, library.toString());
    }

    @AfterEach
    void theStandardPlaceAgain() {
        System.clearProperty(DocumentLibrary.LOCATION_PROPERTY);
    }

    private Path document(String relative, String text) throws Exception {
        Path file = library.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void theStandardPlaceIsKometDocsInTheHomeFolder() {
        System.clearProperty(DocumentLibrary.LOCATION_PROPERTY);

        assertEquals(Path.of(System.getProperty("user.home"), "komet", "docs"), DocumentLibrary.root());
        assertEquals("~/komet/docs", DocumentLibrary.shownRoot());
    }

    @Test
    void ensureMakesTheFolderAndItsRepositoryOnce() throws Exception {
        assertEquals(library, DocumentLibrary.ensure());
        assertTrue(Files.exists(library.resolve(".git")));
        assertTrue(GitOperations.log(library, null, 1).isEmpty(), "nothing is committed");

        Files.writeString(library.resolve("kept.adoc"), "= Kept\n");
        assertEquals(library, DocumentLibrary.ensure());
        assertTrue(Files.exists(library.resolve("kept.adoc")));
    }

    @Test
    void aSyncedLocationIsRefused() throws Exception {
        Files.createDirectories(scratch.resolve("komet/.stfolder"));

        java.io.IOException refused = assertThrows(java.io.IOException.class, DocumentLibrary::ensure);

        assertTrue(refused.getMessage().contains("synced folder"), refused.getMessage());
        assertFalse(Files.exists(library.resolve(".git")));
    }

    @Test
    void aFolderListsItsFoldersThenItsDocuments() throws Exception {
        DocumentLibrary.ensure();
        document("beta.adoc", "= Beta\n");
        document("Alpha.asciidoc", "= Alpha\n");
        document("notes.txt", "not a document\n");
        document(".hidden.adoc", "= Hidden\n");
        document("zeta/inner.adoc", "= Inner\n");
        Files.createDirectories(library.resolve("attic"));

        assertEquals(List.of("attic", "zeta", "Alpha.asciidoc", "beta.adoc"),
                DocumentLibrary.children(library).stream().map(path -> path.getFileName().toString()).toList());
    }

    @Test
    void aCommittedDocumentHasItsTitleStatusAndLastCommit() throws Exception {
        DocumentLibrary.ensure();
        Path file = document("topics/doc.adoc", ":topic-id: doc\n\n= A Document\n\nIts first paragraph.\n");
        assertTrue(DocumentCommit.first(library, "topics/doc.adoc", "Add the document\n").ok());

        DocumentLibrary.Details details = DocumentLibrary.details(file);

        assertEquals("A Document", details.title());
        assertEquals(library.toRealPath(), details.repository().orElseThrow().toRealPath());
        assertEquals("topics/doc.adoc", details.relative());
        assertEquals("clean", details.status());
        assertEquals(1, details.commits());
        assertEquals("Add the document", details.last().orElseThrow().summary());

        String text = DocumentLibrary.markdown(details, Optional.of(new DocumentLibrary.Stored(4, 1)));
        assertTrue(text.startsWith("### A Document\n"), text);
        assertTrue(text.contains("committed, no changes"), text);
        assertTrue(text.contains("4 lines, 1 affirmed"), text);

        Files.writeString(file, "= Changed\n");
        assertTrue(DocumentLibrary.markdown(DocumentLibrary.details(file), Optional.empty()).contains("changed since the last commit"));
    }

    @Test
    void aFileNotYetCommittedOrInNoRepositorySaysSo() throws Exception {
        DocumentLibrary.ensure();
        Path untracked = document("new.adoc", "no title here\n");
        String text = DocumentLibrary.markdown(DocumentLibrary.details(untracked), Optional.empty());
        assertTrue(text.startsWith("### new.adoc\n"), text);
        assertTrue(text.contains("offers a first commit"), text);
        assertTrue(text.contains("not read in yet"), text);

        Path outside = Files.createDirectories(scratch.resolve("plain")).resolve("doc.adoc");
        Files.writeString(outside, "= Plain\n");
        assertTrue(DocumentLibrary.details(outside).repository().isEmpty());
        assertTrue(DocumentLibrary.markdown(DocumentLibrary.details(outside), Optional.empty()).contains("opening a document here offers it one"));
    }

    @Test
    void aFolderCountsItsDocumentsAtAnyDepth() throws Exception {
        DocumentLibrary.ensure();
        document("a.adoc", "= A\n");
        document("deep/er/b.adoc", "= B\n");
        document("deep/notes.txt", "no\n");
        document(".hidden/c.adoc", "= C\n");

        DocumentLibrary.Details details = DocumentLibrary.details(library);

        assertTrue(details.folder());
        assertEquals(2, details.documents());
        assertTrue(DocumentLibrary.markdown(details, Optional.empty()).contains("**Documents** 2"));
    }
}
