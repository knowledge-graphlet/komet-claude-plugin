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

import network.ike.komet.document.Span;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.ScoringFixtures;
import network.ike.komet.claude.anf.element.LiftScore.Expectation;
import network.ike.komet.claude.anf.element.LiftScore.Report;
import network.ike.komet.claude.anf.element.LiftScore.Verdict;
import org.junit.jupiter.api.Test;

/**
 * The two-part score of a lift against the fixtures' expected elements: element recall and
 * precision by span overlap, then each matched element's statement, with the hard gates beside.
 * Each test takes the fixture's own expected lift and spoils it in one way.
 */
class LiftScoreTest {

    /** Grounds every identifier, except those {@link #unresolvable} names, to a value keyed by it. */
    private static final Set<String> unresolvable = new java.util.HashSet<>();

    private static final AnfAdoc.ConceptResolver RESOLVER = identifier -> unresolvable.contains(identifier)
            ? Optional.empty()
            : Optional.of(new AnfValue.Grounded(identifier.hashCode(), "pid:" + identifier, identifier, "label of " + identifier));

    private static ScoringFixtures.Narrative narrative(String id) {
        return ScoringFixtures.narratives().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private static Expectation bloodPressure() {
        return ScoringFixtures.expectation(narrative("scored-narrative"), RESOLVER);
    }

    private static ElementSet with(ElementSet set, ClinicalElement element) {
        List<ClinicalElement> elements = new ArrayList<>(set.elements());
        elements.add(element);
        return new ElementSet(set.narrative(), elements, set.notClinical());
    }

    private static ElementSet without(ElementSet set, String id) {
        return new ElementSet(set.narrative(),
                set.elements().stream().filter(e -> !e.id().equals(id)).toList(), set.notClinical());
    }

    private static ClinicalElement element(ElementSet set, String id) {
        return set.element(id).orElseThrow();
    }

    private static Span quote(Expectation expectation, String text) {
        return Span.find(expectation.narrative(), text, 1).orElseThrow();
    }

    private static AnfStatement statement(String fixture, String blockId, String from, String to) {
        String block = ScoringFixtures.blocks(ScoringFixtures.text(fixture)).get(blockId);
        assertTrue(block.contains(from), blockId + " holds " + from);
        return AnfAdoc.parse(block.replace(from, to), RESOLVER);
    }

    @Test
    void everyScoredNarrativeIsTheSourceNarrativesOwnWords() {
        for (ScoringFixtures.Narrative narrative : ScoringFixtures.narratives()) {
            StringBuilder examples = new StringBuilder();
            boolean in = false;
            for (String line : ScoringFixtures.text(narrative.fixture()).split("\n")) {
                if (line.equals("====")) {
                    in = !in;
                } else if (in) {
                    examples.append(line).append(' ');
                }
            }
            assertTrue(squeezed(examples.toString()).contains(squeezed(narrative.text())),
                    narrative.fixture() + " " + narrative.id() + " is verbatim");
        }
    }

    private static String squeezed(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    @Test
    void theExpectedLiftScoresPerfectly() {
        for (ScoringFixtures.Narrative narrative : ScoringFixtures.narratives()) {
            Expectation expectation = ScoringFixtures.expectation(narrative, RESOLVER);
            Report report = LiftScore.score(expectation, ScoringFixtures.perfectLift(expectation));
            String where = narrative.fixture() + " " + narrative.id() + "\n" + report.summary();
            assertEquals(1.0, report.recall(), where);
            assertEquals(1.0, report.precision(), where);
            assertEquals(1.0, report.spanOverlap(), where);
            assertEquals(expectation.elements().size(), report.exact(), where);
            assertEquals(1.0, report.statementAccuracy(), where);
            assertTrue(report.gatesHold(), where);
            assertTrue(report.merged().isEmpty() && report.split().isEmpty(), "elements sharing words are no merge: " + where);
        }
    }

    @Test
    void aMissedElementCostsRecallAndItsStatement() {
        Expectation expectation = bloodPressure();
        Report report = LiftScore.score(expectation, without(ScoringFixtures.perfectLift(expectation), "bp-1-male"));
        assertEquals(6.0 / 7, report.recall(), 1e-9);
        assertEquals(1.0, report.precision());
        assertEquals(Verdict.MISSED, report.matches().getFirst().verdict());
        assertEquals(6.0 / 7, report.statementAccuracy(), 1e-9);
        assertEquals(1.0, report.statementAccuracyOfMatched(), "every element found was lifted correctly");
    }

    @Test
    void anElementOnScoredWordsMatchingNothingCostsPrecisionButOneOnWordsNotScoredDoesNot() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        lift = with(lift, ClinicalElement.proposed("checkup", "The visit was a routine checkup.",
                List.of(quote(expectation, "routine checkup"))));
        Report unscored = LiftScore.score(expectation, lift);
        assertEquals(1.0, unscored.precision(), "the checkup is words the statements leave unencoded");

        lift = with(lift, ClinicalElement.proposed("office", "The patient came to the office.",
                List.of(quote(expectation, "He had"))));
        Report report = LiftScore.score(expectation, lift);
        assertEquals(List.of("office"), report.spurious().stream().map(ClinicalElement::id).toList());
        assertEquals(7.0 / 8, report.precision(), 1e-9);
        assertEquals(9, report.isolatedCount());
    }

    @Test
    void anElementMergingTwoFactsIsReportedAndCostsOne() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        ClinicalElement systolic = element(lift, "bp-3-systolic-bp");
        ClinicalElement diastolic = element(lift, "bp-4-diastolic-bp");
        List<Span> both = new ArrayList<>(systolic.spans());
        both.addAll(diastolic.spans());
        lift = with(without(without(lift, "bp-3-systolic-bp"), "bp-4-diastolic-bp"),
                systolic.withSpans(both).withSentence("The systolic blood pressure was 120 mmHg over 85."));
        Report report = LiftScore.score(expectation, lift);

        assertEquals(List.of("bp-3-systolic-bp", "bp-4-diastolic-bp"), report.merged().get("bp-3-systolic-bp"));
        assertEquals(6.0 / 7, report.recall(), 1e-9);
        assertEquals(Verdict.MISSED, report.matches().get(3).verdict(), "the diastolic fact is lost in the merge");
        assertTrue(report.matches().get(2).overlap() < 1.0, "the merged element's spans reach past the systolic fact");
        assertFalse(report.matches().get(2).exact());
    }

