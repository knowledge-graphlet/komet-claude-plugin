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

import dev.ikm.komet.kview.klwindows.CardKlWindow;
import dev.ikm.komet.kview.klwindows.KlWindowPreferencesUtils;
import dev.ikm.komet.layout_engine.host.JournalWindows;
import dev.ikm.komet.layout_engine.host.JournalWindows.OpenWindow;
import dev.ikm.komet.layout_engine.host.MakeCardWindowEvent;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.events.EvtBusFactory;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.instructions.InstructionEditorCard.Shown;
import network.ike.komet.claude.instructions.InstructionSets.Frontmatter;
import network.ike.komet.claude.instructions.InstructionSets.InstructionSet;
import network.ike.komet.claude.tools.GraphTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Komet Assistant's tools on the person's journal and its Instruction Editors: list the
 * windows open on the journal, open the Instruction Editor, list and read the instruction sets its
 * tiles contain, propose a set's new text, and withdraw that proposal.
 *
 * <p>A proposal is shown in the Instruction Editor as tracked changes, the way the editor's own
 * Assistant drawer shows a draft: nothing is saved until the person presses Save there (Save as…
 * for a read-only system default), and Revert draft takes it back. No tool here saves a set.
 *
 * <p>The sets live in the journal's Instruction Editor tiles, whether a tile's window is open or
 * closed. A tile whose window is closed is reopened, never replaced by a new one, so the sets it
 * contains are the ones the person sees.
 */
public final class InstructionEditorTools {

    private static final String NOT_ATTACHED =
            "The assistant is not attached to a journal yet, so there are no windows or Instruction Editors to see.";

    private static final String NOT_OPEN =
            "The person's journal is not open, so there is nothing on its workspace to see or open.";

    /** The provider an Instruction Editor's card window names. */
    private static final String EDITOR_PROVIDER = InstructionEditorCard.Factory.class.getName();

    private final Supplier<UUID> journalTopic;
    private final Supplier<ViewCalculator> view;

