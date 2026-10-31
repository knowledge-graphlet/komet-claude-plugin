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
package network.ike.komet.claude.anf.element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Spans anchored to a stamped document's sentence segments (dev-anf-element-isolation, "The written form"). */
class SegmentNarrativeTest {

    private static final String S1 = "11111111-0000-0000-0000-000000000001";
    private static final String S2 = "11111111-0000-0000-0000-000000000002";
    private static final String S3 = "11111111-0000-0000-0000-000000000003";

    static SegmentNarrative note(String first, String second, String third) {
        return SegmentNarrative.of(List.of(
                List.of(new SegmentNarrative.Sentence(S1, first), new SegmentNarrative.Sentence(S2, second)),
                List.of(new SegmentNarrative.Sentence(S3, third))));
    }

    static final SegmentNarrative NOTE = note("A 62-year-old man presented for routine checkup.",
            "Systolic blood pressure was 120 mmHg, sitting.", "He tested negative for COVID-19 and RSV.");

    static Span span(SegmentNarrative narrative, String words) {
        int at = narrative.narrative().indexOf(words);
        return new Span(at, at + words.length());
    }

    @Test
    void sentencesJoinByASpaceAndParagraphsByABlankLine() {
        assertEquals("A 62-year-old man presented for routine checkup. Systolic blood pressure was 120 mmHg, sitting."
                + "\n\nHe tested negative for COVID-19 and RSV.", NOTE.narrative());
    }

    @Test
    void aSpanAnchorsToItsSentenceAndColumnsAndResolvesBack() {
        Span sitting = span(NOTE, "sitting");
        String anchor = NOTE.anchor(sitting);
        assertEquals("segment:" + S2 + "[38,45]=sitting", anchor);
        assertEquals(Optional.of(sitting), NOTE.resolve(anchor));
        assertEquals(List.of(S2), SegmentNarrative.ids(anchor));
        assertEquals(Optional.of("sitting"), SegmentNarrative.words(anchor));
    }

    @Test
    void aSpanAcrossTwoSentencesIsOneAnchorPerSentence() {
        Span across = span(NOTE, "checkup. Systolic");
        String anchors = NOTE.anchor(across);
        assertEquals("segment:" + S1 + "[40,48]=checkup.|segment:" + S2 + "[0,8]=Systolic", anchors);
        List<Span> back = ElementText.spans(anchors, NOTE);
        assertEquals(List.of(span(NOTE, "checkup."), span(NOTE, "Systolic")), back);
    }

    @Test
    void anEditElsewhereMovesTheNarrativeButNotTheAnchor() {
        String anchor = NOTE.anchor(span(NOTE, "negative for COVID-19 and RSV"));
        SegmentNarrative edited = note("A 62-year-old man presented to the clinic for a routine checkup today.",
                "Systolic blood pressure was 120 mmHg, sitting.", "He tested negative for COVID-19 and RSV.");
        assertEquals(Optional.of(span(edited, "negative for COVID-19 and RSV")), edited.resolve(anchor));
    }

    @Test
    void aRevisedSentenceKeepsItsSpanWhileItHoldsTheWords() {
        String anchor = NOTE.anchor(span(NOTE, "sitting"));
        SegmentNarrative revised = note("A 62-year-old man presented for routine checkup.",
                "Systolic blood pressure, sitting, was 120 mmHg.", "He tested negative for COVID-19 and RSV.");
        assertEquals(Optional.of(span(revised, "sitting")), revised.resolve(anchor));
        SegmentNarrative gone = note("A 62-year-old man presented for routine checkup.",
                "Systolic blood pressure was 120 mmHg, standing.", "He tested negative for COVID-19 and RSV.");
        assertTrue(gone.resolve(anchor).isEmpty(), "the words are gone: a finding, not a guess");
    }

    @Test
    void anAnchorForASentenceTheNarrativeLacksIsNotFound() {
        SegmentNarrative other = SegmentNarrative.of(List.of(List.of(new SegmentNarrative.Sentence(S3, "sitting"))));
        String anchor = NOTE.anchor(span(NOTE, "sitting"));
        assertFalse(other.holds(anchor));
        assertTrue(other.resolve(anchor).isEmpty());
        assertTrue(NOTE.holds(anchor));
    }

    @Test
    void theWrittenFormCarriesSegmentAnchorsAndReportsWhatItLost() {
        ElementSet set = new ElementSet(NOTE.narrative(), List.of(
                ClinicalElement.proposed("el-1", "The systolic blood pressure is 120 mmHg.",
                        List.of(span(NOTE, "Systolic blood pressure was 120 mmHg"))),
                ClinicalElement.proposed("el-2", "The patient was sitting.", List.of(span(NOTE, "sitting"))),
                ClinicalElement.proposed("el-3", "COVID-19 is absent.", List.of(span(NOTE, "negative for COVID-19 and RSV")))),
                List.of(span(NOTE, "presented for routine checkup")));
        List<String> blocks = ElementText.toBlocks(set, NOTE);
        assertTrue(blocks.get(1).contains("from=\"segment:" + S2 + "[38,45]=sitting\""), blocks.get(1));
        assertTrue(blocks.getLast().startsWith("[anf-not-clinical,from=\"segment:" + S1), blocks.getLast());
        ElementSet read = ElementText.parse(blocks, NOTE, id -> Optional.empty()).orElseThrow();
        assertEquals(set.elements().stream().map(ClinicalElement::spans).toList(),
                read.elements().stream().map(ClinicalElement::spans).toList());
        assertEquals(set.notClinical(), read.notClinical());
        assertEquals(blocks, ElementText.toBlocks(read, NOTE), "byte-stable");
        // the sentence lost its posture: the posture element is a finding, the others follow their sentences
        SegmentNarrative revised = note("A 62-year-old man presented for routine checkup.",
                "Systolic blood pressure was 120 mmHg.", "He tested negative for COVID-19 and RSV.");
        assertEquals(List.of("The patient was sitting."), ElementText.unresolved(blocks, revised));
        ElementSet followed = ElementText.parse(blocks, revised, id -> Optional.empty()).orElseThrow();
        assertEquals(List.of("el-1", "el-3"), followed.elements().stream().map(ClinicalElement::id).toList());
        // the surfaces that only quote read a segment anchor's words
        assertEquals(List.of("sitting"), ElementText.quotes("segment:" + S2 + "[38,45]=sitting"));
    }
}
