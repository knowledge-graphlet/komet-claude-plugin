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

import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import dev.ikm.komet.framework.observable.ObservableComposer;
import dev.ikm.komet.framework.observable.ObservableSemantic;
import dev.ikm.komet.framework.observable.ObservableSemanticVersion;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Draft;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Edit;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Edited;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Status;
import network.ike.komet.claude.authoring.ConceptProposalWriter.Synonym;
import network.ike.komet.claude.authoring.ConceptProposalWriter.SynonymChange;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.koncept.ConceptDefinition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A proposal written by {@link ConceptProposalWriter}, against the starter data
 * ({@code IKE-Network/ike-issues#1292}): everything lands uncommitted in one transaction, so
 * committing that transaction — what a KL window's Publish does for versions it did not save —
 * commits the whole proposal, and discarding it rolls the whole proposal back. An open proposal
 * is edited in place, in that transaction; a withdrawn synonym leaves it without cancelling the rest.
 *
 * <p>The composer requires the JavaFX application thread, so the toolkit is started here; where
 * it cannot start (a headless agent), the class is skipped rather than failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConceptProposalWriterIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

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
    void theWholeProposalIsUncommittedAndOnePublishCommitsAllOfIt() {
        Built built = definition();
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Proposed language (qualifier value)", built.tree(),
                List.of(new Synonym("Proposed tongue", false, false)), "Modelled on English language.", null));
        long concept = written.concept().nid();

        assertTrue(entity(concept).uncommitted(), "nothing is committed by writing");
        // FSN + the derived preferred synonym + one acceptable synonym, each with a dialect: 6;
        // the stated definition and the note: 2.
        assertEquals(8, written.written());

        List<SemanticEntity<SemanticEntityVersion>> descriptions = semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid());
        Set<String> texts = new HashSet<>();
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            texts.add(String.valueOf(last(description).fieldValues().get(1)));
            List<SemanticEntity<SemanticEntityVersion>> dialects =
                    semantics(description.nid(), KernelPattern.US_DIALECT_PATTERN.nid());
            assertEquals(1, dialects.size(), "each description has its US English acceptability");
        }
        assertEquals(Set.of("Proposed language (qualifier value)", "Proposed language", "Proposed tongue"), texts,
                "the name, the preferred synonym derived from it, and the proposed synonym");
        assertTrue(preferredTexts(descriptions).contains("Proposed language"),
                "with no preferred synonym given, the name without its tag is preferred");
        assertFalse(preferredTexts(descriptions).contains("Proposed tongue"));

        DiTreeEntity stored = (DiTreeEntity) last(semantics(concept, KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid())
                .getFirst()).fieldValues().getFirst();
        assertArrayEquals(new long[]{KernelConcept.LANGUAGE.nid()},
                ConceptDefinition.of(stored).orElseThrow().supertypes(), "the stated definition is the block's");

        String note = String.valueOf(last(semantics(concept, KernelPattern.COMMENT_PATTERN.nid()).getFirst()).fieldValues()
                .stream().filter(String.class::isInstance).findFirst().orElseThrow());
        assertTrue(note.startsWith("Modelled on English language."), note);

        assertNull(definitionText(concept), "no textual definition was passed, so none is written");

        // Publish, as a KL window does for versions saved by another composer: commit the
        // transactions of the concept and the semantics it shows. One transaction holds it all,
        // dialects included.
        Set<Transaction> transactions = publishTransactions(concept);
        assertEquals(1, transactions.size(), "the proposal is one transaction");
        onFx(() -> {
            transactions.forEach(Transaction::commit);
            return null;
        });

        assertFalse(entity(concept).uncommitted(), "the concept is committed");
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            assertFalse(entity(description.nid()).uncommitted());
            assertFalse(entity(semantics(description.nid(), KernelPattern.US_DIALECT_PATTERN.nid()).getFirst().nid()).uncommitted(),
                    "a dialect the window does not show is committed with the rest");
        }
    }

    @Test
    void aDiscardedProposalIsRolledBackAndAPublishedOneIsLeftAlone() {
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Discarded language (qualifier value)", definition().tree(), List.of(),
                null, null));
        long concept = written.concept().nid();
        List<SemanticEntity<SemanticEntityVersion>> descriptions = semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid());

        assertTrue(onFx(() -> ConceptProposalWriter.discard(written)));
        assertFalse(entity(concept).uncommitted(), "nothing of the proposal is left uncommitted");
        descriptions.forEach(description -> assertTrue(entity(description.nid()).canceled(),
                "the names are rolled back with it"));
        assertEquals(Status.DISCARDED, onFx(written::status));
        assertFalse(onFx(() -> ConceptProposalWriter.discard(written)), "a second discard finds nothing to withdraw");
        assertRefused(IllegalStateException.class, () -> onFx(() -> written.edit(textual("Too late."))),
                "a discarded proposal is not edited");
    }

    @Test
    void aTextualDefinitionIsWrittenUncommittedAndPublishedWithTheRest() {
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Defined language (qualifier value)", definition().tree(), List.of(),
                null, "  A language defined for this test.  "));
        long concept = written.concept().nid();

        // FSN + derived synonym + textual definition, each with a dialect: 6; the stated definition: 1.
        assertEquals(7, written.written());
        SemanticEntity<SemanticEntityVersion> definition = definitionDescription(concept);
        assertEquals("A language defined for this test.", text(definition));
        assertTrue(entity(definition.nid()).uncommitted(), "the textual definition is uncommitted");
        assertTrue(preferredTexts(List.of(definition)).contains("A language defined for this test."),
                "the textual definition is preferred in US English");

        onFx(() -> {
            publishTransactions(concept).forEach(Transaction::commit);
            return null;
        });
        assertFalse(entity(definition.nid()).uncommitted(), "Publish commits the textual definition");
        assertEquals(Status.PUBLISHED, onFx(written::status));
        assertRefused(IllegalStateException.class, () -> onFx(() -> written.edit(textual("Too late."))),
                "a published proposal is not edited");
        assertEquals("A language defined for this test.", text(definitionDescription(concept)),
                "and the refused edit wrote nothing");
    }

    @Test
    void anEditReplacesTheWrittenVersionsInPlaceAndAnUnchangedOneIsANoOp() {
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Edited language (qualifier value)", definition().tree(),
                List.of(new Synonym("Edited tongue", false, false)), "First rationale.", null));
        long concept = written.concept().nid();

        Edited nothing = onFx(() -> written.edit(new Edit(null, null, null, null, null, null, null)));
        assertTrue(nothing.fields().isEmpty() && nothing.warnings().isEmpty(), "an edit of nothing changes nothing");

        Edited first = onFx(() -> written.edit(textual("First definition.")));
        assertEquals(List.of("textual_definition"), first.fields(), "a proposal without one gets a textual definition");
        long definitionNid = definitionDescription(concept).nid();
        Edited second = onFx(() -> written.edit(new Edit(null, null, "Second definition.", "Second rationale.",
                null, null, null)));
        assertEquals(List.of("textual_definition", "editorial_note"), second.fields());
        SemanticEntity<SemanticEntityVersion> definition = definitionDescription(concept);
        assertEquals(definitionNid, definition.nid(), "the same semantic is edited");
        assertEquals(1, definition.versions().size(), "the edit replaced the written version rather than adding one");
        assertEquals("Second definition.", text(definition));
        assertEquals("Second rationale.", noteText(concept));
        assertTrue(onFx(() -> written.edit(textual("Second definition."))).fields().isEmpty(),
                "setting what is already there is a no-op");

        // The name follows a rename, and so does the preferred synonym derived from it.
        Edited renamed = onFx(() -> written.edit(new Edit("Renamed language (qualifier value)", null, null, null,
                null, null, null)));
        assertEquals(List.of("proposed_name"), renamed.fields());
        Set<String> texts = activeTexts(concept);
        assertTrue(texts.containsAll(Set.of("Renamed language (qualifier value)", "Renamed language", "Edited tongue")), texts.toString());
        assertFalse(texts.contains("Edited language"), texts.toString());
        assertTrue(entity(concept).uncommitted(), "edits commit nothing");
    }

    @Test
    void synonymsAreAddedRemovedAndUpdatedKeepingOnePreferred() {
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Synonymous language (qualifier value)", definition().tree(),
                List.of(new Synonym("Synonymous tongue", true, false), new Synonym("Old speech", false, false)),
                null, null));
        long concept = written.concept().nid();
        Set<String> before = activeTexts(concept);

        assertRefused(IllegalArgumentException.class, () -> onFx(() -> written.edit(synonyms(
                List.of(new Synonym("New speech", true, false)), List.of(), List.of()))),
                "a second preferred synonym is refused");
        assertRefused(IllegalArgumentException.class, () -> onFx(() -> written.edit(synonyms(
                List.of(), List.of("No such term"), List.of()))), "an unknown term is refused");
        assertRefused(IllegalArgumentException.class, () -> onFx(() -> written.edit(synonyms(
                List.of(new Synonym("Old speech", false, false)), List.of(), List.of()))), "a duplicate is refused");
        assertEquals(before, activeTexts(concept), "a refused edit writes nothing");
        SemanticEntity<SemanticEntityVersion> withdrawn = semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid()).stream()
                .filter(description -> "Old speech".equals(text(description))).findFirst().orElseThrow();
        long withdrawnDialect = semantics(withdrawn.nid(), KernelPattern.US_DIALECT_PATTERN.nid()).getFirst().nid();

        Edited edited = onFx(() -> written.edit(synonyms(
                List.of(new Synonym("New speech", true, true)),
                List.of("Old speech"),
                List.of(new SynonymChange("Synonymous tongue", false, null)))));
        assertEquals(List.of("synonyms_remove", "synonyms_update", "synonyms_add"), edited.fields());
        assertEquals(Set.of("Synonymous language (qualifier value)", "Synonymous tongue", "New speech"), activeTexts(concept));
        List<SemanticEntity<SemanticEntityVersion>> active = semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid()).stream()
                .filter(description -> !entity(description.nid()).canceled()).toList();
        assertTrue(preferredTexts(active).containsAll(Set.of("New speech", "Synonymous language (qualifier value)")));
        assertFalse(preferredTexts(active).contains("Synonymous tongue"), "the old preferred synonym was demoted");
        assertEquals(List.of(new Synonym("Synonymous tongue", false, false), new Synonym("New speech", true, true)),
                onFx(written::synonyms));

        // The withdrawn synonym left the proposal's transaction, canceled; the rest is still in it,
        // uncommitted, and one Publish commits it all.
        assertTrue(entity(withdrawn.nid()).canceled(), "the withdrawn synonym is canceled");
        assertTrue(entity(withdrawnDialect).canceled(), "its acceptability is withdrawn with it");
        assertTrue(entity(concept).uncommitted(), "withdrawing a synonym cancels nothing else");
        active.forEach(description -> assertTrue(entity(description.nid()).uncommitted()));
        Set<Transaction> transactions = publishTransactions(concept);
        assertEquals(1, transactions.size(), "the proposal is still one transaction");
        onFx(() -> {
            transactions.forEach(Transaction::commit);
            return null;
        });
        active.forEach(description -> assertFalse(entity(description.nid()).uncommitted()));
        assertTrue(entity(withdrawn.nid()).canceled(), "Publish does not bring the withdrawn synonym back");
    }

    @Test
    void aFieldThePersonEditedInTheWindowIsLeftAsTheyLeftIt() {
        Draft written = onFx(() -> ConceptProposalWriter.write(view, KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE,
                KernelConcept.DEVELOPMENT_PATH, "Contested language (qualifier value)", definition().tree(), List.of(),
                null, "The assistant's definition."));
        long concept = written.concept().nid();
        SemanticEntity<SemanticEntityVersion> definition = definitionDescription(concept);

        // The window edits through a composer, and transaction, of its own.
        onFx(() -> {
            ObservableComposer window = ObservableComposer.create(view, State.ACTIVE, KernelConcept.USER,
                    KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, "The window");
            ObservableComposer.EntityComposer<ObservableSemanticVersion.Editable, ObservableSemantic> editor =
                    window.composeSemantic(definition.publicId(), EntityProxy.make(concept),
                            EntityProxy.Pattern.make(definition.patternNid()));
            editor.getEditableVersion().getEditableFields().get(1).setObjectValue("The person's definition.");
            editor.save();
            return null;
        });

        Edited edited = onFx(() -> written.edit(textual("The assistant's second definition.")));
        assertTrue(edited.fields().isEmpty(), edited.toString());
        assertEquals(1, edited.warnings().size());
        assertTrue(edited.warnings().getFirst().startsWith("textual_definition"), edited.warnings().getFirst());
        assertFalse(definitionDescription(concept).versions().stream()
                        .anyMatch(version -> "The assistant's second definition.".equals(version.fieldValues().get(1))),
                "the person's edit was not written over");
    }

    @Test
    void aSemanticTagIsDroppedFromTheDerivedSynonym() {
        assertEquals("Eosinophilic esophagitis", ConceptProposalWriter.withoutSemanticTag("Eosinophilic esophagitis (disorder)"));
        assertEquals("No tag here", ConceptProposalWriter.withoutSemanticTag("No tag here"));
    }

    private static Edit textual(String text) {
        return new Edit(null, null, text, null, null, null, null);
    }

    private static Edit synonyms(List<Synonym> add, List<String> remove, List<SynonymChange> change) {
        return new Edit(null, null, null, null, add, remove, change);
    }

    /** The transactions a KL window's Publish commits: those of the concept and of the semantics it shows. */
    private static Set<Transaction> publishTransactions(long concept) {
        Set<Transaction> transactions = new HashSet<>();
        List<Long> shown = new java.util.ArrayList<>(List.of(concept));
        semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid()).forEach(description -> shown.add(description.nid()));
        for (long nid : shown) {
            for (EntityVersion version : entity(nid).versions()) {
                if (version.uncommitted()) {
                    Transaction.forStamp(version.stamp().publicId()).ifPresent(transactions::add);
                }
            }
        }
        return transactions;
    }

    /** The concept's textual definition description, or null. */
    private static SemanticEntity<SemanticEntityVersion> definitionDescription(long concept) {
        return semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid()).stream()
                .filter(description -> last(description).fieldValues().stream().anyMatch(value ->
                        value instanceof EntityFacade facade && facade.nid() == KernelConcept.DEFINITION_DESCRIPTION_TYPE.nid()))
                .findFirst().orElse(null);
    }

    private static String definitionText(long concept) {
        SemanticEntity<SemanticEntityVersion> definition = definitionDescription(concept);
        return definition == null ? null : text(definition);
    }

    private static String text(SemanticEntity<SemanticEntityVersion> description) {
        return String.valueOf(last(description).fieldValues().get(1));
    }

    /** The texts of the concept's descriptions not withdrawn. */
    private static Set<String> activeTexts(long concept) {
        Set<String> texts = new HashSet<>();
        semantics(concept, KernelPattern.DESCRIPTION_PATTERN.nid()).stream()
                .filter(description -> !entity(description.nid()).canceled())
                .forEach(description -> texts.add(text(description)));
        return texts;
    }

    private static String noteText(long concept) {
        return String.valueOf(last(semantics(concept, KernelPattern.COMMENT_PATTERN.nid()).getFirst()).fieldValues()
                .stream().filter(String.class::isInstance).findFirst().orElseThrow());
    }

    private Built definition() {
        return ProposedDefinition.build(ProposedDefinition.parse(
                "proposed: x\nnecessary\n  " + ComponentText.badge(view, KernelConcept.LANGUAGE.nid()).orElseThrow() + "\n"));
    }

    private Set<String> preferredTexts(List<SemanticEntity<SemanticEntityVersion>> descriptions) {
        Set<String> preferred = new HashSet<>();
        for (SemanticEntity<SemanticEntityVersion> description : descriptions) {
            Object acceptability = last(semantics(description.nid(), KernelPattern.US_DIALECT_PATTERN.nid()).getFirst())
                    .fieldValues().getFirst();
            if (acceptability instanceof EntityFacade facade && facade.nid() == KernelConcept.PREFERRED.nid()) {
                preferred.add(String.valueOf(last(description).fieldValues().get(1)));
            }
            assertTrue(acceptability instanceof EntityFacade facade
                    && (facade.nid() == KernelConcept.PREFERRED.nid() || facade.nid() == KometConcept.ACCEPTABLE.nid()));
        }
        return preferred;
    }

    private static Entity<?> entity(long nid) {
        return EntityHandle.get(nid).expectEntity();
    }

    private static List<SemanticEntity<SemanticEntityVersion>> semantics(long component, long pattern) {
        return EntityService.get().semanticsForComponentOfPattern(component, pattern).toList();
    }

    private static SemanticEntityVersion last(SemanticEntity<SemanticEntityVersion> semantic) {
        return semantic.versions().getLast();
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
