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
import network.ike.komet.claude.doc.DocumentVocabulary;
import org.eclipse.collections.api.factory.Lists;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The starter set's {@code Edit document sentence} action: replaces the text of one sentence of a
 * document materialized from git as semantic vertices, through {@link DocumentEditor#editSentence}
 * (IKE-Network/ike-issues#1313; decision 6 of {@code design-2026-10-21-stored-text-segments}), so
 * the Graph view, the assistant and the headless runner edit a sentence the same way. The sentence
 * goes on a line of its own, a fragment whose words survive keeps its line, and only changed text
 * is broken anew; the block's lines are then edited as {@link EditDocumentBlock} edits them.
 *
 * <p>The input takes the {@code Document block edit input pattern}: the subject is the sentence's
 * vertex, and the one further field is its new text. The result takes the {@code Version change
 * result pattern}: the sentence's pending version, under the stamp of the document's one pending
 * edit. The edit stays uncommitted until the person commits the document in git, which settles it.
 *
 * <p>Refused, with nothing written: a subject that is not a sentence of a document, empty text, a
 * text that would change the document's structure, and a document whose pending edit is someone
 * else's.
 */
public final class EditDocumentSentence implements KometAction {

    /** For the service loader. */
    public EditDocumentSentence() { }

    @Override
    public ConceptFacade concept() {
        return KometConcept.EDIT_DOCUMENT_SENTENCE;
    }

    /**
     * @param sentence the nid of the sentence's vertex
     * @param text     the sentence's new text
     * @return a new input asking for the edit
     */
    public static KometActionInput input(long sentence, String text) {
        return new KometActionInput(PublicIds.newRandom(), KometConcept.EDIT_DOCUMENT_SENTENCE,
                EntityProxy.Semantic.make(sentence), Lists.immutable.of(text));
    }

    @Override
    public KometActionResult perform(KometActionInput input, ViewCalculator view, EditCoordinate edit) {
        if (!EntityFacade.same(input.action(), concept())) {
            return KometActionResult.refused(input, "This input asks for " + input.action().description()
                    + ", not " + concept().description() + ".");
        }
        if (input.fields().size() != 1 || !(input.fields().getFirst() instanceof String text)) {
            return KometActionResult.refused(input, "Editing a sentence takes one field, the sentence's new text.");
        }
        try {
            DocumentVocabulary.requirePresent();
        } catch (IllegalStateException missing) {
            return KometActionResult.refused(input, missing.getMessage());
        }
        long sentence = input.subject().nid();
        Entity<?> entity = EntityHandle.get(sentence).orNull();
        if (!(entity instanceof SemanticEntity<?> semantic) || !VertexReader.isDocumentPattern(semantic.patternNid())
                || !isSentence(semantic)) {
            return KometActionResult.refused(input, "The subject is not a sentence of a document.");
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
            done = DocumentEditor.editSentence(anchor, sentence, text, edit);
        } catch (IllegalArgumentException refused) {
            return KometActionResult.refused(input, refused.getMessage() + ".");      // thrown before anything is written
        } catch (RuntimeException e) {
            return KometActionResult.failed(input, "Could not edit the sentence: " + e.getMessage());
        }
        if (!done.changedAnything()) {
            return new KometActionResult(input.id(), KometConcept.ACTION_COMPLETED, Optional.empty(),
                    "The text is unchanged; nothing was written.");
        }
        Optional<SemanticEntityVersion> written = pendingVersion(sentence);
        if (written.isEmpty()) {
            return KometActionResult.failed(input, "The edit left no pending version of the sentence.");
        }
        return KometActionResult.completed(input, written.get().stamp(), describe(done)
                + "; uncommitted until you commit the document in git.");
    }

    /** Whether a document vertex is a sentence: its latest version's meaning. */
    private static boolean isSentence(SemanticEntity<?> semantic) {
        SemanticEntityVersion latest = null;
        for (SemanticEntityVersion version : semantic.versions()) {
            if (!version.canceled() && (latest == null || version.time() >= latest.time())) {
                latest = version;
            }
        }
        return latest != null && latest.fieldValues().get(0) instanceof dev.ikm.tinkar.entity.graph.EntityVertex vertex
                && vertex.getMeaningNid() == network.ike.foundation.ike.bindings.IkeConcept.SENTENCE.nid();
    }

    /** The sentence's uncommitted version, the one the edit wrote. */
    private static Optional<SemanticEntityVersion> pendingVersion(long sentence) {
        if (!(EntityHandle.get(sentence).orNull() instanceof SemanticEntity<?> semantic)) {
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
