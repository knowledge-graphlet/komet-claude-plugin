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
package network.ike.komet.claude.stamp;

import dev.ikm.komet.actions.KometAction;
import dev.ikm.komet.actions.KometActionInput;
import dev.ikm.komet.actions.KometActionResult;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.doc.DocumentVocabulary;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The {@code Delete document block} action: removes one block whose members are all lines from a
 * document materialized from git, through {@link DocumentEditor#deleteBlock}, so the card's menu,
 * the assistant's {@code propose_block_delete} and the headless runner delete a block the same way
 * (ike-lab-documents topic {@code dev-komet-actions}; IKE-Network/ike-issues#806, #1300).
 *
 * <p>The input's subject is the block; it takes no field. The result takes the {@code Version
 * change result pattern}: the version written is the block's inactive one, under the stamp of the
 * document's one pending edit, uncommitted until the person commits the document in git, which
 * settles it: the commit retires the lines where git recorded their removal.
 *
 * <p>Refused, with nothing written: a subject that is not a document block, a block holding other
 * blocks (remove its blocks first), and a document whose pending edit is someone else's, as
 * {@link EditDocumentBlock} refuses. Deleting is not a relaxation of editing: an edit keeps at
 * least one line, and this is the verb that removes the block.
 */
public final class DeleteDocumentBlock implements KometAction {

    /** For the service loader. */
    public DeleteDocumentBlock() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.DELETE_DOCUMENT_BLOCK;
    }

    /**
     * @param block the nid of the block to delete
     * @return a new input asking for the deletion
     */
    public static KometActionInput input(long block) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.DELETE_DOCUMENT_BLOCK,
                EntityProxy.Semantic.make(block), Lists.immutable.empty());
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        if (!input.fields().isEmpty()) {
            return KometActionResult.refused(input, "Deleting a block takes no field; the subject is the block.");
        }
        try {
            DocumentVocabulary.requirePresent();
        } catch (IllegalStateException missing) {
            return KometActionResult.refused(input, missing.getMessage());
        }
        long block = input.subject().nid();
        Entity<?> entity = EntityHandle.get(block).orNull();
        if (!(entity instanceof SemanticEntity<?> semantic) || (semantic.patternNid() != IkePattern.DOCUMENT_BLOCK_PATTERN.nid()
                && !(VertexReader.isDocumentPattern(semantic.patternNid()) && semantic.referencedComponentNid() != semantic.patternNid()))) {
            return KometActionResult.refused(input, "The subject is not a block of a document.");
        }
        PublicId anchor = PrimitiveData.publicId(semantic.referencedComponentNid());
        OptionalLong pendingAuthor = DocumentEditor.pendingAuthor(anchor);
        if (pendingAuthor.isPresent() && pendingAuthor.getAsLong() != edit.getAuthorNidForChanges()) {
            return KometActionResult.refused(input, "An edit of this document by "
                    + StampNames.name(view, pendingAuthor.getAsLong())
                    + " is pending; it is committed in git or discarded before anyone else edits the document.");
        }
        DocumentEditor.Edit done;
        try {
            done = DocumentEditor.deleteBlock(anchor, block, edit);
        } catch (IllegalArgumentException refused) {
            return KometActionResult.refused(input, refused.getMessage() + ".");      // thrown before anything is written
        } catch (RuntimeException e) {
            return KometActionResult.failed(input, "Could not delete the block: " + e.getMessage());
        }
        Optional<SemanticEntityVersion> written = EditDocumentBlock.pendingVersion(block);
        if (written.isEmpty()) {
            return KometActionResult.failed(input, "The deletion left no pending version of the block.");
        }
        return KometActionResult.completed(input, written.get().stamp(), "a block of " + done.removed()
                + (done.removed() == 1 ? " line" : " lines") + " removed; uncommitted until you commit the document in git.");
    }
}
