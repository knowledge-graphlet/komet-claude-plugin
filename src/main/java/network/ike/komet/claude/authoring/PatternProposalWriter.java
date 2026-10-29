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
import dev.ikm.komet.framework.observable.ObservablePattern;
import dev.ikm.komet.framework.observable.ObservablePatternVersion;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.FieldDefinitionRecord;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;

import java.util.List;

/**
 * Writes a proposed pattern into the store <em>uncommitted</em>, for a person to review and
 * publish from the journal's Unpublished Changes panel ({@code ikmdev/komet-desktop#209}).
 *
 * <p>A pattern is its meaning, its purpose and its ordered field definitions, each field a
 * meaning, a purpose and a data type; beside it go a fully qualified name, a preferred synonym
 * (derived from the name when none is given), any further synonyms, each with its US English
 * acceptability, and an editorial note. Everything is written in one transaction left pending
 * for the person, as {@link ConceptProposalWriter} does for a concept, so one Publish commits
 * all of it and one Discard withdraws all of it. Nothing here commits.
 *
 * <p>The field definitions are written the way Komet's own pattern window writes them
 * ({@code PatternViewModel.createPattern}): records on the pattern's edit stamp, indexed in
 * the order given. The writer does not judge the data types; the tool that calls it does.
 */
public final class PatternProposalWriter {

    /**
     * A proposed field definition.
     *
     * @param meaning  what the field holds
     * @param purpose  why the field is there
     * @param dataType the field's data type, a child of Komet's "Display Fields"
     */
    public record Field(EntityFacade meaning, EntityFacade purpose, EntityFacade dataType) {
    }

    /** Where a proposal stands. */
    public enum Status {
        /** Uncommitted: it can be discarded or published. */
        OPEN,
        /** Committed by Publish: a pattern, no longer a proposal. */
        PUBLISHED,
        /** Rolled back: nothing of it remains. */
        DISCARDED
    }

    /** A written proposal: the new pattern and the transaction holding it. */
    public static final class Draft {
        private final EntityFacade pattern;
        private final ObservableComposer composer;
        private int written;

        private Draft(EntityFacade pattern, ObservableComposer composer) {
            this.pattern = pattern;
            this.composer = composer;
        }

        /** @return the new pattern */
        public EntityFacade pattern() {
            return pattern;
        }

        /** @return how many semantics were written beside the pattern when it was proposed */
        public int written() {
            return written;
        }

        /** @return where the proposal stands */
        public Status status() {
            Entity<?> entity = EntityHandle.get(pattern.nid()).expectEntity();
            if (entity.uncommitted()) {
                return Status.OPEN;
            }
            return entity.canceled() ? Status.DISCARDED : Status.PUBLISHED;
        }
    }

    private PatternProposalWriter() {
    }

    /**
     * Writes the proposal, uncommitted, on the JavaFX application thread.
     *
     * @param calc          the view the pattern's names are written against
     * @param author        the author of the proposal's stamp
     * @param module        the module of the proposal's stamp
     * @param path          the path of the proposal's stamp
     * @param fsn           the fully qualified name
     * @param meaning       the pattern's meaning
     * @param purpose       the pattern's purpose
     * @param fields        the field definitions, in order
     * @param synonyms      further names; when none is preferred, the name becomes the preferred synonym
     * @param editorialNote the rationale, or null
     * @return the draft, to discard or to watch
     */
    public static Draft write(ViewCalculator calc, EntityFacade author, EntityFacade module, EntityFacade path,
                              String fsn, EntityFacade meaning, EntityFacade purpose, List<Field> fields,
                              List<Synonym> synonyms, String editorialNote) {
        // Left pending for the person: the Unpublished Changes panel ends the transaction (ike-issues#1326).
        ObservableComposer composer = ObservableComposer.create(calc, State.ACTIVE, author, module, path,
                "Assistant proposal: " + fsn).joinPendingWork();
        PublicId publicId = PublicIds.newRandom();
        EntityComposer<ObservablePatternVersion.Editable, ObservablePattern> patternComposer =
                composer.composePattern(publicId);
        ObservablePatternVersion.Editable editable = patternComposer.getEditableVersion();
        editable.getMeaningProperty().set(meaning);
        editable.getPurposeProperty().set(purpose);
        long patternNid = PrimitiveData.nid(publicId);
        long stampNid = editable.getEditStamp().nid();
        MutableList<FieldDefinitionRecord> definitions = Lists.mutable.ofInitialCapacity(fields.size());
        for (int i = 0; i < fields.size(); i++) {
            Field field = fields.get(i);
            definitions.add(new FieldDefinitionRecord(field.dataType().nid(), field.purpose().nid(),
                    field.meaning().nid(), stampNid, patternNid, i));
        }
        editable.setFieldDefinitions(definitions.toImmutable());
        patternComposer.save();
        Draft draft = new Draft(patternComposer.getEntity(), composer);
        EntityFacade pattern = draft.pattern;
        ConceptProposalWriter.description(composer, calc, pattern, fsn, false,
                KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE, true);
        draft.written += 2;
        if (synonyms.stream().noneMatch(Synonym::preferred)) {
            ConceptProposalWriter.description(composer, calc, pattern, ConceptProposalWriter.withoutSemanticTag(fsn),
                    false, KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE, true);
            draft.written += 2;
        }
        for (Synonym synonym : synonyms) {
            ConceptProposalWriter.description(composer, calc, pattern, synonym.term(), synonym.caseSensitive(),
                    KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE, synonym.preferred());
            draft.written += 2;
        }
        if (editorialNote != null && !editorialNote.isBlank()) {
            int noteField = ConceptProposalWriter.firstStringField(calc, KernelPattern.COMMENT_PATTERN);
            if (noteField >= 0) {
                ConceptProposalWriter.semantic(composer, pattern, KernelPattern.COMMENT_PATTERN,
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
