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
import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.LineRunModel.LineRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context menu's annotation path, end to end without the pointer (ike-lab-documents topic
 * {@code dev-stamped-document-surface}): a drag across a paragraph's runs snaps to whole lines,
 * the selection becomes the committed line range the trailer names, the proposal is hashed over
 * the file at HEAD exactly as the trailer grammar wants, the person's commit records it, and the
 * store then shows the affirmation in force on those lines and no others.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentSurfaceAnnotationIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String ORIGINAL = """
            :topic-id: anf-surface
            = Surface

            One claim on its own line.
            A second claim, by alice.
            A third claim.

            Another paragraph.
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void aDragOverTwoRunsAffirmsExactlyThoseTwoLines() throws Exception {
        Path dir = repository("affirm");
        Path file = dir.resolve("surface.adoc");
        Files.writeString(file, ORIGINAL.replace("A second claim, by alice.", "A second claim, by bob."));
        commit(dir, "bob", "Revise the second claim");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        Map<Long, Integer> committed = committedNumbers(anchor);
        Map<Long, Integer> ordinals = LineSelection.ordinals(document.lines());

        // The paragraph as the Reading view models it: one run per line, joined by spaces.
        StoredBlock paragraph = blockStartingWith(document, Block.Kind.PARAGRAPH, "One claim on its own line.");
        LineRunModel model = proseModel(document, paragraph);
        assertEquals("One claim on its own line. A second claim, by bob. A third claim.", model.getPlainText(0));
        assertEquals(2, model.runs().stream().map(r -> r.line().introduced().nid()).distinct().count(),
                "the paragraph joins lines from two commits");

        // A drag from the middle of the first run to the middle of the second.
        List<LineRun> dragged = model.runsBetween(TextPos.ofLeading(0, 5), TextPos.ofLeading(0, 40));
        LineSelection selection = new LineSelection(ordinals.get(dragged.getFirst().nid()), ordinals.get(dragged.getLast().nid()));
        assertEquals(2, selection.size(), "snapped to whole lines: the two runs the drag touched");
        List<int[]> ranges = selection.ranges(o -> StampedDocumentCard.committedNumber(document.lines().get(o), committed));
        assertEquals(1, ranges.size());
        assertEquals(4, ranges.getFirst()[0], "the paragraph starts on line 4 of the file");
        assertEquals(5, ranges.getFirst()[1]);

        // The proposal the context menu's Affirm… shows: trailers over the file at HEAD.
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, ranges, dir, Path.of("surface.adoc"), committedLines(anchor));
        String head;
        List<String> content;
        try (GitRepo repo = GitRepo.open(dir)) {
            head = repo.headRevision();
            content = repo.headLines(Path.of("surface.adoc"));
        }
        assertEquals(Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("surface.adoc"), 4, 5, head, content)).trailer(),
                proposal.trailers(), "the trailer names the lines the selection snapped to, hashed over their committed text");
        assertEquals(2, proposal.lines());

        // The person confirms: their own empty commit, then the store re-reads it.
        AnnotationCommit.Result result = AnnotationCommit.write(dir, proposal.annotations(), "Read both twice.", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, file, true);
        StoredDocument after = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        List<StoredLine> affirmed = after.lines().stream().filter(l -> !l.affirmers().isEmpty()).toList();
        assertEquals(List.of("One claim on its own line.", "A second claim, by bob."), affirmed.stream().map(StoredLine::text).toList(),
                "the affirmation is in force on the two selected lines and no others");
        assertEquals("Read both twice.", affirmed.getFirst().annotations().getFirst().text());
        assertFalse(DocumentSurfaceView.queued(affirmed.getFirst()), "an affirmed line leaves the review queue");
        assertTrue(DocumentSurfaceView.queued(after.line(paragraph.members().getLast()).orElseThrow()), "the third line is still to review");

        // The hover popover's commit line: the subject comes from the repository.
        CommitSummaries summaries = CommitSummaries.read(anchor, dir, Path.of("surface.adoc"));
        StoredLine second = after.lines().stream().filter(l -> l.text().equals("A second claim, by bob.")).findFirst().orElseThrow();
        assertTrue(summaries.summary(second.introduced().nid()).orElseThrow().endsWith("  Revise the second claim"),
                summaries.summary(second.introduced().nid()).toString());
        assertEquals("bob", StampNames.name(Calculators.View.Default(), second.introduced().authorNid()));
    }

    @Test
    void anUncommittedEditIsLeftOutOfTheProposal() throws Exception {
        Path dir = repository("pending");
        Path file = dir.resolve("surface.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        StoredDocument before = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        StoredBlock paragraph = blockStartingWith(before, Block.Kind.PARAGRAPH, "One claim on its own line.");
        DocumentEditor.editBlock(anchor, paragraph.nid(), "One claim on its own line.\nA second claim, edited in Komet.\nA third claim.\n",
                Coordinates.Edit.Default());
        StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        Map<Long, Integer> committed = committedNumbers(anchor);

        // Select the whole paragraph as shown, edited line included.
        StoredBlock edited = blockStartingWith(document, Block.Kind.PARAGRAPH, "One claim on its own line.");
        LineRunModel model = proseModel(document, edited);
        Map<Long, Integer> ordinals = LineSelection.ordinals(document.lines());
        List<LineRun> runs = model.runs();
        LineSelection selection = new LineSelection(ordinals.get(runs.getFirst().nid()), ordinals.get(runs.getLast().nid()));
        java.util.function.IntUnaryOperator number = o -> StampedDocumentCard.committedNumber(document.lines().get(o), committed);

        assertEquals(1, selection.uncommitted(number), "the edited line keeps its identity but its text is uncommitted, so it has no number");
        List<int[]> ranges = selection.ranges(number);
        assertEquals("4-4,6-6", ranges.stream().map(r -> r[0] + "-" + r[1]).reduce((a, b) -> a + "," + b).orElse(""),
                "the committed lines around it are proposed, as two ranges");
        assertTrue(DocumentSurfaceView.uncommitted(document.lines().get(selection.min() + 1)));
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(AnnotationKind.COMMENT, ranges, dir, Path.of("surface.adoc"), committedLines(anchor));
        assertEquals(2, proposal.annotations().size());
    }

    @Test
    void aStoreBehindHeadRefusesToAnchorTrailers() throws Exception {
        Path dir = repository("behind");
        Path file = dir.resolve("surface.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        List<String> stored = committedLines(anchor);
        Files.writeString(dir.resolve("unrelated.adoc"), "= Unrelated\n");
        git(dir, "add", "unrelated.adoc");
        commit(dir, "carol", "Add an unrelated file");
        assertEquals(2, StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, List.of(new int[] {4, 5}), dir, Path.of("surface.adoc"), stored).lines(),
                "a commit elsewhere moves HEAD but not the file: the trailers anchor at the new HEAD");
        Files.writeString(file, ORIGINAL.replace("A third claim.", "A third claim, revised."));
        commit(dir, "carol", "Revise the third claim");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, List.of(new int[] {4, 6}), dir, Path.of("surface.adoc"), stored));

        assertTrue(refused.getMessage().contains("Materialize first"), refused.getMessage());
        assertThrows(IllegalStateException.class,
                () -> StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, List.of(), dir, Path.of("surface.adoc"), stored),
                "nothing selected, nothing to anchor");
        assertThrows(IllegalStateException.class,
                () -> StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, List.of(new int[] {4, 6}), null, null, stored),
                "no working tree, no trailers");
    }

    /**
     * An assembly shown as one document: a drag inside the included chapter selects the
     * chapter's own lines, the trailer names the chapter's file and its line numbers, and the
     * affirmation lands on the chapter in the store and nowhere in the book.
     */
    @Test
    void aDragInsideAnIncludedChildAffirmsTheChildsOwnFile() throws Exception {
        Path dir = Files.createDirectories(root.resolve("assembly"));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("book.adoc"), "= Book\n\nA line of the book.\n\ninclude::chapter.adoc[]\n");
        Files.writeString(dir.resolve("chapter.adoc"), ":topic-id: anf-chapter\n= Chapter\n\nFirst claim of the chapter.\nSecond claim of the chapter.\n");
        git(dir, "add", "book.adoc", "chapter.adoc");
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-03-20T12:00:00", "-m", "Assemble the book");
        GitDocumentMaterializer.AssemblyResult materialized = GitDocumentMaterializer.materializeAssembly(dir, dir.resolve("book.adoc"));
        PublicId bookAnchor = materialized.assembly().anchor();
        PublicId chapterAnchor = materialized.documents().get(1).anchor();
        assertEquals(chapterAnchor, GitDocumentMaterializer.documentAnchor(dir, "chapter.adoc"),
                "the child's anchor follows from the working tree and the path the include resolved to");
        assertEquals("chapter.adoc", GitDocumentMaterializer.recordedPath(chapterAnchor).orElseThrow());
        assertEquals("book.adoc", GitDocumentMaterializer.recordedPath(bookAnchor).orElseThrow());

        // What the card builds when the book is opened: the chapter found by its recorded path.
        StoredDocument book = GitDocumentMaterializer.load(bookAnchor, true).orElseThrow();
        StoredDocument chapter = GitDocumentMaterializer.load(chapterAnchor, true).orElseThrow();
        DocumentAssembly assembly = DocumentAssembly.of(book, "book.adoc",
                path -> path.equals("chapter.adoc") ? java.util.Optional.of(chapter) : java.util.Optional.empty());
        assertEquals(List.of("= Book", "", "A line of the book.", "", "include::chapter.adoc[]",
                ":topic-id: anf-chapter", "= Chapter", "", "First claim of the chapter.", "Second claim of the chapter."),
                assembly.lines().stream().map(StoredLine::text).toList(), "the include line followed by the chapter's own lines");
        assertEquals(1, assembly.children().size());

        // A drag across the chapter's paragraph, as the Reading view models it inside the child.
        StoredBlock paragraph = blockStartingWith(chapter, Block.Kind.PARAGRAPH, "First claim of the chapter.");
        LineRunModel model = proseModel(chapter, paragraph);
        List<LineRun> dragged = model.runsBetween(TextPos.ofLeading(0, 3), TextPos.ofLeading(0, 40));
        LineSelection selection = new LineSelection(assembly.ordinal(dragged.getFirst().nid()), assembly.ordinal(dragged.getLast().nid()));
        assertEquals(new LineSelection(8, 9), selection, "surface ordinals: after the book's five lines and the chapter's three");
        DocumentAssembly.Located located = assembly.locate(selection).orElseThrow();
        assertEquals("chapter.adoc", located.member().path(), "the selection lies in the chapter");
        assertEquals(new LineSelection(3, 4), located.local());
        Map<Long, Integer> committed = committedNumbers(chapterAnchor);
        List<int[]> ranges = located.local().ranges(o -> StampedDocumentCard.committedNumber(chapter.lines().get(o), committed));
        assertEquals(1, ranges.size());
        assertEquals(4, ranges.getFirst()[0], "the chapter's own line numbers, not the book's");
        assertEquals(5, ranges.getFirst()[1]);
        assertTrue(assembly.locate(new LineSelection(4, 8)).isEmpty(), "a drag from the include line into the chapter spans two files");

        // The proposal names the chapter's file; the person's commit records it; the store shows it on the chapter.
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(AnnotationKind.AFFIRMATION, ranges, dir, Path.of("chapter.adoc"), committedLines(chapterAnchor));
        String head;
        List<String> content;
        try (GitRepo repo = GitRepo.open(dir)) {
            head = repo.headRevision();
            content = repo.headLines(Path.of("chapter.adoc"));
        }
        assertEquals(Annotation.of(AnnotationKind.AFFIRMATION, LineRange.of(Path.of("chapter.adoc"), 4, 5, head, content)).trailer(), proposal.trailers());
        AnnotationCommit.Result result = AnnotationCommit.write(dir, proposal.annotations(), "Read the chapter.", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, dir.resolve("chapter.adoc"), true);
        StoredDocument chapterAfter = GitDocumentMaterializer.load(chapterAnchor, true).orElseThrow();
        assertEquals(List.of("First claim of the chapter.", "Second claim of the chapter."),
                chapterAfter.lines().stream().filter(l -> !l.affirmers().isEmpty()).map(StoredLine::text).toList(),
                "the affirmation is in force on the chapter's two lines");
        GitDocumentMaterializer.materialize(dir, dir.resolve("book.adoc"), true);
        StoredDocument bookAfter = GitDocumentMaterializer.load(bookAnchor, true).orElseThrow();
        assertTrue(bookAfter.lines().stream().allMatch(l -> l.affirmers().isEmpty()), "nothing of the book is affirmed");
        DocumentAssembly after = DocumentAssembly.of(bookAfter, "book.adoc",
                path -> path.equals("chapter.adoc") ? java.util.Optional.of(chapterAfter) : java.util.Optional.empty());
        assertEquals(2, after.lines().stream().filter(l -> !l.affirmers().isEmpty()).count(), "the assembly shows the child's affirmations in place");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** The Reading view's model of a prose block: one paragraph, one run per line, joined by spaces. */
    private static LineRunModel proseModel(StoredDocument document, StoredBlock block) {
        List<LineRunModel.Line> lines = new ArrayList<>();
        for (long nid : block.members()) {
            document.line(nid).ifPresent(line -> lines.add(new LineRunModel.Line(line, line.text().strip())));
        }
        return new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(lines, " ", StyleAttributeMap.builder().setFontSize(14).build())))
                .build(LineRunModel.Decoration.NONE);
    }

    private static List<String> committedLines(PublicId anchor) {
        return GitDocumentExporter.export(anchor, false).orElseThrow().lines();
    }

    private static Map<Long, Integer> committedNumbers(PublicId anchor) {
        Map<Long, Integer> numbers = new HashMap<>();
        StoredDocument committed = GitDocumentMaterializer.load(anchor, false).orElseThrow();
        for (int i = 0; i < committed.lines().size(); i++) {
            numbers.put(committed.lines().get(i).nid(), i + 1);
        }
        return numbers;
    }

    private static StoredBlock blockStartingWith(StoredDocument document, Block.Kind kind, String text) {
        return document.blocks().values().stream()
                .filter(b -> b.kind() == kind)
                .filter(b -> document.line(b.members().getFirst()).map(l -> l.text().equals(text)).orElse(false))
                .findFirst().orElseThrow();
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "reviewer");
        git(dir, "config", "user.email", "reviewer@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("surface.adoc"), ORIGINAL);
        commit(dir, "alice", "Add the document");
        return dir;
    }

    /** Each commit a day after the last, as commits are apart in time; git dates have one-second resolution. */
    private int day;

    private void commit(Path dir, String author, String message) throws Exception {
        git(dir, "add", "surface.adoc");
        String when = "2026-03-" + String.format("%02d", 1 + day++) + "T12:00:00";
        git(dir, "-c", "user.name=" + author, "-c", "user.email=" + author + "@example.test",
                "commit", "-q", "--no-gpg-sign", "--date=" + when, "-m", message);
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
