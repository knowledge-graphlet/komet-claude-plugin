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
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding a block (IKE-Network/ike-issues#806, #1300): a new paragraph or listing inserted after a
 * chosen block is pending, uncommitted, beside the committed document, and shows on the surface as
 * uncommitted lines in a block of its own; the person commits the exported file in git;
 * materializing the commit and settling leaves one document, with the commit's lines and no
 * provisional versions, that exports back as the committed file. The assistant proposes the same
 * insertion through {@code propose_block_insert}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentBlockInsertionIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String ORIGINAL = """
            :topic-id: anf-insertion
            = Insertion

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
    void aParagraphAddedAfterABlockIsPendingThenSettlesIntoOneDocument() throws Exception {
        Path dir = repository("paragraph");
        Path file = dir.resolve("insertion.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        long after = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        int blocksBefore = GitDocumentMaterializer.load(anchor, true).orElseThrow().blocks().size();

        DocumentEditor.Edit added = DocumentEditor.addBlock(anchor, after, Block.Kind.PARAGRAPH, "A new paragraph.\nIts second line.\n", edit);

        assertEquals(2, added.added());
        assertEquals(original(dir), export(anchor, false), "the committed document is untouched by a pending insertion");
        String expected = original(dir).replace("The second line.\n", "The second line.\n\nA new paragraph.\nIts second line.\n");
        assertEquals(expected, export(anchor, true), "the pending document carries the new block after the chosen one, a blank line between");
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(blocksBefore + 1, pending.blocks().size(), "one more block");
        StoredBlock block = pending.blocks().get(added.block());
        assertEquals(Block.Kind.PARAGRAPH, block.kind());
        assertEquals(List.of("A new paragraph.", "Its second line."), block.members().stream().map(nid -> pending.line(nid).orElseThrow().text()).toList());
        StoredBlock parent = pending.blocks().values().stream().filter(b -> b.members().contains(added.block())).findFirst().orElseThrow();
        assertEquals(parent.members().indexOf(after) + 1, parent.members().indexOf(added.block()), "the parent lists it right after the chosen block");
        assertEquals(List.of("A new paragraph.", "Its second line."),
                pending.lines().stream().filter(DocumentSurfaceView::uncommitted).map(StoredLine::text).filter(t -> !t.isBlank()).toList(),
                "the surface marks exactly the new lines as edited in Komet");
        assertTrue(DocumentEditor.pending(anchor).isPresent());
        assertEquals(List.of("A new paragraph.", "Its second line."), new DocumentOutline(pending).find("2").orElseThrow().lines().stream()
                .map(StoredLine::text).toList(), "the outline shows the new block as §2, so read_document and propose_block_edit see it");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, export(anchor, true), "bob");

        assertTrue(settlement.settled() && settlement.carried(), settlement.toString());
        assertEquals(List.of(), settlement.notCarried());
        assertEquals(List.of(), settlement.committedOnly());
        assertSettledAs(anchor, dir, expected);
        StoredDocument settled = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(blocksBefore + 1, settled.blocks().size(), "the commit's own block stands where the provisional one was");
        assertTrue(settled.blocks().values().stream().noneMatch(b -> b.nid() == added.block()), "the provisional block is cancelled with the edit");
        long committed = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "A new paragraph.");
        assertEquals("bob", StampNames.name(Calculators.View.Default(), settled.blocks().get(committed).stamp().authorNid()),
                "the new block carries the commit's author");
        assertEquals(List.of(), DocumentEditor.restorable(anchor), "a carried insertion leaves nothing to restore");
    }

    @Test
    void aListingAddedAfterTheLastBlockOfASectionStaysInTheSection() throws Exception {
        Path dir = repository("listing");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("insertion.adoc")).anchor();
        long after = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "In the section.");

        DocumentEditor.Edit added = DocumentEditor.addBlock(anchor, after, Block.Kind.LISTING, "----\nz\n----\n", edit);

        String expected = original(dir) + "\n----\nz\n----\n";
        assertEquals(expected, export(anchor, true));
        StoredDocument pending = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        StoredBlock section = pending.blocks().values().stream().filter(b -> b.kind() == Block.Kind.SECTION).findFirst().orElseThrow();
        assertTrue(section.members().contains(added.block()), "the listing is the section's, as the text has it");
        assertEquals(Block.Kind.LISTING, pending.blocks().get(added.block()).kind());

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, export(anchor, true), "bob");
        assertTrue(settlement.carried(), settlement.toString());
        assertSettledAs(anchor, dir, expected);
    }

    /** A foreign commit elsewhere in the file settles the insertion rather than carrying it forward: what it did not carry is reported. */
    @Test
    void aForeignCommitSettlesAnInsertionAndReportsIt() throws Exception {
        Path dir = repository("foreign");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("insertion.adoc")).anchor();
        DocumentEditor.addBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph."), Block.Kind.PARAGRAPH, "Added in Komet.\n", edit);
        String foreign = original(dir).replace("In the section.", "In the section, from a pull.");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, foreign, "erin");

        assertTrue(settlement.settled() && !settlement.rebased(), settlement.toString());
        assertEquals(List.of("Added in Komet."), settlement.notCarried(), "the added line the commit did not carry is named");
        assertSettledAs(anchor, dir, foreign);
    }

    @Test
    void whatIsNotOneBlockOfTheKindIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path dir = repository("refuses");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("insertion.adoc")).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        long paragraph = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        long section = document.blocks().values().stream().filter(b -> b.kind() == Block.Kind.SECTION).findFirst().orElseThrow().nid();

        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.addBlock(anchor, paragraph, Block.Kind.PARAGRAPH, "Two\n\nparagraphs.\n", edit),
                "a blank line inside the text would make two blocks");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.addBlock(anchor, paragraph, Block.Kind.LISTING, "no delimiters\n", edit),
                "a listing keeps its delimiters");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.addBlock(anchor, paragraph, Block.Kind.PARAGRAPH, "   \n", edit));
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.addBlock(anchor, section, Block.Kind.PARAGRAPH, "After a section.\n", edit),
                "the block to insert after holds lines, not blocks");
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.addBlock(anchor, paragraph, Block.Kind.SECTION, "== New\n", edit));
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "a refused insertion writes nothing");
        assertEquals(original(dir), export(anchor, true));
    }

    // ── the insertion as a Komet action and as the assistant's proposal ─────────

    @Test
    void theInsertionIsPerformedAsAnActionAndProposedByTheAssistantThroughTheCardsPath() throws Exception {
        Path dir = repository("action");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("insertion.adoc")).anchor();
        long after = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        KometAction action = KometActions.expect(KometConcept.ADD_DOCUMENT_BLOCK);
        assertTrue(action instanceof AddDocumentBlock, "the plugin implements Add document block: " + action);

        KometActionInput input = AddDocumentBlock.input(after, Block.Kind.PARAGRAPH, "By action.\n");
        KometActionResult result = action.perform(input, Calculators.View.Default(), edit);

        assertTrue(result.completed(), result.message());
        assertEquals(input.id(), result.input());
        assertTrue(result.message().startsWith("a paragraph of 1 line added"), result.message());
        assertEquals(original(dir).replace("Another paragraph.\n", "Another paragraph.\n\nBy action.\n"), export(anchor, true));
        assertEquals(original(dir), export(anchor, false), "the insertion is uncommitted");
        assertEquals(Long.MAX_VALUE, dev.ikm.tinkar.entity.EntityService.get().getStampFast(result.writtenVersion().orElseThrow().nid()).time(),
                "the version written is the new block's, uncommitted");

        // Another author's pending edit refuses the assistant; the assistant's own proposal lands under its name.
        EditCoordinateRecord assistant = EditCoordinateRecord.make(IkeConcept.KOMET_ASSISTANT.nid(), edit.getDefaultModuleNid(), edit.getDefaultPathNid());
        KometActionResult refused = action.perform(AddDocumentBlock.input(after, Block.Kind.PARAGRAPH, "The assistant's.\n"), Calculators.View.Default(), assistant);
        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertTrue(refused.message().contains("is pending"), refused.message());
        DocumentEditor.cancel(anchor);

        Map<String, AnthropicTool> tools = new java.util.HashMap<>();
        for (AnthropicTool tool : DocumentGraphTools.tools(() -> null, () -> null)) {
            tools.put(tool.name(), tool);
        }
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        String path = new DocumentOutline(document).nodes().stream().filter(n -> n.block().nid() == after).findFirst().orElseThrow().path();
        String name = GitDocumentMaterializer.storedDocuments().entrySet().stream().filter(e -> e.getValue().equals(anchor)).findFirst().orElseThrow().getKey();

        String proposed = tools.get("propose_block_insert").execute(Map.of("document", name, "after", path, "kind", "paragraph", "text", "Proposed by the assistant."));

        assertTrue(proposed.startsWith("Proposed after §" + path), proposed);
        assertTrue(proposed.contains("Nothing is in git until the person commits it"), proposed);
        assertEquals(IkeConcept.KOMET_ASSISTANT.nid(), DocumentEditor.pendingAuthor(anchor).orElseThrow(), "the insertion is the assistant's");
        assertEquals(original(dir).replace("Another paragraph.\n", "Another paragraph.\n\nProposed by the assistant.\n"), export(anchor, true));
        String shown = tools.get("read_document").execute(Map.of("document", name));
        assertTrue(shown.contains("Proposed by the assistant.") && shown.contains("Pending edit (uncommitted"), shown);
        String refusedTool = tools.get("propose_block_insert").execute(Map.of("document", name, "after", path, "kind", "listing", "text", "no delimiters"));
        assertTrue(refusedTool.startsWith("Not proposed: "), refusedTool);
        DocumentEditor.cancel(anchor);
        assertEquals(original(dir), export(anchor, true), "discarded, the document reads as committed");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The document as a repository holds it: {@link #ORIGINAL} with that repository's own topic id. */
    static String original(Path dir) {
        return ORIGINAL.replace(":topic-id: anf-insertion", ":topic-id: anf-insertion-" + dir.getFileName());
    }

    Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        // one topic id per repository, so the assistant's tools resolve the document by name without ambiguity
        Files.writeString(dir.resolve("insertion.adoc"), ORIGINAL.replace(":topic-id: anf-insertion", ":topic-id: anf-insertion-" + name));
        commit(dir, "alice", "Add the document");
        return dir;
    }

    /** Each commit a day after the last, as commits are apart in time; git dates have one-second resolution. */
    private int day;

    private void commit(Path dir, String author, String message) throws Exception {
        git(dir, "add", "insertion.adoc");
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test",
                "commit", "-q", "--no-gpg-sign", "--date=2026-02-" + String.format("%02d", ++day) + "T00:00:00Z", "-m", message);
    }

    static String export(PublicId anchor, boolean pending) {
        return GitDocumentExporter.export(anchor, pending).orElseThrow().text();
    }

    static long blockStartingWith(PublicId anchor, Block.Kind kind, String text) {
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        return document.blocks().values().stream()
                .filter(b -> b.kind() == kind)
                .filter(b -> document.line(b.members().getFirst()).map(l -> l.text().equals(text)).orElse(false))
                .findFirst().orElseThrow().nid();
    }

    /** The person writes the file, commits it, and the commit is materialized and the edit settled. */
    private DocumentEditor.Settlement commitAndSettle(Path dir, PublicId anchor, String text, String author) throws Exception {
        Path file = dir.resolve("insertion.adoc");
        Files.writeString(file, text);
        commit(dir, author, "Commit the edit");
        GitDocumentMaterializer.materialize(dir, file);
        return DocumentEditor.settle(anchor);
    }

    /** Nothing pending, no provisional element, and the store gives back the committed file. */
    static void assertSettledAs(PublicId anchor, Path dir, String committed) throws Exception {
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing is left pending");
        assertEquals(committed, Files.readString(dir.resolve("insertion.adoc")));
        assertEquals(committed, export(anchor, false), "the committed document is the commit");
        assertEquals(committed, export(anchor, true), "no provisional element or pending version remains");
        assertEquals(0, DocumentEditingIT.pendingVersionsPerElement(anchor));
        assertFalse(GitDocumentMaterializer.load(anchor, true).orElseThrow().lines().stream().anyMatch(DocumentSurfaceView::uncommitted),
                "no line reads as edited in Komet");
        assertEquals(GitDocumentExporter.Relation.IDENTICAL_TO_HEAD,
                GitDocumentExporter.compare(GitDocumentExporter.export(anchor).orElseThrow(), dir).relation());
    }

    static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
