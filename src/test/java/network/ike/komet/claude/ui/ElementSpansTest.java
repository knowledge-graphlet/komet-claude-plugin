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
package network.ike.komet.claude.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import network.ike.komet.claude.anf.element.ElementColours;
import network.ike.komet.claude.anf.element.ElementLevel;
import org.junit.jupiter.api.Test;

/** The note's element spans on the document surface (dev-anf-element-isolation, "What the reader sees"). */
class ElementSpansTest {

    private static final String NOTE = """
            ## Note

            A 62-year-old man presented for routine checkup. Negative for COVID-19 and RSV.

            ```anf type=performance id=el-1 element="The patient is 62 years old." from="quote:A 62-year-old man#1"
            topic:: k:Age[Chronological age] @id=397669002
            ```

            ```anf id=el-2 element="COVID-19 is absent." from="quote:Negative for COVID-19 and RSV#1"
            ```

            ```anf id=el-3 element="RSV infection is absent." from="quote:Negative for COVID-19 and RSV#1"
            ```

            ```anf-not-clinical from="quote:routine checkup#1"
            ```
            """;

    @Test
    void scansElementFencesInOrderWithTheirColours() {
        List<ElementSpans.Fence> fences = ElementSpans.scan(NOTE);
        assertEquals(4, fences.size());
        assertTrue(fences.get(0).element());
        assertEquals(ElementColours.of(0), fences.get(0).colour());
        assertEquals(ElementColours.of(2), fences.get(2).colour());
        assertFalse(fences.get(3).element());
        assertEquals("routine checkup", fences.get(3).quotes().getFirst().text());
    }

    @Test
    void underlinesSpansShadesUncoveredWordsAndLeavesNotClinicalAlone() {
        ElementSpans spans = new ElementSpans();
        spans.prepare(NOTE);
        String run = "A 62-year-old man presented for routine checkup. Negative for COVID-19 and RSV.";
        List<ElementSpans.Mark> marks = spans.marks(run);
        assertEquals(new ElementSpans.Mark(0, 17, ElementSpans.Kind.SPAN, ElementColours.of(0)), marks.get(0));
        assertEquals("presented for", text(run, marks, ElementSpans.Kind.UNCOVERED));
        assertEquals("routine checkup", text(run, marks, ElementSpans.Kind.NOT_CLINICAL));
        // two elements share a span: the earlier element's colour draws it
        ElementSpans.Mark shared = marks.stream().filter(m -> m.start() == run.indexOf("Negative")).findFirst().orElseThrow();
        assertEquals(ElementColours.of(1), shared.colour());
        assertEquals(run.length(), marks.getLast().end());
        for (int i = 1; i < marks.size(); i++) {
            assertEquals(marks.get(i - 1).end(), marks.get(i).start(), "marks cover the run without gaps");
        }
    }

    @Test
    void countsAQuotesOccurrencesAcrossRunsInOrder() {
        ElementSpans spans = new ElementSpans();
        spans.prepare("""
                ```anf id=el-1 element="The patient is male." from="quote:man#2"
                ```
                """);
        assertEquals(List.of(new ElementSpans.Mark(0, 22, ElementSpans.Kind.PLAIN, null)),
                spans.marks("The woman and the man."), "a woman is no man, and the first man is not the span");
        List<ElementSpans.Mark> second = spans.marks("A man presented.");
        assertEquals(List.of(new ElementSpans.Mark(2, 5, ElementSpans.Kind.SPAN, ElementColours.of(0))),
                second.stream().filter(m -> m.kind() == ElementSpans.Kind.SPAN).toList());
    }

    @Test
    void aRunWithNoSpanIsPlainAndTheFirstElementCarriesTheSwitch() {
        ElementSpans spans = new ElementSpans();
        spans.prepare(NOTE);
        assertEquals(List.of(new ElementSpans.Mark(0, 12, ElementSpans.Kind.PLAIN, null)), spans.marks("Nothing here"));
        String first = "anf type=performance id=el-1 element=\"The patient is 62 years old.\" from=\"quote:A 62-year-old man#1\"";
        assertTrue(spans.isSwitchHost(first));
        assertFalse(spans.isSwitchHost("anf id=el-2 element=\"COVID-19 is absent.\" from=\"quote:Negative for COVID-19 and RSV#1\""));
        assertEquals(ElementColours.of(0), spans.colour(first).orElseThrow());
    }

    @Test
    void levelsAreCumulative() {
        assertTrue(ElementLevel.ANF.shows(ElementLevel.ELEMENTS));
        assertTrue(ElementLevel.ELEMENTS.shows(ElementLevel.NARRATIVE));
        assertFalse(ElementLevel.NARRATIVE.shows(ElementLevel.ELEMENTS));
    }

    private static String text(String run, List<ElementSpans.Mark> marks, ElementSpans.Kind kind) {
        return marks.stream().filter(m -> m.kind() == kind).map(m -> run.substring(m.start(), m.end()))
                .reduce((a, b) -> a + "|" + b).orElse("");
    }
}
