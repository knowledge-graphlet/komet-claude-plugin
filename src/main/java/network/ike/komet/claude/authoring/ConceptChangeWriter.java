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
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Writes changes to an existing concept <em>uncommitted</em>, for a person to review in the
 * Unpublished Changes tray and publish or discard ({@code IKE-Network/ike-issues#1326}). The
 * assistant proposes; it never commits.
 *
 * <p>A {@link Change} holds one {@link ObservableComposer}, and so one transaction, for one
 * concept, on the edit coordinate's author, default module and path, as a KL window's composer
 * is made. The tray groups a change under the concept it is on, and assumes a transaction never
 * spans two of its headings, so one concept's changes are one heading, published or discarded
 * together. Every edit of the concept made through the same change lands in that transaction,
 * each replacing the version the last one saved.
 *
 * <p>A stated-definition edit starts from the stated-axiom semantic's version the composer's
 * editor opens — the latest on the view, or this change's own pending one — and rewrites it
 * surgically ({@link DefinitionRewrite}): operations ({@link Change#editDefinition}) or an
 * "after" block ({@link Change#replaceDefinition}) give the definition to leave, and only what
 * differs is removed or added, so the rest keeps its vertices. An "after" block is refused for
 * a definition holding what the {@code koncept-definition} grammar cannot express, which a
 * rewrite through the grammar would drop.
 *
 * <p>An edit is refused, writing nothing, when the stated definition already carries an
 * unpublished version of another transaction, such as the person's open window: two
 * uncommitted versions of two transactions would commit in whichever order they happened to be
 * published. The check runs before the editor opens, since opening one stamps the transaction.
 *
 * <p>Must run on the JavaFX application thread, as {@link ObservableComposer} requires.
 */
public final class ConceptChangeWriter {

    /**
     * What a definition edit did.
     *
     * @param changed whether it wrote a new version; false when the definition already was what was asked
     * @param before  the definition it started from, as a {@code koncept-definition} block body
     * @param after   the definition it left, as a block body
     * @param notes   what the person should know: parts of the definition the block cannot show, kept as they were
     */
    public record DefinitionEdited(boolean changed, String before, String after, List<String> notes) {
    }

    /**
     * What a description edit did.
     *
     * @param changed whether it wrote anything; false when every description already was what was asked
     * @param changes what changed, one line each, for the person
     * @param added   the descriptions it added, in order
     */
    public record DescriptionsEdited(boolean changed, List<String> changes, List<EntityFacade> added) {
    }

    /**
     * An active description of the concept as the view reads it.
     *
     * @param nid           the description semantic
     * @param type          its description type
     * @param text          its text
     * @param caseSensitive whether its case is significant
     * @param dialect       its active US English acceptability semantic, or null
     * @param preferred     whether that makes it preferred; null with no acceptability
     */
    private record Description(long nid, long type, String text, boolean caseSensitive, Long dialect, Boolean preferred) {
    }

    /** What an edit does to one description; a null part is left as it is. */
    private static final class Planned {
        final Description description;
        boolean retire;
        String text;
        Boolean caseSensitive;
        Boolean preferred;

        Planned(Description description) {
            this.description = description;
        }
    }

    /** Where a change stands. */
    public enum Status {
        /** Nothing written yet. */
        EMPTY,
        /** Written uncommitted: it can be edited further, discarded, or published. */
        OPEN,
        /** Committed by the person's Publish: no longer a change to make. */
        PUBLISHED,
        /** Rolled back: nothing of it remains. */
        DISCARDED
    }

    /**
     * The changes to one concept, in one transaction. Every method must run on the JavaFX
     * application thread.
     */
    public static final class Change {
        private final ViewCalculator calc;
        private final EntityFacade concept;
        private final ObservableComposer composer;
        /** The transaction this change saved into; null until it saves. */
        private Transaction transaction;
        /** The stamp of this change's saved versions; 0 until it saves. */
        private long stampNid;

        private Change(ViewCalculator calc, EntityFacade concept, ObservableComposer composer) {
            this.calc = calc;
            this.concept = concept;
            this.composer = composer;
        }

        /** @return the concept the change is on */
        public EntityFacade concept() {
            return concept;
        }

        /** @return where the change stands */
        public Status status() {
            if (transaction == null) {
                return Status.EMPTY;
            }
            if (transaction.isOpen()) {
                return Status.OPEN;
            }
            return Entity.getStamp(stampNid).time() == Long.MIN_VALUE ? Status.DISCARDED : Status.PUBLISHED;
        }

        /**
         * Changes the stated definition by operations, each naming what it changes by the
         * concepts in it. What no operation names is left as it is stored.
         *
         * @param operations the operations, applied in order
         * @return what the edit did
         * @throws IllegalArgumentException when an operation names what the definition does not
         *                                  hold, holds in more than one place, or already holds,
         *                                  or the edit would leave no definition
         * @throws IllegalStateException    when the change was published or discarded, the concept
         *                                  has no stated definition, or it has an unpublished
         *                                  change of another transaction
         */
        public DefinitionEdited editDefinition(List<DefinitionOperation> operations) {
            return edit(stored -> DefinitionRewrite.apply(stored, operations, calc));
        }

        /**
         * Changes the stated definition to an "after" block: the whole definition to leave, with
         * the parts it shares with the stored one kept as they are.
         *
         * @param block the {@code koncept-definition} block body; a {@code proposed:} line is ignored
         * @return what the edit did
         * @throws IllegalArgumentException when the block is malformed, names a concept the
         *                                  knowledge base does not hold or states a gap, or would
         *                                  leave no definition
         * @throws IllegalStateException    when the stored definition holds what the block's
         *                                  grammar cannot express, or as for {@link #editDefinition}
         */
        public DefinitionEdited replaceDefinition(String block) {
            Proposal proposal = block == null ? null : ProposedDefinition.parse(block);
            if (proposal == null) {
                throw new IllegalArgumentException("the definition is not a well-formed koncept-definition block");
            }
            DefinitionShape target = DefinitionShape.of(proposal);
            return edit(stored -> {
                if (!stored.expressible()) {
                    throw new IllegalStateException("the stated definition of " + name() + " holds " + stored.opaqueParts()
                            + " part(s) a koncept-definition block cannot express (a property set, feature, interval "
                            + "role, disjoint, or a universal or nested role), which replacing it with a block would "
                            + "drop; change it with operations instead");
                }
                DefinitionRewrite.align(stored, target);
                return target;
            });
        }

        private DefinitionEdited edit(UnaryOperator<DefinitionShape> targetOf) {
            refuseIfEnded();
            SemanticEntity<SemanticEntityVersion> stated = stated();
            refuseOtherPendingVersions(stated.nid(), "the stated definition of " + name());

            EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor = composer.composeSemantic(
                    stated.publicId(), concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN);
            boolean saved = false;
            try {
                ObservableField.Editable<?> field = editor.getEditableVersion().getEditableFields().getFirst();
                if (!(field.getValue() instanceof DiTreeEntity tree)) {
                    throw new IllegalStateException("the stated definition of " + name() + " holds no definition tree");
                }
                DefinitionShape stored = DefinitionShape.of(tree);
                DefinitionShape target = targetOf.apply(stored);
                if (target.isEmpty()) {
                    throw new IllegalArgumentException("the edit would leave " + name() + " with no stated definition");
                }
                List<String> notes = new ArrayList<>();
                if (stored.opaqueParts() > 0) {
                    notes.add("the definition also holds " + stored.opaqueParts() + " part(s) a koncept-definition "
                            + "block cannot show; they are kept as they were");
                }
                String before = stored.block(calc);
                DiTreeEntity rewritten = DefinitionRewrite.rewrite(tree, stored, target);
                if (rewritten == null) {
                    return new DefinitionEdited(false, before, before, List.copyOf(notes));
                }
                field.setObjectValue(rewritten);
                editor.save();
                saved = true;
                remember(stated.nid());
                return new DefinitionEdited(true, before, DefinitionShape.of(rewritten).block(calc), List.copyOf(notes));
            } finally {
                if (!saved) {
                    // Opening the editor stamped the transaction; an edit that wrote nothing leaves nothing.
                    composer.discardUnsavedEdits(stated.nid());
                }
            }
        }

        /**
         * Changes the concept's descriptions: retires them, changes their text, case significance
         * or US English acceptability, or adds synonyms and textual definitions. Every operation is
         * checked before anything is written, so a refused edit writes nothing; several operations
         * on one description are written as one version of it. A retired description's
         * acceptability is retired with it.
         *
         * <p>An edit that retires a synonym, changes an acceptability, or adds a synonym must leave
         * exactly one preferred synonym in US English: to change which one, demote the old and
         * promote the new in the same edit. The last fully specified name is not retired.
         *
         * @param operations the operations
         * @return what the edit did
         * @throws IllegalArgumentException when an operation names what is not an active
         *                                  description of the concept, retires one it also
         *                                  changes, gives blank text, adds a description the
         *                                  concept already has, or would leave other than one
         *                                  preferred synonym or no fully specified name
         * @throws IllegalStateException    when the change was published or discarded, or a
         *                                  description it changes has an unpublished change of
         *                                  another transaction
         */
        public DescriptionsEdited editDescriptions(List<DescriptionOperation> operations) {
            refuseIfEnded();
            Map<Long, Description> active = descriptions();
            Map<Long, Planned> planned = new LinkedHashMap<>();
            List<DescriptionOperation> additions = new ArrayList<>();
            for (DescriptionOperation operation : operations) {
                switch (operation) {
                    case DescriptionOperation.Retire(EntityFacade description) -> plan(planned, active, description).retire = true;
                    case DescriptionOperation.ChangeText(EntityFacade description, String text) ->
                            plan(planned, active, description).text = nonBlank(text);
                    case DescriptionOperation.ChangeCaseSignificance(EntityFacade description, boolean caseSensitive) ->
                            plan(planned, active, description).caseSensitive = caseSensitive;
                    case DescriptionOperation.ChangeAcceptability(EntityFacade description, boolean preferred) ->
                            plan(planned, active, description).preferred = preferred;
                    case DescriptionOperation.AddSynonym synonym -> additions.add(
                            new DescriptionOperation.AddSynonym(nonBlank(synonym.text()), synonym.preferred(), synonym.caseSensitive()));
                    case DescriptionOperation.AddTextualDefinition definition -> additions.add(
                            new DescriptionOperation.AddTextualDefinition(nonBlank(definition.text())));
                }
            }
            check(active, planned, additions);
            for (Planned plan : planned.values()) {
                refuseOtherPendingVersions(plan.description.nid(), "the description \"" + plan.description.text() + "\"");
                if (plan.description.dialect() != null) {
                    refuseOtherPendingVersions(plan.description.dialect(), "the acceptability of \"" + plan.description.text() + "\"");
                }
            }

            List<String> changes = new ArrayList<>();
            List<EntityFacade> added = new ArrayList<>();
            Set<Long> opened = new LinkedHashSet<>();
            Set<Long> saved = new LinkedHashSet<>();
            try {
                for (Planned plan : planned.values()) {
                    write(plan, changes, opened, saved);
                }
                for (DescriptionOperation addition : additions) {
                    ConceptProposalWriter.Described written = switch (addition) {
                        case DescriptionOperation.AddSynonym(String text, boolean preferred, boolean caseSensitive) -> {
                            changes.add("added the synonym \"" + text + "\", " + (preferred ? "preferred" : "acceptable"));
                            yield ConceptProposalWriter.description(composer, calc, concept, text, caseSensitive,
                                    KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE, preferred);
                        }
                        case DescriptionOperation.AddTextualDefinition(String text) -> {
                            changes.add("added a textual definition");
                            yield ConceptProposalWriter.description(composer, calc, concept, text, false,
                                    KernelConcept.DEFINITION_DESCRIPTION_TYPE, true);
                        }
                        default -> throw new IllegalStateException("not an addition: " + addition);
                    };
                    added.add(EntityProxy.Semantic.make(written.description()));
                    saved.add(written.description());
                }
            } finally {
                // Opening an editor stamped the transaction; a description it wrote nothing to keeps nothing.
                opened.stream().filter(nid -> !saved.contains(nid)).forEach(composer::discardUnsavedEdits);
            }
            if (!saved.isEmpty()) {
                remember(saved.iterator().next());
            }
            return new DescriptionsEdited(!saved.isEmpty(), List.copyOf(changes), List.copyOf(added));
        }

        /** Writes one description's planned changes: a retirement, or its fields and acceptability. */
        private void write(Planned plan, List<String> changes, Set<Long> opened, Set<Long> saved) {
            Description description = plan.description;
            EntityFacade facade = EntityProxy.Semantic.make(description.nid());
            if (plan.retire) {
                composer.composeSemantic(facade.publicId(), concept, KernelPattern.DESCRIPTION_PATTERN, State.INACTIVE).save();
                saved.add(description.nid());
                opened.add(description.nid());
                if (description.dialect() != null) {
                    composer.composeSemantic(EntityProxy.Semantic.make(description.dialect()).publicId(), facade,
                            KernelPattern.US_DIALECT_PATTERN, State.INACTIVE).save();
                    saved.add(description.dialect());
                    opened.add(description.dialect());
                }
                changes.add("retired \"" + description.text() + "\"");
                return;
            }
            if (plan.text != null || plan.caseSensitive != null) {
                PatternEntityVersion pattern = ConceptProposalWriter.latest(calc, KernelPattern.DESCRIPTION_PATTERN);
                EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor =
                        composer.composeSemantic(facade.publicId(), concept, KernelPattern.DESCRIPTION_PATTERN);
                opened.add(description.nid());
                var fields = editor.getEditableVersion().getEditableFields();
                if (plan.text != null) {
                    fields.get(ConceptProposalWriter.index(pattern, KernelConcept.TEXT_FOR_DESCRIPTION)).setObjectValue(plan.text);
                }
                if (plan.caseSensitive != null) {
                    fields.get(ConceptProposalWriter.index(pattern, KernelConcept.DESCRIPTION_CASE_SIGNIFICANCE)).setObjectValue(
                            plan.caseSensitive ? KernelConcept.DESCRIPTION_CASE_SENSITIVE : KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE);
                }
                if (editor.hasUnsavedChanges()) {
                    editor.save();
                    saved.add(description.nid());
                    if (plan.text != null && !plan.text.equals(description.text())) {
                        changes.add("\"" + description.text() + "\" now reads \"" + plan.text + "\"");
                    }
                    if (plan.caseSensitive != null && plan.caseSensitive != description.caseSensitive()) {
                        changes.add("\"" + description.text() + "\" is now case " + (plan.caseSensitive ? "sensitive" : "insensitive"));
                    }
                }
            }
            if (plan.preferred != null && !plan.preferred.equals(description.preferred())) {
                Object acceptability = plan.preferred ? KernelConcept.PREFERRED : KometConcept.ACCEPTABLE;
                int field = ConceptProposalWriter.index(ConceptProposalWriter.latest(calc, KernelPattern.US_DIALECT_PATTERN),
                        KometConcept.US_ENGLISH_DIALECT);
                EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor = description.dialect() == null
                        ? composer.composeSemantic(PublicIds.newRandom(), facade, KernelPattern.US_DIALECT_PATTERN)
                        : composer.composeSemantic(EntityProxy.Semantic.make(description.dialect()).publicId(), facade,
                                KernelPattern.US_DIALECT_PATTERN);
                editor.getEditableVersion().getEditableFields().get(field).setObjectValue(acceptability);
                editor.save();
                long dialect = editor.getEntity().nid();
                opened.add(dialect);
                saved.add(dialect);
                changes.add("\"" + description.text() + "\" is now " + (plan.preferred ? "preferred" : "acceptable"));
            }
        }

        /**
         * Checks the planned changes and additions against the concept's active descriptions:
         * a retired description changes nothing else, the last fully specified name stays, an
         * addition repeats no active description, and an edit touching acceptability leaves one
         * preferred synonym.
         */
        private void check(Map<Long, Description> active, Map<Long, Planned> planned, List<DescriptionOperation> additions) {
            for (Planned plan : planned.values()) {
                if (plan.retire && (plan.text != null || plan.caseSensitive != null || plan.preferred != null)) {
                    throw new IllegalArgumentException("\"" + plan.description.text() + "\" is both retired and changed");
                }
            }
            long names = active.values().stream()
                    .filter(description -> description.type() == KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.nid())
                    .filter(description -> !planned.containsKey(description.nid()) || !planned.get(description.nid()).retire)
                    .count();
            boolean hadName = active.values().stream()
                    .anyMatch(description -> description.type() == KernelConcept.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.nid());
            if (hadName && names == 0) {
                throw new IllegalArgumentException("the edit would retire the last fully specified name of " + name());
            }
            for (DescriptionOperation addition : additions) {
                long type = addition instanceof DescriptionOperation.AddSynonym
                        ? KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid() : KernelConcept.DEFINITION_DESCRIPTION_TYPE.nid();
                String text = switch (addition) {
                    case DescriptionOperation.AddSynonym synonym -> synonym.text();
                    case DescriptionOperation.AddTextualDefinition definition -> definition.text();
                    default -> "";
                };
                if (active.values().stream().anyMatch(description -> description.type() == type && description.text().equals(text))) {
                    throw new IllegalArgumentException(name() + " already has the " + (type == KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid()
                            ? "synonym" : "textual definition") + " \"" + text + "\"");
                }
            }
            boolean touchesPreferred = additions.stream().anyMatch(DescriptionOperation.AddSynonym.class::isInstance)
                    || planned.values().stream().anyMatch(plan -> plan.preferred != null
                    || plan.retire && plan.description.type() == KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid());
            if (touchesPreferred) {
                long preferred = active.values().stream()
                        .filter(description -> description.type() == KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid())
                        .filter(description -> {
                            Planned plan = planned.get(description.nid());
                            if (plan != null && plan.retire) {
                                return false;
                            }
                            Boolean now = plan != null && plan.preferred != null ? plan.preferred : description.preferred();
                            return Boolean.TRUE.equals(now);
                        }).count()
                        + additions.stream().filter(addition -> addition instanceof DescriptionOperation.AddSynonym synonym
                        && synonym.preferred()).count();
                if (preferred != 1) {
                    throw new IllegalArgumentException("the edit would leave " + preferred + " preferred synonyms; a concept "
                            + "has exactly one preferred synonym in US English, so make one preferred and the others "
                            + "acceptable in the same edit");
                }
            }
        }

        /** The plan for a description an operation names, which must be an active description of the concept. */
        private Planned plan(Map<Long, Planned> planned, Map<Long, Description> active, EntityFacade description) {
            Description found = active.get(description.nid());
            if (found == null) {
                throw new IllegalArgumentException(ComponentText.identifier(description.nid()) + " is not an active "
                        + "description of " + name() + "; its descriptions are " + active.values().stream()
                        .map(d -> "\"" + d.text() + "\" " + ComponentText.identifier(d.nid())).toList());
            }
            return planned.computeIfAbsent(found.nid(), nid -> new Planned(found));
        }

        /** The concept's active descriptions on the view, with their US English acceptability. */
        private Map<Long, Description> descriptions() {
            PatternEntityVersion pattern = ConceptProposalWriter.latest(calc, KernelPattern.DESCRIPTION_PATTERN);
            int text = ConceptProposalWriter.index(pattern, KernelConcept.TEXT_FOR_DESCRIPTION);
            int type = ConceptProposalWriter.index(pattern, KernelConcept.DESCRIPTION_TYPE);
            int caseSignificance = ConceptProposalWriter.index(pattern, KernelConcept.DESCRIPTION_CASE_SIGNIFICANCE);
            int acceptability = ConceptProposalWriter.index(ConceptProposalWriter.latest(calc, KernelPattern.US_DIALECT_PATTERN),
                    KometConcept.US_ENGLISH_DIALECT);
            Map<Long, Description> descriptions = new LinkedHashMap<>();
            EntityService.get().semanticsForComponentOfPattern(concept.nid(), KernelPattern.DESCRIPTION_PATTERN.nid()).forEach(semantic -> {
                Latest<SemanticEntityVersion> latest = calc.latest(semantic.nid());
                if (!latest.isPresent() || latest.get().state() != State.ACTIVE) {
                    return;
                }
                var fields = latest.get().fieldValues();
                Long dialect = null;
                Boolean preferred = null;
                for (SemanticEntity<SemanticEntityVersion> candidate : EntityService.get()
                        .semanticsForComponentOfPattern(semantic.nid(), KernelPattern.US_DIALECT_PATTERN.nid()).toList()) {
                    Latest<SemanticEntityVersion> dialectLatest = calc.latest(candidate.nid());
                    if (dialectLatest.isPresent() && dialectLatest.get().state() == State.ACTIVE) {
                        dialect = candidate.nid();
                        preferred = dialectLatest.get().fieldValues().get(acceptability) instanceof EntityFacade value
                                && value.nid() == KernelConcept.PREFERRED.nid();
                    }
                }
                descriptions.put(semantic.nid(), new Description(semantic.nid(),
                        fields.get(type) instanceof EntityFacade value ? value.nid() : 0L,
                        String.valueOf(fields.get(text)),
                        fields.get(caseSignificance) instanceof EntityFacade value
                                && value.nid() == KernelConcept.DESCRIPTION_CASE_SENSITIVE.nid(),
                        dialect, preferred));
            });
            return descriptions;
        }

        private static String nonBlank(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("a description's text cannot be blank");
            }
            return text.strip();
        }

        /** The concept's one stated-axiom semantic. */
        private SemanticEntity<SemanticEntityVersion> stated() {
            List<SemanticEntity<SemanticEntityVersion>> stated = EntityService.get()
                    .semanticsForComponentOfPattern(concept.nid(), KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid())
                    .filter(semantic -> !semantic.canceled()).toList();
            if (stated.isEmpty()) {
                throw new IllegalStateException(name() + " has no stated definition to change");
            }
            if (stated.size() > 1) {
                throw new IllegalStateException(name() + " has " + stated.size() + " stated definitions; it is not "
                        + "clear which to change");
            }
            return stated.getFirst();
        }

        /** Refuses a change that was published or discarded. */
        private void refuseIfEnded() {
            Status status = status();
            if (status == Status.PUBLISHED || status == Status.DISCARDED) {
                throw new IllegalStateException("this change to " + name() + " was " + status.name().toLowerCase()
                        + "; start a new change to change it again");
            }
        }

        /** Refuses a semantic with an unpublished version of any transaction but this change's. */
        private void refuseOtherPendingVersions(long semanticNid, String what) {
            for (EntityVersion version : EntityHandle.get(semanticNid).expectEntity().versions()) {
                if (!version.uncommitted()) {
                    continue;
                }
                Optional<Transaction> owner = Transaction.forVersion(version);
                if (owner.isEmpty() || owner.get() != transaction) {
                    throw new IllegalStateException(what + " already has an unpublished change from another window or "
                            + "session; publish or discard it in the Unpublished Changes tray first. Nothing was written");
                }
            }
        }

        /** Remembers the transaction and stamp this change saved into. */
        private void remember(long semanticNid) {
            transaction = composer.getOrCreateTransaction();
            for (EntityVersion version : EntityHandle.get(semanticNid).expectEntity().versions()) {
                if (version.uncommitted() && Transaction.forVersion(version).filter(t -> t == transaction).isPresent()) {
                    stampNid = version.stampNid();
                }
            }
        }

        private String name() {
            return ComponentText.name(calc, concept.nid());
        }
    }

    private ConceptChangeWriter() {
    }

    /**
     * Opens a change to an existing concept. Nothing is written until an edit is made.
     *
     * @param calc      the view the change reads the concept on
     * @param author    the author of the change's versions — the edit coordinate's
     * @param module    the module they are written in
     * @param path      the path they are written on
     * @param concept   the existing concept
     * @param rationale why the change is proposed, kept in the transaction comment; may be null
     * @return the change
     */
    public static Change open(ViewCalculator calc, EntityFacade author, EntityFacade module, EntityFacade path,
                              EntityFacade concept, String rationale) {
        String comment = "Assistant change: " + ComponentText.name(calc, concept.nid())
                + (rationale == null || rationale.isBlank() ? "" : ": " + rationale.strip());
        return new Change(calc, concept, ObservableComposer.create(calc, State.ACTIVE, author, module, path, comment));
    }

    /**
     * Discards a change that is still uncommitted: its transaction is rolled back, and the concept
     * is as it was before. A change the person already published is left alone.
     *
     * @param change the change
     * @return true when it was discarded; false when nothing was written, or it was already
     *         published or discarded
     */
    public static boolean discard(Change change) {
        if (change.status() != Status.OPEN) {
            return false;
        }
        change.composer.rollback();
        return true;
    }
}
