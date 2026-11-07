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
package network.ike.komet.claude.tools;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.events.MakeKLWindowEvent;
import dev.ikm.komet.kview.events.genediting.MakeGenEditingWindowEvent;
import dev.ikm.komet.layout.editor.StandardEditorWindows;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.events.EvtBusFactory;
import dev.ikm.tinkar.events.Subscriber;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.koncept.NidFree;
import network.ike.komet.claude.ui.ComposeChips;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code open_component} and the read tools it leans on, called as the model calls them, against
 * the starter set in a memory-only store. Where the journal would be, a subscriber on the real
 * event bus records what was asked of it; no window is built, so these tests prove what the
 * journal is told, under which view, and that nothing is written up to that point.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentToolsIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** The heading a semantic is listed with: its pattern, then its own labelled UUID. */
    private static final Pattern SEMANTIC_HEADING = Pattern.compile("(?m)^Pattern: .*  semantic \\[([0-9a-f-]{36})]");

    private final UUID topic = UUID.randomUUID();
    private final List<MakeKLWindowEvent> klWindows = new CopyOnWriteArrayList<>();
    private final List<MakeGenEditingWindowEvent> semanticWindows = new CopyOnWriteArrayList<>();
    private final Subscriber<MakeKLWindowEvent> klSubscriber = event -> {
        klWindows.add(event);
    };
    private final Subscriber<MakeGenEditingWindowEvent> semanticSubscriber = event -> {
        semanticWindows.add(event);
    };

    private ViewProperties viewProperties;
    private Map<String, AnthropicTool> tools;

    @BeforeAll
    void setup() {
        try {
            HeadlessFx.select();
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
        viewProperties = ConceptProposalTools.onFxThread(() ->
                new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
        tools = new HashMap<>();
        new GraphTools(viewProperties::calculator).tools().forEach(tool -> tools.put(tool.name(), tool));
        new ComponentWindowTools(() -> viewProperties, () -> topic).tools().forEach(tool -> tools.put(tool.name(), tool));
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @BeforeEach
    void listen() {
        klWindows.clear();
        semanticWindows.clear();
        EvtBusFactory.getDefaultEvtBus().subscribe(topic, MakeKLWindowEvent.class, klSubscriber);
        EvtBusFactory.getDefaultEvtBus().subscribe(topic, MakeGenEditingWindowEvent.class, semanticSubscriber);
    }

    @AfterEach
    void stopListening() {
        EvtBusFactory.getDefaultEvtBus().unsubscribe(topic, MakeKLWindowEvent.class, klSubscriber);
        EvtBusFactory.getDefaultEvtBus().unsubscribe(topic, MakeGenEditingWindowEvent.class, semanticSubscriber);
    }

    // ── open_component ──────────────────────────────────────────────────

    @Test
    void aConceptOpensInItsKlWindowUnderTheAssistantsView() {
        EntityFacade concept = KernelConcept.ENGLISH_LANGUAGE;

        String result = call("open_component", Map.of("id", uuid(concept), "kind", "concept"));

        assertTrue(result.startsWith("Opened a new KL concept window"), result);
        assertAnswers(result, concept.nid(), "concept");
        MakeKLWindowEvent window = onlyKlWindow();
        assertSame(MakeKLWindowEvent.VIEW_STANDARD_WINDOW, window.getEventType(),
                "the path that does not seed, because seeding can commit");
        assertEquals(StandardEditorWindows.CONCEPT_WINDOW, window.getWindowTitle());
        assertEquals(concept.nid(), window.getEntityFacade().nid());
        assertSame(viewProperties, window.getViewProperties(), "the window is given the view the tools read");
    }

    @Test
    void aPatternOpensInItsKlWindow() {
        EntityFacade pattern = KernelPattern.DESCRIPTION_PATTERN;

        String result = call("open_component", Map.of("id", uuid(pattern), "kind", "pattern"));

        assertTrue(result.startsWith("Opened a new KL pattern window"), result);
        assertAnswers(result, pattern.nid(), "pattern");
        MakeKLWindowEvent window = onlyKlWindow();
        assertSame(MakeKLWindowEvent.VIEW_STANDARD_WINDOW, window.getEventType());
        assertEquals(StandardEditorWindows.PATTERN_WINDOW, window.getWindowTitle());
        assertEquals(pattern.nid(), window.getEntityFacade().nid());
    }

    @Test
    void aSemanticOpensInKometsWindowForASemantic() {
        SemanticEntity<SemanticEntityVersion> semantic = descriptionOf(KernelConcept.ENGLISH_LANGUAGE);

        String result = call("open_component", Map.of("id", uuid(semantic), "kind", "semantic"));

        assertTrue(result.startsWith("Opened a new semantic window"), result);
        assertAnswers(result, semantic.nid(), "semantic");
        assertTrue(klWindows.isEmpty(), "a semantic is not walked up to its concept's window");
        assertEquals(1, semanticWindows.size());
        MakeGenEditingWindowEvent window = semanticWindows.getFirst();
        assertSame(MakeGenEditingWindowEvent.OPEN_GEN_EDIT, window.getEventType());
        assertEquals(semantic.nid(), window.getComponent().nid());
        assertSame(viewProperties, window.getViewProperties());
    }

    @Test
    void aUuidWithNoKindOpensAsWhatItIs() {
        call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));
        assertEquals(StandardEditorWindows.CONCEPT_WINDOW, onlyKlWindow().getWindowTitle());

        klWindows.clear();
        call("open_component", Map.of("id", uuid(KernelPattern.DESCRIPTION_PATTERN)));
        assertEquals(StandardEditorWindows.PATTERN_WINDOW, onlyKlWindow().getWindowTitle());

        klWindows.clear();
        SemanticEntity<SemanticEntityVersion> semantic = descriptionOf(KernelConcept.ENGLISH_LANGUAGE);
        String result = call("open_component", Map.of("id", uuid(semantic)));
        assertAnswers(result, semantic.nid(), "semantic");
        assertTrue(klWindows.isEmpty());
        assertEquals(1, semanticWindows.size());
    }

    @Test
    void anUnknownIdentifierOpensNothingAndWritesNothing() {
        UUID unknown = UUID.randomUUID();
        String sctid = "900000000000999999";
        UUID sctidUuid = UuidUtil.fromSNOMED(sctid);

        for (String id : List.of(unknown.toString(), sctid, "not an identifier")) {
            String result = call("open_component", Map.of("id", id));
            assertTrue(result.startsWith("Error: No component found for '" + id + "'"), result);
            assertTrue(result.endsWith("Nothing was opened."), result);
        }

        assertNothingOpened();
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(unknown)), "asking must not mint a nid");
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(sctidUuid)), "asking must not mint a nid");
    }

    @Test
    void aKindTheComponentIsNotIsRefused() {
        String result = call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE), "kind", "pattern"));
        assertTrue(result.startsWith("Error: '" + uuid(KernelConcept.ENGLISH_LANGUAGE) + "' is a concept"), result);

        result = call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE), "kind", "stamp"));
        assertTrue(result.startsWith("Error: kind must be concept, semantic or pattern"), result);

        assertNothingOpened();
    }

    @Test
    void openingWritesNothing() {
        long semanticsBefore = EntityService.get().countSemantics();
        long conceptsBefore = EntityService.get().countConcepts();
        int transactionsBefore = Transaction.activeTransactions().size();

        call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));
        call("open_component", Map.of("id", uuid(KernelPattern.DESCRIPTION_PATTERN)));
        call("open_component", Map.of("id", uuid(descriptionOf(KernelConcept.ENGLISH_LANGUAGE))));

        assertEquals(semanticsBefore, EntityService.get().countSemantics());
        assertEquals(conceptsBefore, EntityService.get().countConcepts());
        assertEquals(transactionsBefore, Transaction.activeTransactions().size(), "no transaction was opened");
    }

    @Test
    void eachCallOpensANewWindow() {
        call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));
        call("open_component", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));

        assertEquals(2, klWindows.size());
    }

    @Test
    void withNoJournalThereIsNowhereToOpenAWindow() {
        AnthropicTool unattached = new ComponentWindowTools(() -> viewProperties, () -> null).tools().getFirst();

        String result = unattached.execute(Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));

        assertTrue(result.startsWith("Error: The assistant is not attached to a journal"), result);
        assertNothingOpened();
    }

    // ── concept_semantics ───────────────────────────────────────────────

    @Test
    void everySemanticConceptSemanticsListsCanBeReadBySemanticInfo() {
        String listed = call("concept_semantics", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE)));

        Matcher heading = SEMANTIC_HEADING.matcher(listed);
        int semantics = 0;
        while (heading.find()) {
            semantics++;
            String read = call("semantic_info", Map.of("id", heading.group(1)));
            assertTrue(read.startsWith("Pattern: ") && read.contains("semantic [" + heading.group(1) + "]"), read);
        }
        assertTrue(semantics > 0, "the listing labels each semantic's own UUID: " + listed);
        assertTrue(listed.endsWith("[" + semantics + " semantics]"), listed);
    }

    // ── referencing_semantics ───────────────────────────────────────────

    @Test
    void aSemanticAttachedToTheComponentPointsAtItByItsReference() {
        SemanticEntity<SemanticEntityVersion> description = descriptionOf(KernelConcept.ENGLISH_LANGUAGE);

        String result = call("referencing_semantics", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE),
                "pattern", "Description", "limit", 1000));

        String block = blockOf(result, description);
        assertTrue(block.contains("points here by: reference"), block);
        NidFree.assertNoNid("referencing_semantics", result, KernelConcept.ENGLISH_LANGUAGE.nid());
    }

    @Test
    void aSemanticHoldingTheComponentInAFieldPointsAtItThere() {
        // A description of another concept names English Language as its language.
        SemanticEntity<SemanticEntityVersion> elsewhere = descriptionOf(KernelConcept.DESCRIPTION_TYPE);
        assertEquals(KernelConcept.DESCRIPTION_TYPE.nid(), elsewhere.referencedComponentNid());

        String result = call("referencing_semantics", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE),
                "pattern", "Description", "limit", 100000));

        String block = blockOf(result, elsewhere);
        assertTrue(block.contains("points here by: field \""), block);
        assertTrue(block.contains("attached to: "
                + ComponentText.preferredName(viewProperties.calculator(), KernelConcept.DESCRIPTION_TYPE.nid())), block);
    }

    @Test
    void aDefinitionNamingTheComponentPointsAtIt() {
        // English Language is defined under Language, so its stated definition's tree names Language.
        SemanticEntity<SemanticEntityVersion> definition = EntityService.get().semanticsForComponentOfPattern(
                KernelConcept.ENGLISH_LANGUAGE.nid(), KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid())
                .findFirst().orElseThrow();

        String result = call("referencing_semantics", Map.of("id", uuid(KernelConcept.LANGUAGE),
                "pattern", ComponentText.preferredName(viewProperties.calculator(),
                        KernelPattern.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid()), "limit", 100000));

        assertTrue(blockOf(result, definition).contains("points here by: field \""), result);
    }

    @Test
    void aSemanticCanBeTheComponentPointedAt() {
        SemanticEntity<SemanticEntityVersion> description = descriptionOf(KernelConcept.ENGLISH_LANGUAGE);

        String result = call("referencing_semantics", Map.of("id", uuid(description)));

        assertFalse(result.startsWith("No component found"), result);
        assertTrue(result.contains("points here by: reference"), "its acceptability is attached to it: " + result);
    }

    @Test
    void anUnknownComponentIsSaidToBeUnknownAndNotMinted() {
        UUID unknown = UUID.randomUUID();

        String result = call("referencing_semantics", Map.of("id", unknown.toString()));

        assertTrue(result.startsWith("No component found for '" + unknown + "'"), result);
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(unknown)), "asking must not mint a nid");
    }

    @Test
    void theLimitIsReportedAndNotMistakenForTheWholeAnswer() {
        String result = call("referencing_semantics", Map.of("id", uuid(KernelConcept.ENGLISH_LANGUAGE), "limit", 1));

        assertEquals(1, SEMANTIC_HEADING.matcher(result).results().count(), result);
        assertTrue(result.contains("STOPPED AT THE LIMIT"), result);
    }

    @Test
    void aSearchOutOfTimeSaysSoAndKeepsWhatTheIndexGave() {
        GraphTools.References found = GraphTools.references(KernelConcept.ENGLISH_LANGUAGE.nid(), null, 1000,
                viewProperties.calculator(), Duration.ZERO);

        assertTrue(found.outOfTime(), "an exhausted budget is reported");
        assertEquals(0, found.searched());
        assertTrue(found.total() > 0);
        assertFalse(found.referrers().isEmpty(), "the attached semantics come from the index, not the search");
        String text = GraphTools.render(GraphTools.component(uuid(KernelConcept.ENGLISH_LANGUAGE),
                viewProperties.calculator()), found);
        assertTrue(text.contains("STOPPED FOR TIME"), text);
    }

    // ── identifiers the store does not hold ─────────────────────────────

    /**
     * A nid minted for an identifier reaches the store's Identities with no record behind it, where
     * every later session scans for it. A model can hand any tool an identifier it made up, so
     * none may mint one. Each call gets an identifier of its own, so one tool's mint cannot hide
     * another's.
     */
    @Test
    void noReadToolMintsANidForAnIdentifierTheStoreDoesNotHold() {
        for (String tool : List.of("concept", "parents", "children", "ancestors", "descendants",
                "axioms", "debug_concept", "concept_semantics", "pattern")) {
            UUID unknown = UUID.randomUUID();
            assertTrue(call(tool, Map.of("id", unknown.toString())).startsWith("No concept found for"), tool);
            assertNotMinted(tool + " given a UUID", unknown);

            String sctid = unknownSctid();
            assertTrue(call(tool, Map.of("id", sctid)).startsWith("No concept found for"), tool);
            assertNotMinted(tool + " given an SCTID", UuidUtil.fromSNOMED(sctid));

            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            assertTrue(call(tool, Map.of("id", first + "," + second)).startsWith("No concept found for"), tool);
            assertNotMinted(tool + " given a public id", first);
            assertNotMinted(tool + " given a public id", second);
        }

        UUID unknownSemantic = UUID.randomUUID();
        assertTrue(call("semantic_info", Map.of("id", unknownSemantic.toString())).startsWith("No semantic found for"));
        assertNotMinted("semantic_info", unknownSemantic);

        UUID unknownChild = UUID.randomUUID();
        assertTrue(call("is_a", Map.of("child", unknownChild.toString(),
                "parent", uuid(KernelConcept.LANGUAGE))).startsWith("No concept found for"));
        assertNotMinted("is_a's child", unknownChild);

        UUID unknownParent = UUID.randomUUID();
        assertTrue(call("is_a", Map.of("child", uuid(KernelConcept.LANGUAGE),
                "parent", unknownParent.toString())).startsWith("No concept found for"));
        assertNotMinted("is_a's parent", unknownParent);

        UUID unknownGround = UUID.randomUUID();
        assertTrue(GraphTools.resolveConcept(unknownGround.toString(), viewProperties.calculator()).isEmpty());
        assertNotMinted("the lift's grounding", unknownGround);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** An SCTID in the form of one that names nothing in the starter set. */
    /**
     * The chip path a caller reaches with a public id: every caller today asks first, so this is
     * the chip's own check. An id the store does not hold draws its identicon and takes no nid; a
     * stored one is still a badge.
     */
    @Test
    void aChipForAnIdentifierTheStoreDoesNotHoldDrawsOnlyItsIdenticon() {
        UUID unknown = UUID.randomUUID();
        Node chip = ConceptProposalTools.onFxThread(() -> ComposeChips.chip(PublicIds.of(unknown), viewProperties, 12));
        assertInstanceOf(ImageView.class, chip);
        assertNotMinted("a chip", unknown);

        PublicId language = PublicIds.of(UUID.fromString(uuid(KernelConcept.LANGUAGE)));
        Node stored = ConceptProposalTools.onFxThread(() -> ComposeChips.chip(language, viewProperties, 12));
        assertInstanceOf(KonceptBadge.class, stored);
    }

    private static String unknownSctid() {
        return "9000000000" + (10_000_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(80_000_000));
    }

    private static void assertNotMinted(String what, UUID uuid) {
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(uuid)), what + " minted a nid");
    }

    private String call(String tool, Map<String, Object> input) {
        return tools.get(tool).execute(input);
    }

    /** The answer gives the component's name, UUID and kind, and no nid. */
    private void assertAnswers(String result, long nid, String kind) {
        assertTrue(result.contains("\nname: " + ComponentText.fullyQualifiedName(viewProperties.calculator(), nid) + "\n"), result);
        assertTrue(result.contains("\nuuid: " + ComponentText.leastUuid(nid).orElseThrow() + "\n"), result);
        assertTrue(result.contains("\nkind: " + kind + "\n"), result);
        NidFree.assertNoNid("open_component", result, nid);
    }

    private MakeKLWindowEvent onlyKlWindow() {
        assertEquals(1, klWindows.size(), "one KL window: " + klWindows);
        assertTrue(semanticWindows.isEmpty());
        return klWindows.getFirst();
    }

    private void assertNothingOpened() {
        assertTrue(klWindows.isEmpty() && semanticWindows.isEmpty(), "nothing is opened");
    }

    private static String uuid(EntityFacade component) {
        return ComponentText.leastUuid(component.nid()).orElseThrow().toString();
    }

    private static String uuid(SemanticEntity<?> semantic) {
        return ComponentText.leastUuid(semantic.nid()).orElseThrow().toString();
    }

    /** A description of the concept, loaded so its reference and versions can be read. */
    @SuppressWarnings("unchecked")
    private static SemanticEntity<SemanticEntityVersion> descriptionOf(EntityFacade concept) {
        long nid = EntityService.get().semanticsForComponentOfPattern(concept.nid(), KernelPattern.DESCRIPTION_PATTERN.nid())
                .findFirst().orElseThrow().nid();
        return (SemanticEntity<SemanticEntityVersion>) EntityHandle.getEntityOrThrow(nid);
    }

    /** One semantic's block of a {@code referencing_semantics} answer. */
    private static String blockOf(String result, SemanticEntity<?> semantic) {
        List<String> blocks = new ArrayList<>(List.of(result.split("(?m)^(?=Pattern: )")));
        String id = "semantic [" + uuid(semantic) + "]";
        return blocks.stream().filter(block -> block.contains(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + id + " in:\n" + result));
    }
}
