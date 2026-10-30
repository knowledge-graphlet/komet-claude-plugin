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

import design.knowledge.stamp.adoc.dialect.Block;
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
 * The {@code Add document block} action: inserts a new paragraph or listing after one block of a
 * document materialized from git, through {@link DocumentEditor#addBlock}, so the card's menu, the
 * assistant's {@code propose_block_insert} and the headless runner add a block the same way
 * (ike-lab-documents topic {@code dev-komet-actions}; IKE-Network/ike-issues#806, #1300).
 *
 * <p>The input's subject is the block to insert after, a block whose members are all lines; its
 * two fields are the new block's kind, the starter set's {@code Document paragraph} or
 * {@code Document listing} concept, and the new block's text, one line per line. The result takes
 * the {@code Version change result pattern}: the version written is the new block's, under the
 * stamp of the document's one pending edit, uncommitted until the person commits the document in
 * git, which settles it.
 *
 * <p>Refused, with nothing written: a subject that is not a document block, a block holding other
 * blocks, a kind other than paragraph or listing, text that does not form exactly one block of that
 * kind, and a document whose pending edit is someone else's, as {@link EditDocumentBlock} refuses.
 */
public final class AddDocumentBlock implements KometAction {

    /** For the service loader. */
    public AddDocumentBlock() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.ADD_DOCUMENT_BLOCK;
    }

    /**
     * @param after the nid of the block to insert after
     * @param kind  the new block's kind, a paragraph or a listing
     * @param text  the new block's text, one line per line
     * @return a new input asking for the insertion
     */
    public static KometActionInput input(long after, Block.Kind kind, String text) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.ADD_DOCUMENT_BLOCK,
                EntityProxy.Semantic.make(after), Lists.immutable.of(AsciiDocModelTerms.kind(kind), text));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        if (input.fields().size() != 2 || !(input.fields().get(0) instanceof ConceptFacade kindConcept)
                || !(input.fields().get(1) instanceof String text)) {
            return KometActionResult.refused(input, "Adding a block takes two fields: the new block's kind and its text.");
        }
        Optional<Block.Kind> kind = AsciiDocModelTerms.kindOf(kindConcept.publicId());
        if (kind.isEmpty() || (kind.get() != Block.Kind.PARAGRAPH && kind.get() != Block.Kind.LISTING)) {
            return KometActionResult.refused(input, "A new block is a paragraph or a listing.");
        }
        try {
            DocumentVocabulary.requirePresent();
        } catch (IllegalStateException missing) {
            return KometActionResult.refused(input, missing.getMessage());
        }
        long after = input.subject().nid();
        Entity<?> entity = EntityHandle.get(after).orNull();
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
            done = DocumentEditor.addBlock(anchor, after, kind.get(), text, edit);
        } catch (IllegalArgumentException refused) {
            return KometActionResult.refused(input, refused.getMessage() + ".");      // thrown before anything is written
        } catch (RuntimeException e) {
            return KometActionResult.failed(input, "Could not add the block: " + e.getMessage());
        }
        Optional<SemanticEntityVersion> written = EditDocumentBlock.pendingVersion(done.block());
        if (written.isEmpty()) {
            return KometActionResult.failed(input, "The insertion left no pending version of the new block.");
        }
        return KometActionResult.completed(input, written.get().stamp(), "a " + kind.get().name().toLowerCase() + " of "
                + done.added() + (done.added() == 1 ? " line" : " lines") + " added; uncommitted until you commit the document in git.");
    }
}
