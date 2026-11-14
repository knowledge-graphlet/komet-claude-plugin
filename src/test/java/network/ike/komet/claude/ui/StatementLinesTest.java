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

import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.StructuredBlockKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An ANF statement as a page reads it: one line under its group, in a reader's words. */
class StatementLinesTest {

    private static final List<String> REMOVAL = List.of(
            "topic:: candidate[Removal of the implant] disp=PENDING",
            "subject_of_information:: candidate[The implant in the jaw joint, in patients] disp=PENDING",
            "statement_time:: [1989,1989] k:Year[year] @id=258707000",
            "result:: [0.8,0.8] candidate[Population share] disp=PENDING",
            "method:: candidate[Clinical report] disp=PENDING",
            "timing:: [0,30] k:Month[month] @id=258706009");

    private static final List<String> COUNTED = List.of(
            "topic:: candidate[Joints counted] disp=PENDING",
            "subject_of_information:: candidate[The implant in the jaw joint, in patients] disp=PENDING",
            "statement_time:: [1989,1989] k:Year[year] @id=258707000",
            "result:: [55,55] candidate[Joints] disp=PENDING");

    private static final List<String> NARRATIVE = List.of(
            "topic:: candidate[Capping of the cut head of the jawbone with the PTFE composite] disp=PENDING",
            "subject_of_information:: candidate[Jaw joints with the disc left in place, in patients] disp=PENDING",
            "statement_time:: [1982,1982] k:Year[year] @id=258707000",
            "text:: \"12 patients; the composite was not a disc replacement\"");

    private static List<String> words(DecisionFigure figure) {
        return figure.marks().stream().filter(DecisionFigure.Text.class::isInstance).map(m -> ((DecisionFigure.Text) m).text()).toList();
    }

    private static List<String> words(DecisionFigure figure, String role) {
        return figure.marks().stream().filter(DecisionFigure.Text.class::isInstance).filter(m -> m.role().equals(role))
                .map(m -> ((DecisionFigure.Text) m).text()).toList();
    }

    @Test
    void aMeasureReadsInAReadersWords() {
        assertEquals("Present", StatementLines.measure("[1,1] presence"));
        assertEquals("Absent", StatementLines.measure("[0,0] presence"));
        assertEquals("Not determined", StatementLines.measure("[0,1] presence"));
        assertEquals("80%", StatementLines.measure("[0.8,0.8] Population share"));
        assertEquals("30% to 70%", StatementLines.measure("[0.3,0.7] Population share"));
        assertEquals("55 joints", StatementLines.measure("[55,55] Joints"));
        assertEquals("1 month", StatementLines.measure("[1,1] month"));
        assertEquals("$100,000", StatementLines.measure("[100000,100000] US dollar"));
        assertEquals("at least 2 years", StatementLines.measure("[2,INF) year"));
        assertEquals("less than 5 months", StatementLines.measure("[,5) month"));
        assertEquals("1", StatementLines.measure("[1,1] Countable quantity"));
    }

    @Test
    void whatIsNotAMeasureStandsAsWritten() {
        assertEquals("Clinical report", StatementLines.measure("Clinical report"));
        assertEquals("[first, second] of three", StatementLines.measure("[first, second] of three"));
    }

    @Test
    void aYearHasNoSeparatorAndATimingFromNothingIsWithin() {
        assertEquals("1982", StatementLines.time("[1982,1982] year"));
        assertEquals("1982 to 1984", StatementLines.time("[1982,1984] year"));
        assertEquals("within 30 months", StatementLines.timing("[0,30] month"));
        assertEquals("within 1 month", StatementLines.timing("[0,1] month"));
        assertEquals("at 24 months", StatementLines.timing("[24,24] month"));
        assertEquals("6 to 12 months", StatementLines.timing("[6,12] month"));
    }

    @Test
    void aStatementIsReadFromItsFields() {
        StatementLines.Statement statement = StatementLines.read("[anf,type=performance]", REMOVAL);
        assertEquals("performance", statement.type());
        assertEquals("Removal of the implant", statement.topic());
        assertEquals("The implant in the jaw joint, in patients", statement.group());
        assertEquals("80%", statement.result());
        assertEquals("within 30 months", statement.within());
        assertEquals("Clinical report", statement.how());
        assertEquals("1989", statement.when());
        assertTrue(statement.rest().isEmpty(), statement.rest().toString());
    }

