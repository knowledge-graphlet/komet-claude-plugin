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
package network.ike.komet.claude.documenttools;

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.document.DocumentEditor;
import network.ike.komet.document.DocumentNarratives;
import network.ike.komet.document.GitDocumentMaterializer;
import network.ike.komet.document.SegmentNarrative;
import network.ike.komet.document.Span;
import network.ike.komet.document.VertexMaterializer;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lift's clinical elements anchor to a stamped document's stored sentences, and follow them
 * through an edit of the document over the vertex model: the element isolation of the assistant's
 * ANF lift (dev-anf-element-isolation) over the document's sentences.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SegmentAnchorEditingIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    @TempDir
    Path root;

    EditCoordinateRecord edit;

    @BeforeAll
    void setupDatabase() {
        System.setProperty(VertexMaterializer.MODEL_PROPERTY, "vertex");
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        try {
            HeadlessFx.select();
            javafx.application.Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        }
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
    }

    @AfterAll
    void teardownDatabase() {
        System.clearProperty(VertexMaterializer.MODEL_PROPERTY);
        PrimitiveData.stop();
    }

    /**
     * A lift's spans anchored to a stamped document's sentences (dev-anf-element-isolation, "The
     * written form"): they name their document, survive an edit of another sentence, follow their
     * own sentence through a revision that keeps their words, and are reported when it does not.
     */
    @Test
    void aLiftsSegmentAnchorsFollowTheirSentencesThroughAnEdit() throws Exception {
        Path dir = repository("vertex-anchors", ":topic-id: anf-vertex-anchors\n= Visit\n\n"
                + "A 62-year-old man presented for routine checkup.\nSystolic blood pressure was 120 mmHg, sitting.\n\n"
                + "He tested negative for COVID-19 and RSV.\n");
        PublicId anchor = GitDocumentMaterializer.materialize(dir, dir.resolve("editing.adoc")).anchor();
        assertTrue(DocumentNarratives.documents().stream().anyMatch(c -> c.anchor().equals(anchor)), "offered to the lift");
        SegmentNarrative before = DocumentNarratives.narrative(anchor).orElseThrow();
        assertTrue(before.narrative().contains("A 62-year-old man presented for routine checkup. Systolic blood pressure was 120 mmHg, sitting."
                + "\n\nHe tested negative for COVID-19 and RSV."), before.narrative());

        ElementSet set = new ElementSet(before.narrative(), List.of(
                ClinicalElement.proposed("el-1", "The patient is 62 years old.", List.of(span(before, "A 62-year-old man"))),
                ClinicalElement.proposed("el-2", "The patient was sitting.", List.of(span(before, "sitting"))),
                ClinicalElement.proposed("el-3", "COVID-19 is absent.", List.of(span(before, "negative for COVID-19 and RSV")))),
                List.of());
        List<String> blocks = ElementText.toBlocks(set, before);
        String header = blocks.get(1).lines().findFirst().orElseThrow();
        String sittingFrom = ElementText.attributes(header.substring(1, header.length() - 1)).get("from");
        assertTrue(sittingFrom.startsWith("segment:"), sittingFrom);
        assertEquals(Optional.of(anchor), DocumentNarratives.documentOf(SegmentNarrative.ids(sittingFrom).getFirst()),
                "an anchor names its document");

        long paragraph = blockStartingWith(anchor, Block.Kind.PARAGRAPH, "A 62-year-old man presented for routine checkup.");
        List<GitDocumentMaterializer.StoredSentence> sentences = GitDocumentMaterializer.load(anchor, true).orElseThrow()
                .sentences().get(paragraph);
        DocumentEditor.editSentence(anchor, sentences.get(0).nid(),
                "A 62-year-old man presented to the clinic for a routine checkup.", edit);
        DocumentEditor.editSentence(anchor, sentences.get(1).nid(), "Systolic blood pressure, sitting, was 120 mmHg.", edit);
        SegmentNarrative edited = DocumentNarratives.narrative(anchor).orElseThrow();
        assertTrue(ElementText.unresolved(blocks, edited).isEmpty());
        ElementSet followed = ElementText.parse(blocks, edited, id -> Optional.empty()).orElseThrow();
        assertEquals(List.of(List.of("A 62-year-old man"), List.of("sitting"), List.of("negative for COVID-19 and RSV")),
                followed.elements().stream().map(e -> e.quotes(edited.narrative())).toList(),
                "each span follows its words: an edit elsewhere, and a revision that keeps them");

        DocumentEditor.editSentence(anchor, sentences.get(1).nid(), "Systolic blood pressure was 120 mmHg.", edit);
        assertEquals(List.of("The patient was sitting."),
                ElementText.unresolved(blocks, DocumentNarratives.narrative(anchor).orElseThrow()),
                "a revision that drops the words is a finding");
        assertTrue(DocumentEditor.cancel(anchor));
    }

    private static Span span(SegmentNarrative narrative, String words) {
        int at = narrative.narrative().indexOf(words);
        return new Span(at, at + words.length());
    }

    private Path repository(String name, String text) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("editing.adoc"), text);
        git(dir, "add", "editing.adoc");
        git(dir, "-c", "user.name=alice", "-c", "user.email=alice@example.test",
                "commit", "-q", "--no-gpg-sign", "--date=2026-01-01T00:00:00Z", "-m", "Add the document");
        return dir;
    }

    private static long blockStartingWith(PublicId anchor, Block.Kind kind, String text) {
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(anchor, true).orElseThrow();
        return document.blocks().values().stream()
                .filter(b -> b.kind() == kind)
                .filter(b -> document.line(b.members().getFirst()).map(l -> l.text().equals(text)).orElse(false))
                .findFirst().orElseThrow().nid();
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
