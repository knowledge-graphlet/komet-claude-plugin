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

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfIdentityTable;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfRefusal;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance;
import network.ike.komet.claude.anf.model.AnfStatement.Context;
import network.ike.komet.claude.anf.model.AnfStatement.Root;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static network.ike.komet.claude.anf.AnfTestStore.concept;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ANF expression builder constructs every vertex kind and refuses, with a reason, every
 * edit that would break the statement tree's rules (IKE-Network/ike-issues#1312).
 */
class AnfExpressionBuilderTest {

    @BeforeAll
    static void startStore() {
        AnfTestStore.start();
    }

    @AfterAll
    static void stopStore() {
        AnfTestStore.stop();
    }

    private static AnfExpressionBuilder performance() {
        return new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void aPerformanceStatementIsBuiltAndReadBackThroughItsAdaptors() {
        AnfExpressionBuilder builder = performance();
        Context context = builder.context();
        builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.expression(context, AnfSemantic.TOPIC, concept("Systolic blood pressure"));
        builder.participant(context, AnfSemantic.SUBJECT_OF_RECORD, concept("The patient"), concept("Patient role"));
        builder.measure(context, AnfSemantic.STATEMENT_TIME, new BigDecimal("1700000000"), new BigDecimal("1700000000"),
                true, true, null, concept("Epoch seconds"));
        Circumstance.Performance circumstance = (Circumstance.Performance) builder.circumstance();
        builder.measure(circumstance, AnfSemantic.RESULT, new BigDecimal("120"), new BigDecimal("120"), true, true,
                BigDecimal.ONE, concept("mmHg"));
        builder.conceptReference(circumstance, AnfSemantic.BODY_SITE, concept("Left arm"));
        builder.expression(circumstance, AnfSemantic.PURPOSE, concept("Hypertension monitoring"));
        builder.author(context, concept("Dr Example"), AnfExpressionBuilder.singleConceptExpression(concept("Attending")));

        AnfExpression expression = builder.build();
        Root root = expression.root();
        assertEquals(AnfSemantic.PERFORMANCE, root.kindSemantic());
        Context readContext = root.context().orElseThrow();
        assertEquals(concept("Systolic blood pressure").nid(),
                readContext.topic().orElseThrow().singleConcept().orElseThrow().nid());
        assertEquals(concept("Patient role").nid(), readContext.subjectOfRecord().orElseThrow().role().orElseThrow().nid());
        assertEquals(1, readContext.authors().size());
        assertEquals(concept("Attending").nid(), singleConcept(readContext.authors().get(0).role().orElseThrow()));
        Circumstance.Performance performance = (Circumstance.Performance) root.circumstance().orElseThrow();
        Attribute.Measure result = performance.result().orElseThrow();
        assertEquals(new BigDecimal("120"), result.lowerBound().orElseThrow());
        assertTrue(result.includeLowerBound());
        assertEquals(BigDecimal.ONE, result.resolution().orElseThrow());
        assertEquals(concept("mmHg").nid(), result.unit().orElseThrow().nid());
        assertEquals(concept("Left arm").nid(), performance.bodySite().orElseThrow().concept().orElseThrow().nid());
        assertEquals(1, performance.purposes().size());
        assertTrue(builder.completeness().isEmpty(), "every required attribute is present");
    }

    private static long singleConcept(LogicalExpression expression) {
        LogicalAxiom.LogicalSet set = expression.definitionRoot().sets().getOnly();
        LogicalAxiom.Atom.Connective and = set.elements().getOnly();
        return ((LogicalAxiom.Atom.ConceptAxiom) and.elements().getOnly()).concept().nid();
    }

    @Test
    void aRequestCarriesItsRepetitionAndTriggersAndANarrativeItsText() {
        AnfExpressionBuilder request = new AnfExpressionBuilder(IkeConcept.REQUEST_CIRCUMSTANCE);
        Circumstance.Request circumstance = (Circumstance.Request) request.circumstance();
        Attribute.Repetition repetition = request.repetition(circumstance);
        request.measure(repetition, AnfSemantic.EVENT_FREQUENCY, new BigDecimal("2"), new BigDecimal("2"), true, true,
                null, concept("Per day"));
        request.conceptReference(circumstance, AnfSemantic.CONDITIONAL_TRIGGER, concept("If febrile"));
        request.conceptReference(circumstance, AnfSemantic.CONDITIONAL_TRIGGER, concept("If in pain"));
        Circumstance.Request read = (Circumstance.Request) request.build().root().circumstance().orElseThrow();
        assertEquals(new BigDecimal("2"), read.repetition().orElseThrow().eventFrequency().orElseThrow().lowerBound().orElseThrow());
        assertEquals(2, read.conditionalTriggers().size());

        AnfExpressionBuilder narrative = new AnfExpressionBuilder(IkeConcept.NARRATIVE_CIRCUMSTANCE);
        Circumstance.Narrative text = (Circumstance.Narrative) narrative.circumstance();
        narrative.narrativeText(text, "Patient reports feeling well.");
        assertEquals("Patient reports feeling well.", ((Circumstance.Narrative) narrative.build().root()
                .circumstance().orElseThrow()).text().orElseThrow().text());
    }

    @Test
    void anAssociatedStatementIsRecordedInItsCanonicalDirection() {
        AnfExpressionBuilder builder = performance();
        EntityProxy.Semantic other = anotherStatement();
        Attribute.AssociatedStatement association = builder.associatedStatement(other, IkeConcept.STATEMENT_FOLLOWS_IN_TIME);
        assertEquals(other.nid(), association.statement().orElseThrow().nid());
        assertEquals(IkeConcept.STATEMENT_FOLLOWS_IN_TIME.nid(), association.associationType().orElseThrow().nid());
        assertEquals(1, builder.build().root().associatedStatements().size());
    }

    /**
     * A second statement to associate with: a semantic of the ANF statement pattern, registered
     * under that pattern first, since the store mints no nid for a UUID whose kind it cannot tell.
     */
    private static EntityProxy.Semantic anotherStatement() {
        UUID uuid = UUID.fromString("7d1b2c3e-4f5a-5b6c-8d7e-9f0a1b2c3d4e");
        EntityService.get().nidForSemantic(IkePattern.ANF_STATEMENT_PATTERN.publicId(), PublicIds.of(uuid));
        return EntityProxy.Semantic.make("Another statement", uuid);
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    void aSecondContextIsRefused() {
        AnfExpressionBuilder builder = performance();
        builder.context();
        AnfRefusal refusal = assertThrows(AnfRefusal.class, builder::context);
        assertTrue(refusal.getMessage().contains("at most one"), refusal.getMessage());
    }

    @Test
    void aSecondCircumstanceIsRefusedWhateverItsKind() {
        AnfExpressionBuilder builder = performance();
        builder.circumstance();
        AnfRefusal same = assertThrows(AnfRefusal.class, builder::circumstance);
        assertTrue(same.getMessage().contains("exactly one circumstance"), same.getMessage());
        AnfRefusal other = assertThrows(AnfRefusal.class,
                () -> builder.circumstance(IkeConcept.REQUEST_CIRCUMSTANCE));
        assertTrue(other.getMessage().contains("exactly one circumstance"), other.getMessage());
    }

    @Test
    void aCircumstanceOfAnotherKindThanTheStatementIsRefused() {
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> performance().circumstance(IkeConcept.NARRATIVE_CIRCUMSTANCE));
        assertTrue(refusal.getMessage().contains("statement's kind"), refusal.getMessage());
    }

    @Test
    void aStatementTypeNamingAnotherKindIsRefused() {
        AnfExpressionBuilder builder = performance();
        Context context = builder.context();
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, IkeConcept.REQUEST_CIRCUMSTANCE));
        assertTrue(refusal.getMessage().contains("statement's kind"), refusal.getMessage());
        AnfRefusal notAKind = assertThrows(AnfRefusal.class,
                () -> builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, concept("Not a kind")));
        assertTrue(notAKind.getMessage().contains("Not a circumstance kind"), notAKind.getMessage());
    }

    @Test
    void aChildTheParentDoesNotAdmitIsRefused() {
        AnfExpressionBuilder builder = performance();
        Circumstance circumstance = builder.circumstance();
        AnfRefusal priority = assertThrows(AnfRefusal.class,
                () -> builder.conceptReference(circumstance, AnfSemantic.PRIORITY, concept("Stat")));
        assertTrue(priority.getMessage().contains("does not sit under"), priority.getMessage());
        Context context = builder.context();
        AnfRefusal result = assertThrows(AnfRefusal.class,
                () -> builder.measure(context, AnfSemantic.RESULT, null, null, true, true, null, concept("mmHg")));
        assertTrue(result.getMessage().contains("does not sit under"), result.getMessage());
        AnfRefusal narrativeText = assertThrows(AnfRefusal.class,
                () -> builder.conceptReference(builder.root(), AnfSemantic.BODY_SITE, concept("Arm")));
        assertTrue(narrativeText.getMessage().contains("does not sit under"), narrativeText.getMessage());
    }

    @Test
    void aSecondOfASingularAttributeIsRefusedWhileRepeatableOnesAreNot() {
        AnfExpressionBuilder builder = performance();
        Circumstance circumstance = builder.circumstance();
        builder.measure(circumstance, AnfSemantic.RESULT, BigDecimal.ONE, BigDecimal.TEN, true, true, null, concept("mmHg"));
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> builder.measure(circumstance, AnfSemantic.RESULT, BigDecimal.ONE, BigDecimal.TEN, true, true,
                        null, concept("mmHg")));
        assertTrue(refusal.getMessage().contains("at most one"), refusal.getMessage());
        builder.expression(circumstance, AnfSemantic.PURPOSE, concept("Purpose one"));
        builder.expression(circumstance, AnfSemantic.PURPOSE, concept("Purpose two"));
        assertEquals(2, ((Circumstance.Performance) circumstance).purposes().size());
    }

    @Test
    void aMeasureWithInvertedBoundsOrANegativeResolutionIsRefused() {
        AnfExpressionBuilder builder = performance();
        Circumstance circumstance = builder.circumstance();
        AnfRefusal inverted = assertThrows(AnfRefusal.class,
                () -> builder.measure(circumstance, AnfSemantic.RESULT, BigDecimal.TEN, BigDecimal.ONE, true, true,
                        null, concept("mmHg")));
        assertTrue(inverted.getMessage().contains("lower bound is at most its upper"), inverted.getMessage());
        AnfRefusal negative = assertThrows(AnfRefusal.class,
                () -> builder.measure(circumstance, AnfSemantic.RESULT, BigDecimal.ONE, BigDecimal.TEN, true, true,
                        new BigDecimal("-1"), concept("mmHg")));
        assertTrue(negative.getMessage().contains("not negative"), negative.getMessage());
        // An unbounded side is never inverted.
        assertNotNull(builder.measure(circumstance, AnfSemantic.RESULT, BigDecimal.ONE, null, true, false, null,
                concept("Presence")));
        Attribute.Measure measure = ((Circumstance.Performance) circumstance).result().orElseThrow();
        AnfRefusal update = assertThrows(AnfRefusal.class,
                () -> builder.updateMeasure(measure, BigDecimal.TEN, BigDecimal.ONE, true, true, null, null));
        assertTrue(update.getMessage().contains("lower bound"), update.getMessage());
    }

    @Test
    void aNestedExpressionThatIsNotALogicalExpressionIsRefused() {
        AnfExpressionBuilder builder = performance();
        Context context = builder.context();
        DiTreeEntity anfTree = performance().asDiTree();
        AnfRefusal refusal = assertThrows(AnfRefusal.class,
                () -> builder.expression(context, AnfSemantic.TOPIC, anfTree));
        assertTrue(refusal.getMessage().contains("logical expression"), refusal.getMessage());
        assertTrue(context.topic().isEmpty(), "a refused edit leaves the tree unchanged");
    }

    @Test
    void aBlankNarrativeTextIsRefused() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.NARRATIVE_CIRCUMSTANCE);
        Circumstance.Narrative narrative = (Circumstance.Narrative) builder.circumstance();
        AnfRefusal refusal = assertThrows(AnfRefusal.class, () -> builder.narrativeText(narrative, "   "));
        assertTrue(refusal.getMessage().contains("not blank"), refusal.getMessage());
    }

    @Test
    void anInverseAssociationIsRefusedNamingTheCanonicalType() {
        AnfExpressionBuilder builder = performance();
        EntityProxy.Semantic other = anotherStatement();
        AnfRefusal precedes = assertThrows(AnfRefusal.class,
                () -> builder.associatedStatement(other, IkeConcept.STATEMENT_PRECEDES_IN_TIME));
        assertTrue(precedes.getMessage().contains("canonical direction"), precedes.getMessage());
        assertTrue(precedes.getMessage().contains(IkeConcept.STATEMENT_FOLLOWS_IN_TIME.description()), precedes.getMessage());
        AnfRefusal composedOf = assertThrows(AnfRefusal.class,
                () -> builder.associatedStatement(other, IkeConcept.STATEMENT_COMPOSED_OF));
        assertTrue(composedOf.getMessage().contains(IkeConcept.STATEMENT_COMPOSES.description()), composedOf.getMessage());
        AnfRefusal notAnAssociation = assertThrows(AnfRefusal.class,
                () -> builder.associatedStatement(other, concept("Not an association")));
        assertTrue(notAnAssociation.getMessage().contains("Not a statement association"), notAnAssociation.getMessage());
        assertTrue(builder.root().associatedStatements().isEmpty());
    }

    @Test
    void aWrongShapeAndAForeignVertexAndTheRootAreRefused() {
        AnfExpressionBuilder builder = performance();
        Circumstance circumstance = builder.circumstance();
        AnfRefusal shape = assertThrows(AnfRefusal.class,
                () -> builder.conceptReference(circumstance, AnfSemantic.RESULT, concept("mmHg")));
        assertTrue(shape.getMessage().contains("not a concept reference attribute"), shape.getMessage());
        AnfExpressionBuilder another = performance();
        Context foreignContext = another.context();
        AnfRefusal foreign = assertThrows(AnfRefusal.class,
                () -> builder.expression(foreignContext, AnfSemantic.TOPIC, concept("Blood pressure")));
        assertTrue(foreign.getMessage().contains("another tree"), foreign.getMessage());
        AnfRefusal root = assertThrows(AnfRefusal.class, () -> builder.remove(builder.root()));
        assertTrue(root.getMessage().contains("cannot be removed"), root.getMessage());
    }

    // ── Edits ─────────────────────────────────────────────────────────────────

    @Test
    void settlingAProvisionalChipKeepsItsVertexAndDropsTheProvisionalProperties() {
        AnfExpressionBuilder builder = performance();
        Circumstance circumstance = builder.circumstance();
        Attribute.ConceptReference chip = builder.provisionalConceptReference(circumstance, AnfSemantic.BODY_SITE,
                new AnfProvisional.Candidate("Left antecubital fossa", "the left elbow crease", "PENDING",
                        List.of("1", "2")));
        UUID identity = chip.vertexUUID();
        assertInstanceOf(AnfProvisional.Candidate.class, chip.provisional().orElseThrow());
        assertTrue(chip.concept().isEmpty());

        builder.updateConcept(chip, concept("Antecubital fossa"));
        assertEquals(identity, chip.vertexUUID());
        assertTrue(chip.provisional().isEmpty());
        assertEquals(concept("Antecubital fossa").nid(), chip.concept().orElseThrow().nid());
    }

    @Test
    void anEditInsideANestedExpressionIsSplicedBackAsAnUpdateOfOneVertex() {
        AnfExpressionBuilder builder = performance();
        Context context = builder.context();
        Attribute.Expression topic = builder.expression(context, AnfSemantic.TOPIC, concept("Blood pressure"));
        UUID identity = topic.vertexUUID();

        // The edit runs through the logical expression builder on the extracted tree.
        LogicalExpressionBuilder inner = new LogicalExpressionBuilder(topic.expressionTree().orElseThrow());
        LogicalAxiom.Atom.ConceptAxiom reference = inner.build().nodesOfType(LogicalAxiom.Atom.ConceptAxiom.class).getOnly();
        inner.updateConceptReference(reference, concept("Systolic blood pressure"));
        builder.splice(topic, (DiTreeEntity) inner.build().sourceGraph());

        Attribute.Expression read = builder.build().root().context().orElseThrow().topic().orElseThrow();
        assertEquals(identity, read.vertexUUID());
        assertEquals(concept("Systolic blood pressure").nid(), read.singleConcept().orElseThrow().nid());
    }

    @Test
    void removingAVertexRemovesWhatIsBeneathIt() {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.REQUEST_CIRCUMSTANCE);
        Circumstance.Request request = (Circumstance.Request) builder.circumstance();
        Attribute.Repetition repetition = builder.repetition(request);
        builder.measure(repetition, AnfSemantic.PERIOD_DURATION, BigDecimal.ONE, BigDecimal.ONE, true, true, null,
                concept("Week"));
        builder.conceptReference(request, AnfSemantic.PRIORITY, concept("Routine"));

        Root root = builder.remove(repetition);
        Circumstance.Request read = (Circumstance.Request) root.circumstance().orElseThrow();
        assertTrue(read.repetition().isEmpty());
        assertTrue(read.priority().isPresent());
        AnfExpression built = builder.build();
        assertEquals(3, built.sourceGraph().vertexMap().size(), "root, request and priority remain");
        assertFalse(built.nodesOfType(Attribute.Measure.class).notEmpty());
    }

    @Test
    void completenessNamesWhatAStatementStillLacks() {
        AnfExpressionBuilder builder = performance();
        List<String> missing = builder.completeness();
        assertTrue(missing.contains("a context"), missing.toString());
        assertTrue(missing.stream().anyMatch(reason -> reason.contains("Performance circumstance")), missing.toString());
        Context context = builder.context();
        builder.circumstance();
        List<String> envelope = builder.completeness();
        assertEquals(List.of("a subject of record", "a statement time", "a topic"), envelope);
        builder.participant(context, AnfSemantic.SUBJECT_OF_RECORD, concept("The patient"), null);
        builder.measure(context, AnfSemantic.STATEMENT_TIME, BigDecimal.ONE, BigDecimal.ONE, true, true, null, concept("s"));
        builder.expression(context, AnfSemantic.TOPIC, concept("Blood pressure"));
        assertTrue(builder.completeness().isEmpty());
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    // ── Whole-tree rules ──────────────────────────────────────────────────────

    /** A performance with a context, a statement type, a topic and a result: well formed. */
    private static DiTreeEntity wellFormedPerformance() {
        AnfExpressionBuilder builder = performance();
        Context context = builder.context();
        builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.expression(context, AnfSemantic.TOPIC, concept("Systolic blood pressure"));
        Circumstance circumstance = builder.circumstance();
        builder.measure(circumstance, AnfSemantic.RESULT, new BigDecimal("120"), new BigDecimal("120"), true, true,
                BigDecimal.ONE, concept("mmHg"));
        return builder.asDiTree();
    }

    /** Adds a vertex of a meaning under a parent, past the builder's rules. */
    private static void addRaw(DiTreeEntity.Builder raw, AnfSemantic meaning, int parentIndex) {
        EntityVertex vertex = EntityVertex.make(UUID.randomUUID(), meaning.nid);
        raw.addVertex(vertex);
        raw.addEdge(vertex.vertexIndex(), parentIndex);
    }

    private static void assertBreaks(List<String> broken, String rule) {
        assertTrue(broken.stream().anyMatch(reason -> reason.contains(rule)), "expected <" + rule + "> in " + broken);
    }

    @Test
    void aTreeTheBuilderBuiltBreaksNoRule() {
        assertEquals(List.of(), AnfExpressionBuilder.wellFormedness(wellFormedPerformance()));
        AnfExpressionBuilder request = new AnfExpressionBuilder(IkeConcept.REQUEST_CIRCUMSTANCE);
        request.context();
        request.repetition((Circumstance.Request) request.circumstance());
        assertEquals(List.of(), AnfExpressionBuilder.wellFormedness(request.asDiTree()),
                "an incomplete statement is still well formed; completeness lists what it lacks");
    }

    @Test
    void aTreeThatIsNotAStatementIsNamedSo() {
        assertBreaks(AnfExpressionBuilder.wellFormedness(null), "has a root");
        assertBreaks(AnfExpressionBuilder.wellFormedness(
                AnfExpressionBuilder.singleConceptExpression(concept("Systolic blood pressure"))), "is an ANF statement");
    }

    @Test
    void childrenTheAdmissionTableRefusesAreNamed() {
        DiTreeEntity tree = wellFormedPerformance();
        DiTreeEntity.Builder raw = DiTreeEntity.builder(tree);
        int root = tree.root().vertexIndex();
        addRaw(raw, AnfSemantic.TOPIC, root);
        addRaw(raw, AnfSemantic.CONTEXT, root);
        addRaw(raw, AnfSemantic.REQUEST, root);
        List<String> broken = AnfExpressionBuilder.wellFormedness(raw.build());
        assertBreaks(broken, "does not sit under");
        assertBreaks(broken, "has at most one");
        assertBreaks(broken, "has one circumstance");
        assertBreaks(broken, "but the statement is a");
    }

    @Test
    void anAttributesOwnRulesAreCheckedOverTheWholeTree() {
        DiTreeEntity tree = wellFormedPerformance();
        DiTreeEntity.Builder raw = DiTreeEntity.builder(tree);
        Root root = AnfExpression.from(tree).root();
        Attribute.Measure result = ((Circumstance.Performance) root.circumstance().orElseThrow()).result().orElseThrow();
        EntityVertex measure = raw.vertex(result.vertexIndex());
        measure.putUncommittedProperty(KernelConcept.INTERVAL_LOWER_BOUND.nid(), new BigDecimal("130"));
        measure.commitProperties();
        EntityVertex rootVertex = raw.vertex(root.vertexIndex());
        rootVertex.putUncommittedProperty(IkeConcept.ANF_STATEMENT_KIND.nid(),
                EntityProxy.Concept.make(IkeConcept.REQUEST_CIRCUMSTANCE));
        rootVertex.commitProperties();
        List<String> broken = AnfExpressionBuilder.wellFormedness(raw.build());
        assertBreaks(broken, "lower bound is at most its upper");
        assertBreaks(broken, "The statement type must name the statement's kind");
        assertBreaks(broken, "but the statement is a");
    }

    @Test
    void theIdentityTableCoversEveryMeaningAndIsFoundForAStatementTree() {
        AnfIdentityTable table = new AnfIdentityTable();
        for (AnfSemantic semantic : AnfSemantic.values()) {
            assertTrue(table.covers(semantic.nid), semantic.name());
            assertNotNull(table.rule(semantic.nid), semantic.name());
        }
        assertEquals(IdentityRule.Successors.OPERANDS, AnfIdentityTable.rule(AnfSemantic.CONTEXT).successors());
        assertEquals(IdentityRule.Successors.NONE, AnfIdentityTable.rule(AnfSemantic.RESULT).successors());
        assertTrue(AnfIdentityTable.rule(AnfSemantic.RESULT).identifying().isEmpty(),
                "a singular attribute is identified by its meaning alone, so a change is an update");
        assertEquals(IkeConcept.ANF_LOGICAL_EXPRESSION.nid(),
                AnfIdentityTable.rule(AnfSemantic.PURPOSE).identifying().getOnly().nid());

        AnfExpressionBuilder builder = performance();
        builder.context();
        builder.circumstance();
        DiTreeEntity tree = builder.asDiTree();
        assertInstanceOf(AnfIdentityTable.class, VertexIdentityTable.forTree(tree).orElseThrow());
    }
}
