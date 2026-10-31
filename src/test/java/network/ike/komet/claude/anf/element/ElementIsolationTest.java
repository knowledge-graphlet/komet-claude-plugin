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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.Circumstance;
import org.junit.jupiter.api.Test;

/**
 * The element model of the two-step lift ({@code dev-anf-element-isolation}): spans found again by
 * their quotes, coverage, the gestures of step one, the states, the written form round trip, and
 * the isolate tool's refusals.
 */
class ElementIsolationTest {

    static final String NOTE = "A 62-year-old man presented for routine checkup. Systolic blood pressure 120 mmHg, sitting. "
            + "HDL cholesterol was not measured. Negative for COVID-19 and RSV.";

    static Span span(String quote) {
        return Span.find(NOTE, quote, 1).orElseThrow();
    }

    static ElementSet isolated() {
        Map<String, Object> input = Map.of(
                "elements", List.of(
                        Map.of("sentence", "The patient is 62 years old.", "spans", List.of(Map.of("quote", "A 62-year-old man"))),
                        Map.of("sentence", "The patient is male.", "spans", List.of(Map.of("quote", "A 62-year-old man"))),
                        Map.of("sentence", "The systolic blood pressure is 120 mmHg.",
                                "spans", List.of(Map.of("quote", "Systolic blood pressure 120 mmHg"))),
                        Map.of("sentence", "The patient is sitting.", "spans", List.of(Map.of("quote", "sitting")),
                                "links", List.of(2)),
                        Map.of("sentence", "HDL cholesterol has no result: it was not measured.",
                                "spans", List.of(Map.of("quote", "HDL cholesterol was not measured"))),
                        Map.of("sentence", "COVID-19 is absent.", "spans", List.of(Map.of("quote", "Negative for COVID-19 and RSV")),
                                "reason", "each could be present without the other"),
                        Map.of("sentence", "RSV infection is absent.", "spans", List.of(Map.of("quote", "Negative for COVID-19 and RSV")))),
                "not_clinical", List.of(Map.of("quote", "presented for routine checkup")));
        IsolateElementsTool.Result result = IsolateElementsTool.read(input, NOTE);
        assertTrue(result.problems().isEmpty(), () -> result.problems().toString());
        return result.set().orElseThrow();
    }

    static AnfStatement statement(String topic) {
        AnfValue candidate = new AnfValue.Candidate(topic, topic, List.of(), AnfValue.Disposition.PENDING);
        return new AnfStatement(AnfStatement.Type.PERFORMANCE, candidate, null,
                new Circumstance.Performance(null, List.of(), null,
                        new AnfStatement.Result(62.0, 62.0, true, true, new AnfValue.Clarify("unit", "which unit?")),
                        null, null, null, null, null));
    }

    @Test
    void aQuoteFindsItsSpanAgainAndItsAnchorRoundTrips() {
        Span man = span("A 62-year-old man");
        assertEquals(0, man.start());
        assertEquals("A 62-year-old man", man.text(NOTE));
        assertEquals("quote:A 62-year-old man#1", man.quoteAnchor(NOTE));
        assertEquals(Optional.of(man), Span.resolve(man.quoteAnchor(NOTE), NOTE));
        // case and whitespace are forgiven when the exact text is absent
        assertEquals(Optional.of(span("Systolic blood pressure 120 mmHg")),
                Span.find(NOTE, "systolic  blood pressure 120 mmhg", 1));
        assertTrue(Span.find(NOTE, "diastolic", 1).isEmpty());
        // a second occurrence is counted
        String twice = "pain left, pain right";
        Span second = Span.find(twice, "pain", 2).orElseThrow();
        assertEquals(11, second.start());
        assertEquals("quote:pain#2", second.quoteAnchor(twice));
    }