    /**
     * @param journalTopic the person's journal; may supply null before the card is attached
     * @param view         names the components windows show; may supply null
     */
    public InstructionEditorTools(Supplier<UUID> journalTopic, Supplier<ViewCalculator> view) {
        this.journalTopic = journalTopic;
        this.view = view;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the tools
     */
    public List<AnthropicTool> tools() {
        return List.of(listWindows(), openInstructionEditor(), listInstructionSets(), readInstructionSet(),
                proposeInstructionEdit(), discardInstructionProposal());
    }

    /*
     * The journal's Instruction Editor tiles.
     */

    /**
     * One Instruction Editor tile of a journal, its window open or closed.
     *
     * @param name  the tile's preferences node, a child of the journal's node
     * @param label the name the journal's Tiles menu lists it under
     * @param card  the card's preferences node, which contains the tile's sets
     */
    record Tile(String name, String label, KometPreferences card) {

        /** @return the tile's sets */
        InstructionSets sets() {
            return new InstructionSets(card);
        }
    }

    /** A set and the tile that contains it. */
    record Located(Tile tile, InstructionSet set) {
    }

    /**
     * The Instruction Editor tiles of a journal, open or closed, in the order of their labels.
     *
     * @param journalTopic the journal
     * @return the tiles
     */
    static List<Tile> tiles(UUID journalTopic) {
        List<Tile> tiles = new ArrayList<>();
        try {
            KometPreferences journal = KlWindowPreferencesUtils.getJournalPreferences(journalTopic);
            String cardNode = InstructionEditorCard.class.getSimpleName();
            for (KometPreferences window : journal.children()) {
                if (!window.name().startsWith(CardKlWindow.CARD_WINDOW_TYPE.getPrefix()) || !window.nodeExists(cardNode)) {
                    continue;
                }
                KometPreferences card = window.node(cardNode);
                if (InstructionEditorCard.CARD_TYPE_VALUE.equals(card.get(InstructionEditorCard.CARD_TYPE_KEY, ""))) {
                    tiles.add(new Tile(window.name(), card.get(InstructionEditorCard.TILE_LABEL_KEY,
                            InstructionEditorCard.CARD_NAME), card));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the journal's Instruction Editor tiles: " + e.getMessage(), e);
        }
        tiles.sort(Comparator.comparing(Tile::label, String.CASE_INSENSITIVE_ORDER));
        return tiles;
    }

    /**
     * The set a tool names, by its id or its exact name, across the journal's tiles.
     *
     * @param journalTopic the journal
     * @param wanted       the set's id or name
     * @return the set and its tile
     */
    static Located locate(UUID journalTopic, String wanted) {
        String key = wanted == null ? "" : wanted.strip();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("\"set\" is required: the set's id, as list_instruction_sets gives it, "
                    + "or its exact name.");
        }
        List<Located> byName = new ArrayList<>();
        for (Tile tile : tiles(journalTopic)) {
            InstructionSets sets = tile.sets();
            for (InstructionSet set : sets.list()) {
                if (set.id().equals(key)) {
                    return new Located(tile, set);
                }
                if (set.name().equalsIgnoreCase(key)) {
                    byName.add(new Located(tile, set));
                }
            }
            if (sets.byId(key).isPresent()) {
                throw new IllegalArgumentException("The instruction set " + key + " is in the Trash. The person can put "
                        + "it back from the Trash in the Instruction Editor.");
            }
        }
        if (byName.size() == 1) {
            return byName.getFirst();
        }
        if (byName.size() > 1) {
            StringBuilder named = new StringBuilder();
            for (Located located : byName) {
                named.append("\n- ").append(located.set().name()).append(" [").append(located.set().id())
                        .append("] in \"").append(located.tile().label()).append('"');
            }
            throw new IllegalArgumentException("More than one instruction set is named \"" + key
                    + "\". Name it by its id:" + named);
        }
        throw new IllegalArgumentException("No instruction set in the journal's Instruction Editors has the id or name \""
                + key + "\". list_instruction_sets lists the sets there are.");
    }

    /** What an open editor shows, read on the JavaFX thread. */
    private record EditorState(InstructionSet shown, boolean unsaved, String onScreen) {
    }

    /** The state of a tile's open editor, or empty when its window is closed. Call on the JavaFX thread. */
    private static Optional<EditorState> state(Tile tile) {
        return InstructionEditorCard.open(tile.name()).map(card -> new EditorState(card.shownSet().orElse(null),
                card.hasUnsavedChanges(), card.shownDocument()));
    }

    /*
     * The tools.
     */

    private AnthropicTool listWindows() {
        return tool("list_windows", """
                        List the windows open on the person's journal: each card by its title (an Instruction \
                        Editor, the Rules card, a Stamped Document, an assistant), and each concept, pattern or \
                        semantic window by the component it shows. For an open Instruction Editor it says which \
                        instruction set is on screen and whether it has unsaved changes; it also names the \
                        Instruction Editors whose windows are closed. Call it before you answer whether a window \
                        is open. Takes no input. It only reads; it opens and closes nothing.""",
                objectSchema(Map.of(), List.of()),
                input -> {
                    UUID topic = topic();
                    record Seen(List<OpenWindow> windows, List<Tile> tiles, Map<String, EditorState> editors) {
                    }
                    Seen seen = ConceptProposalTools.onFxThread(() -> {
                        List<OpenWindow> windows = JournalWindows.of(topic).orElse(null);
                        List<Tile> tiles = tiles(topic);
                        Map<String, EditorState> editors = new LinkedHashMap<>();
                        for (Tile tile : tiles) {
                            state(tile).ifPresent(state -> editors.put(tile.name(), state));
                        }
                        return new Seen(windows, tiles, editors);
                    });
                    if (seen.windows() == null) {
                        throw new IllegalStateException(NOT_OPEN);
                    }
                    StringBuilder text = new StringBuilder();
                    text.append(seen.windows().isEmpty() ? "No window is open on the person's journal."
                            : "The person's journal has " + count(seen.windows().size(), "window")
                                    + " open, in the order the journal keeps them:");
                    for (OpenWindow window : seen.windows()) {
                        text.append("\n- ").append(describe(window));
                        EditorState editor = seen.editors().get(window.tile());
                        if (editor != null) {
                            text.append(editorSays(editor));
                        }
                    }
                    List<String> closed = seen.tiles().stream()
                            .filter(tile -> seen.windows().stream().noneMatch(window -> window.tile().equals(tile.name())))
                            .map(tile -> "\"" + tile.label() + "\"")
                            .toList();
                    if (!closed.isEmpty()) {
                        text.append("\nClosed Instruction Editors, which the journal's Tiles menu reopens (as does "
                                + "open_instruction_editor): ").append(String.join(", ", closed)).append('.');
                    }
                    return text.toString();
                });
    }

    /** One open window, in words. */
    private String describe(OpenWindow window) {
        return switch (window.kind()) {
            case "card" -> {
                String title = window.title().isBlank() ? "Card" : window.title();
                yield window.label().isBlank() || window.label().equals(title) ? title
                        : title + " \"" + window.label() + "\"";
            }
            case "concept", "pattern", "semantic" -> capitalized(window.kind()) + " window: " + component(window);
            case "layout" -> "Layout window" + (window.title().isBlank() ? "" : " \"" + window.title() + "\"")
                    + (window.component().isEmpty() ? "" : ": " + component(window));
            default -> capitalized(window.kind()) + " window"
                    + (window.component().isEmpty() ? "" : ": " + component(window));
        };
    }

    /** The component a window shows, by name and UUID, or only its UUID when the view cannot name it. */
    private String component(OpenWindow window) {
        if (window.component().isEmpty()) {
            return "a new component, not yet saved";
        }
        String uuid = window.component().getFirst().toString();
        ViewCalculator calculator = view.get();
        if (calculator != null) {
            try {
                GraphTools.Component component = GraphTools.component(uuid, calculator);
                return component.name() + " [" + component.uuid() + "]";
            } catch (RuntimeException notNamed) {
                // Not in the store under this view: the UUID alone still says which it is.
            }
        }
        return "[" + uuid + "]";
    }

    /** What an open Instruction Editor shows, in words, after its title. */
    private static String editorSays(EditorState editor) {
        if (editor.shown() == null) {
            return ", showing no instruction set";
        }
        return ", showing the instruction set \"" + editor.shown().name() + "\" [" + editor.shown().id() + "]"
                + (editor.unsaved() ? ", with unsaved changes" : "");
    }

    private AnthropicTool openInstructionEditor() {
        return tool("open_instruction_editor", """
                        Open the Instruction Editor in the person's journal and bring it to the front, at the \
                        instruction set you name. An editor whose window is closed is reopened with the sets it \
                        contains; with no Instruction Editor in the journal, a new one is made. Use it ONLY when \
                        the person asks to open or see the Instruction Editor or a set; propose_instruction_edit \
                        opens it by itself to show a proposal. When the editor has unsaved changes to another \
                        set, it stays on that set, so the person's text is not lost. Opening writes nothing.""",
                objectSchema(Map.of("set", strProp("""
                        Optional. The instruction set to show: its id, as list_instruction_sets gives it, or its \
                        exact name. Omit it to open the editor as it is.""")), List.of()),
                input -> {
                    UUID topic = topic();
                    Object wanted = input.get("set");
                    Located located = wanted == null || wanted.toString().isBlank() ? null : locate(topic, wanted.toString());
                    return ConceptProposalTools.onFxThread(() -> {
                        if (JournalWindows.of(topic).isEmpty()) {
                            throw new IllegalStateException(NOT_OPEN);
                        }
                        Tile tile = located != null ? located.tile() : editorToOpen(topic);
                        if (tile == null) {
                            EvtBusFactory.getDefaultEvtBus().publish(topic, new MakeCardWindowEvent(this,
                                    MakeCardWindowEvent.FOCUS_OR_CREATE, EDITOR_PROVIDER));
                            return "The journal had no Instruction Editor, so a new one was made and opened. It "
                                    + "contains Komet's system defaults: read-only sets the person can copy with "
                                    + "Save as…. Nothing else was written.";
                        }
                        Optional<InstructionEditorCard> open = InstructionEditorCard.open(tile.name());
                        if (open.isPresent()) {
                            InstructionEditorCard card = open.get();
                            card.reveal();
                            if (located == null) {
                                return "Brought \"" + tile.label() + "\" to the front" + editorSays(state(tile).orElseThrow())
                                        + ". Nothing was written.";
                            }
                            return card.showSet(located.set()) == Shown.SHOWN
                                    ? "Brought \"" + tile.label() + "\" to the front, showing \"" + located.set().name()
                                            + "\". Nothing was written."
                                    : "Brought \"" + tile.label() + "\" to the front. It stayed on \""
                                            + card.shownSet().map(InstructionSet::name).orElse("")
                                            + "\", which has unsaved changes, so they are not lost. The person can open \""
                                            + located.set().name() + "\" from the editor's list once they save or revert "
                                            + "them. Nothing was written.";
                        }
                        EvtBusFactory.getDefaultEvtBus().publish(topic, MakeCardWindowEvent.focusOrReopen(this, tile.name(),
                                card -> {
                                    if (located != null && card instanceof InstructionEditorCard editor) {
                                        editor.whenShown(() -> editor.showSet(located.set()));
                                    }
                                }));
                        return "Reopened \"" + tile.label() + "\", whose window was closed"
                                + (located == null ? "" : ", at \"" + located.set().name() + "\"") + ". Nothing was written.";
                    });
                });
    }

    /** The editor to open with no set named: an open one, else a closed one, else none. Call on the FX thread. */
    private static Tile editorToOpen(UUID topic) {
        List<Tile> tiles = tiles(topic);
        return tiles.stream().filter(tile -> InstructionEditorCard.open(tile.name()).isPresent()).findFirst()
                .orElse(tiles.isEmpty() ? null : tiles.getFirst());
    }

    private AnthropicTool listInstructionSets() {
        return tool("list_instruction_sets", """
                        List the instruction sets the journal's Instruction Editors contain: each set's name, id, \
                        category (System Prompt, Skill or Tool Contract), description, and whether it is a \
                        read-only system default, under the editor that contains it. A Skill set a person wrote is \
                        one of your skills; a System Prompt or Tool Contract set is chosen in an assistant card's \
                        settings. Call it before reading or proposing an edit to a set, to find its id. Takes no \
                        input. It only reads.""",
                objectSchema(Map.of(), List.of()),
                input -> {
                    UUID topic = topic();
                    List<Tile> tiles = tiles(topic);
                    if (tiles.isEmpty()) {
                        return "The journal has no Instruction Editor, so it contains no instruction sets. "
                                + "open_instruction_editor makes one when the person asks for it.";
                    }
                    Map<String, Boolean> open = ConceptProposalTools.onFxThread(() -> {
                        Map<String, Boolean> shown = new LinkedHashMap<>();
                        tiles.forEach(tile -> shown.put(tile.name(), InstructionEditorCard.open(tile.name()).isPresent()));
                        return shown;
                    });
                    StringBuilder text = new StringBuilder();
                    for (Tile tile : tiles) {
                        List<InstructionSet> sets = tile.sets().list();
                        text.append(text.isEmpty() ? "" : "\n\n").append('"').append(tile.label()).append("\" (")
                                .append(open.get(tile.name()) ? "open" : "closed").append(") contains ")
                                .append(count(sets.size(), "instruction set")).append(sets.isEmpty() ? "." : ":");
                        for (InstructionSet set : sets) {
                            text.append("\n- ").append(set.name()).append(" [").append(set.id()).append("] · ")
                                    .append(set.category().display())
                                    .append(set.readOnly() ? " · read-only system default" : "");
                            if (!set.description().isBlank()) {
                                text.append("\n  ").append(set.description());
                            }
                        }
                    }
                    return text.toString();
                });
    }

    private AnthropicTool readInstructionSet() {
        return tool("read_instruction_set", """
                        Read one instruction set: its name, category, description and whole Markdown body, in \
                        the portable form (frontmatter, then the body), as it is saved. When an open Instruction \
                        Editor shows the set with unsaved changes, the text on screen is returned too, and a \
                        proposal waiting for the person's review is named. It only reads.""",
                objectSchema(Map.of("set", strProp("The set's id, as list_instruction_sets gives it, or its exact name.")),
                        List.of("set")),
                input -> {
                    UUID topic = topic();
                    Located located = locate(topic, required(input, "set"));
                    InstructionSet set = located.set();
                    String saved = savedDocument(located);
                    record OnScreen(EditorState state, Optional<String> proposal) {
                    }
                    OnScreen onScreen = ConceptProposalTools.onFxThread(() -> InstructionEditorCard.open(located.tile().name())
                            .map(card -> new OnScreen(state(located.tile()).orElseThrow(), card.proposalWaiting(set.id())))
                            .orElse(null));
                    StringBuilder text = new StringBuilder();
                    text.append("Instruction set \"").append(set.name()).append("\" [").append(set.id()).append("] in \"")
                            .append(located.tile().label()).append("\"\ncategory: ").append(set.category().display());
                    if (set.readOnly()) {
                        text.append("\nread-only: a system default. A change to it is kept as a copy, with Save as….");
                    }
                    text.append("\n\nAs saved:\n\n").append(saved);
                    if (onScreen != null && onScreen.state().shown() != null && onScreen.state().shown().id().equals(set.id())
                            && onScreen.state().unsaved()) {
                        text.append("\n\nThe Instruction Editor shows this set now with unsaved changes. On screen:\n\n")
                                .append(onScreen.state().onScreen());
                    }
                    if (onScreen != null) {
                        onScreen.proposal().ifPresent(by -> text.append("\n\nA proposal from ").append(by)
                                .append(" waits for the person's review."));
                    }
                    return text.toString();
                });
    }

    /** A set as saved, in the portable form: the editor's index entries over the payload's body. */
    private static String savedDocument(Located located) {
        Frontmatter parsed = located.tile().sets().read(located.set()).orElseThrow(() -> new IllegalStateException(
                "The text of \"" + located.set().name() + "\" could not be read."));
        return InstructionSets.withFrontmatter(located.set().name(), located.set().description(),
                located.set().category(), parsed.body());
    }

    private AnthropicTool proposeInstructionEdit() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("set", strProp("The set's id, as list_instruction_sets gives it, or its exact name."));
        properties.put("body", strProp("""
                The set's whole new Markdown body, without frontmatter: every line the set should have, \
                changed and unchanged, since it replaces the body. Keep each k: token as it is."""));
        properties.put("name", strProp("Optional. A new name for the set; omit it to keep the name."));
        properties.put("description", strProp("""
                Optional. A new description: a sentence or two on what the set does and when to use it. Omit it \
                to keep the description."""));
        properties.put("category", Map.of("type", "string",
                "enum", Arrays.stream(InstructionCategory.values()).map(InstructionCategory::display).toList(),
                "description", "Optional. A new category; omit it to keep the category."));
        return tool("propose_instruction_edit", """
                        Propose new text for an instruction set, for the person to review. The Instruction Editor \
                        that contains the set opens (reopened if its window is closed) and shows your text as \
                        tracked changes. NOTHING IS SAVED: the person keeps it with Save there, or with Save as… \
                        for a read-only system default, which makes a copy and leaves the default as it is; Revert \
                        draft takes it back. Tell the person it is waiting there. Read the set with \
                        read_instruction_set first; when it returns text on screen with unsaved changes, build on \
                        that text, since yours replaces it on screen (Revert draft brings it back). When the \
                        editor has unsaved changes to another set, your proposal waits with this set and shows \
                        when the person opens it. Returns where it is shown.""",
                objectSchema(properties, List.of("set", "body")),
                input -> {
                    UUID topic = topic();
                    Located located = locate(topic, required(input, "set"));
                    InstructionSet set = located.set();
                    String body = required(input, "body");
                    String name = optional(input, "name").orElse(set.name());
                    String description = optional(input, "description").map(text -> text.replaceAll("\\s*\\R\\s*", " "))
                            .orElse(set.description());
                    InstructionCategory category = optional(input, "category").map(InstructionEditorTools::category)
                            .orElse(set.category());
                    String document = InstructionSets.withFrontmatter(name, description, category, body);
                    if (document.strip().equals(savedDocument(located).strip())) {
                        throw new IllegalArgumentException("The proposal is the same as the saved text of \"" + set.name()
                                + "\", so there is nothing to review. Nothing was proposed.");
                    }
                    String where = ConceptProposalTools.onFxThread(() -> {
                        if (JournalWindows.of(topic).isEmpty()) {
                            throw new IllegalStateException(NOT_OPEN);
                        }
                        Optional<InstructionEditorCard> open = InstructionEditorCard.open(located.tile().name());
                        if (open.isPresent()) {
                            InstructionEditorCard card = open.get();
                            Shown shown = card.proposeFromKometAssistant(set, document);
                            card.reveal();
                            return shown == Shown.SHOWN
                                    ? "\"" + located.tile().label() + "\" shows it now as tracked changes."
                                    : "\"" + located.tile().label() + "\" shows \"" + card.shownSet().map(InstructionSet::name)
                                            .orElse("") + "\", which has unsaved changes, so it stayed on that set. The "
                                            + "proposal waits with \"" + set.name() + "\" and shows when the person opens "
                                            + "it from the editor's list.";
                        }
                        EvtBusFactory.getDefaultEvtBus().publish(topic, MakeCardWindowEvent.focusOrReopen(this,
                                located.tile().name(), card -> {
                                    if (card instanceof InstructionEditorCard editor) {
                                        editor.whenShown(() -> editor.proposeFromKometAssistant(set, document));
                                    }
                                }));
                        return "\"" + located.tile().label() + "\" was closed; it is reopening, and shows it as tracked "
                                + "changes.";
                    });
                    return "Proposed new text for \"" + set.name() + "\" [" + set.id() + "]. " + where
                            + "\nNOTHING IS SAVED until the person presses "
                            + (set.readOnly() ? "Save as… (this is a read-only system default, so it is kept as a copy "
                                    + "under a new name and the default stays as it is)" : "Save")
                            + "; Revert draft takes it back.";
                });
    }

    private AnthropicTool discardInstructionProposal() {
        return tool("discard_instruction_proposal", """
                        Withdraw a proposal you made with propose_instruction_edit that the person has not saved. \
                        When the set is on screen, the editor shows the text it had before your proposal again, \
                        the person's own unsaved changes included. A draft from the editor's own Assistant drawer \
                        is left alone. Use it when the person asks you to drop the proposal, or when you proposed \
                        in error.""",
                objectSchema(Map.of("set", strProp("The set's id, as list_instruction_sets gives it, or its exact name.")),
                        List.of("set")),
                input -> {
                    UUID topic = topic();
                    Located located = locate(topic, required(input, "set"));
                    boolean withdrawn = ConceptProposalTools.onFxThread(() -> InstructionEditorCard.open(located.tile().name())
                            .map(card -> card.withdrawKometAssistantProposal(located.set().id()))
                            .orElse(false));
                    if (!withdrawn) {
                        throw new IllegalArgumentException("No proposal of yours waits on \"" + located.set().name()
                                + "\": the person saved it or reverted it, or its editor was closed, which drops a "
                                + "proposal. Nothing was withdrawn.");
                    }
                    return "Withdrew your proposal for \"" + located.set().name() + "\". Its saved text is as it was.";
                });
    }

    /*
     * Shared parts.
     */

    private UUID topic() {
        UUID topic = journalTopic.get();
        if (topic == null) {
            throw new IllegalStateException(NOT_ATTACHED);
        }
        return topic;
    }

    private static InstructionCategory category(String value) {
        for (InstructionCategory category : InstructionCategory.values()) {
            if (category.display().equalsIgnoreCase(value.strip())) {
                return category;
            }
        }
        throw new IllegalArgumentException("category must be one of " + Arrays.stream(InstructionCategory.values())
                .map(InstructionCategory::display).toList() + ", not \"" + value + "\".");
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String capitalized(String word) {
        return word.isEmpty() ? word : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1);
    }

    private static String required(Map<String, Object> input, String key) {
        return optional(input, key).orElseThrow(() -> new IllegalArgumentException("\"" + key + "\" is required."));
    }

    private static Optional<String> optional(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null || value.toString().isBlank() ? Optional.empty() : Optional.of(value.toString());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** A tool whose refusals come back to the model as errors. */
    private static AnthropicTool tool(String name, String description, Map<String, Object> schema,
                                      Function<Map<String, Object>, String> body) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return body.apply(input == null ? Map.of() : input);
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }
}
