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
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.stamp.StampNames;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;

/**
 * The starter set's {@code Edit ANF statement} action: replaces the tree of one ANF statement with
 * a new tree, as a new version of the statement's semantic (ike-lab-documents topic
 * {@code dev-ditree-editor-skin-family}; IKE-Network/ike-issues#1312). It is the write path of
 * the ANF statement control, whose every edit produces a whole new tree, and of the assistant.
 *
 * <p>The input takes the {@code ANF statement tree input pattern}: the subject is the statement's
 * semantic, and the one further field is its new tree. The result takes the
 * {@code Version change result pattern}: the STAMP of the version written, in the statement's
 * module under the edit coordinate's author and path, left uncommitted. A second edit by the same
 * person joins their pending transaction and replaces their pending version, so a run of edits is
 * one uncommitted change, not a stack of them; vertices the new tree keeps keep their identities.
 *
 * <p>Refused, with nothing written: a subject that is not an ANF statement, an input whose field
 * is not a tree, a tree that is not a well-formed statement, with every rule it breaks named, and a
 * statement whose pending edit is someone else's, since a change is never made on top of a change
 * nobody has settled. An unchanged tree completes with nothing written.
 */
public final class EditAnfStatement implements KometAction {

    /** For the service loader. */
    public EditAnfStatement() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.EDIT_ANF_STATEMENT;
    }

    /**
     * @param statement the nid of the statement's semantic
     * @param tree      the statement's new tree
     * @return a new input asking for the edit
     */
    public static KometActionInput input(long statement, DiTreeEntity tree) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.EDIT_ANF_STATEMENT,
                EntityProxy.Semantic.make(statement), Lists.immutable.of(tree));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        Optional<DiTreeEntity> tree = AnfStatementWrites.tree(input);
        if (tree.isEmpty()) {
            return KometActionResult.refused(input, "Editing a statement takes one field, the statement's new tree.");
        }
        long statement = input.subject().nid();
        if (!(EntityHandle.get(statement).orNull() instanceof SemanticRecord record)
                || record.patternNid() != IkePattern.ANF_STATEMENT_PATTERN.nid()) {
            return KometActionResult.refused(input, "The subject is not an ANF statement.");
        }
        Optional<String> broken = AnfStatementWrites.notWellFormed(tree.get());
        if (broken.isPresent()) {
            return KometActionResult.refused(input, broken.get());
        }
        Latest<SemanticEntityVersion> latest = view.latest(statement);
        if (latest.isAbsent()) {
            return KometActionResult.refused(input, "No version of the statement is visible in this view.");
        }
        SemanticEntityVersion head = latest.get();
        Optional<Transaction> pending = Optional.empty();
        if (head.uncommitted()) {
            long pendingAuthor = head.stamp().authorNid();
            if (pendingAuthor != edit.getAuthorNidForChanges()) {
                return KometActionResult.refused(input, "An edit of this statement by "
                        + StampNames.name(view, pendingAuthor)
                        + " is pending; it is committed or cancelled before anyone else edits the statement.");
            }
            pending = Transaction.forVersion(head);
        }
        if (tree.get().equals(head.fieldValues().get(0))) {
            return new KometActionResult(input.id(), KometConcept.ACTION_COMPLETED, Optional.empty(),
                    "The tree is unchanged; nothing was written.");
        }

        Transaction transaction = pending.orElseGet(() ->
                Transaction.make(concept().description() + " " + record.publicId().idString()));
        try {
            StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(),
                    head.moduleNid(), edit.getDefaultPathNid());
            SemanticRecord updated = record.with(new SemanticVersionRecord(record, stamp.nid(),
                    Lists.immutable.of(tree.get()))).build();
            transaction.addComponent(updated);
            EntityService.get().putEntity(updated);
            return KometActionResult.completed(input, stamp, "The statement's tree is replaced in an uncommitted"
                    + " version; commit to keep it.");
        } catch (RuntimeException e) {
            if (pending.isEmpty()) {
                transaction.cancel();
            }
            return KometActionResult.failed(input, "Could not edit the statement: " + e.getMessage());
        }
    }
}
