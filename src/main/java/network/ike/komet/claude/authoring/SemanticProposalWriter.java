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
package network.ike.komet.claude.authoring;

import dev.ikm.komet.framework.observable.ObservableComposer;
import dev.ikm.komet.framework.observable.ObservableComposer.EntityComposer;
import dev.ikm.komet.framework.observable.ObservableField;
import dev.ikm.komet.framework.observable.ObservableSemantic;
import dev.ikm.komet.framework.observable.ObservableSemanticVersion;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.List;

/**
 * Writes a proposed semantic into the store <em>uncommitted</em>, for a person to review and
 * publish from the journal's Unpublished Changes panel ({@code ikmdev/komet-desktop#210}).
 *
 * <p>A semantic is an instance of a pattern on a reference component: one value per field of
 * the pattern, in the pattern's field order. The values arrive already in the form the store
 * holds them ({@link SemanticProposalTools} converts the assistant's input and judges it against
 * the pattern's field definitions; the writer does not). Beside the semantic goes an optional
 * editorial note. Everything is written in one transaction left pending for the person, as
 * {@link ConceptProposalWriter} and {@link PatternProposalWriter} do, so one Publish commits all
 * of it and one Discard withdraws all of it. Nothing here commits.
 */
public final class SemanticProposalWriter {

    /** Where a proposal stands. */
    public enum Status {
        /** Uncommitted: it can be discarded or published. */
        OPEN,
        /** Committed by Publish: a semantic, no longer a proposal. */
        PUBLISHED,
        /** Rolled back: nothing of it remains. */
        DISCARDED
    }

    /** A written proposal: the new semantic and the transaction holding it. */
    public static final class Draft {
        private final EntityFacade semantic;
        private final ObservableComposer composer;
        private int written;

        private Draft(EntityFacade semantic, ObservableComposer composer) {
            this.semantic = semantic;
            this.composer = composer;
        }

        /** @return the new semantic */
        public EntityFacade semantic() {
            return semantic;
        }

        /** @return how many semantics were written beside the semantic when it was proposed */
        public int written() {
            return written;
        }

        /** @return where the proposal stands */
        public Status status() {
            Entity<?> entity = EntityHandle.get(semantic.nid()).expectEntity();
            if (entity.uncommitted()) {
                return Status.OPEN;
            }
            return entity.canceled() ? Status.DISCARDED : Status.PUBLISHED;
        }
    }

    private SemanticProposalWriter() {
    }

    /**
     * Writes the proposal, uncommitted, on the JavaFX application thread.
     *
     * @param calc          the view the transaction is named against
     * @param author        the author of the proposal's stamp
     * @param module        the module of the proposal's stamp
     * @param path          the path of the proposal's stamp
     * @param referenced    the component the semantic is on: a concept, a pattern or a semantic
     * @param pattern       the pattern the semantic is an instance of
     * @param values        one value per field of the pattern, in field order, each in the form
     *                      the store holds for the field's data type
     * @param editorialNote the rationale, or null
     * @return the draft, to discard or to watch
     */
    public static Draft write(ViewCalculator calc, EntityFacade author, EntityFacade module, EntityFacade path,
                              EntityFacade referenced, PatternFacade pattern, List<Object> values,
                              String editorialNote) {
        // Left pending for the person: the Unpublished Changes panel ends the transaction (ike-issues#1326).
        ObservableComposer composer = ObservableComposer.create(calc, State.ACTIVE, author, module, path,
                "Assistant proposal: " + ComponentText.preferredName(calc, pattern.nid())
                        + " on " + ComponentText.preferredName(calc, referenced.nid())).joinPendingWork();
        EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> semanticComposer =
                composer.composeSemantic(PublicIds.newRandom(), referenced, pattern);
        List<ObservableField.Editable<?>> fields = semanticComposer.getEditableVersion().getEditableFields();
        if (fields.size() != values.size()) {
            throw new IllegalArgumentException("the pattern has " + fields.size() + " field(s) but " + values.size()
                    + " value(s) were given");
        }
        for (int i = 0; i < values.size(); i++) {
            fields.get(i).setObjectValue(values.get(i));
        }
        semanticComposer.save();
        Draft draft = new Draft(semanticComposer.getEntity(), composer);
        if (editorialNote != null && !editorialNote.isBlank()) {
            int noteField = ConceptProposalWriter.firstStringField(calc, KernelPattern.COMMENT_PATTERN);
            if (noteField >= 0) {
                ConceptProposalWriter.semantic(composer, draft.semantic, KernelPattern.COMMENT_PATTERN,
                        fieldList -> fieldList.get(noteField).setObjectValue(editorialNote.strip()));
                draft.written++;
            }
        }
        return draft;
    }

    /**
     * Withdraws an open proposal, rolling back its whole transaction.
     *
     * @param draft the proposal
     * @return whether anything was withdrawn; false once it was published or already discarded
     */
    public static boolean discard(Draft draft) {
        if (draft.status() != Status.OPEN) {
            return false;
        }
        draft.composer.rollback();
        return true;
    }
}
