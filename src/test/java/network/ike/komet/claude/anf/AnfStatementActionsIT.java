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

import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.actions.KometActions;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinateRecord;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PublicIdentifierRecord;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticRecordBuilder;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecordBuilder;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement.Circumstance;
import network.ike.komet.claude.anf.model.AnfStatement.Context;
import org.eclipse.collections.api.factory.Lists;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.math.BigDecimal;
import java.util.List;

import static network.ike.komet.claude.anf.AnfTestStore.concept;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The starter set's {@code Add ANF statement} and {@code Edit ANF statement} actions against the
 * starter set itself (IKE-Network/ike-issues#1312): a statement is added to a document block as a
 * semantic of the ANF statement pattern referencing the block, edited as new versions of that
 * semantic, and refused, with nothing written, whenever the subject, the input or the tree is
 * not what the action takes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnfStatementActionsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

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

    /** A committed document block, referencing a concept that stands for its document. */
    private long block(String name) {
        Transaction transaction = Transaction.make("A block for " + name);
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(),
                edit.getDefaultModuleNid(), edit.getDefaultPathNid());
        long nid = EntityService.get().nidForSemantic(IkePattern.DOCUMENT_BLOCK_PATTERN.publicId(),
                PublicIds.newRandom());
        PublicIdentifierRecord identifier = PublicIdentifierRecord.make(PrimitiveData.publicId(nid));
        RecordListBuilder<SemanticVersionRecord> versions = RecordListBuilder.make();
        SemanticRecord semantic = SemanticRecordBuilder.builder()
                .nid(nid)
                .mostSignificantBits(identifier.mostSignificantBits())
                .leastSignificantBits(identifier.leastSignificantBits())
                .additionalUuidLongs(identifier.additionalUuidLongs())
                .patternNid(IkePattern.DOCUMENT_BLOCK_PATTERN.nid())
                .referencedComponentNid(concept("A document: " + name).nid())
                .versions(versions)
                .build();
        versions.add(SemanticVersionRecordBuilder.builder().chronology(semantic).stampNid(stamp.nid())
                .fieldValues(Lists.immutable.empty()).build());
        SemanticRecord built = SemanticRecordBuilder.builder(semantic).versions(versions.toImmutable()).build();
        transaction.addComponent(built);
        EntityService.get().putEntity(built);
        transaction.commit();
        return nid;
    }

    /** A well-formed performance statement with a topic and a result. */
    private static AnfExpressionBuilder performance(String topic, String result) {
        AnfExpressionBuilder builder = new AnfExpressionBuilder(IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        Context context = builder.context();
        builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, IkeConcept.PERFORMANCE_CIRCUMSTANCE);
        builder.expression(context, AnfSemantic.TOPIC, concept(topic));
        builder.measure(builder.circumstance(), AnfSemantic.RESULT, new BigDecimal(result), new BigDecimal(result),
                true, true, BigDecimal.ONE, concept("mmHg"));
        return builder;
    }

    /** The statements of the ANF statement pattern that reference a block. */
    private static List<SemanticEntity<SemanticEntityVersion>> statementsOf(long block) {
        return EntityService.get().semanticsForComponentOfPattern(block, IkePattern.ANF_STATEMENT_PATTERN.nid()).toList();
    }

    /** The tree of a statement's latest version in this view. */
    private Object latestTree(long statement) {
        Latest<SemanticEntityVersion> latest = view.latest(statement);
        return latest.get().fieldValues().get(0);
    }

    private static long uncommittedVersions(SemanticEntity<SemanticEntityVersion> semantic) {
        return semantic.versions().stream().filter(SemanticEntityVersion::uncommitted).count();
    }

    @Test
    void thePluginImplementsBothActions() {
        assertInstanceOf(AddAnfStatement.class, KometActions.expect(KometConcept.ADD_ANF_STATEMENT));
        assertInstanceOf(EditAnfStatement.class, KometActions.expect(KometConcept.EDIT_ANF_STATEMENT));
    }

    @Test
    void aStatementIsAddedToABlockAndEditedAsOneUncommittedChange() {
        long block = block("added and edited");
        KometAction add = KometActions.expect(KometConcept.ADD_ANF_STATEMENT);
        DiTreeEntity first = performance("Systolic blood pressure", "120").asDiTree();

        KometActionInput input = AddAnfStatement.input(block, first);
        KometActionResult added = add.perform(input, view, edit);

        assertTrue(added.completed(), added.message());
        assertEquals(input.id(), added.input(), "the result refers to its input");
        assertEquals(Long.MAX_VALUE, EntityService.get().getStampFast(added.writtenVersion().orElseThrow().nid()).time(),
                "the statement is uncommitted");
        List<SemanticEntity<SemanticEntityVersion>> statements = statementsOf(block);
        assertEquals(1, statements.size(), "the statement references the block");
        SemanticEntity<SemanticEntityVersion> statement = statements.getFirst();
        assertTrue(added.message().contains(statement.publicId().idString()), "the message names the statement");
        assertEquals(first, statement.versions().getFirst().fieldValues().get(0));

        KometAction edit = KometActions.expect(KometConcept.EDIT_ANF_STATEMENT);
        AnfExpressionBuilder revised = new AnfExpressionBuilder(first);
        revised.conceptReference((Circumstance) revised.root().circumstance().orElseThrow(), AnfSemantic.BODY_SITE,
                concept("Left arm"));
        DiTreeEntity second = revised.asDiTree();
        KometActionResult edited = edit.perform(EditAnfStatement.input(statement.nid(), second), view, this.edit);

        assertTrue(edited.completed(), edited.message());
        SemanticEntity<SemanticEntityVersion> after = statementsOf(block).getFirst();
        assertEquals(1, uncommittedVersions(after), "the edit replaced the pending version rather than stacking on it");
        assertEquals(second, latestTree(after.nid()));
        assertEquals(added.writtenVersion().orElseThrow().nid(), edited.writtenVersion().orElseThrow().nid(),
                "the edit joined the pending change");

        KometActionResult same = edit.perform(EditAnfStatement.input(statement.nid(), second), view, this.edit);
        assertTrue(same.completed() && same.writtenVersion().isEmpty(), "an unchanged tree writes nothing: " + same);
    }

    @Test
    void anEditOnTopOfSomeoneElsesPendingEditIsRefused() {
        long block = block("another author");
        DiTreeEntity tree = performance("Heart rate", "72").asDiTree();
        assertTrue(KometActions.expect(KometConcept.ADD_ANF_STATEMENT).perform(AddAnfStatement.input(block, tree),
                view, edit).completed());
        long statement = statementsOf(block).getFirst().nid();
        EditCoordinateRecord assistant = EditCoordinateRecord.make(IkeConcept.KOMET_ASSISTANT.nid(),
                edit.getDefaultModuleNid(), edit.getDefaultPathNid());
        DiTreeEntity changed = performance("Heart rate", "80").asDiTree();

        KometActionResult refused = KometActions.expect(KometConcept.EDIT_ANF_STATEMENT)
                .perform(EditAnfStatement.input(statement, changed), view, assistant);

        assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
        assertTrue(refused.message().contains("is pending"), refused.message());
        assertEquals(tree, latestTree(statement), "nothing was written over the pending edit");
    }

    @Test
    void whatTheActionsDoNotTakeIsRefusedWithNothingWritten() {
        long block = block("refusals");
        DiTreeEntity tree = performance("Body temperature", "37").asDiTree();
        DiTreeEntity notAStatement = AnfExpressionBuilder.singleConceptExpression(concept("Body temperature"));
        KometAction add = KometActions.expect(KometConcept.ADD_ANF_STATEMENT);
        KometAction edit = KometActions.expect(KometConcept.EDIT_ANF_STATEMENT);

        List<KometActionResult> refusals = List.of(
                add.perform(AddAnfStatement.input(concept("Not a block").nid(), tree), view, this.edit),
                add.perform(AddAnfStatement.input(block, notAStatement), view, this.edit),
                add.perform(new KometActionInput(PublicIds.newRandom(), KometConcept.ADD_ANF_STATEMENT,
                        EntityProxy.Semantic.make(block), Lists.immutable.of("Not a tree.")), view, this.edit),
                add.perform(new KometActionInput(PublicIds.newRandom(), KometConcept.EDIT_ANF_STATEMENT,
                        EntityProxy.Semantic.make(block), Lists.immutable.of(tree)), view, this.edit),
                edit.perform(EditAnfStatement.input(block, tree), view, this.edit));

        for (KometActionResult refused : refusals) {
            assertEquals(KometConcept.ACTION_REFUSED.nid(), refused.outcome().nid(), refused.message());
            assertTrue(refused.writtenVersion().isEmpty(), refused.message());
        }
        assertTrue(refusals.get(1).message().contains("not a well-formed ANF statement"), refusals.get(1).message());
        assertEquals(List.of(), statementsOf(block), "no refused run added a statement");
    }
}
