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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.authoring.ProposedDefinition.Proposal;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
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
            Status status = status();
            if (status == Status.PUBLISHED || status == Status.DISCARDED) {
                throw new IllegalStateException("this change to " + name() + " was " + status.name().toLowerCase()
                        + "; start a new change to change it again");
            }
            SemanticEntity<SemanticEntityVersion> stated = stated();
            refuseOtherPendingVersions(stated);

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
                remember(stated);
                return new DefinitionEdited(true, before, DefinitionShape.of(rewritten).block(calc), List.copyOf(notes));
            } finally {
                if (!saved) {
                    // Opening the editor stamped the transaction; an edit that wrote nothing leaves nothing.
                    composer.discardUnsavedEdits(stated.nid());
                }
            }
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

        /** Refuses a semantic with an unpublished version of any transaction but this change's. */
        private void refuseOtherPendingVersions(SemanticEntity<SemanticEntityVersion> semantic) {
            for (EntityVersion version : EntityHandle.get(semantic.nid()).expectEntity().versions()) {
                if (!version.uncommitted()) {
                    continue;
                }
                Optional<Transaction> owner = Transaction.forVersion(version);
                if (owner.isEmpty() || owner.get() != transaction) {
                    throw new IllegalStateException("the stated definition of " + name() + " already has an unpublished "
                            + "change from another window or session; publish or discard it in the Unpublished Changes "
                            + "tray first. Nothing was written");
                }
            }
        }

        /** Remembers the transaction and stamp this change saved into. */
        private void remember(SemanticEntity<?> semantic) {
            transaction = composer.getOrCreateTransaction();
            for (EntityVersion version : EntityHandle.get(semantic.nid()).expectEntity().versions()) {
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