    @Test
    void theIsolateToolReadsElementsWithSharedSpansAndSymmetricLinks() {
        ElementSet set = isolated();
        assertEquals(7, set.elements().size());
        ClinicalElement age = set.element("el-1").orElseThrow();
        ClinicalElement sex = set.element("el-2").orElseThrow();
        assertEquals(age.spans(), sex.spans());
        assertEquals(List.of("el-3"), set.element("el-4").orElseThrow().links());
        assertEquals(List.of("el-4"), set.element("el-3").orElseThrow().links());
        assertEquals(Optional.of("each could be present without the other"), set.element("el-6").orElseThrow().reason());
        assertFalse(set.allAccepted());
        assertTrue(set.elements().stream().allMatch(e -> e.state() == ElementState.NOT_LIFTED));
    }

    @Test
    void theIsolateToolRefusesAnInferenceAndAQuoteTheNoteDoesNotHold() {
        Map<String, Object> input = Map.of("elements", List.of(
                Map.of("sentence", "The patient does not smoke.", "spans", List.of()),
                Map.of("sentence", "The diastolic pressure is 80 mmHg.", "spans", List.of(Map.of("quote", "diastolic 80")))));
        IsolateElementsTool.Result result = IsolateElementsTool.read(input, NOTE);
        assertTrue(result.set().isEmpty());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("would be an inference")), result.problems().toString());
        assertTrue(result.problems().stream().anyMatch(p -> p.contains("which the note does not hold")), result.problems().toString());
        StringBuilder sunk = new StringBuilder();
        String answer = new IsolateElementsTool(NOTE, set -> sunk.append("sunk")).execute(input);
        assertTrue(answer.startsWith("The elements were refused"), answer);
        assertEquals("", sunk.toString());
    }

    @Test
    void anElementWithoutASpanCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> ClinicalElement.proposed("el-1", "A fact.", List.of()));
    }

    @Test
    void coverageShowsWhatNoElementAccountsFor() {
        ElementSet set = isolated();
        assertTrue(set.fullyCovered(), () -> set.uncovered().stream().map(s -> s.text(NOTE)).toList().toString());
        ElementSet without = set.unmarkNotClinical(span("presented for routine checkup"));
        assertEquals(List.of("presented for routine checkup"), without.uncovered().stream().map(s -> s.text(NOTE)).toList());
        ElementSet bare = ElementSet.empty(NOTE);
        assertEquals(1, bare.uncovered().size());
        assertEquals(NOTE.strip().replaceAll("\\.$", ""), bare.uncovered().get(0).text(NOTE));
    }

    @Test
    void theGesturesOfStepOne() {
        ElementSet set = isolated();
        // split one element in two on the same spans
        ElementSet split = set.split("el-3", "The systolic blood pressure is 120 mmHg.", "The pressure was taken at rest.");
        assertEquals(8, split.elements().size());
        ClinicalElement added = split.element("el-8").orElseThrow();
        assertEquals(split.element("el-3").orElseThrow().spans(), added.spans());
        // merge two: spans unite, links carry over, the lift is dropped
        ElementSet merged = set.lifted("el-6", statement("COVID-19"))
                .merge("el-6", "el-7", "COVID-19 and RSV infection are absent.");
        assertEquals(6, merged.elements().size());
        assertEquals(ElementState.NOT_LIFTED, merged.element("el-6").orElseThrow().state());
        assertTrue(merged.element("el-7").isEmpty());
        // remove drops the links that named it
        ElementSet removed = set.remove("el-3");
        assertTrue(removed.element("el-4").orElseThrow().links().isEmpty());
        // add from a selection
        ElementSet more = set.add("Blood pressure was measured sitting.", List.of(span("sitting")));
        assertEquals("el-8", more.elements().stream().map(ClinicalElement::id).filter(id -> id.equals("el-8")).findFirst().orElseThrow());
        assertTrue(more.element("el-8").orElseThrow().accepted());
        // elements stay in the order of their first span
        assertEquals(List.of("el-1", "el-2", "el-3", "el-4", "el-8", "el-5", "el-6", "el-7"),
                more.elements().stream().map(ClinicalElement::id).toList());
        // the lift-all gate
        ElementSet accepted = set;
        for (ClinicalElement e : set.elements()) {
            accepted = accepted.accept(e.id(), true);
        }
        assertTrue(accepted.allAccepted());
    }

    @Test
    void anElementsStateFollowsItsLift() {
        ElementSet set = isolated().lifted("el-1", statement("Chronological age"));
        ClinicalElement age = set.element("el-1").orElseThrow();
        // the candidate topic and the clarify unit need attention
        assertEquals(ElementState.NEEDS_ATTENTION, age.state());
        ClinicalElement edited = set.edit("el-1", "The patient is sixty-two years old.").element("el-1").orElseThrow();
        assertEquals(ElementState.STALE, edited.state());
        assertEquals(ElementState.NEEDS_ATTENTION, edited.acceptLift().state());
        assertEquals(6, set.toLift().size());
    }

    @Test
    void theWrittenFormRoundTrips() {
        ElementSet set = isolated().lifted("el-1", statement("Chronological age")).accept("el-1", true);
        List<String> blocks = ElementText.toBlocks(set);
        assertEquals(8, blocks.size());
        String first = blocks.get(0);
        assertTrue(first.startsWith("[anf,type=performance,id=el-1,element=\"The patient is 62 years old.\","
                + "from=\"quote:A 62-year-old man#1\",lifted=\"The patient is 62 years old.\",accepted]\n--\n"), first);
        assertTrue(blocks.get(1).endsWith("]\n--\n--\n"), "an unlifted element has an empty body: " + blocks.get(1));
        assertTrue(blocks.get(7).startsWith("[anf-not-clinical,from=\"quote:presented for routine checkup#1\"]"));

        ElementSet read = ElementText.parse(blocks, NOTE, id -> Optional.empty()).orElseThrow();
        assertEquals(set.elements().size(), read.elements().size());
        assertEquals(set.notClinical(), read.notClinical());
        for (int i = 0; i < set.elements().size(); i++) {
            ClinicalElement a = set.elements().get(i);
            ClinicalElement b = read.elements().get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.sentence(), b.sentence());
            assertEquals(a.spans(), b.spans());
            assertEquals(a.links(), b.links());
            assertEquals(a.accepted(), b.accepted());
            assertEquals(a.state(), b.state());
        }
        assertEquals(ElementText.toBlocks(set), ElementText.toBlocks(read));
        // a lift written before elements holds none
        assertTrue(ElementText.parse(List.of("[anf,type=performance]\n--\ntopic:: candidate[x] disp=PENDING\n--\n"),
                NOTE, id -> Optional.empty()).isEmpty());
    }

    @Test
    void attributesReadFromAHeaderOrAFence() {
        Map<String, String> header = ElementText.attributes("anf,type=performance,id=el-1,element=\"A, b \\\"c\\\".\",accepted");
        assertEquals("", header.get("anf"));
        assertEquals("A, b \"c\".", header.get("element"));
        assertTrue(header.containsKey("accepted"));
        Map<String, String> fence = ElementText.attributes("anf type=performance id=el-2 element=\"COVID-19 is absent.\" "
                + "from=\"quote:Negative for COVID-19 and RSV#1\"");
        assertEquals("el-2", fence.get("id"));
        assertEquals(List.of("Negative for COVID-19 and RSV"), ElementText.quotes(fence.get("from")));
    }

    @Test
    void anElementsLiftMessageNamesOneFactAndQuotesItsWords() {
        String message = network.ike.komet.claude.anf.AnfLiftAccess.elementMessage(NOTE, "COVID-19 is absent.",
                List.of("Negative for COVID-19 and RSV"));
        assertTrue(message.contains("Element: COVID-19 is absent."), message);
        assertTrue(message.contains("\"Negative for COVID-19 and RSV\""), message);
        assertTrue(message.contains("do not lift its other facts"), message);
    }
}
