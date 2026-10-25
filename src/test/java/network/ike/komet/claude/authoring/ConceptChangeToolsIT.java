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
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import javafx.application.Platform;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.koncept.ComponentText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant's tools for changing existing concepts ({@code IKE-Network/ike-issues#1326}),
 * called as the model calls them, with the input a tool call carries, against the starter set
 * in a memory-only store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConceptChangeToolsIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewProperties viewProperties;
    private Map<String, AnthropicTool> tools;

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
        viewProperties = onFx(() -> new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
        tools = new java.util.LinkedHashMap<>();
        new ConceptChangeTools(() -> viewProperties).tools().forEach(tool -> tools.put(tool.name(), tool));
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void theToolsAreTheFourTheContractNames() {
        assertEquals(List.of("propose_definition_change", "propose_description_change", "discard_change", "pending_changes"),
                List.copyOf(tools.keySet()));
    }

    @Test
    void aDefinitionChangeIsWrittenUncommittedAndShownBeforeAndAfter() {
        EntityFacade concept = fixture("Tool definition fixture");

        String result = call("propose_definition_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "replace_supertype", "concept", id(KernelConcept.LANGUAGE),
                        "to", id(KernelConcept.ENGLISH_LANGUAGE))),
                "rationale", "English is the parent its peers have"));

        assertTrue(result.startsWith("Wrote an uncommitted change"), result);
        assertTrue(result.contains("Before:\n```koncept-definition") && result.contains("After:\n```koncept-definition"), result);
        assertTrue(result.contains("NOTHING IS COMMITTED"), result);
        assertTrue(EntityHandle.get(stated(concept)).expectEntity().uncommitted());
        String pending = call("pending_changes", Map.of("concept", id(concept)));
        assertTrue(pending.contains("— yours"), pending);
        assertFalse(pending.contains("— not yours"), pending);
    }

    @Test
    void aDefinitionBlockIsAcceptedInPlaceOfOperations() {
        EntityFacade concept = fixture("Tool block fixture");
        String block = "necessary\n  " + ComponentText.badge(viewProperties.calculator(), KernelConcept.ENGLISH_LANGUAGE.nid()).orElseThrow() + "\n";

        String result = call("propose_definition_change", Map.of("concept", id(concept), "definition", block));

        assertTrue(result.startsWith("Wrote an uncommitted change"), result);
        assertTrue(call("propose_definition_change", Map.of("concept", id(concept), "definition", block))
                .startsWith("No change"), "the same block again changes nothing");
    }

    @Test
    void descriptionAndDefinitionChangesToOneConceptAreOneChangeThatDiscardWithdraws() {
        EntityFacade concept = fixture("Tool description fixture");
        long alternative = description(concept, "Tool description fixture alternative");

        String result = call("propose_description_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "retire", "description", id(alternative)),
                        Map.of("op", "add_synonym", "text", "Tool description fixture other", "preferred", false))));
        assertTrue(result.contains("retired \"Tool description fixture alternative\""), result);
        assertTrue(result.contains("Added descriptions"), result);
        call("propose_definition_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "add_supertype", "concept", id(KernelConcept.ENGLISH_LANGUAGE)))));
        assertEquals(transactions(stated(concept)), transactions(alternative), "one change, one transaction");

        String discarded = call("discard_change", Map.of("concept", id(concept)));
        assertTrue(discarded.startsWith("Discarded"), discarded);
        assertFalse(EntityHandle.get(stated(concept)).expectEntity().uncommitted());
        assertFalse(EntityHandle.get(alternative).expectEntity().uncommitted());
        assertTrue(call("pending_changes", Map.of("concept", id(concept))).startsWith("Nothing is waiting"));
    }

    @Test
    void aPendingEditThatIsNotTheAssistantsIsReportedAsNotYours() {
        EntityFacade concept = fixture("Tool pending fixture");
        long alternative = description(concept, "Tool pending fixture alternative");
        onFx(() -> {
            ObservableComposer window = ObservableComposer.create(viewProperties.calculator(), State.ACTIVE, KernelConcept.USER,
                    KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, "The window");
            var editor = window.composeSemantic(EntityHandle.get(alternative).expectEntity().publicId(), concept,
                    KernelPattern.DESCRIPTION_PATTERN);
            for (var field : editor.getEditableVersion().getEditableFields()) {
                if (field.getValue() instanceof String) {
                    field.setObjectValue("The person's text");
                }
            }
            editor.save();
            return null;
        });

        String pending = call("pending_changes", Map.of("concept", id(concept)));
        assertTrue(pending.contains("— not yours"), pending);
        String refused = call("propose_description_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "change_case_significance", "description", id(alternative),
                        "case_sensitive", true))));
        assertTrue(refused.startsWith("Error:") && refused.contains("Unpublished Changes"), refused);
    }

    @Test
    void malformedCallsAreErrorsAndWriteNothing() {
        EntityFacade concept = fixture("Tool error fixture");

        assertTrue(call("propose_definition_change", Map.of("concept", id(concept))).startsWith("Error:"),
                "neither operations nor a block");
        assertTrue(call("propose_definition_change", Map.of("concept", "00000000-0000-0000-0000-000000000000",
                "operations", List.of(Map.of("op", "remove_supertype", "concept", id(KernelConcept.LANGUAGE)))))
                .startsWith("Error:"), "an unknown concept");
        assertTrue(call("propose_definition_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "make_it_better"))))
                .startsWith("Error:"), "an unknown operation");
        assertTrue(call("propose_description_change", Map.of("concept", id(concept),
                "operations", List.of(Map.of("op", "retire", "description", id(concept)))))
                .startsWith("Error:"), "a concept is not a description");
        assertFalse(EntityHandle.get(stated(concept)).expectEntity().uncommitted());
    }

    // ── fixtures and reads ──────────────────────────────────────────────

    /** A committed concept: necessary set of Language, the preferred synonym {@code <name>}, the acceptable {@code <name> alternative}. */
    private EntityFacade fixture(String name) {
        String fsn = name + " (qualifier value)";
        ProposedDefinition.Built built = ProposedDefinition.build(ProposedDefinition.parse("proposed: " + fsn + "\nnecessary\n  "
                + ComponentText.badge(viewProperties.calculator(), KernelConcept.LANGUAGE.nid()).orElseThrow() + "\n"));
        ConceptProposalWriter.Draft draft = onFx(() -> ConceptProposalWriter.write(viewProperties.calculator(),
                KernelConcept.USER, KernelConcept.PRIMORDIAL_MODULE, KernelConcept.DEVELOPMENT_PATH, fsn, built.tree(),
                List.of(new ConceptProposalWriter.Synonym(name + " alternative", false, false)), null, null));
        onFx(() -> {
            transactionsUnder(draft.concept().nid()).forEach(Transaction::commit);
            return null;
        });
        return draft.concept();
    }

    private String call(String tool, Map<String, Object> input) {
        return tools.get(tool).execute(input);
    }

    private static String id(EntityFacade component) {
        return component.publicId().asUuidArray()[0].toString();
    }

    private static String id(long nid) {
        return id(EntityHandle.get(nid).expectEntity());
    }

    private static long stated(EntityFacade concept) {
        return EntityService.get().semanticsForComponentOfPattern(concept.nid(),
                KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()).toList().getFirst().nid();
    }

    private long description(EntityFacade concept, String text) {
        for (SemanticEntity<SemanticEntityVersion> description : EntityService.get()
                .semanticsForComponentOfPattern(concept.nid(), KernelPattern.DESCRIPTION_PATTERN.nid()).toList()) {
            Latest<SemanticEntityVersion> latest = viewProperties.calculator().latest(description.nid());
            if (latest.isPresent() && latest.get().fieldValues().contains(text)) {
                return description.nid();
            }
        }
        throw new AssertionError("no description \"" + text + "\"");
    }

    private static Set<Transaction> transactions(long nid) {
        Set<Transaction> transactions = new HashSet<>();
        for (EntityVersion version : EntityHandle.get(nid).expectEntity().versions()) {
            if (version.uncommitted()) {
                Transaction.forVersion(version).ifPresent(transactions::add);
            }
        }
        return transactions;
    }

    /** The open transactions of the component's and its semantics' unpublished versions. */
    private static Set<Transaction> transactionsUnder(long component) {
        Set<Transaction> transactions = transactions(component);
        EntityService.get().forEachSemanticForComponent(component, semantic ->
                transactions.addAll(transactionsUnder(semantic.nid())));
        return transactions;
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
