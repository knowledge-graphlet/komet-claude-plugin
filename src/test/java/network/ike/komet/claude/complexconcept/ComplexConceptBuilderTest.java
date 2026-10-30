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
package network.ike.komet.claude.complexconcept;

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.complexconcept.model.ComplexConcept;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Prerequisite;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import network.ike.komet.claude.complexconcept.model.ComplexConceptExpression;
import network.ike.komet.claude.complexconcept.model.ComplexConceptIdentityTable;
import network.ike.komet.claude.complexconcept.model.ComplexConceptRefusal;
import network.ike.komet.claude.complexconcept.model.ComplexConceptSemantic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The complex concept adaptor layer (IKE-Network/ike-issues#1312), built on the worked examples of
 * ike-lab-documents topic {@code anf-complex-concept-examples}: sitting systolic blood pressure as
 * a focused expansion with three prerequisites, PERRLA as a uniform AND expansion, and NVD
 * screening as a uniform OR expansion, whose non-absent value needs a policy.
 */
class ComplexConceptBuilderTest {

    private static final UUID NAMESPACE = UUID.fromString("6d1f0c2a-4b8e-5a3d-9c7f-1e2d3c4b5a69");

    @BeforeAll
    static void startStore() {
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
    }

    @AfterAll
    static void stopStore() {
        PrimitiveData.stop();
    }

    private static EntityProxy.Concept concept(String name) {
        return EntityProxy.Concept.make(name, UuidT5Generator.get(NAMESPACE, "complex-concept:" + name));
    }

    private static DiTreeEntity definition(String parent) {
        return AnfExpressionBuilder.singleConceptExpression(concept(parent));
    }

    /** A prerequisite as an ANF statement: the condition as its topic, its fixed value as its result. */
    private static DiTreeEntity prerequisiteStatement(String topic, String lower, String upper, String unit) {
        AnfExpressionBuilder statement = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        AnfStatement.Context context = statement.context();
        statement.expression(context, AnfSemantic.TOPIC, concept(topic));
        statement.measure(statement.circumstance(), AnfSemantic.RESULT, new BigDecimal(lower),
                upper == null ? null : new BigDecimal(upper), true, upper != null, null, concept(unit));
        return statement.asDiTree();
    }

    /** Sitting systolic blood pressure: a focused expansion with three preconditions. */
    private static ComplexConceptBuilder sittingSystolic() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        builder.definition(definition("Systolic blood pressure observation"));
        Expansion.Focused focused = builder.focusedExpansion(concept("Systolic blood pressure observation"));
        builder.prerequisite(focused, prerequisiteStatement("Seated position with feet flat on floor", "1", "1", "Presence"),
                IkeConcept.PRECONDITION_ASSOCIATION);
        builder.prerequisite(focused, prerequisiteStatement("Seated duration", "5", null, "Minutes"),
                IkeConcept.PRECONDITION_ASSOCIATION);
        builder.prerequisite(focused, prerequisiteStatement("Recent voiding", "1", "1", "Presence"),
                IkeConcept.PRECONDITION_ASSOCIATION);
        return builder;
    }

    /** PERRLA: a uniform AND expansion of four members on the presence scale. */
    private static ComplexConceptBuilder perrla() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        builder.definition(definition("Clinical assessment panel"));
        Expansion.Uniform uniform = builder.uniformExpansion(IkeConcept.AND_CONJUNCTION, concept("Presence"));
        for (String member : List.of("Pupil equality assessment", "Pupil roundness assessment",
                "Light reactivity assessment", "Accommodation assessment")) {
            builder.member(uniform, concept(member));
        }
        return builder;
    }

    // ── Construction ──────────────────────────────────────────────────────────

    @Test
    void aFocusedSpecificationIsBuiltAndReadBackThroughItsAdaptors() {
        ComplexConceptBuilder builder = sittingSystolic();
        ComplexConceptExpression expression = builder.build();
        ComplexConcept.Root root = expression.root();

        assertTrue(root.definition().orElseThrow().expression().isPresent(), "the definition is an EL++ expression");
        Expansion.Focused focused = assertInstanceOf(Expansion.Focused.class, root.expansion().orElseThrow());
        assertEquals(concept("Systolic blood pressure observation").nid(), focused.focus().orElseThrow().nid());
        assertEquals(3, focused.prerequisites().size());
        Prerequisite seatedDuration = focused.prerequisites().get(1);
        assertEquals(IkeConcept.PRECONDITION_ASSOCIATION.nid(), seatedDuration.association().orElseThrow().nid());
        AnfStatement.Root statement = seatedDuration.statement().orElseThrow().root();
        assertEquals(concept("Seated duration").nid(),
                statement.context().orElseThrow().topic().orElseThrow().singleConcept().orElseThrow().nid());
        AnfStatement.Attribute.Measure fixedValue =
                ((AnfStatement.Circumstance.Performance) statement.circumstance().orElseThrow()).result().orElseThrow();
        assertEquals(new BigDecimal("5"), fixedValue.lowerBound().orElseThrow());
        assertTrue(fixedValue.upperBound().isEmpty(), "five minutes or more");
        assertEquals(List.of(), builder.completeness());
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
    }

    @Test
    void aUniformAndExpansionIsCompleteWithoutAPolicy() {
        ComplexConceptBuilder builder = perrla();
        Expansion.Uniform uniform = (Expansion.Uniform) builder.root().expansion().orElseThrow();
        assertEquals(4, uniform.members().size());
        assertEquals(IkeConcept.AND_CONJUNCTION.nid(), uniform.conjunction().orElseThrow().nid());
        assertEquals(concept("Presence").nid(), uniform.measurementDomain().orElseThrow().nid());
        assertTrue(uniform.unsafeDistributionPolicy().isEmpty());
        assertEquals(List.of(), builder.completeness());
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
    }

    @Test
    void aUniformOrExpansionLacksItsPolicyUntilItIsSet() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        builder.definition(definition("Gastrointestinal symptom screening"));
        Expansion.Uniform uniform = builder.uniformExpansion(IkeConcept.OR_CONJUNCTION, concept("Presence"));
        for (String member : List.of("Nausea assessment", "Vomiting assessment", "Diarrhea assessment")) {
            builder.member(uniform, concept(member));
        }
        assertEquals(List.of("how the OR conjunction handles a non-absent value"), builder.completeness());
        builder.setUnsafeDistributionPolicy(uniform, IkeConcept.REFUSE_EXPANSION);
        assertEquals(List.of(), builder.completeness());
        assertEquals(IkeConcept.REFUSE_EXPANSION.nid(),
                ((Expansion.Uniform) builder.root().expansion().orElseThrow()).unsafeDistributionPolicy().orElseThrow().nid());
    }

    @Test
    void completenessNamesWhatASpecificationStillLacks() {
        ComplexConceptBuilder empty = new ComplexConceptBuilder();
        assertEquals(List.of("a definition", "an expansion, uniform or focused"), empty.completeness());
        ComplexConceptBuilder thin = new ComplexConceptBuilder();
        Expansion.Uniform uniform = thin.uniformExpansion(IkeConcept.AND_CONJUNCTION, null);
        thin.member(uniform, concept("Only one"));
        List<String> missing = thin.completeness();
        assertTrue(missing.contains("a definition"), missing.toString());
        assertTrue(missing.contains("the uniform expansion's measurement domain"), missing.toString());
        assertTrue(missing.contains("at least two members"), missing.toString());
        ComplexConceptBuilder bareFocus = new ComplexConceptBuilder();
        bareFocus.focusedExpansion(concept("A focus"));
        assertTrue(bareFocus.completeness().contains("at least one prerequisite"), bareFocus.completeness().toString());
    }

    // ── Refusals ──────────────────────────────────────────────────────────────

    @Test
    void aSecondExpansionOrDefinitionIsRefused() {
        ComplexConceptBuilder builder = perrla();
        ComplexConceptRefusal second = assertThrows(ComplexConceptRefusal.class,
                () -> builder.focusedExpansion(concept("A focus")));
        assertTrue(second.getMessage().contains("one expansion"), second.getMessage());
        ComplexConceptRefusal definition = assertThrows(ComplexConceptRefusal.class,
                () -> builder.definition(definition("Another parent")));
        assertTrue(definition.getMessage().contains("at most one"), definition.getMessage());
    }

    @Test
    void theConjunctionAndItsPolicyKeepTheSafetyRules() {
        ComplexConceptBuilder builder = perrla();
        Expansion.Uniform uniform = (Expansion.Uniform) builder.root().expansion().orElseThrow();
        ComplexConceptRefusal andPolicy = assertThrows(ComplexConceptRefusal.class,
                () -> builder.setUnsafeDistributionPolicy(uniform, IkeConcept.REFUSE_EXPANSION));
        assertTrue(andPolicy.getMessage().contains("Only an OR conjunction"), andPolicy.getMessage());
        assertThrows(ComplexConceptRefusal.class, () -> builder.setConjunction(uniform, concept("Not a conjunction")));
        assertThrows(ComplexConceptRefusal.class,
                () -> new ComplexConceptBuilder().uniformExpansion(concept("Not a conjunction"), concept("Presence")));

        builder.setConjunction(uniform, IkeConcept.OR_CONJUNCTION);
        ComplexConceptRefusal notAPolicy = assertThrows(ComplexConceptRefusal.class,
                () -> builder.setUnsafeDistributionPolicy(uniform, concept("Guess")));
        assertTrue(notAPolicy.getMessage().contains("refuse expansion"), notAPolicy.getMessage());
        builder.setUnsafeDistributionPolicy(uniform, IkeConcept.FLAG_AS_INDETERMINATE_POSITIVE);
        ComplexConceptRefusal backToAnd = assertThrows(ComplexConceptRefusal.class,
                () -> builder.setConjunction(uniform, IkeConcept.AND_CONJUNCTION));
        assertTrue(backToAnd.getMessage().contains("remove the policy"), backToAnd.getMessage());
    }

    @Test
    void aPhenomenonIsAMemberAtMostOnce() {
        ComplexConceptBuilder builder = perrla();
        Expansion.Uniform uniform = (Expansion.Uniform) builder.root().expansion().orElseThrow();
        assertThrows(ComplexConceptRefusal.class, () -> builder.member(uniform, concept("Pupil equality assessment")));
        ComplexConcept.Member last = uniform.members().get(3);
        assertThrows(ComplexConceptRefusal.class, () -> builder.updateMember(last, concept("Pupil roundness assessment")));
        builder.updateMember(last, concept("Near response assessment"));
        assertEquals(concept("Near response assessment").nid(),
                ((Expansion.Uniform) builder.root().expansion().orElseThrow()).members().get(3).phenomenon().orElseThrow().nid());
    }

    @Test
    void aPrerequisiteHoldsAWellFormedStatementAndAPreconditionAssociation() {
        ComplexConceptBuilder builder = new ComplexConceptBuilder();
        Expansion.Focused focused = builder.focusedExpansion(concept("A focus"));
        ComplexConceptRefusal notAStatement = assertThrows(ComplexConceptRefusal.class,
                () -> builder.prerequisite(focused, definition("Not a statement"), IkeConcept.PRECONDITION_ASSOCIATION));
        assertTrue(notAStatement.getMessage().contains("well-formed ANF statement"), notAStatement.getMessage());
        ComplexConceptRefusal association = assertThrows(ComplexConceptRefusal.class,
                () -> builder.prerequisite(focused, prerequisiteStatement("A condition", "1", "1", "Presence"),
                        IkeConcept.STATEMENT_COMPOSES));
        assertTrue(association.getMessage().contains("precondition association"), association.getMessage());
        assertThrows(ComplexConceptRefusal.class, () -> builder.remove(builder.root()));
    }

    // ── Whole-tree rules ──────────────────────────────────────────────────────

    private static void addRaw(DiTreeEntity.Builder raw, ComplexConceptSemantic meaning, int parentIndex) {
        EntityVertex vertex = EntityVertex.make(UUID.randomUUID(), meaning.nid);
        raw.addVertex(vertex);
        raw.addEdge(vertex.vertexIndex(), parentIndex);
    }

    private static void assertBreaks(List<String> broken, String rule) {
        assertTrue(broken.stream().anyMatch(reason -> reason.contains(rule)), "expected <" + rule + "> in " + broken);
    }

    @Test
    void aTreeThatIsNotASpecificationIsNamedSo() {
        assertBreaks(ComplexConceptBuilder.wellFormedness(null), "has a root");
        assertBreaks(ComplexConceptBuilder.wellFormedness(definition("Not a specification")),
                "is a complex concept specification");
    }

    @Test
    void treesTheBuilderWouldRefuseAreNamedRuleByRule() {
        DiTreeEntity tree = perrla().asDiTree();
        DiTreeEntity.Builder raw = DiTreeEntity.builder(tree);
        int root = tree.root().vertexIndex();
        addRaw(raw, ComplexConceptSemantic.FOCUSED_EXPANSION, root);
        addRaw(raw, ComplexConceptSemantic.MEMBER, root);
        addRaw(raw, ComplexConceptSemantic.DEFINITION, root);
        List<String> broken = ComplexConceptBuilder.wellFormedness(raw.build());
        assertBreaks(broken, "has one expansion");
        assertBreaks(broken, "does not sit under");
        assertBreaks(broken, "has at most one");
        assertBreaks(broken, "names its focus phenomenon");
        assertBreaks(broken, "names its phenomenon");
        assertBreaks(broken, "holds its EL++ expression");
    }

    @Test
    void anAndExpansionCarryingAPolicyBreaksTheSafetyRule() {
        DiTreeEntity tree = perrla().asDiTree();
        DiTreeEntity.Builder raw = DiTreeEntity.builder(tree);
        Expansion.Uniform uniform = (Expansion.Uniform) ComplexConceptExpression.from(tree).root().expansion().orElseThrow();
        EntityVertex vertex = raw.vertex(uniform.vertexIndex());
        vertex.putUncommittedProperty(IkeConcept.UNSAFE_DISTRIBUTION_POLICY.nid(),
                EntityProxy.Concept.make(IkeConcept.RETAIN_COMPOUND_CODE));
        vertex.commitProperties();
        assertBreaks(ComplexConceptBuilder.wellFormedness(raw.build()), "Only an OR conjunction");
    }

    // ── Identity ──────────────────────────────────────────────────────────────

    @Test
    void theIdentityTableCoversEveryMeaningAndIsFoundForASpecificationTree() {
        ComplexConceptIdentityTable table = new ComplexConceptIdentityTable();
        for (ComplexConceptSemantic semantic : ComplexConceptSemantic.values()) {
            assertTrue(table.covers(semantic.nid), semantic.name());
            assertNotNull(table.rule(semantic.nid), semantic.name());
        }
        assertInstanceOf(ComplexConceptIdentityTable.class,
                VertexIdentityTable.forTree(perrla().asDiTree()).orElseThrow());
    }

    @Test
    void editingTheDefinitionIsAnUpdateOfOneVertex() {
        ComplexConceptBuilder builder = sittingSystolic();
        ComplexConcept.Definition definition = builder.root().definition().orElseThrow();
        UUID before = definition.vertexUUID();
        builder.spliceDefinition(definition, definition("Blood pressure observation"));
        ComplexConcept.Definition after = builder.build().root().definition().orElseThrow();
        assertEquals(before, after.vertexUUID(), "the definition keeps its identity");
        assertEquals(definition("Blood pressure observation"), after.expressionTree().orElseThrow(),
                "the definition holds the new expression");
    }
}