    @Test
    void aLineHasNoFieldNameAndNoIntervalNotation() {
        DecisionFigure figure = StatementLines.figure(StatementLines.read("[anf,type=performance]", REMOVAL), true);
        assertEquals(StatementLines.ROLE, figure.role());
        assertEquals(List.of("The implant in the jaw joint, in patients"), words(figure, "statement-group"));
        assertEquals(List.of("Removal of the implant"), words(figure, "statement-topic"));
        assertEquals(List.of("80%"), words(figure, "statement-result"));
        assertEquals(List.of("within 30 months"), words(figure, "statement-timing"));
        assertEquals(List.of("Clinical report · 1989"), words(figure, "statement-detail"));
        String all = String.join(" ", words(figure));
        assertFalse(all.contains("subject_of_information") || all.contains("statement_time") || all.contains("topic")
                || all.contains("[") || all.contains("@id") || all.contains("disp="), all);
    }

    @Test
    void aNarrativeHasItsWordsWhereAResultWouldBe() {
        DecisionFigure figure = StatementLines.figure(StatementLines.read("[anf,type=narrative,id=gallagher-1982]", NARRATIVE), true);
        assertTrue(words(figure, "statement-result").isEmpty());
        String detail = String.join(" ", words(figure, "statement-detail"));
        assertTrue(detail.startsWith("“12 patients; the composite was not a disc replacement”"), detail);
        assertTrue(detail.endsWith("1982"), detail);
    }

    @Test
    void aFieldWithNoPlaceOfItsOwnIsNamedInPlainWords() {
        StatementLines.Statement statement = StatementLines.read("[anf,type=request]", List.of(
                "topic:: k:Mra[MRA of head] @id=x", "requested_result:: [1,1] k:Count[Countable quantity] @id=y",
                "subject_of_record:: candidate[The patient] disp=PENDING"));
        assertEquals("1", statement.result());
        assertEquals(List.of("subject of record: The patient"), statement.rest());
        String detail = String.join(" ", words(StatementLines.figure(statement, true), "statement-detail"));
        assertTrue(detail.startsWith("requested"), detail);
    }

    @Test
    void aGroupIsNamedOnceInARun() {
        DocumentBlockKinds.Anf kind = new DocumentBlockKinds.Anf();
        assertTrue(kind.readsAsRun());
        StructuredBlockKind.Source removal = source("[anf,type=performance]", REMOVAL);
        StructuredBlockKind.Source counted = source("[anf,type=performance]", COUNTED);
        StructuredBlockKind.Source narrative = source("[anf,type=narrative]", NARRATIVE);
        StructuredBlockKind.Context context = new StructuredBlockKind.Context(null, new ProfileBlocks().shared(), 14, null);

        DecisionFigure first = kind.figures(removal, List.of(narrative), context).getFirst();
        assertEquals(List.of("The implant in the jaw joint, in patients"), words(first, "statement-group"), "a new group is named");
        DecisionFigure second = kind.figures(counted, List.of(narrative, removal), context).getFirst();
        assertTrue(words(second, "statement-group").isEmpty(), "the group of the statement before is not named again");
        assertEquals(List.of("55 joints"), words(second, "statement-result"));
        DecisionFigure alone = kind.figures(counted, context).getFirst();
        assertEquals(List.of("The implant in the jaw joint, in patients"), words(alone, "statement-group"), "a statement that begins a run names its group");
    }

    @Test
    void aBlockThatNamesNoTopicPrintsItsFields() {
        DocumentBlockKinds.Anf kind = new DocumentBlockKinds.Anf();
        StructuredBlockKind.Context context = new StructuredBlockKind.Context(null, new ProfileBlocks().shared(), 14, null);
        List<DecisionFigure> figures = kind.figures(source("[anf]", List.of("method:: candidate[an inspection] disp=PENDING")), context);
        assertEquals(1, figures.size());
        assertTrue(words(figures.getFirst()).containsAll(List.of("method", "an inspection")), words(figures.getFirst()).toString());
    }

    private static StructuredBlockKind.Source source(String parameters, List<String> content) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(parameters);
        lines.add("--");
        lines.addAll(content);
        lines.add("--");
        return new StructuredBlockKind.Source(parameters, lines);
    }
}
