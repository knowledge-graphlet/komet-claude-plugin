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

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retracting an annotation (IKE-Network/ike-issues#806, #1300; ike-lab-documents topic
 * {@code dev-annotation-trailer}): the person's empty commit with one {@code Annotation:} trailer
 * naming an earlier annotation commit withdraws every annotation that commit recorded. Read into
 * the store, the retracted annotation gets an inactive version under the retraction's STAMP; the
 * lines it covered read as before it on the surface (no mark, back in the review queue), while an
 * unrelated affirmation and a later one by the same person stand, and a judgment the retracted one
 * had replaced does not come back. The popover still names what was withdrawn, by whom and when;
 * {@code stamp_lines} reads the same from git; {@code propose_annotation} prepares the retraction
 * for the card.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRetractionIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String ORIGINAL = """
            :topic-id: anf-retraction
            = Retraction

            One claim on its own line.
            A second claim.
            A third claim.

            Another paragraph.
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void aRetractionWithdrawsItsTargetAndNothingElseOnTheSurface() throws Exception {
        Path dir = repository("retract");
        Path file = dir.resolve("retraction.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        List<String> committed = GitDocumentExporter.export(anchor, false).orElseThrow().lines();
        Path relative = Path.of("retraction.adoc");

        String ritaAffirms = annotate(dir, "rita", AnnotationKind.AFFIRMATION, List.of(new int[] {4, 5}), "Read both.", relative, committed);
        String samAffirms = annotate(dir, "sam", AnnotationKind.AFFIRMATION, List.of(new int[] {6, 6}), "", relative, committed);
        String ritaRefutes = annotate(dir, "rita", AnnotationKind.REFUTATION, List.of(new int[] {5, 5}), "The second claim overstates.", relative, committed);
        String ritaAffirmsLater = annotate(dir, "rita", AnnotationKind.AFFIRMATION, List.of(new int[] {8, 8}), "", relative, committed);
        GitDocumentMaterializer.materialize(dir, file, true);
        StoredDocument before = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(List.of("✓", "✗", "✓", " ", "✓"), marks(before, 4, 8), "affirmed, refuted (rita's later stance), sam's, blank, rita's later affirmation");

        // The person withdraws the refutation: their own empty commit, then the store re-reads it.
        AnnotationCommit.Result retraction = retract(dir, "rita", ritaRefutes, "The second claim reads right after all.");
        GitDocumentMaterializer.Result read = GitDocumentMaterializer.materialize(dir, file, true);
        assertEquals(1, read.annotations(), "one annotation retired");
        StoredDocument after = GitDocumentMaterializer.load(anchor, true).orElseThrow();

        assertEquals(List.of("✓", " ", "✓", " ", "✓"), marks(after, 4, 8),
                "line 5 reads as before the refutation; the affirmation it replaced does not come back; sam's and rita's later one stand");
        StoredLine second = after.lines().get(4);
        assertTrue(second.affirmers().isEmpty() && second.refuters().isEmpty(), "nothing is in force on line 5");
        assertTrue(DocumentSurfaceView.queued(second), "line 5 is back in the review queue");
        assertFalse(DocumentSurfaceView.queued(after.lines().get(3)), "line 4 is still affirmed by rita's first commit");
        List<StoredAnnotation> history = second.annotations();
        assertEquals(1, history.size(), history.toString());
        assertTrue(history.getFirst().retracted(), "the popover still names what was withdrawn");
        assertEquals(AnnotationKind.REFUTATION, history.getFirst().kind());
        assertEquals("rita", StampNames.name(Calculators.View.Default(), history.getFirst().retractedBy().authorNid()), "by whom");
        String row = ProvenancePopover.annotationText(history.getFirst(), Calculators.View.Default());
        assertTrue(row.startsWith("↶ Refutation by rita on ") && row.contains(", retracted by rita on ") && row.endsWith(": The second claim overstates."), row);
        assertEquals(1, retractedVersions(anchor), "the refutation's semantic has one inactive version, under the retraction's STAMP");

        // Materializing again converges: nothing is retired twice.
        assertEquals(0, GitDocumentMaterializer.materialize(dir, file, true).annotations());
        assertEquals(1, retractedVersions(anchor));

        // The git reading agrees: stamp_lines no longer lists the refutation, and marks line 5 as plain.
        Map<String, AnthropicTool> tools = tools();
        String report = tools.get("stamp_lines").execute(Map.of("file", file.toString(), "from", 4, "to", 8));
        assertFalse(report.contains(ritaRefutes.substring(0, 7)), report);
        assertTrue(report.contains(ritaAffirms.substring(0, 7)) && report.contains(samAffirms.substring(0, 7)) && report.contains(ritaAffirmsLater.substring(0, 7)), report);
        assertTrue(report.lines().anyMatch(l -> l.matches("\\s*5\\s+[0-9a-f]{7}\\s{4}A second claim\\.")), "line 5 carries no mark: " + report);
        assertTrue(report.lines().anyMatch(l -> l.matches("\\s*4\\s+[0-9a-f]{7} ✓  One claim on its own line\\.")), "line 4 keeps its mark: " + report);
        assertTrue(report.contains("Review queue") && report.contains("5"), report);
        assertFalse(report.contains("Not applied"), report);

        // Withdrawing the first affirmation too: line 4 loses its mark, line 5 stays as it is.
        retract(dir, "rita", ritaAffirms, "Affirmed too early.");
        GitDocumentMaterializer.materialize(dir, file, true);
        StoredDocument twice = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        assertEquals(List.of(" ", " ", "✓", " ", "✓"), marks(twice, 4, 8));
        assertTrue(DocumentSurfaceView.queued(twice.lines().get(3)));
        assertEquals(1, twice.lines().get(3).annotations().size());
        assertTrue(twice.lines().get(3).annotations().getFirst().retracted());
        assertTrue(retraction.ok());
    }

    @Test
    void theAssistantPreparesARetractionForTheCardButWritesNothing() throws Exception {
        Path dir = repository("propose");
        Path file = dir.resolve("retraction.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        List<String> committed = GitDocumentExporter.export(anchor, false).orElseThrow().lines();
        String affirmed = annotate(dir, "rita", AnnotationKind.AFFIRMATION, List.of(new int[] {4, 6}), "", Path.of("retraction.adoc"), committed);
        GitDocumentMaterializer.materialize(dir, file, true);
        String head = gitOut(dir, "rev-parse", "HEAD");
        Map<String, AnthropicTool> tools = tools();

        String prepared = tools.get("propose_annotation").execute(Map.of("file", file.toString(), "kind", "retract", "commit", affirmed.substring(0, 7),
                "text", "Affirmed too early."));

        assertTrue(prepared.startsWith("Prepared the retraction of " + affirmed.substring(0, 7)), prepared);
        assertTrue(prepared.contains(Annotation.of(AnnotationKind.RETRACTION, new AnnotationTarget.Commit(affirmed)).trailer()), "the trailer names the commit in full: " + prepared);
        assertTrue(prepared.contains("No Stamped Document card shows this file"), prepared);
        assertEquals(head, gitOut(dir, "rev-parse", "HEAD"), "nothing was committed");
        assertEquals(3, GitDocumentMaterializer.load(anchor, true).orElseThrow().lines().stream().filter(l -> !l.affirmers().isEmpty()).count(),
                "the affirmation is still in force");

        String noText = tools.get("propose_annotation").execute(Map.of("file", file.toString(), "kind", "retract", "commit", affirmed.substring(0, 7)));
        assertTrue(noText.startsWith("Error: a retraction needs text"), noText);
        String unknown = tools.get("propose_annotation").execute(Map.of("file", file.toString(), "kind", "retract", "commit", "0000000", "text", "x"));
        assertTrue(unknown.startsWith("Error: no annotation in force"), unknown);
        String notAnAnnotation = tools.get("propose_annotation").execute(Map.of("file", file.toString(), "kind", "retract", "commit", head.substring(0, 7), "text", "x"));
        assertTrue(notAnAnnotation.startsWith("Error: no annotation in force") || notAnAnnotation.startsWith("Prepared"), notAnAnnotation);
        assertEquals(AnnotationKind.RETRACTION, StampTools.kind("retract"));
        assertEquals(List.of("affirm", "refute", "comment", "retract"),
                ((Map<?, ?>) ((Map<?, ?>) tools.get("propose_annotation").inputSchema().get("properties")).get("kind")).get("enum"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, AnthropicTool> tools() {
        Map<String, AnthropicTool> tools = new java.util.HashMap<>();
        for (AnthropicTool tool : StampTools.tools(() -> null)) {
            tools.put(tool.name(), tool);
        }
        return tools;
    }

    /** The marks the Reading view's ledger shows for lines {@code from}–{@code to}, 1-based. */
    private static List<String> marks(StoredDocument document, int from, int to) {
        List<String> marks = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            marks.add(DocumentSurfaceView.mark(document.lines().get(i - 1)));
        }
        return marks;
    }

    /** How many of the document's annotation semantics have an inactive latest version: the retracted ones. */
    private static int retractedVersions(PublicId anchor) {
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        int[] count = {0};
        EntityService.get().semanticsForComponentOfPattern(anchorNid, network.ike.foundation.ike.bindings.IkePattern.ANNOTATION_PATTERN.nid())
                .forEach(a -> {
                    int inactive = 0;
                    for (Object o : a.versions()) {
                        if (o instanceof dev.ikm.tinkar.entity.SemanticEntityVersion v && !v.canceled() && !v.active()) {
                            inactive++;
                        }
                    }
                    count[0] += inactive;
                });
        return count[0];
    }

    /** An annotation commit by {@code author}, prepared as the card prepares it; returns its SHA. */
    private String annotate(Path dir, String author, AnnotationKind kind, List<int[]> ranges, String text, Path file, List<String> committed) throws Exception {
        StampedDocumentCard.Proposal proposal = StampedDocumentCard.prepare(kind, ranges, dir, file, committed);
        return commitAs(dir, author, () -> AnnotationCommit.write(dir, proposal.annotations(), text, false));
    }

    /** A retraction commit by {@code author} of the annotation commit {@code sha}. */
    private AnnotationCommit.Result retract(Path dir, String author, String sha, String why) throws Exception {
        AnnotationCommit.Result[] result = new AnnotationCommit.Result[1];
        commitAs(dir, author, () -> result[0] = AnnotationCommit.write(dir,
                List.of(Annotation.of(AnnotationKind.RETRACTION, new AnnotationTarget.Commit(sha))), why, false));
        return result[0];
    }

    private interface Write {
        AnnotationCommit.Result run() throws Exception;
    }

    /** Each commit a day after the last, as commits are apart in time; git dates have one-second resolution. */
    private int day;

    private String commitAs(Path dir, String author, Write write) throws Exception {
        git(dir, "config", "user.name", author);
        git(dir, "config", "user.email", author + "@example.test");
        AnnotationCommit.Result result = write.run();
        assertTrue(result.ok(), result.output());
        // the commit's author time, a day on, so the STAMPs are apart
        git(dir, "commit", "--amend", "--allow-empty", "--no-edit", "-q", "--no-gpg-sign", "--date=2026-04-" + String.format("%02d", ++day) + "T12:00:00Z");
        return gitOut(dir, "rev-parse", "HEAD");
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("retraction.adoc"), ORIGINAL);
        git(dir, "add", "retraction.adoc");
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test", "commit", "-q", "--no-gpg-sign",
                "--date=2026-04-" + String.format("%02d", ++day) + "T12:00:00Z", "-m", "Add the document");
        return dir;
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
