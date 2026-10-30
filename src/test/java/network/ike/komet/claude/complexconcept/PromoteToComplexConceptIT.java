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

import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.complexconcept.model.ComplexConcept;
import network.ike.komet.claude.complexconcept.model.ComplexConceptExpression;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter set's {@code Promote to complex concept} action against the starter set itself
 * (IKE-Network/ike-issues#1312): a statement whose topic is a composition is promoted, writing the
 * complex concept, its specification and the statement's new version as one uncommitted change, and
 * every topic or statement the action does not take is refused with nothing written.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromoteToComplexConceptIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final UUID NAMESPACE = UUID.fromString("5e8a0c3d-2f71-5b94-a6c8-7d9e0f1a2b3c");

    private EditCoordinateRecord edit;
    private ViewCalculator view;

    @BeforeAll
    void startStore() {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        edit = Coordinates.Edit.Default();
        view = Calculators.View.Default();
    }

    @AfterAll
    void stopStore() {
        PrimitiveData.stop();
    }

    private static EntityProxy.Concept concept(String name) {
        return EntityProxy.Concept.make(name, UuidT5Generator.get(NAMESPACE, "promote:" + name));
    }

    /** Sitting systolic blood pressure, as a composition: the observation, measured in a sitting position. */
    private static DiTreeEntity composedTopic() {
        LogicalExpressionBuilder logical = new LogicalExpressionBuilder();
        logical.NecessarySet(logical.And(
                logical.ConceptAxiom(concept("Systolic blood pressure observation")),
                logical.SomeRole(concept("Body position"), logical.ConceptAxiom(concept("Sitting position")))));
        return (DiTreeEntity) logical.build().sourceGraph();
    }

    /** A composition with a role but no parent concept beside it. */
    private static DiTreeEntity roleOnlyTopic() {
        LogicalExpressionBuilder logical = new LogicalExpressionBuilder();
        logical.NecessarySet(logical.And(
                logical.SomeRole(concept("Body position"), logical.ConceptAxiom(concept("Sitting position")))));
        return (DiTreeEntity) logical.build().sourceGraph();
    }

    private StampedWriter writer(String name) {
        return StampedWriter.open(name, State.ACTIVE, ConceptFacade.make(edit.getAuthorNidForChanges()),
                ConceptFacade.make(edit.getDefaultModuleNid()), ConceptFacade.make(edit.getDefaultPathNid()));
    }

    /** A committed statement on a committed block, whose topic is the given tree. */
    private long committedStatement(String name, DiTreeEntity topic) {
        AnfExpressionBuilder statement = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        statement.expression(statement.context(), AnfSemantic.TOPIC, topic);
        statement.circumstance();
        PublicId block = PublicIds.newRandom();
        PublicId statementId = PublicIds.newRandom();
        // The block's document is a test concept the store holds no record of; register it, since
        // a semantic may reference only a component the store knows.
        PublicId document = concept("A document: " + name).publicId();
        EntityService.get().nidForConcept(document);
        StampedWriter writer = writer("A statement: " + name);
        writer.semantic(block, IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(), document);
        long nid = writer.semantic(statementId, IkePattern.ANF_STATEMENT_PATTERN.publicId(), block, statement.asDiTree());
        writer.commit();
        return nid;
    }

    private DiTreeEntity latestTree(long semantic) {
        Latest<SemanticEntityVersion> latest = view.latest(semantic);
        return (DiTreeEntity) latest.get().fieldValues().get(0);
    }

    @Test
    void thePluginImplementsTheAction() {
        assertInstanceOf(PromoteToComplexConcept.class, KometActions.expect(KometConcept.PROMOTE_TO_COMPLEX_CONCEPT));
    }

    @Test
    void aComposedTopicIsPromotedAsOneUncommittedChange() {
        long statement = committedStatement("promoted", composedTopic());
        KometAction promote = KometActions.expect(KometConcept.PROMOTE_TO_COMPLEX_CONCEPT);

        KometActionResult result = promote.perform(PromoteToComplexConcept.input(statement, "Sitting systolic blood pressure"),
                view, edit);

        assertTrue(result.completed(), result.message());
        assertEquals(Long.MAX_VALUE, EntityService.get().getStampFast(result.writtenVersion().orElseThrow().nid()).time(),
                "the change is uncommitted");

        AnfStatement.Attribute.Expression topic = AnfExpression.from(latestTree(statement)).root().context()
                .flatMap(AnfStatement.Context::topic).orElseThrow();
        ConceptFacade complexConcept = topic.singleConcept().orElseThrow(() -> new AssertionError("the topic is one concept"));
        assertTrue(result.message().contains(PrimitiveData.publicId(complexConcept.nid()).idString()),
                "the message names the new concept");
        SemanticEntity<?> statementEntity = (SemanticEntity<?>) EntityHandle.get(statement).orNull();
        assertEquals(2, statementEntity.versions().size(), "the statement's earlier version remains");

        assertInstanceOf(ConceptEntity.class, EntityHandle.get(complexConcept.nid()).orNull(), "the concept is written");
        assertEquals("Sitting systolic blood pressure", view.getDescriptionTextOrNid(complexConcept.nid()));
        Latest<?> conceptVersion = view.latest(complexConcept.nid());
        assertEquals(IkeConcept.COMPLEX_CONCEPTS_MODULE.nid(),
                ((dev.ikm.tinkar.entity.EntityVersion) conceptVersion.get()).moduleNid(), "in the Complex concepts module");

        List<SemanticEntity<SemanticEntityVersion>> specifications = EntityService.get()
                .semanticsForComponentOfPattern(complexConcept.nid(), IkePattern.COMPLEX_CONCEPT_PATTERN.nid()).toList();
        assertEquals(1, specifications.size(), "one specification references the concept");
        ComplexConcept.Root specification = ComplexConceptExpression.from(latestTree(specifications.getFirst().nid())).root();
        assertEquals(composedTopic(), specification.definition().orElseThrow().expressionTree().orElseThrow(),
                "the composition is the definition");
        ComplexConcept.Expansion.Focused focused = assertInstanceOf(ComplexConcept.Expansion.Focused.class,
                specification.expansion().orElseThrow());
        assertEquals(concept("Systolic blood pressure observation").nid(), focused.focus().orElseThrow().nid(),
                "the expansion focuses on the composition's parent");
    }

    @Test
    void whatThePromotionDoesNotTakeIsRefusedWithNothingWritten() {
        KometAction promote = KometActions.expect(KometConcept.PROMOTE_TO_COMPLEX_CONCEPT);
        long single = committedStatement("single", AnfExpressionBuilder.singleConceptExpression(concept("Heart rate")));
        long roleOnly = committedStatement("role only", roleOnlyTopic());
        long composed = committedStatement("blank name", composedTopic());

        List<KometActionResult> refusals = List.of(
                promote.perform(PromoteToComplexConcept.input(single, "Heart rate, promoted"), view, edit),
                promote.perform(PromoteToComplexConcept.input(roleOnly, "Sitting something"), view, edit),
                promote.perform(PromoteToComplexConcept.input(composed, "  "), view, edit),
                promote.perform(PromoteToComplexConcept.input(concept("Not a statement").nid(), "A name"), view, edit));

        for (KometActionResult refused : refusals) {
            assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
            assertTrue(refused.writtenVersion().isEmpty(), refused.message());
        }
        assertTrue(refusals.get(0).message().contains("already a single concept"), refusals.get(0).message());
        assertTrue(refusals.get(1).message().contains("names no parent concept"), refusals.get(1).message());
        for (long statement : List.of(single, roleOnly, composed)) {
            assertEquals(1, ((SemanticEntity<?>) EntityHandle.get(statement).orNull()).versions().size(),
                    "no refused run wrote a version");
        }
    }

    @Test
    void aStatementWithAPendingEditIsRefused() {
        long statement = committedStatement("pending", composedTopic());
        AnfExpressionBuilder edited = new AnfExpressionBuilder(latestTree(statement));
        edited.conceptReference(edited.root().context().orElseThrow(), AnfSemantic.STATEMENT_TYPE,
                IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        assertTrue(KometActions.expect(KometConcept.EDIT_ANF_STATEMENT).perform(
                network.ike.komet.claude.anf.EditAnfStatement.input(statement, edited.asDiTree()), view, edit).completed());

        KometActionResult refused = KometActions.expect(KometConcept.PROMOTE_TO_COMPLEX_CONCEPT)
                .perform(PromoteToComplexConcept.input(statement, "Sitting systolic, pending"), view, edit);

        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertTrue(refused.message().contains("pending"), refused.message());
    }
}
