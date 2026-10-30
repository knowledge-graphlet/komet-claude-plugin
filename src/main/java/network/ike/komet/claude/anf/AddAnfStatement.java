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
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
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
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;

/**
 * The starter set's {@code Add ANF statement} action: adds an ANF statement to a document block,
 * as a new semantic of the {@code ANF statement pattern} whose referenced component is the block
 * (ike-lab-documents topic {@code dev-ditree-editor-skin-family}; IKE-Network/ike-issues#1312).
 *
 * <p>The input takes the {@code ANF statement tree input pattern}: the subject is the block's
 * element semantic, and the one further field is the statement's tree. The result takes the
 * {@code Version change result pattern}: the STAMP of the statement's first version, written in
 * the block's module under the edit coordinate's author and path and left uncommitted, and a
 * message naming the new statement's public id. A block may hold more than one statement.
 *
 * <p>Refused, with nothing written: a subject that is not a document block, an input whose field
 * is not a tree, and a tree that is not a well-formed statement, with every rule it breaks named.
 * A statement may still lack attributes; {@link network.ike.komet.claude.anf.model.AnfExpressionBuilder#completeness}
 * lists them, and editing the statement adds them.
 */
public final class AddAnfStatement implements KometAction {

    /** For the service loader. */
    public AddAnfStatement() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.ADD_ANF_STATEMENT;
    }

    /**
     * @param block the nid of the block's element semantic
     * @param tree  the statement's tree
     * @return a new input asking for the statement to be added
     */
    public static KometActionInput input(long block, DiTreeEntity tree) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.ADD_ANF_STATEMENT,
                EntityProxy.Semantic.make(block), Lists.immutable.of(tree));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        Optional<DiTreeEntity> tree = AnfStatementWrites.tree(input);
        if (tree.isEmpty()) {
            return KometActionResult.refused(input, "Adding a statement takes one field, the statement's tree.");
        }
        long block = input.subject().nid();
        if (!(EntityHandle.get(block).orNull() instanceof SemanticEntity<?> semantic)
                || semantic.patternNid() != IkePattern.DOCUMENT_BLOCK_PATTERN.nid()) {
            return KometActionResult.refused(input, "The subject is not a block of a document.");
        }
        Optional<String> broken = AnfStatementWrites.notWellFormed(tree.get());
        if (broken.isPresent()) {
            return KometActionResult.refused(input, broken.get());
        }
        Latest<SemanticEntityVersion> blockHead = view.latest(block);
        if (blockHead.isAbsent()) {
            return KometActionResult.refused(input, "No version of the block is visible in this view.");
        }

        PublicId statement = PublicIds.newRandom();
        Transaction transaction = Transaction.make(concept().description() + " " + statement.idString());
        try {
            StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(),
                    blockHead.get().moduleNid(), edit.getDefaultPathNid());
            writeNew(transaction, statement, block, stamp, tree.get());
            return KometActionResult.completed(input, stamp, "Added ANF statement " + statement.idString()
                    + " to the block in an uncommitted version; commit to keep it.");
        } catch (RuntimeException e) {
            transaction.cancel();
            return KometActionResult.failed(input, "Could not add the statement: " + e.getMessage());
        }
    }

    /** Writes the statement's semantic with its one version, in the transaction. */
    private static void writeNew(Transaction transaction, PublicId statement, long block, StampEntity<?> stamp,
                                 DiTreeEntity tree) {
        PublicId pattern = IkePattern.ANF_STATEMENT_PATTERN.publicId();
        long nid = EntityService.get().nidForSemantic(pattern, statement);
        PublicIdentifierRecord identifier = PublicIdentifierRecord.make(statement);
        RecordListBuilder<SemanticVersionRecord> versions = RecordListBuilder.make();
        SemanticRecord semantic = SemanticRecordBuilder.builder()
                .nid(nid)
                .mostSignificantBits(identifier.mostSignificantBits())
                .leastSignificantBits(identifier.leastSignificantBits())
                .additionalUuidLongs(identifier.additionalUuidLongs())
                .patternNid(IkePattern.ANF_STATEMENT_PATTERN.nid())
                .referencedComponentNid(block)
                .versions(versions)
                .build();
        versions.add(SemanticVersionRecordBuilder.builder().chronology(semantic).stampNid(stamp.nid())
                .fieldValues(Lists.immutable.of(tree)).build());
        SemanticRecord built = SemanticRecordBuilder.builder(semantic).versions(versions.toImmutable()).build();
        transaction.addComponent(built);
        EntityService.get().putEntity(built);
    }
}
