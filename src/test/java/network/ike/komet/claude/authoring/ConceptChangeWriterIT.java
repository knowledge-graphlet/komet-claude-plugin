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
import dev.ikm.komet.framework.observable.ObservableSemantic;
import dev.ikm.komet.framework.observable.ObservableSemanticVersion;
import dev.ikm.komet.kview.mvvm.view.changes.UnpublishedChange;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalAxiom;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpressionBuilder;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import javafx.application.Platform;
import network.ike.komet.claude.authoring.ConceptChangeWriter.Change;
import network.ike.komet.claude.authoring.ConceptChangeWriter.DefinitionEdited;
import network.ike.komet.claude.authoring.ConceptChangeWriter.DescriptionsEdited;
import network.ike.komet.claude.authoring.DescriptionOperation.AddSynonym;
import network.ike.komet.claude.authoring.DescriptionOperation.AddTextualDefinition;
import network.ike.komet.claude.authoring.DescriptionOperation.ChangeAcceptability;
import network.ike.komet.claude.authoring.DescriptionOperation.ChangeCaseSignificance;
import network.ike.komet.claude.authoring.DescriptionOperation.ChangeText;
import network.ike.komet.claude.authoring.DescriptionOperation.Retire;
import network.ike.komet.claude.authoring.ConceptChangeWriter.Status;
import network.ike.komet.claude.authoring.DefinitionOperation.AddSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.AddToGroup;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveRole;
import network.ike.komet.claude.authoring.DefinitionOperation.RemoveSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.ReplaceSupertype;
import network.ike.komet.claude.authoring.DefinitionOperation.SetKind;
import network.ike.komet.claude.authoring.DefinitionOperation.SetSelector;
import network.ike.komet.claude.authoring.DefinitionShape.Group;
import network.ike.komet.claude.authoring.DefinitionShape.Role;
import network.ike.komet.claude.authoring.DefinitionShape.RoleNode;
import network.ike.komet.claude.authoring.DefinitionShape.SetNode;
import network.ike.komet.claude.authoring.DefinitionShape.Supertype;
import network.ike.komet.claude.koncept.ComponentText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Changes to an existing concept's stated definition, written uncommitted for the person to
 * publish or discard ({@code IKE-Network/ike-issues#1326}), against the starter set in a
 * memory-only store.
 *
 * <p>Each test defines a concept of its own and commits it, then changes it: the fixture
 * definition is a necessary set of one supertype, one ungrouped role and one role group of two
 * roles.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConceptChangeWriterIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    // The fixture's parts: kernel concepts every starter set holds, standing in for a domain's.
    private static final ConceptFacade SUPERTYPE = KernelConcept.LANGUAGE;
    private static final ConceptFacade OTHER_SUPERTYPE = KernelConcept.ENGLISH_LANGUAGE;
    private static final DefinitionOperation.Role UNGROUPED = new DefinitionOperation.Role(
            KernelConcept.LANGUAGE_CONCEPT_NID_FOR_DESCRIPTION, KernelConcept.ENGLISH_LANGUAGE);
    private static final DefinitionOperation.Role GROUPED_CASE = new DefinitionOperation.Role(
            KernelConcept.DESCRIPTION_CASE_SIGNIFICANCE, KernelConcept.DESCRIPTION_CASE_SENSITIVE);
    private static final DefinitionOperation.Role GROUPED_TYPE = new DefinitionOperation.Role(
            KernelConcept.DESCRIPTION_TYPE, KernelConcept.PREFERRED);

    private ViewCalculator view;

    @BeforeAll
    void setup() {
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        view = Calculators.View.Default();
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void operationsChangeOnlyWhatTheyNameAndKeepTheRestsVertices() {
        EntityFacade concept = fixture("Operations fixture (qualifier value)");
        DefinitionShape before = shape(concept);
        Change change = open(concept);

        DefinitionEdited edited = onFx(() -> change.editDefinition(List.of(
                new ReplaceSupertype(SUPERTYPE, OTHER_SUPERTYPE),
                new AddToGroup(List.of(GROUPED_CASE, GROUPED_TYPE), UNGROUPED))));

        assertTrue(edited.changed());
        assertEquals(Status.OPEN, change.status());
        assertTrue(edited.before().contains(token(SUPERTYPE.nid())), edited.before());
        assertTrue(edited.after().contains(token(OTHER_SUPERTYPE.nid())), edited.after());
        assertFalse(edited.after().contains(token(SUPERTYPE.nid())), edited.after());

        SemanticEntity<SemanticEntityVersion> stated = stated(concept);
        assertTrue(entity(stated.nid()).uncommitted(), "the stated definition has an unpublished version");
        assertFalse(entity(concept.nid()).uncommitted(), "the concept itself is not changed");

        DefinitionShape after = shape(concept);
        SetNode was = before.sets.getFirst();
        SetNode now = after.sets.getFirst();
        assertEquals(1, after.sets.size());
        assertEquals(was.origin, now.origin, "the set keeps its vertex");
        assertEquals(was.andVertex, now.andVertex, "the set's And keeps its vertex");
        assertEquals(List.of(OTHER_SUPERTYPE.nid()), now.supertypes.stream().map(Supertype::concept).toList());
        assertNotEquals(was.supertypes.getFirst().vertex(), now.supertypes.getFirst().vertex(),
                "a replaced supertype is a new operand");
        assertEquals(was.roles, now.roles, "the ungrouped role keeps its vertices");

        Group wasGroup = was.groups.getFirst();
        Group nowGroup = now.groups.getFirst();
        assertEquals(wasGroup.origin, nowGroup.origin, "the group keeps its vertex");
        assertEquals(wasGroup.andVertex, nowGroup.andVertex);
        assertTrue(nowGroup.roles.containsAll(wasGroup.roles), "the group's roles keep their vertices");
        assertEquals(Set.of(role(UNGROUPED), role(GROUPED_CASE), role(GROUPED_TYPE)),
                Set.copyOf(nowGroup.roles.stream().map(RoleNode::role).toList()));

        UnpublishedChange row = UnpublishedChange.of(entity(stated.nid()), view);
        assertEquals(concept.nid(), row.rootNid(), "the tray lists the change under the concept");
        assertEquals(UnpublishedChange.Kind.EDITED, row.kind());
    }

    @Test
    void editsThroughOneChangeLandInOneTransactionAndOneVersion() {
        EntityFacade concept = fixture("Two edits fixture (qualifier value)");
        Change change = open(concept);

        onFx(() -> change.editDefinition(List.of(new RemoveRole(UNGROUPED))));
        onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));

        List<EntityVersion> pending = uncommitted(stated(concept));
        assertEquals(1, pending.size(), "the second edit replaced the first's version");
        DefinitionShape after = shape(concept);
        assertTrue(after.sets.getFirst().roles.isEmpty(), "the first edit stands");
        assertEquals(Set.of(SUPERTYPE.nid(), OTHER_SUPERTYPE.nid()),
                Set.copyOf(after.sets.getFirst().supertypes.stream().map(Supertype::concept).toList()));
    }

    @Test
    void publishingCommitsTheChangeAndEndsIt() {
        EntityFacade concept = fixture("Publish fixture (qualifier value)");
        Change change = open(concept);
        onFx(() -> change.editDefinition(List.of(new RemoveSupertype(SUPERTYPE),
                new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));

        // The tray's Publish of the concept's heading: commit the transactions its versions are in.
        SemanticEntity<SemanticEntityVersion> stated = stated(concept);
        onFx(() -> {
            transactionsOf(stated.nid()).forEach(Transaction::commit);
            return null;
        });

        assertFalse(entity(stated.nid()).uncommitted());
        assertEquals(Status.PUBLISHED, change.status());
        assertEquals(List.of(OTHER_SUPERTYPE.nid()),
                shape(concept).sets.getFirst().supertypes.stream().map(Supertype::concept).toList());
        assertRefused(IllegalStateException.class,
                () -> onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, SUPERTYPE)))),
                "a published change is not edited again");
    }

    @Test
    void discardingRestoresTheCommittedDefinition() {
        EntityFacade concept = fixture("Discard fixture (qualifier value)");
        DefinitionShape before = shape(concept);
        Change change = open(concept);
        onFx(() -> change.editDefinition(List.of(new RemoveSupertype(SUPERTYPE),
                new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));

        assertTrue(onFx(() -> ConceptChangeWriter.discard(change)));

        assertFalse(entity(stated(concept).nid()).uncommitted(), "nothing of the change is left unpublished");
        assertEquals(Status.DISCARDED, change.status());
        assertEquals(before.sets.getFirst().supertypes, shape(concept).sets.getFirst().supertypes,
                "the committed definition stands, vertices and all");
        assertFalse(onFx(() -> ConceptChangeWriter.discard(change)), "a discarded change is not discarded twice");
    }

    @Test
    void anAfterBlockIsAppliedSurgically() {
        EntityFacade concept = fixture("After block fixture (qualifier value)");
        DefinitionShape before = shape(concept);
        Change change = open(concept);

        // The ungrouped role goes; the grouped description type takes a new filler.
        String block = "necessary\n"
                + "  " + token(SUPERTYPE.nid()) + "\n"
                + "  group\n"
                + "    " + line(GROUPED_CASE) + "\n"
                + "    " + token(GROUPED_TYPE.type().nid()) + " = " + token(KometConcept.ACCEPTABLE.nid()) + "\n";
        DefinitionEdited edited = onFx(() -> change.replaceDefinition(block));
        assertTrue(edited.changed());

        SetNode was = before.sets.getFirst();
        SetNode now = shape(concept).sets.getFirst();
        assertEquals(was.origin, now.origin, "the set keeps its vertex");
        assertEquals(was.supertypes, now.supertypes, "the kept supertype keeps its vertex");
        assertTrue(now.roles.isEmpty(), "the ungrouped role is gone");

        Group wasGroup = was.groups.getFirst();
        Group nowGroup = now.groups.getFirst();
        assertEquals(wasGroup.origin, nowGroup.origin, "the group keeps its vertex");
        RoleNode wasType = node(wasGroup, GROUPED_TYPE.type().nid());
        RoleNode nowType = node(nowGroup, GROUPED_TYPE.type().nid());
        assertEquals(KometConcept.ACCEPTABLE.nid(), nowType.role().filler());
        assertEquals(wasType.roleVertex(), nowType.roleVertex(), "a role given a new filler keeps its vertex");
        assertEquals(wasType.fillerVertex(), nowType.fillerVertex(), "and its filler's");
        assertEquals(node(wasGroup, GROUPED_CASE.type().nid()), node(nowGroup, GROUPED_CASE.type().nid()));
    }

    @Test
    void aSetChangingKindKeepsWhatItHoldsAndIsANewSet() {
        EntityFacade concept = fixture("Kind fixture (qualifier value)");
        DefinitionShape before = shape(concept);
        Change change = open(concept);

        assertTrue(onFx(() -> change.editDefinition(List.of(new SetKind(SetSelector.NECESSARY, true)))).changed());

        SetNode was = before.sets.getFirst();
        SetNode now = shape(concept).sets.getFirst();
        assertTrue(now.sufficient);
        assertEquals(was.supertypes.stream().map(Supertype::concept).toList(), now.supertypes.stream().map(Supertype::concept).toList());
        assertEquals(was.roles.stream().map(RoleNode::role).toList(), now.roles.stream().map(RoleNode::role).toList());
        assertEquals(Set.copyOf(was.groups.getFirst().roles.stream().map(RoleNode::role).toList()),
                Set.copyOf(now.groups.getFirst().roles.stream().map(RoleNode::role).toList()));
        // A set's kind is its identity among the definition root's operands (IKE-Network/ike-issues#1310).
        assertNotEquals(was.origin, now.origin, "a set of another kind is a new set");
    }

    @Test
    void anAfterBlockIsRefusedForWhatTheGrammarCannotExpressButOperationsKeepIt() {
        LogicalExpressionBuilder builder = new LogicalExpressionBuilder();
        builder.NecessarySet(builder.And(builder.ConceptAxiom(SUPERTYPE), builder.DisjointWithAxiom(OTHER_SUPERTYPE)));
        EntityFacade concept = fixture("Disjoint fixture (qualifier value)", tree(builder));
        UUID disjoint = disjointVertex(concept);
        Change change = open(concept);

        assertRefused(IllegalStateException.class,
                () -> onFx(() -> change.replaceDefinition("necessary\n  " + token(SUPERTYPE.nid()) + "\n")),
                "the block would drop the disjoint");
        assertFalse(entity(stated(concept).nid()).uncommitted(), "a refused edit writes nothing");
        assertEquals(Status.EMPTY, change.status());

        DefinitionEdited edited = onFx(() -> change.editDefinition(
                List.of(new AddSupertype(SetSelector.NECESSARY, KernelConcept.DESCRIPTION_TYPE))));
        assertTrue(edited.changed());
        assertEquals(1, edited.notes().size(), edited.notes().toString());
        assertEquals(disjoint, disjointVertex(concept), "the disjoint is kept, vertex and all");
    }

    @Test
    void aDefinitionWithAnotherModulesPendingVersionIsRefused() {
        EntityFacade concept = fixture("Window fixture (qualifier value)");
        SemanticEntity<SemanticEntityVersion> stated = stated(concept);

        // A window edits the definition in another module, through a composer, and transaction, of its own.
        LogicalExpressionBuilder builder = new LogicalExpressionBuilder();
        builder.NecessarySet(builder.And(builder.ConceptAxiom(OTHER_SUPERTYPE)));
        DiTreeEntity windowTree = tree(builder);
        onFx(() -> {
            ObservableComposer window = ObservableComposer.create(view, State.ACTIVE, KernelConcept.USER,
                    KernelConcept.SOLOR_MODULE, KernelConcept.DEVELOPMENT_PATH, "The window").joinPendingWork();
            ObservableComposer.EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor =
                    window.composeSemantic(stated.publicId(), concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN);
            editor.getEditableVersion().getEditableFields().getFirst().setObjectValue(windowTree);
            editor.save();
            return null;
        });
        Set<Transaction> windowTransactions = transactionsOf(stated.nid());

        Change change = open(concept);
        assertRefused(IllegalStateException.class,
                () -> onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, SUPERTYPE)))),
                "another module's pending edit is not written over");
        assertEquals(Status.EMPTY, change.status());
        assertEquals(windowTransactions, transactionsOf(stated.nid()), "only the window's transaction holds a version");
        assertEquals(1, uncommitted(stated).size());
    }

    @Test
    void anEditThatChangesNothingWritesNothing() {
        EntityFacade concept = fixture("Unchanged fixture (qualifier value)");
        Change change = open(concept);

        DefinitionEdited edited = onFx(() -> change.replaceDefinition(shape(concept).block(view)));

        assertFalse(edited.changed());
        assertEquals(edited.before(), edited.after());
        assertEquals(Status.EMPTY, change.status());
        assertFalse(entity(stated(concept).nid()).uncommitted());
    }

    @Test
    void anOperationOnWhatTheDefinitionDoesNotHoldIsRefused() {
        EntityFacade concept = fixture("Refusal fixture (qualifier value)");
        Change change = open(concept);

        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDefinition(List.of(new RemoveSupertype(OTHER_SUPERTYPE)))),
                "not a supertype");
        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDefinition(List.of(new SetKind(SetSelector.SUFFICIENT, false)))),
                "no sufficient set");
        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDefinition(List.of(new RemoveSupertype(SUPERTYPE),
                        new RemoveRole(UNGROUPED),
                        new DefinitionOperation.RemoveGroup(List.of(GROUPED_CASE, GROUPED_TYPE))))),
                "nothing would be left");
        assertEquals(Status.EMPTY, change.status());
        assertFalse(entity(stated(concept).nid()).uncommitted());
    }

    // ── descriptions ────────────────────────────────────────────────────

    @Test
    void retiringASynonymRetiresItsAcceptabilityInTheChangesTransaction() {
        EntityFacade concept = describedFixture("Retire fixture");
        EntityFacade alternative = description(concept, "Retire fixture alternative");
        Change change = open(concept);

        DescriptionsEdited edited = onFx(() -> change.editDescriptions(List.of(new Retire(alternative))));

        assertTrue(edited.changed(), edited.toString());
        assertEquals(State.INACTIVE, pendingState(alternative.nid()));
        long dialect = dialect(alternative.nid());
        assertEquals(State.INACTIVE, pendingState(dialect), "its acceptability is retired with it");
        assertEquals(transactionsOf(alternative.nid()), Set.of(transactionOf(dialect)), "one transaction holds both");
        UnpublishedChange row = UnpublishedChange.of(entity(alternative.nid()), view);
        assertEquals(concept.nid(), row.rootNid());
        assertEquals(UnpublishedChange.Kind.INACTIVATED, row.kind());

        onFx(() -> {
            transactionsOf(concept.nid()).forEach(Transaction::commit);
            return null;
        });
        assertEquals(Status.PUBLISHED, change.status());
        assertFalse(activeTexts(concept).contains("Retire fixture alternative"));
    }

    @Test
    void thePreferredSynonymChangesWhenTheOldIsDemotedAndTheNewPromotedTogether() {
        EntityFacade concept = describedFixture("Swap fixture");
        EntityFacade preferred = description(concept, "Swap fixture");
        EntityFacade alternative = description(concept, "Swap fixture alternative");
        Change change = open(concept);

        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new ChangeAcceptability(alternative, true)))),
                "two preferred synonyms");
        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new Retire(preferred)))),
                "no preferred synonym");
        assertEquals(Status.EMPTY, change.status(), "a refused edit writes nothing");

        onFx(() -> change.editDescriptions(List.of(new ChangeAcceptability(preferred, false),
                new ChangeAcceptability(alternative, true))));
        assertFalse(isPreferred(preferred.nid()));
        assertTrue(isPreferred(alternative.nid()));
        assertEquals(transactionsOf(preferred.nid()), transactionsOf(alternative.nid()));
    }

    @Test
    void severalChangesToOneDescriptionAreOneVersion() {
        EntityFacade concept = describedFixture("Text fixture");
        EntityFacade alternative = description(concept, "Text fixture alternative");
        Change change = open(concept);

        DescriptionsEdited edited = onFx(() -> change.editDescriptions(List.of(
                new ChangeText(alternative, "Text fixture variant"), new ChangeCaseSignificance(alternative, true))));

        assertEquals(2, edited.changes().size(), edited.changes().toString());
        List<EntityVersion> pending = uncommitted(alternative.nid());
        assertEquals(1, pending.size());
        List<Object> fields = ((SemanticEntityVersion) pending.getFirst()).fieldValues().castToList();
        assertTrue(fields.contains("Text fixture variant"), fields.toString());
        assertTrue(fields.stream().anyMatch(value -> value instanceof EntityFacade facade
                && facade.nid() == KernelConcept.DESCRIPTION_CASE_SENSITIVE.nid()), fields.toString());
    }

    @Test
    void synonymsAndTextualDefinitionsAreAddedButNotRepeated() {
        EntityFacade concept = describedFixture("Add fixture");
        Change change = open(concept);

        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new AddSynonym("Add fixture alternative", false, false)))),
                "an existing synonym");
        DescriptionsEdited edited = onFx(() -> change.editDescriptions(List.of(
                new AddSynonym("Add fixture other", false, false), new AddTextualDefinition("Another definition."))));

        assertEquals(2, edited.added().size());
        Set<String> texts = activeTexts(concept);
        assertTrue(texts.containsAll(Set.of("Add fixture other", "Another definition.")), texts.toString());
        edited.added().forEach(added -> assertTrue(entity(added.nid()).uncommitted()));
        assertFalse(isPreferred(edited.added().getFirst().nid()), "the synonym is acceptable");
    }

    @Test
    void definitionAndDescriptionChangesShareOneTransactionAndDiscardTogether() {
        EntityFacade concept = describedFixture("Together fixture");
        EntityFacade alternative = description(concept, "Together fixture alternative");
        Change change = open(concept);

        onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));
        onFx(() -> change.editDescriptions(List.of(new Retire(alternative))));
        assertEquals(transactionsOf(stated(concept).nid()), transactionsOf(alternative.nid()),
                "the tray publishes or discards the concept's changes as one");

        assertTrue(onFx(() -> ConceptChangeWriter.discard(change)));
        assertFalse(entity(stated(concept).nid()).uncommitted());
        assertFalse(entity(alternative.nid()).uncommitted());
        assertTrue(activeTexts(concept).contains("Together fixture alternative"));
    }

    @Test
    void descriptionEditsAreRefusedForWhatTheConceptDoesNotHoldOrAnotherWindowChanged() {
        EntityFacade concept = describedFixture("Refuse fixture");
        EntityFacade other = describedFixture("Other fixture");
        EntityFacade name = description(concept, "Refuse fixture (qualifier value)");
        EntityFacade alternative = description(concept, "Refuse fixture alternative");
        Change change = open(concept);

        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new Retire(description(other, "Other fixture alternative"))))),
                "another concept's description");
        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new Retire(name)))),
                "the last fully specified name");
        assertRefused(IllegalArgumentException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new Retire(alternative), new ChangeText(alternative, "x")))),
                "retired and changed");

        // A composer that commits at once leaves its transaction unmarked: its edit is not continued.
        onFx(() -> {
            ObservableComposer window = ObservableComposer.create(view, State.ACTIVE, KernelConcept.USER,
                    KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, "Commits at once");
            var editor = window.composeSemantic(alternative.publicId(), concept, KernelPattern.DESCRIPTION_PATTERN);
            var fields = editor.getEditableVersion().getEditableFields();
            for (var field : fields) {
                if (field.getValue() instanceof String) {
                    field.setObjectValue("The person's text");
                }
            }
            editor.save();
            return null;
        });
        assertRefused(IllegalStateException.class,
                () -> onFx(() -> change.editDescriptions(List.of(new ChangeCaseSignificance(alternative, true)))),
                "the pending edit of a composer that commits at once");
        assertEquals(Status.EMPTY, change.status());
    }

    // ── modify before commit (Phase 4) ─────────────────────────────────

    @Test
    void theChangeContinuesTheWindowsPendingEditOfTheDefinition() {
        EntityFacade concept = fixture("Continue window fixture (qualifier value)");
        SemanticEntity<SemanticEntityVersion> stated = stated(concept);
        ObservableComposer window = window();
        LogicalExpressionBuilder builder = new LogicalExpressionBuilder();
        builder.NecessarySet(builder.And(builder.ConceptAxiom(OTHER_SUPERTYPE)));
        DiTreeEntity windowTree = tree(builder);
        onFx(() -> {
            var editor = window.composeSemantic(stated.publicId(), concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN);
            editor.getEditableVersion().getEditableFields().getFirst().setObjectValue(windowTree);
            editor.save();
            return null;
        });
        Transaction windowTransaction = transactionOf(stated.nid());

        Change change = open(concept);
        DefinitionEdited edited = onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, SUPERTYPE))));

        assertTrue(edited.changed());
        assertTrue(edited.before().contains(token(OTHER_SUPERTYPE.nid())), "the edit starts from the window's version: " + edited.before());
        assertEquals(1, uncommitted(stated).size(), "the change replaced the window's pending version");
        assertSame(windowTransaction, transactionOf(stated.nid()), "in the window's transaction");
        assertSame(windowTransaction, change.transaction());
        assertEquals(Set.of(SUPERTYPE.nid(), OTHER_SUPERTYPE.nid()),
                Set.copyOf(shape(concept).sets.getFirst().supertypes.stream().map(Supertype::concept).toList()));
        assertTrue(change.sharedWithOthers(stated.nid()), "the version holds the person's edit too");
        assertRefused(IllegalStateException.class, () -> onFx(() -> ConceptChangeWriter.discard(change)),
                "discarding the change would discard the window's edit");
        assertEquals(1, uncommitted(stated).size(), "a refused discard discards nothing");

        onFx(() -> {
            window.commit();
            return null;
        });
        assertFalse(entity(stated.nid()).uncommitted(), "the window's Publish published both");
        assertEquals(Status.PUBLISHED, change.status());
    }

    @Test
    void aWindowEditOfWhatTheChangeWroteJoinsTheChange() {
        EntityFacade concept = fixture("Window joins fixture (qualifier value)");
        SemanticEntity<SemanticEntityVersion> stated = stated(concept);
        Change change = open(concept);
        onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));
        Transaction changeTransaction = transactionOf(stated.nid());
        assertFalse(change.sharedWithOthers(stated.nid()));

        // The person modifies the assistant's change before publishing it.
        ObservableComposer window = window();
        onFx(() -> {
            var editor = window.composeSemantic(stated.publicId(), concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN);
            var field = editor.getEditableVersion().getEditableFields().getFirst();
            DefinitionShape shape = DefinitionShape.of((DiTreeEntity) field.getValue());
            DefinitionShape target = shape.copy();
            target.sets.getFirst().roles.clear();
            field.setObjectValue(DefinitionRewrite.rewrite((DiTreeEntity) field.getValue(), shape, target));
            editor.save();
            return null;
        });

        assertEquals(1, uncommitted(stated).size(), "the window's edit replaced the change's version");
        assertSame(changeTransaction, transactionOf(stated.nid()), "in the change's transaction");
        assertTrue(shape(concept).sets.getFirst().roles.isEmpty(), "the window's edit stands");
        assertTrue(change.sharedWithOthers(stated.nid()), "the change sees the person edited it since");
        assertRefused(IllegalStateException.class, () -> onFx(() -> ConceptChangeWriter.discard(change)),
                "discarding the change would discard the window's edit");

        onFx(() -> {
            window.commit();
            return null;
        });
        assertFalse(entity(stated.nid()).uncommitted());
        assertEquals(Status.PUBLISHED, change.status());
    }

    @Test
    void theChangesWorkMovesIntoTheWindowsTransactionToContinueIt() {
        EntityFacade concept = describedFixture("Move fixture");
        EntityFacade alternative = description(concept, "Move fixture alternative");
        SemanticEntity<SemanticEntityVersion> stated = stated(concept);
        ObservableComposer window = window();
        onFx(() -> {
            var editor = window.composeSemantic(alternative.publicId(), concept, KernelPattern.DESCRIPTION_PATTERN);
            for (var field : editor.getEditableVersion().getEditableFields()) {
                if (field.getValue() instanceof String) {
                    field.setObjectValue("Move fixture, as the person wrote it");
                }
            }
            editor.save();
            return null;
        });
        Transaction windowTransaction = transactionOf(alternative.nid());

        Change change = open(concept);
        onFx(() -> change.editDefinition(List.of(new AddSupertype(SetSelector.NECESSARY, OTHER_SUPERTYPE))));
        Transaction changeTransaction = transactionOf(stated.nid());
        assertNotSame(windowTransaction, changeTransaction, "the definition was the change's alone");

        onFx(() -> change.editDescriptions(List.of(new ChangeCaseSignificance(alternative, true))));

        assertFalse(changeTransaction.isOpen(), "the change's own transaction was emptied into the window's");
        assertSame(windowTransaction, transactionOf(stated.nid()), "the change's definition edit moved with it");
        assertSame(windowTransaction, transactionOf(alternative.nid()));
        assertSame(windowTransaction, change.transaction());
        assertEquals(1, uncommitted(alternative.nid()).size(), "one pending version of the description");
        assertEquals(Status.OPEN, change.status());

        onFx(() -> {
            window.commit();
            return null;
        });
        assertFalse(entity(stated.nid()).uncommitted() || entity(alternative.nid()).uncommitted(),
                "the window's Publish published the change too");
        assertEquals(Status.PUBLISHED, change.status());
    }

    @Test
    void retiringADescriptionTheWindowEditedReplacesTheEditInItsTransaction() {
        EntityFacade concept = describedFixture("Retire edited fixture");
        EntityFacade alternative = description(concept, "Retire edited fixture alternative");
        ObservableComposer window = window();
        onFx(() -> {
            var editor = window.composeSemantic(alternative.publicId(), concept, KernelPattern.DESCRIPTION_PATTERN);
            for (var field : editor.getEditableVersion().getEditableFields()) {
                if (field.getValue() instanceof String) {
                    field.setObjectValue("Retire edited fixture, as the person wrote it");
                }
            }
            editor.save();
            return null;
        });
        Transaction windowTransaction = transactionOf(alternative.nid());

        Change change = open(concept);
        onFx(() -> change.editDescriptions(List.of(new Retire(alternative))));

        List<EntityVersion> pending = uncommitted(alternative.nid());
        assertEquals(1, pending.size(), "the retirement replaced the window's version: " + pending);
        assertEquals(State.INACTIVE, pending.getFirst().state());
        assertTrue(((SemanticEntityVersion) pending.getFirst()).fieldValues()
                        .contains("Retire edited fixture, as the person wrote it"),
                "the retirement starts from the person's text");
        assertSame(windowTransaction, transactionOf(alternative.nid()));
        assertSame(windowTransaction, transactionOf(dialect(alternative.nid())), "its acceptability retires there too");
        assertTrue(change.sharedWithOthers(alternative.nid()));
        assertRefused(IllegalStateException.class, () -> onFx(() -> ConceptChangeWriter.discard(change)),
                "discarding the change would discard the window's edit");

        onFx(() -> {
            window.commit();
            return null;
        });
        assertFalse(entity(alternative.nid()).uncommitted());
        assertEquals(Status.PUBLISHED, change.status());
        assertFalse(activeTexts(concept).contains("Retire edited fixture, as the person wrote it"));
        assertFalse(activeTexts(concept).contains("Retire edited fixture alternative"));
    }

    // ── fixtures ────────────────────────────────────────────────────────

    /** A KL window's composer: on the edit coordinate's author, module and path, joining pending work. */
    private ObservableComposer window() {
        return onFx(() -> ObservableComposer.create(view, State.ACTIVE, KernelConcept.USER,
                KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, "Edit Semantic Details").joinPendingWork());
    }

    /** A committed concept with the fixture definition. */
    private EntityFacade fixture(String fsn) {
        String block = "proposed: " + fsn + "\nnecessary\n"
                + "  " + token(SUPERTYPE.nid()) + "\n"
                + "  " + line(UNGROUPED) + "\n"
                + "  group\n"
                + "    " + line(GROUPED_CASE) + "\n"
                + "    " + line(GROUPED_TYPE) + "\n";
        ProposedDefinition.Built built = ProposedDefinition.build(ProposedDefinition.parse(block));
        assertTrue(built.gaps().isEmpty(), built.gaps().toString());
        return fixture(fsn, built.tree());
    }

    /** A committed concept with this stated definition, written as a published proposal. */
    private EntityFacade fixture(String fsn, DiTreeEntity definition) {
        ConceptProposalWriter.Draft draft = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER,
                KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, fsn, definition, List.of(), null, null));
        long concept = draft.concept().nid();
        onFx(() -> {
            transactionsOf(concept).forEach(Transaction::commit);
            return null;
        });
        assertFalse(entity(concept).uncommitted());
        assertFalse(entity(stated(draft.concept()).nid()).uncommitted());
        return draft.concept();
    }

    /**
     * A committed concept with the fixture definition and descriptions: its fully specified name
     * {@code <name> (qualifier value)}, the preferred synonym {@code <name>}, the acceptable
     * synonym {@code <name> alternative}, and a textual definition.
     */
    private EntityFacade describedFixture(String name) {
        String fsn = name + " (qualifier value)";
        ProposedDefinition.Built built = ProposedDefinition.build(ProposedDefinition.parse(
                "proposed: " + fsn + "\nnecessary\n  " + token(SUPERTYPE.nid()) + "\n"));
        ConceptProposalWriter.Draft draft = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER,
                KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, fsn, built.tree(),
                List.of(new ConceptProposalWriter.Synonym(name + " alternative", false, false)), null, "A textual definition."));
        onFx(() -> {
            transactionsOf(draft.concept().nid()).forEach(Transaction::commit);
            return null;
        });
        assertFalse(entity(draft.concept().nid()).uncommitted());
        return draft.concept();
    }

    private Change open(EntityFacade concept) {
        return onFx(() -> ConceptChangeWriter.open(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, concept, "Restated for the test"));
    }

    private static DiTreeEntity tree(LogicalExpressionBuilder builder) {
        return switch (builder.build().sourceGraph()) {
            case DiTreeEntity tree -> tree;
            case DiTreeEntity.Builder treeBuilder -> treeBuilder.build();
            default -> throw new IllegalStateException("Unexpected source graph type");
        };
    }

    // ── reading what was written ────────────────────────────────────────

    private static SemanticEntity<SemanticEntityVersion> stated(EntityFacade concept) {
        List<SemanticEntity<SemanticEntityVersion>> stated = EntityService.get()
                .semanticsForComponentOfPattern(concept.nid(), KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).toList();
        assertEquals(1, stated.size());
        return stated.getFirst();
    }

    /** The shape of the concept's stated definition, latest on the view (an unpublished version included). */
    private DefinitionShape shape(EntityFacade concept) {
        Latest<SemanticEntityVersion> latest = view.latest(stated(concept).nid());
        assertTrue(latest.isPresent());
        return DefinitionShape.of((DiTreeEntity) latest.get().fieldValues().getFirst());
    }

    private UUID disjointVertex(EntityFacade concept) {
        Latest<SemanticEntityVersion> latest = view.latest(stated(concept).nid());
        List<LogicalAxiom.Atom.DisjointWithAxiom> disjoints = new LogicalExpression((DiTreeEntity) latest.get().fieldValues().getFirst())
                .nodesOfType(LogicalAxiom.Atom.DisjointWithAxiom.class).castToList();
        assertEquals(1, disjoints.size());
        return disjoints.getFirst().vertexUUID();
    }

    /** The concept's active description with this text. */
    private EntityFacade description(EntityFacade concept, String text) {
        for (SemanticEntity<SemanticEntityVersion> description : EntityService.get()
                .semanticsForComponentOfPattern(concept.nid(), KernelPattern.DESCRIPTION_PATTERN.nid()).toList()) {
            Latest<SemanticEntityVersion> latest = view.latest(description.nid());
            if (latest.isPresent() && latest.get().state() == State.ACTIVE && latest.get().fieldValues().contains(text)) {
                return EntityProxy.Semantic.make(description.nid());
            }
        }
        throw new AssertionError("no active description \"" + text + "\"");
    }

    /** The texts of the concept's active descriptions, latest on the view. */
    private Set<String> activeTexts(EntityFacade concept) {
        Set<String> texts = new HashSet<>();
        EntityService.get().semanticsForComponentOfPattern(concept.nid(), KernelPattern.DESCRIPTION_PATTERN.nid()).forEach(description -> {
            Latest<SemanticEntityVersion> latest = view.latest(description.nid());
            if (latest.isPresent() && latest.get().state() == State.ACTIVE) {
                latest.get().fieldValues().forEach(value -> {
                    if (value instanceof String text) {
                        texts.add(text);
                    }
                });
            }
        });
        return texts;
    }

    /** The description's one US English acceptability semantic. */
    private static long dialect(long description) {
        List<SemanticEntity<SemanticEntityVersion>> dialects = EntityService.get()
                .semanticsForComponentOfPattern(description, KernelPattern.US_DIALECT_PATTERN.nid()).toList();
        assertEquals(1, dialects.size());
        return dialects.getFirst().nid();
    }

    private boolean isPreferred(long description) {
        Latest<SemanticEntityVersion> latest = view.latest(dialect(description));
        return latest.get().fieldValues().getFirst() instanceof EntityFacade facade && facade.nid() == KernelConcept.PREFERRED.nid();
    }

    /** The state of the component's one unpublished version. */
    private static State pendingState(long nid) {
        List<EntityVersion> pending = uncommitted(nid);
        assertEquals(1, pending.size());
        return pending.getFirst().state();
    }

    private static Transaction transactionOf(long nid) {
        return Transaction.forVersion(uncommitted(nid).getFirst()).orElseThrow();
    }

    private static RoleNode node(Group group, long type) {
        return group.roles.stream().filter(node -> node.role().type() == type).findFirst().orElseThrow();
    }

    private static List<EntityVersion> uncommitted(SemanticEntity<?> semantic) {
        return uncommitted(semantic.nid());
    }

    private static List<EntityVersion> uncommitted(long nid) {
        return entity(nid).versions().stream().map(EntityVersion.class::cast)
                .filter(EntityVersion::uncommitted).toList();
    }

    /** The open transactions holding the component's, and its semantics', unpublished versions. */
    private static Set<Transaction> transactionsOf(long component) {
        Set<Transaction> transactions = new HashSet<>();
        for (EntityVersion version : entity(component).versions()) {
            if (version.uncommitted()) {
                Transaction.forVersion(version).ifPresent(transactions::add);
            }
        }
        EntityService.get().forEachSemanticForComponent(component, semantic ->
                transactions.addAll(transactionsOf(semantic.nid())));
        return transactions;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Role role(DefinitionOperation.Role role) {
        return new Role(role.type().nid(), role.filler().nid());
    }

    private String line(DefinitionOperation.Role role) {
        return token(role.type().nid()) + " = " + token(role.filler().nid());
    }

    private String token(long nid) {
        return ComponentText.badge(view, nid).orElseThrow();
    }

    private static Entity<?> entity(long nid) {
        return EntityHandle.get(nid).expectEntity();
    }

    /** Asserts the work is refused with the exception type, which {@link #onFx} wraps. */
    private static void assertRefused(Class<? extends RuntimeException> type, Runnable work, String message) {
        AssertionError wrapped = assertThrows(AssertionError.class, work::run, message);
        assertTrue(type.isInstance(wrapped.getCause()), message + ": " + wrapped.getCause());
    }

    private static <T> T onFx(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e.getCause() == null ? e : e.getCause());
        }
    }
}
