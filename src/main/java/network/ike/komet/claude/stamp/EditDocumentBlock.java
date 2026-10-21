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
 * The starter set's {@code Edit document block} action: replaces the text of one block of a
 * document materialized from git, through {@link DocumentEditor#editBlock}, so the card's Save, the
 * assistant and the headless runner edit a block the same way (ike-lab-documents topic
 * {@code dev-komet-actions}; IKE-Network/ike-issues#1306).
 *
 * <p>The input takes the {@code Document block edit input pattern}: the subject is the block's
 * element semantic, and the one further field is the block's new text. The result takes the
 * {@code Version change result pattern}: the edit writes a new version of the block, under the
 * stamp of the document's one pending edit, and that stamp is the version written. The edit stays
 * uncommitted until the person commits the document in git, which settles it.
 *
 * <p>Refused, with nothing written: a subject that is not a document block, a block holding other
 * blocks, empty text, and a document whose pending edit is someone else's, since a change is
 * never made on top of a change nobody has settled. The person's own pending edit is not such a
 * change: editing again replaces its pending versions, as it always has.
 */
public final class EditDocumentBlock implements KometAction {

    /** For the service loader. */
    public EditDocumentBlock() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.EDIT_DOCUMENT_BLOCK;
    }

    /**
     * @param block the nid of the block's element semantic
     * @param text  the block's new text, one line per line
     * @return a new input asking for the edit
     */
    public static KometActionInput input(long block, String text) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.EDIT_DOCUMENT_BLOCK,
                EntityProxy.Semantic.make(block), Lists.immutable.of(text));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        if (input.fields().size() != 1 || !(input.fields().getFirst() instanceof String text)) {
            return KometActionResult.refused(input, "Editing a block takes one field, the block's new text.");
        }
        try {
            DocumentVocabulary.requirePresent();
        } catch (IllegalStateException missing) {
            return KometActionResult.refused(input, missing.getMessage());
        }
        long block = input.subject().nid();
        Entity<?> entity = EntityHandle.get(block).orNull();
        // A block of the prose-element model, or a block vertex of a document stored as vertices (#1313).
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
            done = DocumentEditor.editBlock(anchor, block, text, edit);
        } catch (IllegalArgumentException refused) {
            return KometActionResult.refused(input, refused.getMessage() + ".");      // thrown before anything is written
        } catch (RuntimeException e) {
            return KometActionResult.failed(input, "Could not edit the block: " + e.getMessage());
        }
        if (!done.changedAnything()) {
            return new KometActionResult(input.id(), KometConcept.ACTION_COMPLETED, Optional.empty(),
                    "The text is unchanged; nothing was written.");
        }
        Optional<SemanticEntityVersion> written = pendingVersion(block);
        if (written.isEmpty()) {
            return KometActionResult.failed(input, "The edit left no pending version of the block.");
        }
        return KometActionResult.completed(input, written.get().stamp(), describe(done)
                + "; uncommitted until you commit the document in git.");
    }

    /** The block's uncommitted version, the one the edit wrote. */
    private static Optional<SemanticEntityVersion> pendingVersion(long block) {
        if (!(EntityHandle.get(block).orNull() instanceof SemanticEntity<?> semantic)) {
            return Optional.empty();
        }
        SemanticEntityVersion found = null;
        for (SemanticEntityVersion version : semantic.versions()) {
            if (version.uncommitted() && !version.canceled()) {
                found = version;
            }
        }
        return Optional.ofNullable(found);
    }

    private static String describe(DocumentEditor.Edit done) {
        StringBuilder text = new StringBuilder();
        count(text, done.changed(), "changed");
        count(text, done.added(), "added");
        count(text, done.removed(), "removed");
        return text.toString();
    }

    private static void count(StringBuilder text, int lines, String what) {
        if (lines > 0) {
            text.append(text.isEmpty() ? "" : ", ").append(lines).append(lines == 1 ? " line " : " lines ").append(what);
        }
    }
}
