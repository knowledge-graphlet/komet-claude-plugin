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
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.LineRange;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.boundary.EntityBoundaries;
import dev.ikm.tinkar.entity.boundary.ModuleKinds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.stamp.SegmentNarrative;
import network.ike.komet.claude.stamp.Span;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredSentence;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.TextRange;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Annotations of sentence fragments with a meaning of their own (the segments design's decision 9,
 * revised 2026-10-31): a narrative lift's not-clinical mark recorded on the note through git, and
 * read back as the span it was; and an annotation whose fragments leave a gap.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotClinicalAnnotationIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final String NOTE = """
            :topic-id: dev-visit-%s

            = Visit

            Patient, accompanied by her daughter, reports blurred vision. No nausea, vomiting, or diarrhea.
            """;

    @TempDir
    Path root;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        ModuleKinds.clear();
        EntityBoundaries.clear();
        PrimitiveData.stop();
    }

    @Test
    void aNotClinicalMarkIsRecordedOnTheNoteAndReadBackAsItsSpan() throws Exception {
        Path dir = repository("recorded");
        Path file = dir.resolve("visit.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        SegmentNarrative narrative = DocumentNarratives.narrative(anchor).orElseThrow();
        int at = narrative.narrative().indexOf("accompanied by her daughter");
        Span mark = new Span(at, at + "accompanied by her daughter".length());

        DocumentNarratives.workingTreeOpened(dir);
        assertEquals(dir.toAbsolutePath().normalize(), DocumentNarratives.workingTree(anchor).orElseThrow());
        DocumentNarratives.Recording recording = DocumentNarratives.notClinical(anchor, dir, narrative, List.of(mark));
        assertEquals(1, recording.annotations().size());
        Annotation written = recording.annotations().getFirst();
        assertEquals("Not clinical", written.kind().label());
        assertTrue(written.trailer().contains(" visit.adoc:5.10-5.36@"), written.trailer());

        AnnotationCommit.Result result = AnnotationCommit.write(dir, recording.annotations(), "", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, file, true);

        assertEquals(List.of(mark), DocumentNarratives.recordedNotClinical(anchor, narrative));
        StoredAnnotation stored = sentence(anchor, "Patient,").annotations().getFirst();
        assertEquals(null, stored.kind(), "a meaning the engine gives no behaviour");
        assertEquals(IkeConcept.NOT_CLINICAL.nid(), stored.meaningNid());
        assertEquals(List.of(new TextRange(9, 36)), stored.ranges());
    }

    @Test
    void anAnnotationWithAGapReadsBackAsTwoRangesOfOneSentence() throws Exception {
        Path dir = repository("gap");
        Path file = dir.resolve("visit.adoc");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, file).anchor();
        String head;
        List<String> lines;
        try (GitRepo repo = GitRepo.open(dir)) {
            head = repo.headRevision();
            lines = repo.linesAt(head, Path.of("visit.adoc"));
        }
        // "No" and "vomiting" of line 5, leaving "nausea," between them
        LineRange range = LineRange.of(Path.of("visit.adoc"), List.of(new LineRange.Fragment(5, 63, 5, 64), new LineRange.Fragment(5, 74, 5, 81)),
                head, lines);
        assertEquals("No\nvomiting", range.selected(lines));
        AnnotationCommit.Result result = AnnotationCommit.write(dir, List.of(Annotation.of(AnnotationKind.COMMENT, range)), "Negated.", false);
        assertTrue(result.ok(), result.output());
        GitDocumentMaterializer.materialize(dir, file, true);

        StoredAnnotation stored = sentence(anchor, "No nausea").annotations().getFirst();
        assertEquals(AnnotationKind.COMMENT, stored.kind());
        assertEquals(List.of(new TextRange(0, 2), new TextRange(11, 19)), stored.ranges());
        assertEquals("Negated.", stored.text().strip());
    }

    private Path repository(String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.name", "Rita Reviewer");
        git(dir, "config", "user.email", "rita@example.test");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve("visit.adoc"), NOTE.formatted(name));   // one topic per store: the pattern is the topic's
        git(dir, "add", "visit.adoc");
        git(dir, "commit", "-q", "-m", "Write the visit note");
        return dir;
    }

    private static StoredSentence sentence(PublicId anchor, String start) {
        return GitDocumentMaterializer.load(anchor, true).orElseThrow().sentences().values().stream()
                .flatMap(List::stream).filter(s -> s.text().startsWith(start)).findFirst().orElseThrow();
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