    @Test
    void aFactSplitInTwoIsReported() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        ClinicalElement request = element(lift, "bp-7-blood-pressure-request");
        lift = with(without(lift, request.id()), request.withSpans(List.of(request.spans().getFirst())));
        lift = with(lift, ClinicalElement.proposed("weeks", "The home measurements last 4 weeks.",
                List.of(request.spans().getLast())));
        Report report = LiftScore.score(expectation, lift);

        assertEquals(List.of("bp-7-blood-pressure-request", "weeks"), report.split().get("bp-7-blood-pressure-request"));
        assertEquals(1.0, report.recall());
        assertEquals(List.of("weeks"), report.spurious().stream().map(ClinicalElement::id).toList());
    }

    @Test
    void aWrongValueIsMisEncodedAndNamesTheField() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        lift = lift.replace(element(lift, "bp-3-systolic-bp")
                .lifted(statement("blood-pressure.adoc", "bp-3-systolic-bp", "[120,120]", "[85,85]")));
        Report report = LiftScore.score(expectation, lift);

        LiftScore.Match systolic = report.matches().get(2);
        assertEquals(Verdict.MIS_ENCODED, systolic.verdict());
        assertEquals(List.of(new LiftScore.Difference("result", List.of("[120,120] @id=pid:259018001"),
                List.of("[85,85] @id=pid:259018001"))), systolic.differences());
        assertEquals(1.0, report.recall(), "the fact was found, and mis-encoded");
        assertEquals(6.0 / 7, report.statementAccuracy(), 1e-9);
        assertTrue(report.gatesHold(), "a wrong value is no flip");
    }

    @Test
    void labelsDispositionsAndNearestMatchesDoNotCount() {
        Expectation expectation = ScoringFixtures.expectation(narrative("scored-narrative-1"), RESOLVER);
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        AnfStatement relabelled = statement("perrla.adoc", "perrla-1-pupils-equal",
                "candidate[Bilateral symmetry assessment] disp=PENDING",
                "candidate[bilateral symmetry ASSESSMENT] disp=ACCEPTED near=363926002");
        lift = lift.replace(element(lift, "perrla-1-pupils-equal").lifted(relabelled));
        assertEquals(Verdict.CORRECT, LiftScore.score(expectation, lift).matches().getFirst().verdict());
    }

    @Test
    void absenceForPresenceIsAPolarityFlip() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        lift = lift.replace(element(lift, "bp-1-male")
                .lifted(statement("blood-pressure.adoc", "bp-1-male", "[1,1]", "[0,0]")));
        Report report = LiftScore.score(expectation, lift);
        assertEquals(1, report.polarityFlips());
        assertFalse(report.gatesHold());
        assertTrue(report.summary().contains("bp-1-male: mis_encoded (bp-1-male, overlap 1.00), polarity flipped"),
                report.summary());
    }

    @Test
    void anUnresolvedCodeFailsTheGate() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        unresolvable.add("271649006");
        try {
            lift = lift.replace(element(lift, "bp-3-systolic-bp")
                    .lifted(AnfAdoc.parse(ScoringFixtures.blocks(ScoringFixtures.text("blood-pressure.adoc"))
                            .get("bp-3-systolic-bp"), RESOLVER)));
        } finally {
            unresolvable.clear();
        }
        Report report = LiftScore.score(expectation, lift);
        assertEquals(1, report.unresolvedCodes());
        assertFalse(report.gatesHold());
        assertEquals(Verdict.MIS_ENCODED, report.matches().get(2).verdict());
    }

    @Test
    void anElementNotLiftedIsFoundButNotLifted() {
        Expectation expectation = bloodPressure();
        ElementSet lift = ScoringFixtures.perfectLift(expectation);
        ClinicalElement age = element(lift, "bp-2-chronological-age");
        lift = lift.replace(ClinicalElement.proposed(age.id(), age.sentence(), age.spans()));
        Report report = LiftScore.score(expectation, lift);
        assertEquals(Verdict.NOT_LIFTED, report.matches().get(1).verdict());
        assertEquals(1.0, report.recall());
    }

    @Test
    void elementsOnTheSameWordsPairBySentence() {
        Expectation expectation = ScoringFixtures.expectation(narrative("scored-narrative-2"), RESOLVER);
        ElementSet perfect = ScoringFixtures.perfectLift(expectation);
        List<ClinicalElement> renamed = new ArrayList<>();
        int n = 0;
        for (ClinicalElement element : perfect.elements().reversed()) {
            ClinicalElement copy = ClinicalElement.proposed("el-" + ++n, element.sentence(), element.spans());
            renamed.add(copy.lifted(element.statement().orElseThrow()));
        }
        Report report = LiftScore.score(expectation, new ElementSet(perfect.narrative(), renamed, List.of()));
        assertEquals(1.0, report.statementAccuracy(), report.summary());
    }
}
