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
package network.ike.komet.claude.instructions;

import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.klwindows.CardKlWindow;
import dev.ikm.komet.kview.klwindows.KlWindowPreferencesUtils;
import dev.ikm.komet.layout.context.KlContext;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.JournalWindows;
import dev.ikm.komet.layout_engine.host.JournalWindows.OpenWindow;
import dev.ikm.komet.layout_engine.host.MakeCardWindowEvent;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.events.EvtBusFactory;
import dev.ikm.tinkar.events.Subscriber;
import dev.ikm.tinkar.terms.KernelConcept;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.instructions.InstructionSets.InstructionSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Komet Assistant's tools on the journal's windows and its Instruction Editors, called as the
 * model calls them, on the starter set in a memory-only store. Each test has a journal of its own:
 * a topic, a preferences node, a listing of what is open, and where the journal would be, a
 * subscriber on the real event bus that does what the journal does with a {@code FOCUS_OR_REOPEN}
 * (restores the tile's card from its preferences and realizes it a pulse later). The Instruction
 * Editors are real cards in a scene of their own, so a proposal is checked on what the person
 * would see and on what is saved.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstructionEditorToolsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String BODY = "Use this skill when a person asks for a discharge summary.\n\n## Instructions\n\n- Keep it short.";

    private ViewProperties viewProperties;
    private UUID topic;
    private KometPreferences journal;
    private Runnable listingRegistration;
    private final List<OpenWindow> workspace = new CopyOnWriteArrayList<>();
    private final List<MakeCardWindowEvent> asked = new CopyOnWriteArrayList<>();
    private final List<AbstractHostCard> shown = new CopyOnWriteArrayList<>();
    private Map<String, AnthropicTool> tools;

    /** What the journal does with the gestures: front an open card, or restore and show a closed tile. */
    private final Subscriber<MakeCardWindowEvent> journalSubscriber = event -> {
        asked.add(event);
        if (event.getEventType() != MakeCardWindowEvent.FOCUS_OR_REOPEN) {
            return;
        }
        InstructionEditorCard open = InstructionEditorCard.open(event.getTile()).orElse(null);
        if (open != null) {
            if (event.getOnCreated() != null) {
                event.getOnCreated().accept(open);
            }
            return;
        }
        AbstractHostCard card = new InstructionEditorCard.Factory().restoreCard(journal.node(event.getTile()));
        shown.add(card);
        if (event.getOnCreated() != null) {
            event.getOnCreated().accept(card);
        }
        // The journal binds a card on the pulse after its window is shown.
        Platform.runLater(() -> realize(card));
    };

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
        viewProperties = onFx(() -> new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @BeforeEach
    void openAJournal() {
        topic = UUID.randomUUID();
        journal = KlWindowPreferencesUtils.getJournalPreferences(topic);
        workspace.clear();
        asked.clear();
        shown.clear();
        listingRegistration = JournalWindows.register(topic, this::listing);
        EvtBusFactory.getDefaultEvtBus().subscribe(topic, MakeCardWindowEvent.class, journalSubscriber);
        tools = new HashMap<>();
        new InstructionEditorTools(() -> topic, viewProperties::calculator).tools()
                .forEach(tool -> tools.put(tool.name(), tool));
    }

    @AfterEach
    void closeTheJournal() {
        EvtBusFactory.getDefaultEvtBus().unsubscribe(topic, MakeCardWindowEvent.class, journalSubscriber);
        listingRegistration.run();
        onFx(() -> {
            shown.forEach(AbstractHostCard::knowledgeLayoutUnbind);
            return null;
        });
        // The sets' Markdown files are the editor's own, beside the preferences: removing the node
        // leaves them, so they go first.
        journal.directory().ifPresent(folder -> {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".md")).toList()) {
                    Files.delete(file);
                }
            } catch (IOException | UncheckedIOException neverWritten) {
                // a journal never flushed has no folder
            }
        });
        try {
            journal.removeNode();
        } catch (Exception neverWritten) {
            // a node never flushed has no folder to delete
        }
    }

    @Test
    void theToolsAreTheSixTheContractNames() {
        assertEquals(List.of("list_windows", "open_instruction_editor", "list_instruction_sets", "read_instruction_set",
                        "propose_instruction_edit", "discard_instruction_proposal"),
                new InstructionEditorTools(() -> topic, viewProperties::calculator).tools().stream()
                        .map(AnthropicTool::name).toList());
    }

    @Test
    void aClosedEditorsSetsAreListedAndReadAsSaved() {
        String tile = closedEditor();
        InstructionSet set = createSet(tile, "Discharge summaries");

        String listed = call("list_instruction_sets", Map.of());
        assertTrue(listed.contains("(closed) contains"), listed);
        assertTrue(listed.contains("Discharge summaries [" + set.id() + "] · Skill\n"), listed);
        assertTrue(listed.contains("Komet tool contract [") && listed.contains("Tool Contract · read-only system default"),
                "the editor's system defaults are listed with it: " + listed);

        String read = call("read_instruction_set", Map.of("set", "discharge summaries"));
        assertTrue(read.startsWith("Instruction set \"Discharge summaries\" [" + set.id() + "]"), read);
        assertTrue(read.contains("name: Discharge summaries\ndescription: When a person asks for a discharge summary\n"
                + "category: Skill\n---\n\n" + BODY), read);
        assertFalse(read.contains("unsaved"), "a closed editor has nothing unsaved: " + read);
    }

    @Test
    void theWindowsListedAreTheJournalsWithTheirComponentsAndTheClosedEditors() {
        String tile = closedEditor();
        UUID english = KernelConcept.ENGLISH_LANGUAGE.publicId().asUuidArray()[0];
        workspace.add(new OpenWindow("card", "Rules", "", List.of(), "network.ike.komet.claude.rules.RulesCard$Factory",
                "journal_card_rules"));
        workspace.add(new OpenWindow("concept", "", "", List.of(english), "", "concept_x"));

        String listed = call("list_windows", Map.of());

        assertTrue(listed.startsWith("The person's journal has 2 windows open"), listed);
        assertTrue(listed.contains("\n- Rules\n"), listed);
        assertTrue(listed.contains("\n- Concept window: ") && listed.contains("[" + english + "]"), listed);
        assertTrue(listed.contains("Closed Instruction Editors") && listed.contains(label(tile)), listed);
    }

    @Test
    void anOpenEditorIsListedWithTheSetOnScreenAndItsUnsavedChanges() {
        InstructionEditorCard card = openEditor();
        InstructionSet set = createSet(card.tile(), "Discharge summaries");
        onFx(() -> card.showSet(set));
        type(card, "Discharge summaries", "Discharge summaries, shorter");

        String listed = call("list_windows", Map.of());

        assertTrue(listed.contains("Instruction Editor \"" + label(card.tile()) + "\", showing the instruction set "
                + "\"Discharge summaries\" [" + set.id() + "], with unsaved changes"), listed);
        assertFalse(listed.contains("Closed Instruction Editors"), listed);
        String read = call("read_instruction_set", Map.of("set", set.id()));
        assertTrue(read.contains("The Instruction Editor shows this set now with unsaved changes. On screen:\n\n---\n"
                + "name: Discharge summaries, shorter"), read);
    }

    @Test
    void aProposalReopensAClosedEditorAndWaitsThereUnsaved() {
        String tile = closedEditor();
        InstructionSet set = createSet(tile, "Discharge summaries");
        String revised = BODY + "\n- Name the discharging clinician.";

        String result = call("propose_instruction_edit", Map.of("set", set.id(), "body", revised));

        assertTrue(result.startsWith("Proposed new text for \"Discharge summaries\""), result);
        assertTrue(result.contains("was closed; it is reopening") && result.contains("NOTHING IS SAVED until the person "
                + "presses Save;"), result);
        assertEquals(1, asked.size());
        assertEquals(MakeCardWindowEvent.FOCUS_OR_REOPEN, asked.getFirst().getEventType());
        assertEquals(tile, asked.getFirst().getTile(), "the tile that contains the set, never a new one");
        settle();
        InstructionEditorCard card = InstructionEditorCard.open(tile).orElseThrow();
        assertEquals(set.id(), onFx(() -> card.shownSet().orElseThrow().id()));
        assertTrue(onFx(card::hasUnsavedChanges));
        assertTrue(onFx(card::shownDocument).endsWith(revised), "the proposal is on screen");
        assertEquals("the Komet Assistant", onFx(() -> card.proposalWaiting(set.id()).orElseThrow()));
        assertEquals(BODY, savedBody(tile, set), "nothing is saved");
    }

    @Test
    void aProposalWaitsWithItsSetWhenAnotherSetHasUnsavedChanges() {
        InstructionEditorCard card = openEditor();
        InstructionSet mine = createSet(card.tile(), "My notes");
        InstructionSet other = createSet(card.tile(), "Discharge summaries");
        onFx(() -> card.showSet(mine));
        type(card, "My notes", "My notes, being edited");

        String result = call("propose_instruction_edit", Map.of("set", other.id(), "body", BODY + "\n- More."));

        assertTrue(result.contains("which has unsaved changes, so it stayed on that set"), result);
        assertEquals(mine.id(), onFx(() -> card.shownSet().orElseThrow().id()), "the person's text is not lost");
        assertTrue(onFx(card::shownDocument).contains("name: My notes, being edited"));
        assertEquals("the Komet Assistant", onFx(() -> card.proposalWaiting(other.id()).orElseThrow()));
        assertTrue(asked.isEmpty(), "an open editor is used as it is");

        String withdrawn = call("discard_instruction_proposal", Map.of("set", other.id()));
        assertTrue(withdrawn.startsWith("Withdrew your proposal for \"Discharge summaries\""), withdrawn);
        assertTrue(onFx(() -> card.proposalWaiting(other.id()).isEmpty()));
        assertTrue(onFx(card::shownDocument).contains("name: My notes, being edited"), "the person's text stays");
    }

    @Test
    void withdrawingAProposalOnScreenPutsBackTheTextFromBefore() {
        InstructionEditorCard card = openEditor();
        InstructionSet set = createSet(card.tile(), "Discharge summaries");
        onFx(() -> card.showSet(set));
        String before = onFx(card::shownDocument);

        String proposed = call("propose_instruction_edit", Map.of("set", "Discharge summaries", "body", BODY + "\n- More.",
                "description", "When a discharge summary is asked for", "category", "Skill"));
        assertTrue(proposed.contains("shows it now as tracked changes"), proposed);
        assertTrue(onFx(card::shownDocument).contains("description: When a discharge summary is asked for\n"));

        call("discard_instruction_proposal", Map.of("set", set.id()));

        assertEquals(before, onFx(card::shownDocument));
        assertFalse(onFx(card::hasUnsavedChanges));
        String again = call("discard_instruction_proposal", Map.of("set", set.id()));
        assertTrue(again.startsWith("Error: No proposal of yours waits on \"Discharge summaries\""), again);
    }

    @Test
    void aSystemDefaultTakesAProposalThatOnlySaveAsKeeps() {
        InstructionEditorCard card = openEditor();
        InstructionSet contract = onFx(() -> new InstructionSets(cardNode(card.tile())).list().stream()
                .filter(set -> set.name().equals("Komet tool contract")).findFirst().orElseThrow());

        String result = call("propose_instruction_edit", Map.of("set", contract.id(), "body", "Only search."));

        assertTrue(result.contains("presses Save as… (this is a read-only system default, so it is kept as a copy"), result);
        assertTrue(onFx(card::hasUnsavedChanges));
        assertTrue(savedBody(card.tile(), contract).contains("`list_windows`"), "the default is as it was, and current");
    }

    @Test
    void openingAtASetFrontsTheEditorThatContainsIt() {
        String tile = closedEditor();
        InstructionSet set = createSet(tile, "Discharge summaries");

        String reopened = call("open_instruction_editor", Map.of("set", set.id()));
        assertTrue(reopened.startsWith("Reopened \"" + label(tile) + "\", whose window was closed, at \"Discharge "
                + "summaries\""), reopened);
        settle();
        InstructionEditorCard card = InstructionEditorCard.open(tile).orElseThrow();
        assertEquals(set.id(), onFx(() -> card.shownSet().orElseThrow().id()));

        String fronted = call("open_instruction_editor", Map.of());
        assertTrue(fronted.startsWith("Brought \"" + label(tile) + "\" to the front, showing the instruction set "
                + "\"Discharge summaries\""), fronted);
        assertEquals(1, asked.size(), "an open editor is fronted without asking the journal");
    }

    @Test
    void aJournalWithNoEditorGetsANewOneOnlyWhenOpened() {
        assertTrue(call("list_instruction_sets", Map.of()).startsWith("The journal has no Instruction Editor"));

        String opened = call("open_instruction_editor", Map.of());

        assertTrue(opened.startsWith("The journal had no Instruction Editor, so a new one was made"), opened);
        assertEquals(MakeCardWindowEvent.FOCUS_OR_CREATE, asked.getFirst().getEventType());
        assertEquals(InstructionEditorCard.Factory.class.getName(), asked.getFirst().getProviderClassName());
    }

    /** The assistant card's settings button, "Open in Instruction Editor…", with the bundled default chosen, then a set. */
    @Test
    void theSettingsButtonReopensThePersonsClosedEditorAndNeverMakesASecond() {
        String tile = closedEditor();
        InstructionSet set = createSet(tile, "Discharge summaries");

        onFx(() -> {
            InstructionEditorTools.openForPerson(this, topic, "");
            return null;
        });

        assertEquals(1, asked.size());
        assertEquals(MakeCardWindowEvent.FOCUS_OR_REOPEN, asked.getFirst().getEventType(),
                "the person's closed editor is reopened, never a second one made");
        assertEquals(tile, asked.getFirst().getTile());
        settle();
        InstructionEditorCard card = InstructionEditorCard.open(tile).orElseThrow();

        onFx(() -> {
            InstructionEditorTools.openForPerson(this, topic, set.id());
            return null;
        });

        assertEquals(1, asked.size(), "an open editor is fronted without asking the journal");
        assertEquals(set.id(), onFx(() -> card.shownSet().orElseThrow().id()), "at the set chosen in the settings");
    }

    @Test
    void theSettingsButtonOpensTheEditorThatContainsTheChosenSet() {
        openEditor();
        String closed = closedEditor();
        InstructionSet set = createSet(closed, "Discharge summaries");

        onFx(() -> {
            InstructionEditorTools.openForPerson(this, topic, set.id());
            return null;
        });

        assertEquals(1, asked.size());
        assertEquals(MakeCardWindowEvent.FOCUS_OR_REOPEN, asked.getFirst().getEventType());
        assertEquals(closed, asked.getFirst().getTile(), "the editor that contains the set, not the open one");
        settle();
        assertEquals(set.id(), onFx(() -> InstructionEditorCard.open(closed).orElseThrow().shownSet().orElseThrow().id()));
    }

    @Test
    void refusalsSayWhyAndChangeNothing() {
        String tile = closedEditor();
        InstructionSet set = createSet(tile, "Discharge summaries");

        assertTrue(call("read_instruction_set", Map.of("set", "No such set")).startsWith(
                "Error: No instruction set in the journal's Instruction Editors has the id or name \"No such set\""));
        assertTrue(call("propose_instruction_edit", Map.of("set", set.id())).startsWith("Error: \"body\" is required."));
        assertTrue(call("propose_instruction_edit", Map.of("set", set.id(), "body", BODY)).contains(
                "is the same as the saved text"));
        assertTrue(call("propose_instruction_edit", Map.of("set", set.id(), "body", "x", "category", "Persona"))
                .startsWith("Error: category must be one of [System Prompt, Skill, Tool Contract]"));
        assertTrue(asked.isEmpty(), "nothing was opened");
        assertEquals(BODY, savedBody(tile, set));

        listingRegistration.run();
        assertTrue(call("list_windows", Map.of()).startsWith("Error: The person's journal is not open"));
        String unattached = new InstructionEditorTools(() -> null, viewProperties::calculator).tools().getFirst()
                .execute(Map.of());
        assertTrue(unattached.startsWith("Error: The assistant is not attached to a journal yet"), unattached);
    }

    /*
     * The journal's part, done as the journal does it.
     */

    /** A new Instruction Editor tile on the journal, its card realized in a scene of its own. */
    private InstructionEditorCard openEditor() {
        return onFx(() -> {
            KometPreferences window = journal.node(CardKlWindow.CARD_WINDOW_TYPE.getPrefix() + UUID.randomUUID());
            AbstractHostCard card = new InstructionEditorCard.Factory().createCard(window, topic);
            shown.add(card);
            realize(card);
            return (InstructionEditorCard) card;
        });
    }

    /**
     * What the journal lists: the cards it shows, described as the journal describes a card window,
     * after the other windows a test puts on the workspace.
     */
    private List<OpenWindow> listing() {
        List<OpenWindow> open = new ArrayList<>(workspace);
        for (AbstractHostCard card : shown) {
            String tile = card.preferences().parent().name();
            open.add(new OpenWindow("card", card.title(), label(tile), List.of(),
                    InstructionEditorCard.Factory.class.getName(), tile));
        }
        return open;
    }

    /** An Instruction Editor tile whose window was open once and is now closed: its sets stay in its node. */
    private String closedEditor() {
        InstructionEditorCard card = openEditor();
        String tile = card.tile();
        onFx(() -> {
            card.knowledgeLayoutUnbind();
            shown.remove(card);
            return null;
        });
        assertTrue(InstructionEditorCard.open(tile).isEmpty());
        return tile;
    }

    private void realize(AbstractHostCard card) {
        new Scene((Parent) card.fxObject(), 1000, 700);
        card.setSeedParentContext(KlContext.overView(card, viewProperties.nodeView(), "Test journal"));
        card.realize();
    }

    /** A set the person made in the tile, as the editor's New and Save make one. */
    private InstructionSet createSet(String tile, String name) {
        return new InstructionSets(cardNode(tile))
                .create(name, "When a person asks for a discharge summary", InstructionCategory.SKILL, BODY)
                .orElseThrow();
    }

    private KometPreferences cardNode(String tile) {
        return journal.node(tile).node(InstructionEditorCard.class.getSimpleName());
    }

    private String label(String tile) {
        return cardNode(tile).get(InstructionEditorCard.TILE_LABEL_KEY, "");
    }

    private String savedBody(String tile, InstructionSet set) {
        return new InstructionSets(cardNode(tile)).read(set).orElseThrow().body();
    }

    /** Types into the field that shows the given text, as a person would. */
    private static void type(InstructionEditorCard card, String showing, String typed) {
        onFx(() -> {
            // A split pane's items join the scene graph with its skin: a CSS and layout pass make them.
            card.fxObject().applyCss();
            card.fxObject().layout();
            for (Node node : card.fxObject().lookupAll(".text-field")) {
                if (node instanceof TextField field && showing.equals(field.getText())) {
                    field.setText(typed);
                    return null;
                }
            }
            throw new IllegalStateException("No field shows \"" + showing + "\"");
        });
    }

    /** Lets the pulses the journal and the card queue run. */
    private static void settle() {
        for (int i = 0; i < 3; i++) {
            onFx(() -> null);
        }
    }

    private String call(String tool, Map<String, Object> input) {
        return tools.get(tool).execute(new HashMap<>(input));
    }

    private static <T> T onFx(Supplier<T> work) {
        return ConceptProposalTools.onFxThread(work);
    }
}
