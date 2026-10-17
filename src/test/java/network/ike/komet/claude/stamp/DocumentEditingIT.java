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
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import org.eclipse.collections.api.factory.Lists;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editing round trip (ike-issues#806): a block edited in the graph is pending, uncommitted,
 * beside the committed document; the person commits the exported file in git; materializing the
 * commit and settling leaves one document, with the commit's lines and no pending or provisional
 * versions, that exports back as the committed file.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentEditingIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String ORIGINAL = """
            :topic-id: anf-editing
            = Editing

            The first line.
            The second line.

            Another paragraph.

            ----
            x
            x
            y
            ----
            """;

    @TempDir
    Path root;

    private EditCoordinateRecord edit;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void anEditCommittedInGitSettlesIntoOneDocument() throws Exception {
        Path dir = repository("settles");
        Path file = dir.resolve("editing.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");

        DocumentEditor.Edit first = DocumentEditor.editBlock(anchor, block, "The first line, revised.\nThe second line.\n", edit);
        assertEquals(new DocumentEditor.Edit(block, 1, 0, 0), first);
        assertEquals(ORIGINAL, export(anchor, false), "the committed document is untouched by a pending edit");

        DocumentEditor.Edit second = DocumentEditor.editBlock(anchor, block,
                "The first line, revised again.\nThe second line.\nA third line.\n", edit);
        assertEquals(1, second.changed());
        assertEquals(1, second.added());
        String edited = ORIGINAL.replace("The second line.\n", "The second line.\nA third line.\n")
                .replace("The first line.", "The first line, revised again.");
        assertEquals(edited, export(anchor, true), "the pending document carries the latest edit");
        assertEquals(1, uncommittedVersions(manifestOf(anchor)), "editing again replaces the pending manifest version");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, export(anchor, true), "bob");

        assertTrue(settlement.settled() && settlement.carried(), settlement.toString());
        assertEquals(List.of(), settlement.notCarried());
        assertEquals(List.of(), settlement.committedOnly());
        assertSettledAs(anchor, dir, edited);
        assertEquals(2, blocks(anchor, Block.Kind.PARAGRAPH).size(), "the edited paragraph is still one block, not two");
        assertEquals(List.of(), DocumentEditor.restorable(anchor), "a carried edit leaves nothing to restore");
    }

    /** Repeated lines pair by position: the two "x" lines and the delimiters are never confused. */
    @Test
    void repeatedLinesAreMatchedInOrder() throws Exception {
        Path dir = repository("repeated");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long listing = blockStartingWith(anchor, Block.Kind.LISTING, "----");
        DocumentEditor.editBlock(anchor, listing, "----\nx\nz\nx\ny\n----\n", edit);
        String carried = ORIGINAL.replace("x\nx\ny", "x\nz\nx\ny");

        DocumentEditor.Settlement exact = commitAndSettle(dir, anchor, carried, "bob");
        assertTrue(exact.carried(), exact.toString());
        assertSettledAs(anchor, dir, carried);

        listing = blockStartingWith(anchor, Block.Kind.LISTING, "----");
        DocumentEditor.editBlock(anchor, listing, "----\nx\nz\nq\nx\ny\n----\n", edit);
        DocumentEditor.Settlement partial = commitAndSettle(dir, anchor, carried.replace("x\nz\nx", "x\nz\nx\nx"), "carol");

        assertTrue(partial.settled() && !partial.carried(), partial.toString());
        assertEquals(List.of("q"), partial.notCarried(), "the inserted line the person did not commit");
        assertEquals(List.of("x"), partial.committedOnly(), "the extra repeated line the person added by hand");
        assertSettledAs(anchor, dir, carried.replace("x\nz\nx", "x\nz\nx\nx"));
    }

    @Test
    void handEditsCommittedBesideTheEditAreReported() throws Exception {
        Path dir = repository("hand");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        DocumentEditor.editBlock(anchor, block, "The first line, in Komet.\nThe second line.\n", edit);
        String committed = export(anchor, true).replace("Another paragraph.", "Another paragraph, by hand.");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, committed, "bob");

        assertTrue(settlement.settled() && !settlement.carried(), settlement.toString());
        assertEquals(List.of(), settlement.notCarried(), "everything edited in Komet was committed");
        assertEquals(List.of("Another paragraph, by hand."), settlement.committedOnly());
        assertSettledAs(anchor, dir, committed);
    }

    @Test
    void aRejectedEditIsCancelledAndReported() throws Exception {
        Path dir = repository("rejected");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        DocumentEditor.editBlock(anchor, block, "Another paragraph, edited in Komet.\n", edit);
        String committed = ORIGINAL.replace("Another paragraph.", "Another paragraph, by hand.");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, committed, "carol");

        assertTrue(settlement.settled() && !settlement.carried(), "a commit to the edited block settles it: " + settlement);
        assertEquals(List.of("Another paragraph, edited in Komet."), settlement.notCarried());
        assertEquals(List.of("Another paragraph, by hand."), settlement.committedOnly());
        assertSettledAs(anchor, dir, committed);
    }

    /** A commit that touches none of the edited blocks carries the edit forward; it stays pending. */
    @Test
    void aForeignCommitToAnotherBlockRebasesTheEdit() throws Exception {
        Path dir = repository("foreign");
        Path file = dir.resolve("editing.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line."),
                "The first line, being edited.\nThe second line.\nA line added in Komet.\n", edit);
        String foreign = ORIGINAL.replace("Another paragraph.", "Another paragraph, from a pull.");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, foreign, "erin");

        assertTrue(settlement.rebased() && !settlement.settled(), settlement.toString());
        assertTrue(DocumentEditor.pending(anchor).isPresent(), "the edit is still pending");
        assertEquals(foreign, export(anchor, false), "the committed document is the foreign commit");
        String rebased = foreign.replace("The first line.", "The first line, being edited.")
                .replace("The second line.\n", "The second line.\nA line added in Komet.\n");
        assertEquals(rebased, export(anchor, true), "the edit carried forward onto the foreign commit");
        assertEquals(1, uncommittedVersions(manifestOf(anchor)), "one pending manifest version, on the new base");

        DocumentEditor.Settlement later = commitAndSettle(dir, anchor, export(anchor, true), "bob");
        assertTrue(later.settled() && later.carried(), later.toString());
        assertSettledAs(anchor, dir, rebased);
    }

    /** A commit to the edited block settles and reports the edit; restoring brings it back as pending over the commit. */
    @Test
    void anEditTheCommitDidNotCarryCanBeRestoredAsPending() throws Exception {
        Path dir = repository("restore");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        DocumentEditor.editBlock(anchor, block, "Another paragraph, edited in Komet.\nA second line from Komet.\n", edit);
        String foreign = ORIGINAL.replace("The first line.", "The first line, from a pull.")
                .replace("Another paragraph.", "Another paragraph, by hand.");

        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, foreign, "erin");

        assertTrue(settlement.settled() && !settlement.carried(), settlement.toString());
        assertEquals(List.of("Another paragraph, edited in Komet.", "A second line from Komet."), settlement.notCarried());
        assertSettledAs(anchor, dir, foreign);
        GitDocumentMaterializer.StoredDocument committed = GitDocumentMaterializer.load(anchor).orElseThrow();
        long byHand = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph, by hand.");
        GitDocumentMaterializer.StoredLine changed = committed.line(
                committed.blocks().get(byHand).members().getFirst()).orElseThrow();
        assertEquals("erin", StampNames.name(dev.ikm.tinkar.coordinate.Calculators.View.Default(), changed.latest().authorNid()),
                "the line the commit changed carries the commit's author");
        List<DocumentEditor.Restorable> restorable = DocumentEditor.restorable(anchor);
        assertEquals(1, restorable.size(), restorable.toString());
        DocumentEditor.Restorable kept = restorable.getFirst();
        assertEquals(DocumentEditor.Match.CONFIRM, kept.match(), "the commit rewrote the line the block took its identity from");
        assertEquals(byHand, kept.target(), "the one paragraph where the edited one was");
        assertEquals("Another paragraph, edited in Komet.\nA second line from Komet.", kept.text(committed));
        assertEquals(0, DocumentEditor.restore(anchor), "nothing is written before the person confirms the block");
        assertTrue(DocumentEditor.pending(anchor).isEmpty());

        assertEquals(1, DocumentEditor.restore(anchor, java.util.Map.of(kept.index(), kept.target())));

        assertTrue(DocumentEditor.pending(anchor).isPresent(), "the edit is pending again");
        assertEquals(1, uncommittedVersions(manifestOf(anchor)), "exactly one pending version of the manifest");
        assertEquals(foreign, export(anchor, false), "the committed document is still the commit");
        assertEquals(foreign.replace("Another paragraph, by hand.", "Another paragraph, edited in Komet.\nA second line from Komet."),
                export(anchor, true), "the edit over the foreign text, the pull's own change kept elsewhere");
        DocumentEditor.Settlement none = DocumentEditor.settle(anchor);
        assertFalse(none.settled() || none.rebased(), "the restored edit is based on the newest commit: " + none);
        assertEquals(List.of(), DocumentEditor.restorable(anchor), "restored, nothing is left to restore");
    }

    /** The block's identifying line survived the commit: restored without asking. */
    @Test
    void anEditOverABlockThatKeptItsIdentityIsRestoredExactly() throws Exception {
        Path dir = repository("exact");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        DocumentEditor.editBlock(anchor, block, "The first line.\nThe second line, edited in Komet.\n", edit);
        String foreign = ORIGINAL.replace("The second line.", "The second line, by hand.");
        DocumentEditor.Settlement settlement = commitAndSettle(dir, anchor, foreign, "erin");
        assertTrue(settlement.settled(), settlement.toString());

        List<DocumentEditor.Restorable> restorable = DocumentEditor.restorable(anchor);
        assertEquals(1, restorable.size(), restorable.toString());
        assertEquals(DocumentEditor.Match.EXACT, restorable.getFirst().match());
        assertEquals(block, restorable.getFirst().target(), "the same block, its identity kept");
        assertEquals(1, DocumentEditor.restore(anchor));
        assertEquals(foreign.replace("The second line, by hand.", "The second line, edited in Komet."), export(anchor, true));
    }

    /** Two paragraphs where the edited one was: nothing is written until the person picks one. */
    @Test
    void severalCandidatesWaitForThePersonToPick() throws Exception {
        String original = ":topic-id: anf-pick\n= Pick\n\nIntro.\n\nTarget paragraph.\n";
        Path dir = repository("several", original);
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Target paragraph."),
                "Target paragraph, edited in Komet.\n", edit);
        String foreign = original.replace("Target paragraph.", "Target rewritten by hand.\n\nAppended paragraph.");
        assertTrue(commitAndSettle(dir, anchor, foreign, "erin").settled());

        List<DocumentEditor.Restorable> restorable = DocumentEditor.restorable(anchor);
        assertEquals(1, restorable.size(), restorable.toString());
        DocumentEditor.Restorable kept = restorable.getFirst();
        assertEquals(DocumentEditor.Match.CHOOSE, kept.match());
        long rewritten = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Target rewritten by hand.");
        long appended = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Appended paragraph.");
        assertEquals(List.of(rewritten, appended), kept.candidates());
        assertEquals(0, DocumentEditor.restore(anchor), "nothing is written while the block is in doubt");
        assertTrue(DocumentEditor.pending(anchor).isEmpty());
        assertEquals(1, DocumentEditor.restorable(anchor).size(), "the edit stays restorable");
        long intro = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Intro.");
        try {
            DocumentEditor.restore(anchor, java.util.Map.of(kept.index(), intro));
            throw new AssertionError("a block that is not a candidate was accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(DocumentEditor.pending(anchor).isEmpty(), "a refused target writes nothing");
        }

        assertEquals(1, DocumentEditor.restore(anchor, java.util.Map.of(kept.index(), rewritten)));
        assertEquals(foreign.replace("Target rewritten by hand.", "Target paragraph, edited in Komet."), export(anchor, true));
        assertEquals(List.of(), DocumentEditor.restorable(anchor));
    }

    /** The edited paragraph is gone: every paragraph is offered, and nothing is written until one is picked. */
    @Test
    void noCandidateOffersEveryBlockOfTheKind() throws Exception {
        String original = ":topic-id: anf-none\n= None\n\nIntro.\n\nTarget paragraph.\n";
        Path dir = repository("none", original);
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Target paragraph."),
                "Target paragraph, edited in Komet.\n", edit);
        String foreign = ":topic-id: anf-none\n= None\n\nIntro.\n";
        assertTrue(commitAndSettle(dir, anchor, foreign, "erin").settled());

        List<DocumentEditor.Restorable> restorable = DocumentEditor.restorable(anchor);
        assertEquals(1, restorable.size(), restorable.toString());
        assertEquals(DocumentEditor.Match.CHOOSE, restorable.getFirst().match());
        assertEquals(List.of(blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Intro.")), restorable.getFirst().candidates(),
                "every paragraph left, for the person to choose from");
        assertEquals(0, DocumentEditor.restore(anchor));
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing is written while the block is in doubt");
    }

    @Test
    void nothingSettlesBeforeANewCommit() throws Exception {
        Path dir = repository("unsettled");
        Path file = dir.resolve("editing.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph."),
                "Another paragraph, still being edited.\n", edit);

        GitDocumentMaterializer.materialize(dir, file);

        assertFalse(DocumentEditor.settle(anchor).settled(), "no commit since the edit was made");
        assertTrue(DocumentEditor.pending(anchor).isPresent(), "the edit is still pending");
        assertTrue(DocumentEditor.cancel(anchor));
        assertEquals(ORIGINAL, export(anchor, true), "cancelled, the document reads as committed");

        assertEquals(1, DocumentEditor.restore(anchor), "a discarded edit can be brought back");
        assertEquals(ORIGINAL.replace("Another paragraph.", "Another paragraph, still being edited."), export(anchor, true));
    }

    /** The card's commit: a draft message, the person's own unsigned commit of one file, then settling. */
    @Test
    void theCardsCommitRecordsTheEditAsThePersonsOwnCommit() throws Exception {
        Path dir = repository("card");
        git(dir, "config", "user.name", "dana");
        git(dir, "config", "user.email", "dana@example.test");
        Path file = dir.resolve("editing.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph."),
                "Another paragraph, committed from the card.\n", edit);
        GitDocumentExporter.Exported pending = GitDocumentExporter.export(anchor, true).orElseThrow();
        String draft = DocumentCommit.draftMessage(pending.path(),
                GitDocumentExporter.export(anchor, false).orElseThrow().lines(), pending.lines());
        assertTrue(draft.startsWith("Edit editing.adoc\n"), draft);
        assertTrue(draft.contains("Another paragraph, committed from the card."), draft);
        Files.writeString(dir.resolve("unrelated.txt"), "left alone\n");

        GitDocumentExporter.writeTo(pending, dir);
        DocumentCommit.Result result = DocumentCommit.write(dir, pending.path(), draft, false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, file, true);
        DocumentEditor.Settlement settlement = DocumentEditor.settle(anchor);

        assertTrue(settlement.carried(), settlement.toString());
        assertSettledAs(anchor, dir, pending.text());
        assertEquals("dana", gitOut(dir, "log", "-1", "--format=%an"), "the commit is the person's own");
        assertEquals("Edit editing.adoc", gitOut(dir, "log", "-1", "--format=%s"));
        assertEquals("editing.adoc", gitOut(dir, "show", "--name-only", "--format=", "HEAD"), "the commit holds the one file");
        assertEquals("N", gitOut(dir, "log", "-1", "--format=%G?"), "unsigned, as chosen");
        assertEquals(2, GitDocumentMaterializer.load(anchor).orElseThrow().manifestVersions(),
                "one manifest version per commit that changed the file; the cancelled pending one is not counted");
    }

    @Test
    void otherStagedChangesRefuseTheCommit() throws Exception {
        Path dir = repository("staged");
        Path file = dir.resolve("editing.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        DocumentEditor.editBlock(anchor, blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph."), "Edited.\n", edit);
        Files.writeString(dir.resolve("other.txt"), "staged\n");
        git(dir, "add", "other.txt");
        String head = gitOut(dir, "rev-parse", "HEAD");
        GitDocumentExporter.writeTo(GitDocumentExporter.export(anchor, true).orElseThrow(), dir);

        DocumentCommit.Result result = DocumentCommit.write(dir, "editing.adoc", "Edit editing.adoc\n", false);

        assertFalse(result.ok());
        assertTrue(result.output().contains("other.txt"), result.output());
        assertEquals(head, gitOut(dir, "rev-parse", "HEAD"), "nothing was committed");
        assertTrue(DocumentEditor.pending(anchor).isPresent(), "the edit is still pending");
    }

    @Test
    void aBlockHoldingBlocksIsNotEditedAsText() throws Exception {
        Path dir = repository("refuses");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        long section = document.blocks().values().stream().filter(b -> b.kind() == Block.Kind.SECTION || b.kind() == Block.Kind.DOCUMENT)
                .findFirst().orElseThrow().nid();
        try {
            DocumentEditor.editBlock(anchor, section, "text", edit);
        } catch (IllegalArgumentException expected) {
            assertTrue(DocumentEditor.pending(anchor).isEmpty(), "a refused edit writes nothing");
            return;
        }
        throw new AssertionError("a block holding blocks was edited as text");
    }

    // ── the edit as a Komet action (IKE-Network/ike-issues#1306) ──────────────────

    @Test
    void theEditIsPerformedAsTheStarterSetsAction() throws Exception {
        Path dir = repository("action");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        long block = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        KometAction action = KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK);
        assertTrue(action instanceof EditDocumentBlock, "the plugin implements Edit document block: " + action);

        KometActionInput input = EditDocumentBlock.input(block, "The first line, by action.\nThe second line.\n");
        KometActionResult result = action.perform(input, Calculators.View.Default(), edit);

        assertTrue(result.completed(), result.message());
        assertEquals(input.id(), result.input(), "the result refers to its input");
        assertEquals(ORIGINAL.replace("The first line.", "The first line, by action."), export(anchor, true));
        assertEquals(ORIGINAL, export(anchor, false), "the edit is uncommitted");
        long stamp = result.writtenVersion().orElseThrow().nid();
        assertEquals(Long.MAX_VALUE, EntityService.get().getStampFast(stamp).time(), "the version written is uncommitted");
        assertEquals(1, uncommittedVersions(manifestOf(anchor)));

        KometActionResult same = action.perform(EditDocumentBlock.input(block, "The first line, by action.\nThe second line.\n"),
                Calculators.View.Default(), edit);
        assertTrue(same.completed() && same.writtenVersion().isEmpty(), "unchanged text writes nothing: " + same);
        DocumentEditor.cancel(anchor);
    }

    @Test
    void theActionRefusesAnEditOnTopOfSomeoneElsesPendingEdit() throws Exception {
        Path dir = repository("other-author");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        KometAction action = KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK);
        long first = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "The first line.");
        long another = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        assertTrue(action.perform(EditDocumentBlock.input(first, "The first line, mine.\nThe second line.\n"),
                Calculators.View.Default(), edit).completed());
        String pending = export(anchor, true);
        EditCoordinateRecord assistant = EditCoordinateRecord.make(IkeConcept.KOMET_ASSISTANT.nid(),
                edit.getDefaultModuleNid(), edit.getDefaultPathNid());

        KometActionResult refused = action.perform(EditDocumentBlock.input(another, "Another paragraph, the assistant's.\n"),
                Calculators.View.Default(), assistant);

        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertTrue(refused.writtenVersion().isEmpty());
        assertEquals(pending, export(anchor, true), "nothing was written over the pending edit");
        assertTrue(action.perform(EditDocumentBlock.input(another, "Another paragraph, mine too.\n"),
                Calculators.View.Default(), edit).completed(), "the pending edit's own author edits on");
        DocumentEditor.cancel(anchor);
    }

    @Test
    void theActionRefusesWhatIsNotEditedAsText() throws Exception {
        Path dir = repository("action-refuses");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor).orElseThrow();
        long holder = document.blocks().values().stream().filter(b -> b.kind() == Block.Kind.SECTION || b.kind() == Block.Kind.DOCUMENT)
                .findFirst().orElseThrow().nid();
        KometAction action = KometActions.expect(KometConcept.EDIT_DOCUMENT_BLOCK);
        long paragraph = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "Another paragraph.");
        long line = document.lines().getFirst().nid();
        for (KometActionInput input : List.of(EditDocumentBlock.input(holder, "text"),
                EditDocumentBlock.input(paragraph, ""),
                EditDocumentBlock.input(line, "A line is not a block."),
                new KometActionInput(PublicIds.newRandom(), KometConcept.INACTIVATE_COMPONENT,
                        EntityProxy.Semantic.make(paragraph), Lists.immutable.of("Wrong action.")))) {
            KometActionResult result = action.perform(input, Calculators.View.Default(), edit);
            assertEquals(KometConcept.ACTION_REFUSED.nid(), result.outcome().nid(), result.message());
            assertTrue(DocumentEditor.pending(anchor).isEmpty(), "a refused edit writes nothing: " + result.message());
        }
    }

    private Path repository(String name) throws Exception {
        return repository(name, ORIGINAL);
    }

    private Path repository(String name, String text) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("editing.adoc"), text);
        commit(dir, "alice", "Add the document");
        return dir;
    }

    /** Each commit a day after the last, as commits are apart in time; git dates have one-second resolution. */
    private int day;

    private void commit(Path dir, String author, String message) throws Exception {
        git(dir, "add", "editing.adoc");
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test",
                "commit", "-q", "--no-gpg-sign", "--date=2026-01-" + String.format("%02d", ++day) + "T00:00:00Z", "-m", message);
    }

    private static String export(PublicId anchor, boolean pending) {
        return GitDocumentExporter.export(anchor, pending).orElseThrow().text();
    }

    private static List<GitDocumentMaterializer.StoredBlock> blocks(PublicId anchor, Block.Kind kind) {
        return GitDocumentMaterializer.load(anchor, true).orElseThrow().blocks().values().stream()
                .filter(b -> b.kind() == kind).toList();
    }

    private static long blockStartingWith(PublicId anchor, Block.Kind kind, String text) {
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        return document.blocks().values().stream()
                .filter(b -> b.kind() == kind)
                .filter(b -> document.line(b.members().getFirst()).map(l -> l.text().equals(text)).orElse(false))
                .findFirst().orElseThrow().nid();
    }

    /** The person writes the file, commits it, and the commit is materialized and the edit settled. */
    private DocumentEditor.Settlement commitAndSettle(Path dir, PublicId anchor, String text, String author) throws Exception {
        Path file = dir.resolve("editing.adoc");
        Files.writeString(file, text);
        commit(dir, author, "Commit the edit");
        GitDocumentMaterializer.materialize(dir, file);
        return DocumentEditor.settle(anchor);
    }

    /** Nothing pending, no provisional line, and the store gives back the committed file. */
    private static void assertSettledAs(PublicId anchor, Path dir, String committed) throws Exception {
        assertTrue(DocumentEditor.pending(anchor).isEmpty(), "nothing is left pending");
        assertEquals(committed, Files.readString(dir.resolve("editing.adoc")));
        assertEquals(committed, export(anchor, false), "the committed document is the commit");
        assertEquals(committed, export(anchor, true), "no provisional line or pending version remains");
        assertEquals(0, uncommittedVersions(manifestOf(anchor)));
        assertEquals(GitDocumentExporter.Relation.IDENTICAL_TO_HEAD,
                GitDocumentExporter.compare(GitDocumentExporter.export(anchor).orElseThrow(), dir).relation());
    }

    private static SemanticEntity<SemanticEntityVersion> manifestOf(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        return EntityService.get().semanticsForComponentOfPattern(anchorNid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid())
                .findFirst().orElseThrow();
    }

    private static int uncommittedVersions(SemanticEntity<SemanticEntityVersion> semantic) {
        SemanticEntity<?> fresh = (SemanticEntity<?>) dev.ikm.tinkar.entity.EntityHandle.get(semantic.nid()).orNull();
        int count = 0;
        for (var v : fresh.versions()) {
            if (v.uncommitted() && !v.canceled()) {
                count++;
            }
        }
        return count;
    }

    private static String gitOut(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return out;
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
