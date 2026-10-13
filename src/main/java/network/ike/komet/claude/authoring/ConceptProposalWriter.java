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
import dev.ikm.komet.terms.KometTerm;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.terms.PatternFacade;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Writes a proposed concept into the store <em>uncommitted</em>, for a person to review and
 * publish in a KL window ({@code IKE-Network/ike-issues#1292}). The assistant proposes; it never
 * commits.
 *
 * <p>Everything — the concept, its fully specified name and synonyms with their US-English
 * acceptability, its stated definition, and an editorial note — is saved in one
 * {@link ObservableComposer} transaction on the view's edit coordinate. A KL window opened on the
 * concept shows each of these as not published, and its Publish button commits the transactions
 * its unpublished versions belong to ({@code WindowEditSession.commitUnpublishedTransactions}), so
 * one transaction means one Publish commits the whole proposal, the dialect semantics the window
 * does not display included.
 *
 * <p>Fields are set by their meaning in the pattern's latest definition, not by position, except
 * where a pattern's meaning is not a published term: the stated-axiom pattern's one field and the
 * comment pattern's string field.
 *
 * <p>Must run on the JavaFX application thread, as {@link ObservableComposer} requires.
 */
public final class ConceptProposalWriter {

    /** A synonym to propose: its text, whether it is preferred in US English, and whether its case matters. */
    public record Synonym(String term, boolean preferred, boolean caseSensitive) {
    }

    /**
     * A proposal written uncommitted.
     *
     * @param concept  the new concept
     * @param composer the composer whose open transaction holds the proposal, for {@link #discard}
     * @param written  how many semantics were written beside the concept
     */
    public record Written(EntityFacade concept, ObservableComposer composer, int written) {
    }

    private ConceptProposalWriter() {
    }

    /**
     * Writes the proposal uncommitted.
     *
     * @param calc          the view the patterns' field definitions are read on
     * @param author        the author of the proposal's versions — the edit coordinate's
     * @param module        the module they are written in
     * @param path          the path they are written on
     * @param fsn           the fully specified name
     * @param definition    the stated definition, as {@link ProposedDefinition#build} made it
     * @param synonyms      the synonyms; when none is preferred, the name without its semantic
     *                      tag is added as the preferred synonym, since a concept needs one
     * @param editorialNote the note to attach, or null for none
     * @return the written proposal
     */
    public static Written write(ViewCalculator calc, EntityFacade author, EntityFacade module, EntityFacade path,
                                String fsn, DiTreeEntity definition, List<Synonym> synonyms, String editorialNote) {
        ObservableComposer composer = ObservableComposer.create(calc, State.ACTIVE, author, module, path,
                "Assistant proposal: " + fsn);

        EntityComposer<?, ?> conceptComposer = composer.composeConcept(PublicIds.newRandom());
        conceptComposer.save();
        EntityFacade concept = conceptComposer.getEntity();

        int written = 0;
        written += description(composer, calc, concept, fsn, false,
                KernelTerm.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE, true);
        boolean anyPreferred = synonyms.stream().anyMatch(Synonym::preferred);
        if (!anyPreferred) {
            written += description(composer, calc, concept, withoutSemanticTag(fsn), false,
                    KernelTerm.REGULAR_NAME_DESCRIPTION_TYPE, true);
        }
        for (Synonym synonym : synonyms) {
            written += description(composer, calc, concept, synonym.term(), synonym.caseSensitive(),
                    KernelTerm.REGULAR_NAME_DESCRIPTION_TYPE, synonym.preferred());
        }

        semantic(composer, concept, KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN,
                fields -> fields.getFirst().setObjectValue(definition));
        written++;

        if (editorialNote != null && !editorialNote.isBlank()) {
            int noteField = firstStringField(calc, KernelTerm.COMMENT_PATTERN);
            if (noteField >= 0) {
                semantic(composer, concept, KernelTerm.COMMENT_PATTERN,
                        fields -> fields.get(noteField).setObjectValue(editorialNote));
                written++;
            }
        }
        return new Written(concept, composer, written);
    }

    /**
     * Discards a proposal that is still uncommitted: its transaction is rolled back. A proposal a
     * window has already published is left alone.
     *
     * @param written the proposal
     * @return true when it was discarded, false when it had already been published
     */
    public static boolean discard(Written written) {
        if (!EntityHandle.get(written.concept().nid()).expectEntity().uncommitted()) {
            return false;
        }
        written.composer().rollback();
        return true;
    }

    /** A description and its US-English acceptability; returns the number of semantics written. */
    private static int description(ObservableComposer composer, ViewCalculator calc, EntityFacade concept,
                                   String text, boolean caseSensitive, ConceptFacade type, boolean preferred) {
        PatternEntityVersion pattern = latest(calc, KernelTerm.DESCRIPTION_PATTERN);
        Map<ConceptFacade, Object> byMeaning = Map.of(
                KernelTerm.LANGUAGE_CONCEPT_NID_FOR_DESCRIPTION, KernelTerm.ENGLISH_LANGUAGE,
                KernelTerm.TEXT_FOR_DESCRIPTION, text,
                KernelTerm.DESCRIPTION_CASE_SIGNIFICANCE, caseSensitive
                        ? KernelTerm.DESCRIPTION_CASE_SENSITIVE : KernelTerm.DESCRIPTION_NOT_CASE_SENSITIVE,
                KernelTerm.DESCRIPTION_TYPE, type);
        ObservableSemantic description = semantic(composer, concept, KernelTerm.DESCRIPTION_PATTERN,
                fields -> byMeaning.forEach((meaning, value) ->
                        fields.get(index(pattern, meaning)).setObjectValue(value)));

        PatternEntityVersion dialect = latest(calc, KernelTerm.US_DIALECT_PATTERN);
        // The dialect pattern's one field is named for the dialect itself; it holds the acceptability.
        int acceptability = index(dialect, KometTerm.US_ENGLISH_DIALECT);
        semantic(composer, description, KernelTerm.US_DIALECT_PATTERN,
                fields -> fields.get(acceptability).setObjectValue(preferred ? KernelTerm.PREFERRED : KometTerm.ACCEPTABLE));
        return 2;
    }

    /** Saves a new semantic of the pattern on the referenced component, its fields set by {@code seed}. */
    private static ObservableSemantic semantic(ObservableComposer composer, EntityFacade referenced,
                                               PatternFacade pattern,
                                               Consumer<List<ObservableField.Editable<?>>> seed) {
        EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> semantic =
                composer.composeSemantic(PublicIds.newRandom(), referenced, pattern);
        seed.accept(semantic.getEditableVersion().getEditableFields());
        semantic.save();
        return semantic.getEntity();
    }

    private static PatternEntityVersion latest(ViewCalculator calc, PatternFacade pattern) {
        return calc.latestPatternEntityVersion(pattern.nid()).orElseThrow(() ->
                new IllegalStateException(pattern.description() + " has no version on this view"));
    }

    private static int index(PatternEntityVersion pattern, ConceptFacade meaning) {
        int index = pattern.indexForMeaning(meaning.nid());
        if (index < 0) {
            List<String> meanings = new java.util.ArrayList<>();
            pattern.fieldDefinitions().forEach(field -> meanings.add(ComponentText.identifier(field.meaningNid())));
            throw new IllegalStateException(ComponentText.identifier(pattern.nid()) + " has no field meaning "
                    + meaning.description() + "; its field meanings are " + meanings);
        }
        return index;
    }

    /** The index of the pattern's first string field, or -1 when it has none. */
    private static int firstStringField(ViewCalculator calc, PatternFacade pattern) {
        PatternEntityVersion version = latest(calc, pattern);
        for (int i = 0; i < version.fieldDefinitions().size(); i++) {
            if (version.fieldDefinitions().get(i).dataTypeNid() == KernelTerm.STRING.nid()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A fully specified name without its trailing semantic tag:
     * {@code Eosinophilic esophagitis (disorder)} → {@code Eosinophilic esophagitis}.
     */
    static String withoutSemanticTag(String fsn) {
        String stripped = fsn.strip();
        if (stripped.endsWith(")")) {
            int open = stripped.lastIndexOf(" (");
            if (open > 0) {
                return stripped.substring(0, open).strip();
            }
        }
        return stripped;
    }
}
