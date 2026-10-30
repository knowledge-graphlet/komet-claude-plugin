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
package network.ike.komet.claude.anf;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfRefusal;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance;
import network.ike.komet.claude.anf.model.AnfStatement.Context;
import network.ike.komet.claude.anf.model.AnfStatement.Root;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static network.ike.komet.claude.anf.AnfTestStore.concept;
import static network.ike.komet.claude.anf.AnfTestStore.grounded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link AnfStatement} record is a projection of the statement tree in both directions, and
 * the {@code [anf]} codec keeps round-tripping the record the tree gives back
 * (IKE-Network/ike-issues#1312).
 */
class AnfProjectionTest {

    @BeforeAll
    static void startStore() {
        AnfTestStore.start();
    }

    @AfterAll
    static void stopStore() {
        AnfTestStore.stop();
    }

    private static AnfStatement.Result measure(Double lower, Double upper, boolean includeLower, boolean includeUpper,
                                               AnfValue unit) {
        return new AnfStatement.Result(lower, upper, includeLower, includeUpper, unit);
    }

    /** A performance with every kind of attribute value: grounded, candidate and clarify. */
    private static AnfStatement performance() {
        AnfValue.Candidate candidate = new AnfValue.Candidate("sitting position", "Sitting position", List.of("a", "b"),
                AnfValue.Disposition.PROPOSE_NEW);
        AnfValue.Clarify clarify = new AnfValue.Clarify("laterality", "Which arm was the cuff on?");
        return new AnfStatement(AnfStatement.Type.PERFORMANCE,
                grounded(concept("Systolic blood pressure")),
                grounded(concept("Mother")),
                new Circumstance.Performance(
                        measure(1700000000.0, 1700000000.0, true, true, grounded(concept("Epoch seconds"))),
                        List.of(grounded(concept("Hypertension monitoring")), candidate),
                        grounded(concept("Performed")),
                        measure(120.0, 120.0, true, true, grounded(concept("mmHg"))),
                        grounded(concept("Normal")),
                        measure(90.0, 140.0, true, false, grounded(concept("mmHg"))),
                        candidate,
                        grounded(concept("Cuff method")),
                        clarify));
    }

    private static AnfStatement request() {
        return new AnfStatement(AnfStatement.Type.REQUEST,
                grounded(concept("Blood glucose measurement")),
                null,
                new Circumstance.Request(
                        null,
                        List.of(grounded(concept("Diabetes monitoring"))),
                        grounded(concept("Routine")),
                        measure(null, 7.0, false, true, grounded(concept("mmol/L"))),
                        new Circumstance.Repetition(null, measure(14.0, 14.0, true, true, grounded(concept("Day"))),
                                null, null, measure(2.0, 2.0, true, true, grounded(concept("Per day")))),
                        List.of(grounded(concept("Before meals")), new AnfValue.Clarify("trigger", "Also at bedtime?")),
                        grounded(concept("Fingerstick"))));
    }

    private static AnfStatement narrative() {
        return new AnfStatement(AnfStatement.Type.NARRATIVE, null, null,
                new Circumstance.Narrative(null, List.of(), "Patient reports feeling well, \"no complaints\"."));
    }

    // ── Both directions ───────────────────────────────────────────────────────

    @Test
    void aRecordWrittenThroughTheBuilderReadsBackEqual() {
        for (AnfStatement statement : List.of(performance(), request(), narrative())) {
            DiTreeEntity tree = AnfProjection.toTree(statement);
            assertEquals(statement, AnfProjection.fromTree(tree, AnfTestStore.grounding()), statement.statementType().name());
            assertEquals(statement, AnfProjection.through(statement, AnfTestStore.grounding()));
        }
    }

    @Test
    void theTreeHoldsTheRecordWhereTheVertexTableSays() {
        Root root = AnfExpression.from(AnfProjection.toTree(performance())).root();
        assertEquals(AnfSemantic.PERFORMANCE, root.kindSemantic());
        Context context = root.context().orElseThrow();
        assertEquals(IkeConcept.PERFORMANCE_CIRCUMSTANCE.nid(),
                context.statementType().orElseThrow().concept().orElseThrow().nid());
        assertEquals(concept("Systolic blood pressure").nid(), context.topic().orElseThrow().singleConcept().orElseThrow().nid());
        assertEquals(concept("Mother").nid(), context.subjectOfInformation().orElseThrow().singleConcept().orElseThrow().nid());
        Performance performance = (Performance) root.circumstance().orElseThrow();
        assertEquals(concept("Performed").nid(), performance.disposition().orElseThrow().concept().orElseThrow().nid(),
                "the record's status is the structural definition's disposition");
        Attribute.Measure normalRange = performance.normalRange().orElseThrow();
        assertEquals(new BigDecimal("90.0"), normalRange.lowerBound().orElseThrow());
        assertTrue(normalRange.includeLowerBound());
        assertTrue(!normalRange.includeUpperBound(), "an excluded bound is the kernel's open bound");
        assertInstanceOf(AnfProvisional.Candidate.class, performance.bodySite().orElseThrow().provisional().orElseThrow());
        assertInstanceOf(AnfProvisional.Clarification.class, performance.laterality().orElseThrow().provisional().orElseThrow());
        assertEquals(2, performance.purposes().size());
        assertInstanceOf(AnfProvisional.Candidate.class, performance.purposes().get(1).provisional().orElseThrow());
    }

    @Test
    void aMeasureWithAProvisionalUnitRoundTrips() {
        AnfValue.Candidate unit = new AnfValue.Candidate("beats per minute", "bpm", List.of(), AnfValue.Disposition.PENDING);
        AnfStatement statement = new AnfStatement(AnfStatement.Type.PERFORMANCE, grounded(concept("Heart rate")), null,
                new Circumstance.Performance(null, List.of(), null, measure(72.0, 72.0, true, true, unit),
                        null, null, null, null, null));
        assertEquals(statement, AnfProjection.through(statement, AnfTestStore.grounding()));
    }

    // ── What the record cannot say ────────────────────────────────────────────

    @Test
    void aTreeHoldingWhatTheRecordCannotSayIsRefusedNamingIt() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        Context context = builder.context();
        builder.expression(context, AnfSemantic.TOPIC, concept("Blood pressure"));
        builder.author(context, concept("Dr Example"), null);
        Performance performance = (Performance) builder.circumstance();
        builder.measure(performance, AnfSemantic.RESULT, BigDecimal.ONE, BigDecimal.TEN, true, true, BigDecimal.ONE,
                concept("mmHg"));
        builder.conceptReference(performance, AnfSemantic.DEVICE, concept("Sphygmomanometer"));
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> AnfProjection.fromTree(builder.asDiTree(), AnfTestStore.grounding()));
        assertTrue(refusal.getMessage().contains("the authors"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("the device"), refusal.getMessage());
        assertTrue(refusal.getMessage().contains("a measure's resolution"), refusal.getMessage());
    }

    @Test
    void aComposedTopicIsRefusedUntilPromoted() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        Context context = builder.context();
        LogicalExpressionBuilder composed = new LogicalExpressionBuilder();
        composed.NecessarySet(composed.And(composed.ConceptAxiom(concept("Blood pressure")),
                composed.SomeRole(concept("Finding site"), composed.ConceptAxiom(concept("Left arm")))));
        builder.expression(context, AnfSemantic.TOPIC, (DiTreeEntity) composed.build().sourceGraph());
        builder.circumstance();
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> AnfProjection.fromTree(builder.asDiTree(), AnfTestStore.grounding()));
        assertTrue(refusal.getMessage().contains("composed expression"), refusal.getMessage());
    }

    @Test
    void aConceptTheGroundingDoesNotKnowIsRefused() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.conceptReference(builder.circumstance(), AnfSemantic.BODY_SITE, concept("Left arm"));
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> AnfProjection.fromTree(builder.asDiTree(), concept -> null));
        assertTrue(refusal.getMessage().contains("does not know"), refusal.getMessage());
    }

    // ── The [anf] codec ───────────────────────────────────────────────────────

    @Test
    void theAnfBlockRoundTripsTheRecordTheTreeGivesBack() {
        for (AnfStatement statement : List.of(performance(), request(), narrative())) {
            AnfStatement fromTree = AnfProjection.through(statement, AnfTestStore.grounding());
            String block = AnfAdoc.toAdoc(fromTree);
            AnfStatement parsed = AnfAdoc.parse(block, AnfTestStore.resolver());
            assertEquals(fromTree, parsed, block);
            // And the parsed record writes through the builder again to the same record.
            assertEquals(fromTree, AnfProjection.through(parsed, AnfTestStore.grounding()));
        }
    }
}
