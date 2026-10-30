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

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deleting a block (IKE-Network/ike-issues#806, #1300): a leaf block removed from the graph is a
 * pending edit, its lines gone from the pending document and its block inactive, the committed
 * document untouched; the person commits the exported file in git; materializing the commit and
 * settling leaves one document, without the block and with no pending versions, that exports back
 * as the committed file. Not a relaxation of {@link DocumentEditor#editBlock}, which still keeps
 * a line. The assistant proposes the same deletion through {@code propose_block_delete}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentBlockDeletionIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String ORIGINAL = """
            :topic-id: anf-deletion
            = Deletion

            The first line.
            The second line.

            Another paragraph.

            ----
            x
            y
            ----

            == Section

            In the section.
            """;

    @TempDir
    Path root;

    EditCoordinateRecord edit;

    /** The document model this class runs over: the prose-element model; a vertex subclass overrides it (IKE-Network/ike-issues#1313). */
    String model() {
        return "prose";
    }

    @BeforeAll
    void setupDatabase() {
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, model());
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        PrimitiveData.stop();
    }

    @Test
    void aDeletedParagraphIsPendingThenSettlesIntoOneDocument() throws Exception {
        Path dir = repository("paragraph");
        Path file = dir.resolve("deletion.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        long block = DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        StoredDocument before = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        DocumentEditor.Edit removed = DocumentEditor.deleteBlock(anchor, block, edit);

        assertEquals(new DocumentEditor.Edit(block, 0, 0, 1), removed);
        assertEquals(original(dir), export(anchor, false), "the committed document is untouched by a pending deletion");
        String expected = original(dir).replace("\nAnother paragraph.\n", "");
        assertEquals(expected, export(anchor, true), "the pending document lacks the block and the blank line that separated it");
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(before.blocks().size() - 1, pending.blocks().size(), "one block fewer");
        assertFalse(pending.blocks().containsKey(block), "the deleted block is not among the pending document's blocks");
        assertTrue(pending.blocks().values().stream().noneMatch(b -> b.members().contains(block)), "no parent lists it");
        SemanticEntityVersion inactive = pendingVersion(block);
        assertEquals(State.INACTIVE, inactive.state(), "the block's pending version is inactive");
        assertTrue(DocumentEditor.pending(anchor).isPresent());
        assertTrue(DocumentEditor.pendingAuthor(anchor).isPresent());
        assertTrue(pending.lines().stream().noneMatch(l -> l.text().equals("Another paragraph.")));

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, export(anchor, true), "bob");

        assertTrue(settlement.settled() && settlement.carried(), settlement.toString());
        assertEquals(List.of(), settlement.notCarried());
        assertEquals(List.of(), settlement.committedOnly());
        assertSettledAs(anchor, dir, expected);
        StoredDocument settled = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(before.blocks().size() - 1, settled.blocks().size());
        assertFalse(settled.blocks().containsKey(block), "the commit retired the block where git recorded its removal");
        assertEquals(List.of(), DocumentEditor.restorable(anchor), "a carried deletion leaves nothing to restore");
    }

    @Test
    void theLastBlockOfTheFileTakesTheBlankBeforeItAndALeadingBlockTheBlankAfterIt() throws Exception {
        Path dir = repository("edges");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("deletion.adoc")).anchor();

        DocumentEditor.deleteBlock(anchor, DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "In the section."), edit);
        String expected = original(dir).replace("\nIn the section.\n", "");
        assertEquals(expected, export(anchor, true), "the section keeps its heading; the blank before the paragraph goes with it");

        DocumentEditor.deleteBlock(anchor, DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.LISTING, "----"), edit);
        expected = expected.replace("\n----\nx\ny\n----\n", "");
        assertEquals(expected, export(anchor, true), "a second deletion joins the same pending edit");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, export(anchor, true), "bob");
        assertTrue(settlement.carried(), settlement.toString());
        assertSettledAs(anchor, dir, expected);
    }

    @Test
    void discardingBringsTheBlockBack() throws Exception {
        Path dir = repository("discard");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("deletion.adoc")).anchor();
        long block = DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        DocumentEditor.deleteBlock(anchor, block, edit);
        assertFalse(export(anchor, true).contains("The first line."));

        assertTrue(DocumentEditor.cancel(anchor));

        assertEquals(original(dir), export(anchor, true), "cancelled, the document reads as committed");
        assertTrue(GitDocumentMaterializer.load(anchor, true).orElseThrow().blocks().containsKey(block), "the block is active again");
    }

    @Test
    void aBlockHoldingBlocksIsNotDeletedAndAnEditStillKeepsALine() throws Exception {
        Path dir = repository("refuses");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("deletion.adoc")).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        long section = document.blocks().values().stream().filter(b -> b.kind() == Block.Kind.SECTION).findFirst().orElseThrow().nid();
        long paragraph = DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");

        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.deleteBlock(anchor, section, edit), "a section is emptied block by block");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.deleteBlock(anchor, document.lines().getFirst().nid(), edit), "a line is not a block");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.editBlock(anchor, paragraph, "", edit),
                "editing a block to nothing is still refused: deleting is its own verb");
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "a refused deletion writes nothing");
    }

    // ── the deletion as a Komet action and as the assistant's proposal ──────────

    @Test
    void theDeletionIsPerformedAsAnActionAndProposedByTheAssistantThroughTheCardsPath() throws Exception {
        Path dir = repository("action");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("deletion.adoc")).anchor();
        long block = DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        KometAction action = KometActions.expect(KometConcept.DELETE_DOCUMENT_BLOCK);
        assertTrue(action instanceof DeleteDocumentBlock, "the plugin implements Delete document block: " + action);

        KometActionInput input = DeleteDocumentBlock.input(block);
        KometActionResult result = action.perform(input, Calculators.View.Default(), edit);

        assertTrue(result.completed(), result.message());
        assertEquals(input.id(), result.input());
        assertTrue(result.message().startsWith("a block of 1 line removed"), result.message());
        assertEquals(original(dir).replace("\nAnother paragraph.\n", ""), export(anchor, true));
        assertEquals(original(dir), export(anchor, false), "the deletion is uncommitted");
        assertEquals(State.INACTIVE, dev.ikm.tinkar.entity.EntityService.get().getStampFast(result.writtenVersion().orElseThrow().nid()).state(),
                "the version written is the block's inactive one");

        EditCoordinateRecord assistant = EditCoordinateRecord.make(IkeConcept.KOMET_ASSISTANT.nid(), edit.getDefaultModuleNid(), edit.getDefaultPathNid());
        long other = DocumentBlockInsertionIT.blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        KometActionResult refused = action.perform(DeleteDocumentBlock.input(other), Calculators.View.Default(), assistant);
        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        DocumentEditor.cancel(anchor);

        Map<String, AnthropicTool> tools = new java.util.HashMap<>();
        for (AnthropicTool tool : DocumentGraphTools.tools(() -> null, () -> null)) {
            tools.put(tool.name(), tool);
        }
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        String path = new DocumentOutline(document).nodes().stream().filter(n -> n.block().nid() == block).findFirst().orElseThrow().path();
        String name = GitDocumentMaterializer.storedDocuments().entrySet().stream().filter(e -> e.getValue().equals(anchor)).findFirst().orElseThrow().getKey();

        String proposed = tools.get("propose_block_delete").execute(Map.of("document", name, "block", path));

        assertTrue(proposed.startsWith("Proposed for §" + path), proposed);
        assertTrue(proposed.contains("Discard edit brings the block back"), proposed);
        assertEquals(IkeConcept.KOMET_ASSISTANT.nid(), DocumentEditor.pendingAuthor(anchor).orElseThrow(), "the deletion is the assistant's");
        assertEquals(original(dir).replace("\nAnother paragraph.\n", ""), export(anchor, true));
        String shown = tools.get("read_document").execute(Map.of("document", name));
        assertFalse(shown.contains("Another paragraph."), shown);
        assertTrue(shown.contains("Pending edit (uncommitted"), shown);
        String sectionPath = new DocumentOutline(GitDocumentMaterializer.load(anchor, true).orElseThrow()).nodes().stream()
                .filter(n -> n.block().kind() == Block.Kind.SECTION).findFirst().orElseThrow().path();
        assertEquals("3", sectionPath, "with the paragraph pending deletion, the section is the third block the assistant sees");
        String refusedTool = tools.get("propose_block_delete").execute(Map.of("document", name, "block", sectionPath));
        assertTrue(refusedTool.startsWith("Error: §" + sectionPath + " is a SECTION holding"), refusedTool);
        DocumentEditor.cancel(anchor);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The document as a repository holds it: {@link #ORIGINAL} with that repository's own topic id. */
    static String original(Path dir) {
        return ORIGINAL.replace(":topic-id: anf-deletion", ":topic-id: anf-deletion-" + dir.getFileName());
    }

    Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        DocumentBlockInsertionIT.git(dir, "init", "-q", "-b", "main");
        // one topic id per repository, so the assistant's tools resolve the document by name without ambiguity
        Files.writeString(dir.resolve("deletion.adoc"), ORIGINAL.replace(":topic-id: anf-deletion", ":topic-id: anf-deletion-" + name));
        commit(dir, "alice", "Add the document");
        return dir;
    }

    private int day;

    private void commit(Path dir, String author, String message) throws Exception {
        DocumentBlockInsertionIT.git(dir, "add", "deletion.adoc");
        DocumentBlockInsertionIT.git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test",
                "commit", "-q", "--no-gpg-sign", "--date=2026-03-" + String.format("%02d", ++day) + "T00:00:00Z", "-m", message);
    }

    private static String export(PublicId anchor, boolean pending) {
        return GitDocumentExporter.export(anchor, pending).orElseThrow().text();
    }

    private DocumentEditor.Settlement commitAndSettle(Path dir, PublicId anchor, String text, String author) throws Exception {
        Path file = dir.resolve("deletion.adoc");
        Files.writeString(file, text);
        commit(dir, author, "Commit the edit");
        GitDocumentMaterializer.materialize(dir, file);
        return DocumentEditor.settle(anchor);
    }

    private static void assertSettledAs(PublicId anchor, Path dir, String committed) throws Exception {
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing is left pending");
        assertEquals(committed, Files.readString(dir.resolve("deletion.adoc")));
        assertEquals(committed, export(anchor, false), "the committed document is the commit");
        assertEquals(committed, export(anchor, true), "no pending version remains");
        assertEquals(0, DocumentEditingIT.pendingVersionsPerElement(anchor));
        assertFalse(GitDocumentMaterializer.load(anchor, true).orElseThrow().lines().stream().anyMatch(DocumentSurfaceView::uncommitted));
        assertEquals(GitDocumentExporter.Relation.IDENTICAL_TO_HEAD,
                GitDocumentExporter.compare(GitDocumentExporter.export(anchor).orElseThrow(), dir).relation());
    }

    private static SemanticEntityVersion pendingVersion(long nid) {
        for (Object o : EntityHandle.get(nid).expectSemantic().versions()) {
            if (o instanceof SemanticEntityVersion v && v.uncommitted() && !v.canceled()) {
                return v;
            }
        }
        throw new AssertionError("no pending version of " + nid);
    }
}
