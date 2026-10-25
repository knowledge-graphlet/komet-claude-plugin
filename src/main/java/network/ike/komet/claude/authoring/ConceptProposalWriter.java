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
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.*;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Writes a proposed concept into the store <em>uncommitted</em>, for a person to review and
 * publish in a KL window ({@code IKE-Network/ike-issues#1292}), and edits it in place while it
 * stays uncommitted. The assistant proposes; it never commits.
 *
 * <p>Everything — the concept, its fully specified name and synonyms with their US-English
 * acceptability, its stated definition, its textual definition, and an editorial note — is saved
 * in one {@link ObservableComposer} transaction on the view's edit coordinate. A KL window opened
 * on the concept shows each of these as not published, and its Publish button commits the
 * transactions its unpublished versions belong to
 * ({@code WindowEditSession.commitUnpublishedTransactions}), so one transaction means one Publish
 * commits the whole proposal, the dialect semantics the window does not display included.
 *
 * <p>A synonym withdrawn by an edit leaves that transaction without cancelling the rest of it
 * ({@link ObservableComposer#withdraw}).
 *
 * <p>An {@linkplain Draft#edit edit} sets a field in the transaction that wrote it: the editor's
 * stamp is that transaction's, so the edited version replaces the written one rather than
 * standing beside it. A field the person has already edited in the window carries a version of
 * the window's transaction as well, and two uncommitted versions of two transactions would
 * commit in whichever order the window happened to commit them; such a field is left as the
 * person left it, and the edit reports a warning instead.
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
     * A change to a proposed synonym, found by its term.
     *
     * @param term          the synonym's text, exactly as proposed
     * @param preferred     its new US-English acceptability, or null to leave it
     * @param caseSensitive its new case significance, or null to leave it
     */
    public record SynonymChange(String term, Boolean preferred, Boolean caseSensitive) {
    }

    /**
     * Edits to an open proposal. A null part leaves that part as it is.
     *
     * @param name              a replacement fully specified name, with its semantic tag
     * @param definition        a replacement stated definition
     * @param textualDefinition a replacement (or first) textual definition
     * @param editorialNote     a replacement (or first) editorial note
     * @param add               synonyms to add
     * @param remove            the terms of proposed synonyms to withdraw
     * @param change            changes to proposed synonyms
     */
    public record Edit(String name, DiTreeEntity definition, String textualDefinition, String editorialNote,
                       List<Synonym> add, List<String> remove, List<SynonymChange> change) {
    }

    /**
     * What an edit did.
     *
     * @param fields   the edit's parts that changed something, by tool parameter name
     * @param warnings the parts left alone, and why
     */
    public record Edited(List<String> fields, List<String> warnings) {
    }

    /** Where a proposal stands. */
    public enum Status {
        /** Uncommitted: it can be edited, discarded, or published. */
        OPEN,
        /** Committed by the window's Publish: a concept, no longer a proposal. */
        PUBLISHED,
        /** Rolled back: nothing of it remains. */
        DISCARDED
    }

    /** A description written with its US-English acceptability. */
    record Described(long description, long dialect) {
    }

    /** A synonym as written. */
    private record WrittenSynonym(Synonym synonym, Described written) {
    }

    /**
     * A proposal written uncommitted, and the transactions that hold it. Every method must run on
     * the JavaFX application thread.
     */
    public static final class Draft {
        private final ViewCalculator calc;
        private final EntityFacade concept;
        private final ObservableComposer composer;
        private final List<WrittenSynonym> synonyms = new ArrayList<>();
        private Described name;
        private long definition;
        private Described textualDefinition;
        private Long note;
        /** The synonym derived from the name when none was preferred, while it still is that. */
        private String derivedTerm;
        private int written;

        private Draft(ViewCalculator calc, EntityFacade concept, ObservableComposer composer) {
            this.calc = calc;
            this.concept = concept;
            this.composer = composer;
        }

        /** @return the new concept */
        public EntityFacade concept() {
            return concept;
        }

        /** @return how many semantics were written beside the concept when it was proposed */
        public int written() {
            return written;
        }

        /** @return the proposed synonyms as they now stand, the one derived from the name included */
        public List<Synonym> synonyms() {
            return synonyms.stream().map(WrittenSynonym::synonym).toList();
        }

        /** @return where the proposal stands */
        public Status status() {
            Entity<?> entity = EntityHandle.get(concept.nid()).expectEntity();
            if (entity.uncommitted()) {
                return Status.OPEN;
            }
            return entity.canceled() ? Status.DISCARDED : Status.PUBLISHED;
        }

        /**
         * Applies the edit to the open proposal, in place. Everything is checked before anything is
         * written, so an edit that is refused writes nothing.
         *
         * @param edit the edit
         * @return what changed, and what was left alone and why
         * @throws IllegalStateException    when the proposal was published or discarded
         * @throws IllegalArgumentException when the edit names a synonym the proposal does not
         *                                  have, adds one it already has, or would leave other
         *                                  than one preferred synonym
         */
        public Edited edit(Edit edit) {
            switch (status()) {
                case PUBLISHED -> throw new IllegalStateException("the proposal was already published; it is a "
                        + "committed concept and is not edited here");
                case DISCARDED -> throw new IllegalStateException("the proposal was discarded; nothing of it remains");
                case OPEN -> { }
            }
            List<String> fields = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            // ── check the synonym edits, before writing anything ──
            List<String> remove = edit.remove() == null ? List.of() : edit.remove();
            List<SynonymChange> change = edit.change() == null ? List.of() : edit.change();
            List<Synonym> add = edit.add() == null ? List.of() : edit.add();
            Map<String, WrittenSynonym> byTerm = new LinkedHashMap<>();
            synonyms.forEach(synonym -> byTerm.putIfAbsent(synonym.synonym().term(), synonym));
            Set<String> removing = new HashSet<>();
            for (String term : remove) {
                WrittenSynonym synonym = existing(byTerm, term);
                if (editedElsewhere(synonym.written())) {
                    warnings.add("synonyms_remove: the person has edited \"" + synonym.synonym().term()
                            + "\" in the window, so it was left as they left it");
                } else {
                    removing.add(synonym.synonym().term());
                }
            }
            Map<String, Synonym> changing = new LinkedHashMap<>();
            for (SynonymChange c : change) {
                WrittenSynonym synonym = existing(byTerm, c.term());
                String term = synonym.synonym().term();
                if (removing.contains(term)) {
                    throw new IllegalArgumentException("\"" + term + "\" is both removed and updated");
                }
                if (editedElsewhere(synonym.written())) {
                    warnings.add("synonyms_update: the person has edited \"" + term
                            + "\" in the window, so it was left as they left it");
                } else {
                    changing.put(term, new Synonym(term,
                            c.preferred() == null ? synonym.synonym().preferred() : c.preferred(),
                            c.caseSensitive() == null ? synonym.synonym().caseSensitive() : c.caseSensitive()));
                }
            }
            Set<String> terms = new HashSet<>(byTerm.keySet());
            terms.removeAll(removing);
            for (Synonym synonym : add) {
                if (synonym.term() == null || synonym.term().isBlank()) {
                    throw new IllegalArgumentException("a synonym to add has no term");
                }
                if (!terms.add(synonym.term())) {
                    throw new IllegalArgumentException("the proposal already has the synonym \"" + synonym.term() + "\"");
                }
            }
            if (!remove.isEmpty() || !change.isEmpty() || !add.isEmpty()) {
                long preferred = synonyms.stream()
                        .filter(synonym -> !removing.contains(synonym.synonym().term()))
                        .map(synonym -> changing.getOrDefault(synonym.synonym().term(), synonym.synonym()))
                        .filter(Synonym::preferred).count()
                        + add.stream().filter(Synonym::preferred).count();
                if (preferred != 1) {
                    throw new IllegalArgumentException("the edit would leave " + preferred + " preferred synonyms; a "
                            + "concept has exactly one preferred synonym in US English, so make one preferred and the "
                            + "others acceptable in the same edit");
                }
            }

            // ── write ──
            if (!removing.isEmpty()) {
                synonyms.removeIf(synonym -> {
                    if (!removing.contains(synonym.synonym().term())) {
                        return false;
                    }
                    withdraw(synonym.written());
                    return true;
                });
                if (removing.contains(derivedTerm)) {
                    derivedTerm = null;
                }
                fields.add("synonyms_remove");
            }
            boolean changed = false;
            for (int i = 0; i < synonyms.size(); i++) {
                WrittenSynonym synonym = synonyms.get(i);
                Synonym to = changing.get(synonym.synonym().term());
                if (to != null) {
                    changed |= setDescription(synonym.written(), null, to.caseSensitive(), to.preferred());
                    synonyms.set(i, new WrittenSynonym(to, synonym.written()));
                }
            }
            if (changed) {
                fields.add("synonyms_update");
            }
            for (Synonym synonym : add) {
                synonyms.add(writeSynonym(new Synonym(synonym.term().strip(), synonym.preferred(), synonym.caseSensitive())));
            }
            if (!add.isEmpty()) {
                fields.add("synonyms_add");
            }

            if (edit.name() != null && !edit.name().isBlank()) {
                String fsn = edit.name().strip();
                if (editedElsewhere(name)) {
                    warnings.add("proposed_name: the person has edited the fully specified name in the window, so "
                            + "it was left as they left it");
                } else if (setDescription(name, fsn, null, null)) {
                    fields.add("proposed_name");
                    renameDerived(withoutSemanticTag(fsn), warnings);
                }
            }

            if (edit.definition() != null) {
                if (editedInWindow(composer, definition)) {
                    warnings.add("definition: the person has edited the stated definition in the window, so it was "
                            + "NOT replaced; their axioms stand. Show them the replacement to enter themselves, or "
                            + "discard the proposal and propose it again");
                } else if (set(composer, definition, 0, edit.definition())) {
                    fields.add("definition");
                }
            }

            if (edit.textualDefinition() != null && !edit.textualDefinition().isBlank()) {
                String text = edit.textualDefinition().strip();
                if (textualDefinition == null) {
                    textualDefinition = description(composer, calc, concept, text, false,
                            KernelConcept.DEFINITION_DESCRIPTION_TYPE, true);
                    fields.add("textual_definition");
                } else if (editedElsewhere(textualDefinition)) {
                    warnings.add("textual_definition: the person has edited the textual definition in the window, "
                            + "so it was left as they left it");
                } else if (setDescription(textualDefinition, text, null, null)) {
                    fields.add("textual_definition");
                }
            }

            if (edit.editorialNote() != null && !edit.editorialNote().isBlank()) {
                String text = edit.editorialNote().strip();
                int noteField = firstStringField(calc, KernelPattern.COMMENT_PATTERN);
                if (noteField < 0) {
                    warnings.add("editorial_note: this knowledge base's comment pattern has no text field");
                } else if (note == null) {
                    note = semantic(composer, concept, KernelPattern.COMMENT_PATTERN,
                            f -> f.get(noteField).setObjectValue(text)).nid();
                    fields.add("editorial_note");
                } else if (editedInWindow(composer, note)) {
                    warnings.add("editorial_note: the person has edited the editorial note in the window, so it "
                            + "was left as they left it");
                } else if (set(composer, note, noteField, text)) {
                    fields.add("editorial_note");
                }
            }
            return new Edited(List.copyOf(fields), List.copyOf(warnings));
        }

        /** The synonym derived from the old name follows the new one, unless the person changed it. */
        private void renameDerived(String term, List<String> warnings) {
            for (int i = 0; derivedTerm != null && i < synonyms.size(); i++) {
                WrittenSynonym synonym = synonyms.get(i);
                if (synonym.synonym().term().equals(derivedTerm)) {
                    if (editedElsewhere(synonym.written())) {
                        warnings.add("proposed_name: the preferred synonym \"" + derivedTerm + "\" was derived from "
                                + "the old name but the person has edited it, so it was left as they left it");
                    } else {
                        setDescription(synonym.written(), term, null, null);
                        synonyms.set(i, new WrittenSynonym(new Synonym(term, synonym.synonym().preferred(),
                                synonym.synonym().caseSensitive()), synonym.written()));
                        derivedTerm = term;
                    }
                    return;
                }
            }
        }

        /** A synonym, in the proposal's transaction. */
        private WrittenSynonym writeSynonym(Synonym synonym) {
            Described written = description(composer, calc, concept, synonym.term(), synonym.caseSensitive(),
                    KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE, synonym.preferred());
            return new WrittenSynonym(synonym, written);
        }

        /**
         * Withdraws a description and its acceptability from the proposal's transaction, leaving
         * the rest of the proposal uncommitted in it ({@link ObservableComposer#withdraw}).
         */
        private void withdraw(Described described) {
            composer.withdraw(described.dialect());
            composer.withdraw(described.description());
        }

        /**
         * Sets a description's text, case significance and acceptability, each only when not null;
         * returns whether anything changed.
         */
        private boolean setDescription(Described described, String text, Boolean caseSensitive, Boolean preferred) {
            PatternEntityVersion pattern = latest(calc, KernelPattern.DESCRIPTION_PATTERN);
            boolean changed = false;
            if (text != null) {
                changed |= set(composer, described.description(), index(pattern, KernelConcept.TEXT_FOR_DESCRIPTION), text);
            }
            if (caseSensitive != null) {
                changed |= set(composer, described.description(), index(pattern, KernelConcept.DESCRIPTION_CASE_SIGNIFICANCE),
                        caseSensitive ? KernelConcept.DESCRIPTION_CASE_SENSITIVE : KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE);
            }
            if (preferred != null) {
                changed |= set(composer, described.dialect(),
                        index(latest(calc, KernelPattern.US_DIALECT_PATTERN), KometConcept.US_ENGLISH_DIALECT),
                        preferred ? KernelConcept.PREFERRED : KometConcept.ACCEPTABLE);
            }
            return changed;
        }

        private boolean editedElsewhere(Described described) {
            return editedInWindow(composer, described.description()) || editedInWindow(composer, described.dialect());
        }
    }

    private ConceptProposalWriter() {
    }

    /**
     * Writes the proposal uncommitted.
     *
     * @param calc              the view the patterns' field definitions are read on
     * @param author            the author of the proposal's versions — the edit coordinate's
     * @param module            the module they are written in
     * @param path              the path they are written on
     * @param fsn               the fully specified name
     * @param definition        the stated definition, as {@link ProposedDefinition#build} made it
     * @param synonyms          the synonyms; when none is preferred, the name without its semantic
     *                          tag is added as the preferred synonym, since a concept needs one
     * @param editorialNote     the note to attach, or null for none
     * @param textualDefinition the prose textual definition, or null for none
     * @return the written proposal
     */
    public static Draft write(ViewCalculator calc, EntityFacade author, EntityFacade module, EntityFacade path,
                              String fsn, DiTreeEntity definition, List<Synonym> synonyms, String editorialNote,
                              String textualDefinition) {
        ObservableComposer composer = ObservableComposer.create(calc, State.ACTIVE, author, module, path,
                "Assistant proposal: " + fsn);

        EntityComposer<?, ?> conceptComposer = composer.composeConcept(PublicIds.newRandom());
        conceptComposer.save();
        Draft draft = new Draft(calc, conceptComposer.getEntity(), composer);
        EntityFacade concept = draft.concept;

        draft.name = description(composer, calc, concept, fsn, false,
                KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE, true);
        draft.written += 2;
        boolean anyPreferred = synonyms.stream().anyMatch(Synonym::preferred);
        if (!anyPreferred) {
            draft.derivedTerm = withoutSemanticTag(fsn);
            draft.synonyms.add(draft.writeSynonym(new Synonym(draft.derivedTerm, true, false)));
            draft.written += 2;
        }
        for (Synonym synonym : synonyms) {
            draft.synonyms.add(draft.writeSynonym(synonym));
            draft.written += 2;
        }

        draft.definition = semantic(composer, concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN,
                fields -> fields.getFirst().setObjectValue(definition)).nid();
        draft.written++;

        if (textualDefinition != null && !textualDefinition.isBlank()) {
            draft.textualDefinition = description(composer, calc, concept, textualDefinition.strip(), false,
                    KernelConcept.DEFINITION_DESCRIPTION_TYPE, true);
            draft.written += 2;
        }

        if (editorialNote != null && !editorialNote.isBlank()) {
            int noteField = firstStringField(calc, KernelPattern.COMMENT_PATTERN);
            if (noteField >= 0) {
                draft.note = semantic(composer, concept, KernelPattern.COMMENT_PATTERN,
                        fields -> fields.get(noteField).setObjectValue(editorialNote)).nid();
                draft.written++;
            }
        }
        return draft;
    }

    /**
     * Discards a proposal that is still uncommitted: its transaction is rolled back. A proposal
     * a window has already published is left alone.
     *
     * @param draft the proposal
     * @return true when it was discarded, false when it had already been published or discarded
     */
    public static boolean discard(Draft draft) {
        if (draft.status() != Status.OPEN) {
            return false;
        }
        draft.composer.rollback();
        return true;
    }

    /** A description and its US-English acceptability. */
    static Described description(ObservableComposer composer, ViewCalculator calc, EntityFacade concept,
                                         String text, boolean caseSensitive, ConceptFacade type, boolean preferred) {
        PatternEntityVersion pattern = latest(calc, KernelPattern.DESCRIPTION_PATTERN);
        Map<ConceptFacade, Object> byMeaning = Map.of(
                KernelConcept.LANGUAGE_CONCEPT_NID_FOR_DESCRIPTION, KernelConcept.ENGLISH_LANGUAGE,
                KernelConcept.TEXT_FOR_DESCRIPTION, text,
                KernelConcept.DESCRIPTION_CASE_SIGNIFICANCE, caseSensitive
                        ? KernelConcept.DESCRIPTION_CASE_SENSITIVE : KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE,
                KernelConcept.DESCRIPTION_TYPE, type);
        ObservableSemantic description = semantic(composer, concept, KernelPattern.DESCRIPTION_PATTERN,
                fields -> byMeaning.forEach((meaning, value) ->
                        fields.get(index(pattern, meaning)).setObjectValue(value)));

        PatternEntityVersion dialect = latest(calc, KernelPattern.US_DIALECT_PATTERN);
        // The dialect pattern's one field is named for the dialect itself; it holds the acceptability.
        int acceptability = index(dialect, KometConcept.US_ENGLISH_DIALECT);
        ObservableSemantic dialectSemantic = semantic(composer, description, KernelPattern.US_DIALECT_PATTERN,
                fields -> fields.get(acceptability).setObjectValue(preferred ? KernelConcept.PREFERRED : KometConcept.ACCEPTABLE));
        return new Described(description.nid(), dialectSemantic.nid());
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

    /**
     * Sets one field of a semantic the composer wrote, in the composer's transaction, so the new
     * version replaces the written one; returns false, writing nothing, when it already holds the value.
     */
    private static boolean set(ObservableComposer composer, long semanticNid, int field, Object value) {
        SemanticEntity<?> semantic = (SemanticEntity<?>) EntityHandle.get(semanticNid).expectEntity();
        if (same(versionIn(composer, semantic).fieldValues().get(field), value)) {
            return false;
        }
        EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor = composer.composeSemantic(
                semantic.publicId(), EntityProxy.make(semantic.referencedComponentNid()),
                EntityProxy.Pattern.make(semantic.patternNid()));
        editor.getEditableVersion().getEditableFields().get(field).setObjectValue(value);
        editor.save();
        return true;
    }

    /** The semantic's version saved in the composer's transaction, or its last version when there is none. */
    private static SemanticEntityVersion versionIn(ObservableComposer composer, SemanticEntity<?> semantic) {
        Transaction own = composer.getOrCreateTransaction();
        for (EntityVersion version : semantic.versions()) {
            if (version.uncommitted() && Transaction.forVersion(version).filter(own::equals).isPresent()) {
                return (SemanticEntityVersion) version;
            }
        }
        return semantic.versions().getLast();
    }

    /** Whether the entity carries an uncommitted version of a transaction other than the composer's: the window's. */
    private static boolean editedInWindow(ObservableComposer composer, long nid) {
        Transaction own = composer.getOrCreateTransaction();
        for (EntityVersion version : EntityHandle.get(nid).expectEntity().versions()) {
            if (version.uncommitted() && Transaction.forVersion(version).filter(t -> !t.equals(own)).isPresent()) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(Object stored, Object value) {
        if (stored instanceof EntityFacade a && value instanceof EntityFacade b) {
            return a.nid() == b.nid();
        }
        return Objects.equals(stored, value);
    }

    private static WrittenSynonym existing(Map<String, WrittenSynonym> byTerm, String term) {
        WrittenSynonym synonym = term == null ? null : byTerm.get(term.strip());
        if (synonym == null) {
            throw new IllegalArgumentException("the proposal has no synonym \"" + term + "\"; its synonyms are "
                    + byTerm.keySet());
        }
        return synonym;
    }

    static PatternEntityVersion latest(ViewCalculator calc, PatternFacade pattern) {
        return calc.latestPatternEntityVersion(pattern.nid()).orElseThrow(() ->
                new IllegalStateException(pattern.description() + " has no version on this view"));
    }

    static int index(PatternEntityVersion pattern, ConceptFacade meaning) {
        int index = pattern.indexForMeaning(meaning.nid());
        if (index < 0) {
            List<String> meanings = new ArrayList<>();
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
            if (version.fieldDefinitions().get(i).dataTypeNid() == KernelConcept.STRING.nid()) {
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
