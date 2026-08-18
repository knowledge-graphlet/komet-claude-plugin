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

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.layout.controls.KlDrawer;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import network.ike.komet.claude.ClaudeCard;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.instructions.InstructionSets.Frontmatter;
import network.ike.komet.claude.instructions.InstructionSets.InstructionSet;
import network.ike.komet.claude.ui.MarkdownDiff;
import network.ike.komet.claude.ui.MarkdownEditPane;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Instruction Editor card ({@code IKE-Network/ike-issues#1044}): the standardized,
 * <em>roleless</em> authoring tile for titled instruction sets — name and description (the
 * Agent Skills frontmatter surface) over a Markdown body in the view/edit pane. The rail lists
 * this tile's registered sets with New and Import…; the editor never asks whether a document is
 * a system prompt or a skill — that is the attachment role at the use site (the assistant's
 * settings pane selects; ike-issues#1042). Sets are payload files registered by preferences
 * entries ({@link InstructionSets}), so they ride the preferences git sync, and the tile carries
 * the {@code card-type} marker so use sites discover it journal-wide (the tile-store pickup
 * model). Assistant support inside this card is the recorded follow-on from the skills brief.
 */
public final class InstructionEditorCard extends AbstractHostCard {

    /** Menu label and card title. */
    static final String CARD_NAME = "Instruction Editor";

    /** Shared discovery key (the assistant tile marker uses the same literal). */
    public static final String CARD_TYPE_KEY = "card-type";

    /** This card kind's marker value — what use-site selectors discover tiles by. */
    public static final String CARD_TYPE_VALUE = "instruction-editor";

    /** Shared tile display-name key (the kind-plus-sequence naming convention). */
    public static final String TILE_LABEL_KEY = "tile-label";

    /** One-shot flag: the ghost examples were seeded (never re-seeded, so overwrites stick). */
    private static final String SEEDED_KEY = "instruction-sets-seeded";

    private static final Logger LOG = LoggerFactory.getLogger(InstructionEditorCard.class);

    private InstructionSets store;
    private ListView<InstructionSet> setList;
    private TextField nameField;
    private TextArea descriptionField;
    private javafx.scene.control.ComboBox<InstructionCategory> categoryBox;
    private MarkdownEditPane bodyPane;
    private Button saveButton;
    private Button saveAsButton;
    /** Pedro's READ-ONLY affordance, mirrored (the KLReadOnly control family's pill); shown
     *  for system defaults. Swaps to the shared control when the family exposes it. */
    private Label readOnlyPill;
    private Label statusLine;
    private Button revertDraftButton;
    private Button revertButton;
    private InstructionSet active;

    /** The pre-review snapshot a drafting proposal's track changes diff against. */
    private record ProposalBase(String name, String description, InstructionCategory category,
                                String body) {
    }

    /**
     * One instruction set's drafting conversation — in-memory this increment (KEC 2026-08-17;
     * durable transcripts with attic-style aging are a recorded follow-on). Every conversation
     * binds to exactly one set: history, exchanges, and any pending proposal are keyed by the
     * set and never coordinate across sets.
     */
    private static final class DraftingSession {
        final List<Map<String, Object>> apiHistory = new ArrayList<>();
        final List<ExchangeView> exchangePanes = new ArrayList<>();
        ProposalBase base;
        String proposal;
        boolean busy;
    }

    /**
     * One drafting exchange: a disclosure row — chevron plus the FULL question, bold and
     * wrapping, never truncated — over the answer at content height. {@code TitledPane} was
     * the wrong control here: its title is one non-wrapping line, and a fixed-height body
     * gave a cut-off answer with double scrollbars (KEC 2026-08-17). The surrounding list is
     * the only scroll surface.
     */
    private static final class ExchangeView extends VBox {
        private final Label chevron = new Label("▾");
        private final VBox answerBox = new VBox();
        private boolean expanded = true;

        ExchangeView(String questionText) {
            Label question = new Label(questionText);
            question.setWrapText(true);
            question.setStyle("-fx-font-weight: 600;");
            HBox headerRow = new HBox(6, chevron, question);
            headerRow.setAlignment(Pos.TOP_LEFT);
            headerRow.setCursor(Cursor.HAND);
            headerRow.setOnMouseClicked(e -> setExpanded(!expanded));
            HBox.setHgrow(question, Priority.ALWAYS);
            answerBox.getChildren().add(new Label("Working…"));
            setSpacing(6);
            getChildren().addAll(headerRow, answerBox);
            setStyle("-fx-background-color: white; -fx-background-radius: 6; "
                    + "-fx-border-color: #d7dbe0; -fx-border-radius: 6; -fx-padding: 8;");
        }

        void setAnswer(Node answer) {
            answerBox.getChildren().setAll(answer);
        }

        void setExpanded(boolean value) {
            expanded = value;
            chevron.setText(value ? "▾" : "▸");
            answerBox.setVisible(value);
            answerBox.setManaged(value);
        }
    }

    private final Map<String, DraftingSession> draftingSessions = new HashMap<>();
    private VBox assistantPanel;
    private Label draftingTitle;
    private VBox exchangesBox;
    private ScrollPane exchangesScroll;
    private TextArea draftingInput;
    private Button draftingSend;
    private Label draftingStatus;
    private String loadedName = "";
    private String loadedDescription = "";
    private InstructionCategory loadedCategory = InstructionCategory.SKILL;
    private String loadedBody = "";
    private BorderPane content;
    private boolean railVisible = true;
    private SplitPane split;
    private VBox rail;
    private VBox editorPane;
    private VBox emptyState;
    private BorderPane editorHost;

    private InstructionEditorCard(KometPreferences preferences) {
        super(preferences);
    }

    private InstructionEditorCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
    }

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** Card-node preference for the editor's base font size (the settings pane's section). */
    private static final String PREF_EDITOR_FONT_SIZE =
            "network.ike.komet.claude.instructionEditor.fontSize";

    /**
     * Card-node preference: the id of the titled System Prompt set steering this editor's
     * drafting assistant — the Instruction Editor's OWN system prompt, distinct from any
     * assistant card's (KEC 2026-08-17). Blank means the bundled drafting persona.
     */
    private static final String PREF_DRAFTING_PROMPT_SET =
            "network.ike.komet.claude.instructionEditor.draftingPromptSet";

    private double baseFontSize = 13;

    /** The transient rail toggle and the standardized settings sliders (ike-issues#1043). */
    @Override
    protected void buildToolbarControls(HBox toolBar) {
        Button toggleRail = new Button("\u2630");
        toggleRail.setTooltip(new Tooltip("Show/hide instruction sets"));
        toggleRail.setOnAction(e -> setRailVisible(!railVisible));
        toggleRail.getStyleClass().add("claude-card-toolbar-button");

        Button settings = new Button();
        settings.setGraphic(dev.ikm.komet.framework.graphics.Icon.PANEL_PREFERENCE_SLIDERS.makeIcon());
        settings.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        settings.setTooltip(new Tooltip("Instruction Editor settings"));
        settings.getStyleClass().addAll("claude-card-toolbar-button", "claude-card-settings-button");
        dev.ikm.komet.layout.controls.SettingsPanePopup pane =
                new dev.ikm.komet.layout.controls.SettingsPanePopup("Instruction Editor Settings");
        pane.addSection("Text size",
                () -> Math.round(baseFontSize) + " px",
                () -> textSizeSettingsContent(pane));
        pane.addSection("Drafting prompt",
                this::draftingPromptSummary,
                () -> draftingPromptSettingsContent(pane));
        pane.attachTo(settings, (javafx.scene.layout.Pane) fxObject());
        toolBar.getChildren().addAll(toggleRail, settings);
    }

    /** The text-size section: live adjustment of the body editor and its rendered view. */
    private javafx.scene.Node textSizeSettingsContent(
            dev.ikm.komet.layout.controls.SettingsPanePopup pane) {
        Label current = new Label(Math.round(baseFontSize) + " px");
        Button down = new Button("A\u2212");
        down.setOnAction(e -> {
            adjustFont(-1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        Button up = new Button("A+");
        up.setOnAction(e -> {
            adjustFont(1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        HBox row = new HBox(8, down, up, current);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Applies and persists the base font size: the raw editor's font, the view re-rendered. */
    private void adjustFont(int delta) {
        baseFontSize = Math.max(9, Math.min(28, baseFontSize + delta));
        preferences().putDouble(PREF_EDITOR_FONT_SIZE, baseFontSize);
        applyFont();
    }

    private void applyFont() {
        if (bodyPane != null) {
            bodyPane.rawEditor().setStyle("-fx-font-size: " + Math.round(baseFontSize) + "px;");
            bodyPane.setText(bodyPane.getText());   // re-render the at-rest view at the new base
        }
    }

    /** Shows or hides the instruction-set rail — full-tile content view (KEC 2026-08-17). */
    private void setRailVisible(boolean visible) {
        railVisible = visible;
        if (split == null) {
            return;
        }
        boolean present = split.getItems().contains(rail);
        if (visible && !present) {
            split.getItems().add(0, rail);
            split.setDividerPositions(0.28);
        } else if (!visible && present) {
            split.getItems().remove(rail);
        }
    }

    @Override
    protected void renderContent() {
        if (content == null) {
            buildBody();
            // Tile identity: the marker makes this tile discoverable by use-site selectors
            // (the assistant's system-prompt section); the display name follows the
            // kind-plus-sequence convention, minted once.
            preferences().put(CARD_TYPE_KEY, CARD_TYPE_VALUE);
            ensureTileLabel();
            seedExamplesOnce();
            refreshSets(null);
        }
    }

    /** The current seed generation — bump when a new system default joins the seed set. */
    private static final String SEED_GENERATION = "3";

    /**
     * Seeds the read-only system defaults, one per category (KEC 2026-08-17, settled): real
     * sets showing the form, versioned by generation so an already-seeded tile gains only the
     * defaults added since — names it has never seen, so a user who deleted an old seed is
     * never fought.
     */
    private void seedExamplesOnce() {
        String seeded = preferences().get(SEEDED_KEY, "");
        if (SEED_GENERATION.equals(seeded)) {
            return;
        }
        if (seeded.isBlank()) {
            // Generation 1: a System Prompt default and a sample Skill.
            store.create("Komet Assistant default",
                    "System default — the assistant's bundled instruction layer. Select it as the "
                            + "system prompt for stock behavior, or Save as… a copy to start your own.",
                    InstructionCategory.SYSTEM_PROMPT, true,
                    bundledResource("system-prompt-instructions.md"));
            store.create("Terminology answer style",
                    "System default — an example skill. Use when answers will be pasted into "
                            + "documents, issues, or reviews and need the lead-with-the-answer, "
                            + "badge-referenced form; Save as… to adapt.",
                    InstructionCategory.SKILL, true,
                    """
                    Use this skill when answers will be pasted into documents, issues, or reviews.

                    ## Instructions

                    - Lead with the answer in one sentence, then the supporting concepts as a table.
                    - Reference every concept as a Koncept Badge; label identifier columns as what \
                    they actually hold.
                    - Prefer the knowledge base's fully-qualified names; give colloquial synonyms in \
                    parentheses.
                    - Close with a one-line caveat when edition or version differences could matter.
                    """);
        } else {
            // A generation-1 tile from before the read-only ruling may carry writable ghosts —
            // mark the two known system defaults read-only once.
            for (InstructionSet set : store.list()) {
                if (!set.readOnly()
                        && ("Komet Assistant default".equals(set.name())
                                || "Terminology answer style".equals(set.name()))
                        && set.description().startsWith("Example")) {
                    store.markReadOnly(set.id());
                }
            }
        }
        if ("2".equals(seeded)) {
            // Generation 3 refreshes the drafting persona in place (conversation + the
            // propose_document protocol): OUR read-only content only — a tile whose default
            // was deleted is left alone, and user sets are untouched.
            for (InstructionSet set : store.list()) {
                if (set.readOnly() && "Instruction Editor default".equals(set.name())) {
                    store.refreshSystemDefault(set, bundledResource("instruction-editor-prompt.md"));
                }
            }
        } else {
            // Generation 2: a default for the remaining categories — the assistant's tool
            // contract, and the drafting persona behind this editor's own Ask Claude area.
            store.create("Komet tool contract",
                    "System default — the assistant's fixed tool-use mechanics: graph-tool calling "
                            + "discipline, grounding labels, identifier-column rules. Select as a "
                            + "card's tool contract; Save as… to improve it in our tooling.",
                    InstructionCategory.TOOL_CONTRACT, true,
                    bundledResource("system-prompt-core.md"));
            store.create("Instruction Editor default",
                    "System default — the drafting persona behind the Instruction Editor's Ask "
                            + "Claude area. It writes and revises titled instruction sets in the "
                            + "portable form; Save as… to tune how drafting behaves.",
                    InstructionCategory.SYSTEM_PROMPT, true,
                    bundledResource("instruction-editor-prompt.md"));
        }
        preferences().put(SEEDED_KEY, SEED_GENERATION);
        try {
            preferences().sync();
        } catch (Exception e) {
            LOG.warn("Could not sync the example seed flag", e);
        }
    }

    /** A bundled Markdown resource from this plugin, empty (never null) when unreadable. */
    static String bundledResource(String fileName) {
        try (java.io.InputStream in = InstructionEditorCard.class.getResourceAsStream(
                "/network/ike/komet/claude/" + fileName)) {
            if (in == null) {
                LOG.warn("No bundled resource {}", fileName);
                return "";
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            LOG.warn("Could not read the bundled resource {}", fileName, e);
            return "";
        }
    }

    private void buildBody() {
        // The card's own hue (KEC 2026-08-17): the plum identity stylesheet scopes to this
        // card's root, tinting tab, toolbar, and drawer bar as one authoring surface.
        fxObject().getStyleClass().add("instruction-editor-card");
        java.net.URL identity = InstructionEditorCard.class.getResource(
                "/network/ike/komet/claude/instruction-editor-card.css");
        if (identity != null) {
            fxObject().getStylesheets().add(identity.toExternalForm());
        }
        store = new InstructionSets(preferences());

        Label railTitle = new Label("Instruction sets");
        railTitle.setStyle("-fx-font-weight: bold;");
        Button newButton = new Button("+ New");
        newButton.setMinWidth(Region.USE_PREF_SIZE);
        newButton.setTooltip(new Tooltip("Create a new titled instruction set"));
        newButton.setOnAction(e -> newSet());
        Button importButton = new Button("Import…");
        importButton.setMinWidth(Region.USE_PREF_SIZE);
        importButton.setTooltip(new Tooltip("Import a SKILL.md-form instruction file"));
        importButton.setOnAction(e -> importSet());
        // Title and actions on separate rows: cramming them into one line ellipsized every
        // label into dot-stubs at rail width (KEC 2026-08-17 first-run review).
        HBox railActions = new HBox(6, newButton, importButton);
        railActions.setAlignment(Pos.CENTER_LEFT);
        VBox railHeader = new VBox(4, railTitle, railActions);
        railHeader.setPadding(new Insets(6));

        setList = new ListView<>();
        setList.setPrefWidth(210);
        setList.setCellFactory(lv -> new ListCell<>() {
            private final Label nameLabel = new Label();
            private final Label descriptionLabel = new Label();
            private final VBox row = new VBox(1, nameLabel, descriptionLabel);
            {
                nameLabel.setWrapText(true);
                // The rail scans, it doesn't read (KEC 2026-08-17): the description ellipsizes
                // on one line — the full routing text lives in the tooltip and the editor.
                descriptionLabel.setWrapText(false);
                setPrefWidth(0);
                maxWidthProperty().bind(lv.widthProperty().subtract(16));
                // Fills flip with selection AND focus: light fills belong only on the focused
                // selection highlight — the unfocused (grey) selection keeps dark text, or the
                // row reads blank (KEC 2026-08-17).
                Runnable syncFills = () -> {
                    boolean lit = isSelected() && lv.isFocused();
                    nameLabel.setStyle(lit
                            ? "-fx-font-weight: bold; -fx-text-fill: white;"
                            : "-fx-font-weight: bold; -fx-text-fill: #1a1a1a;");
                    descriptionLabel.setStyle(lit
                            ? "-fx-text-fill: #dce4f0; -fx-font-size: 11;"
                            : "-fx-text-fill: #6a6a6a; -fx-font-size: 11;");
                };
                syncFills.run();
                selectedProperty().addListener((obs, was, is) -> syncFills.run());
                lv.focusedProperty().addListener((obs, was, is) -> syncFills.run());
            }
            @Override
            protected void updateItem(InstructionSet set, boolean empty) {
                super.updateItem(set, empty);
                if (empty || set == null) {
                    setGraphic(null);
                    return;
                }
                nameLabel.setText(set.name());
                descriptionLabel.setText(
                        "[" + set.category().display() + "] " + set.description());
                setTooltip(set.description().isBlank() ? null
                        : new Tooltip(set.description()));
                setGraphic(row);
            }
        });
        setList.getSelectionModel().selectedItemProperty().addListener((obs, was, sel) -> {
            if (sel != null && sel != active) {
                openSet(sel);
            }
        });
        rail = new VBox(railHeader, setList);
        VBox.setVgrow(setList, Priority.ALWAYS);
        rail.setMinWidth(160);

        nameField = new TextField();
        nameField.setPromptText("Name (the set's title)");
        // The description is the ROUTING text (Agent Skills discipline): it is read before the
        // body is ever loaded, to decide whether to include the set — so it earns a sentence
        // or two, not one line. Stored as a single frontmatter scalar; newlines become spaces.
        descriptionField = new TextArea();
        descriptionField.setPromptText(
                "Description — what this set does and when to use it (a sentence or two)");
        descriptionField.setWrapText(true);
        descriptionField.setPrefRowCount(2);
        descriptionField.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB) {
                if (e.isShiftDown()) {
                    nameField.requestFocus();
                } else {
                    categoryBox.requestFocus();
                }
                e.consume();
            }
        });
        // The standard-category dropdown (KEC 2026-08-17): a closed, enum-backed list. Category
        // CLASSIFIES intent and drives which selectors surface the set; attachment at the use
        // site stays the enforcing act, so the editor remains roleless.
        categoryBox = new javafx.scene.control.ComboBox<>();
        categoryBox.getItems().setAll(InstructionCategory.values());
        categoryBox.getSelectionModel().select(InstructionCategory.SKILL);
        categoryBox.setTooltip(new Tooltip(
                "What this set is intended as — filters where selectors offer it"));
        baseFontSize = preferences().getDouble(PREF_EDITOR_FONT_SIZE, 13);
        // The renderer is built per render call so the settings pane's text size applies live.
        bodyPane = new MarkdownEditPane("", true,
                md -> new MarkdownRichText(safeViewProperties(), baseFontSize).renderMarkdown(md));
        VBox.setVgrow(bodyPane, Priority.ALWAYS);
        // Koncept drop-in (ike-issues#1042, knowledge-referencing instructions): a concept or
        // pattern dragged from anywhere in Komet drops into the raw editor as its k: token;
        // the rendered view shows it as a live badge.
        network.ike.komet.claude.ui.KonceptTokenDrop.install(bodyPane.rawEditor(),
                () -> safeViewProperties() == null ? null : safeViewProperties().calculator());

        statusLine = new Label("");
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        statusLine.setMinWidth(0);
        revertDraftButton = new Button("Revert draft");
        revertDraftButton.setMinWidth(Region.USE_PREF_SIZE);
        revertDraftButton.setTooltip(new Tooltip(
                "Discard the drafted changes and restore the pre-draft text"));
        revertDraftButton.setOnAction(e -> revertDraft());
        revertDraftButton.setVisible(false);
        revertDraftButton.setManaged(false);
        revertButton = new Button("Revert");
        revertButton.setMinWidth(Region.USE_PREF_SIZE);
        revertButton.setTooltip(new Tooltip(
                "Discard all unsaved changes and reload the saved set"));
        revertButton.setOnAction(e -> revertToSaved());
        saveButton = new Button("Save");
        saveButton.setMinWidth(Region.USE_PREF_SIZE);
        saveButton.setOnAction(e -> saveActive());
        saveAsButton = new Button("Save as…");
        saveAsButton.setMinWidth(Region.USE_PREF_SIZE);
        saveAsButton.setTooltip(new Tooltip("Save a copy under a new title"));
        saveAsButton.setOnAction(e -> saveAsCopy());
        HBox buttons = new HBox(8, statusLine, buttonSpacer, revertDraftButton,
                revertButton, saveButton, saveAsButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        nameField.textProperty().addListener((obs, was, is) -> updateChangeState());
        descriptionField.textProperty().addListener((obs, was, is) -> updateChangeState());
        categoryBox.valueProperty().addListener((obs, was, is) -> updateChangeState());
        bodyPane.rawEditor().textProperty().addListener((obs, was, is) -> updateChangeState());

        readOnlyPill = new Label("\ud83d\udd12 READ-ONLY");
        readOnlyPill.setStyle("-fx-border-color: #9aa4b2; -fx-border-radius: 12; "
                + "-fx-background-radius: 12; -fx-padding: 2 10 2 10; "
                + "-fx-text-fill: #5b6472; -fx-font-size: 11; -fx-font-weight: 600;");
        readOnlyPill.setVisible(false);
        readOnlyPill.setManaged(false);

        // One header row for the single-line controls (KEC 2026-08-17): the name grows, the
        // category and read-only pill keep their preferred size \u2014 never crushed to stubs \u2014
        // and the body's view/edit toggle rides at the far right instead of owning a row of
        // its own. The description then gets the full width below.
        categoryBox.setMinWidth(Region.USE_PREF_SIZE);
        readOnlyPill.setMinWidth(Region.USE_PREF_SIZE);
        autoGrow(descriptionField, DESCRIPTION_MIN_HEIGHT);

        // The settled layout (KEC 2026-08-17) mirrors the portable form's own order — name,
        // routing text, then classification/state/mode, then the document. The mode strip
        // reads left-to-right as "what this is → how you're viewing it": document facts
        // left-grouped, the View|Edit switch flush right, directly above the surface it
        // governs. The name keeps the full first row, so titles stop truncating.
        HBox modeSwitch = bodyPane.detachModeSwitch();
        Region stripSpacer = new Region();
        HBox.setHgrow(stripSpacer, Priority.ALWAYS);
        HBox modeStrip = new HBox(8, categoryBox, readOnlyPill, stripSpacer, modeSwitch);
        modeStrip.setAlignment(Pos.CENTER_LEFT);

        buttons.setPadding(new Insets(6, 0, 0, 0));
        buttons.setStyle("-fx-border-color: #d7dbe0 transparent transparent transparent; "
                + "-fx-border-width: 1 0 0 0;");
        editorPane = new VBox(10, nameField, descriptionField, modeStrip, bodyPane, buttons);
        editorPane.setPadding(new Insets(12));

        // First-run empty state: say what the card is for and offer the two first actions —
        // never a blank editor staring back (KEC 2026-08-17).
        Label emptyTitle = new Label("No instruction sets yet");
        emptyTitle.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        Label emptyBody = new Label("""
                A titled instruction set is a named, described Markdown instruction document \
                in the portable SKILL.md form. Sets authored here can be selected as an \
                assistant card's system prompt, or included as skills. Create one, or import \
                an existing SKILL.md file.""");
        emptyBody.setWrapText(true);
        emptyBody.setMaxWidth(420);
        Button emptyNew = new Button("+ New instruction set");
        emptyNew.setOnAction(e -> newSet());
        Button emptyImport = new Button("Import…");
        emptyImport.setOnAction(e -> importSet());
        HBox emptyActions = new HBox(8, emptyNew, emptyImport);
        emptyActions.setAlignment(Pos.CENTER);
        VBox empty = new VBox(10, emptyTitle, emptyBody, emptyActions);
        empty.setAlignment(Pos.CENTER);
        empty.setPadding(new Insets(24));
        emptyState = empty;

        editorHost = new BorderPane(emptyState);

        split = new SplitPane(rail, editorHost);
        SplitPane.setResizableWithParent(rail, Boolean.FALSE);
        split.setDividerPositions(0.28);
        applyFont();

        content = new BorderPane(split);
        // The assistant card's footprint (ike-issues#1045): sibling cards open at sibling sizes.
        content.setPrefSize(900, 680);
        setCardContent(content);

        // The Assistant drawer (KEC 2026-08-17): the drafting conversation is contiguous with
        // the card — the concept-properties paradigm — sliding out on the right with its
        // toggle at the properties position. Invoking it collapses the instruction-sets rail.
        buildAssistantPanel();
        KlDrawer assistantDrawer = addDrawer(Side.RIGHT, assistantPanel, "Assistant");
        assistantDrawer.expandedProperty().addListener((obs, was, open) -> {
            if (open) {
                setRailVisible(false);
                refreshDraftingPanel();
            }
        });
        if (assistantDrawer.expandedProperty().get()) {
            setRailVisible(false);
            refreshDraftingPanel();
        }
    }


    /** The card's view, or {@code null} before bind — chips then degrade to presentation. */
    private dev.ikm.komet.framework.view.ViewProperties safeViewProperties() {
        try {
            return getCardViewProperties();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Reloads the rail, keeping (or setting) the selection; empty swaps in the first-run state. */
    private void refreshSets(InstructionSet select) {
        InstructionSet target = select != null ? select
                : setList.getSelectionModel().getSelectedItem();
        setList.getItems().setAll(store.list());
        editorHost.setCenter(setList.getItems().isEmpty() ? emptyState : editorPane);
        if (setList.getItems().isEmpty()) {
            active = null;
            return;
        }
        if (target != null) {
            setList.getItems().stream()
                    .filter(set -> set.id().equals(target.id()))
                    .findFirst()
                    .ifPresent(set -> setList.getSelectionModel().select(set));
        } else {
            setList.getSelectionModel().select(0);
        }
    }

    /** Loads a set into the editor: index entries name the surface, the payload gives the body. */
    private void openSet(InstructionSet set) {
        active = set;
        nameField.setText(set.name());
        descriptionField.setText(set.description());
        categoryBox.getSelectionModel().select(set.category());
        Optional<Frontmatter> parsed = store.read(set);
        bodyPane.setText(parsed.map(Frontmatter::body).orElse(""));
        loadedName = set.name();
        loadedDescription = set.description();
        loadedCategory = set.category();
        loadedBody = bodyPane.getText();
        readOnlyPill.setVisible(set.readOnly());
        readOnlyPill.setManaged(set.readOnly());
        // The set's own drafting conversation follows it in: a proposal still under review
        // re-applies (buffer plus track changes); otherwise any review chrome clears.
        DraftingSession session = draftingSessions.get(set.id());
        if (session != null && session.proposal != null) {
            applyProposalToEditor(session);
        } else {
            bodyPane.clearChangeBase();
            revertDraftButton.setVisible(false);
            revertDraftButton.setManaged(false);
        }
        refreshDraftingPanel();
        updateChangeState();
    }

    /**
     * Recomputes the unsaved-changes state: the status line carries the signal, Save enables
     * only for a changed, writable set (Save as… stays always available — copying an unchanged
     * set is a legitimate duplicate gesture), and a read-only system default routes every
     * change through Save as….
     */
    private void updateChangeState() {
        if (active == null) {
            saveButton.setDisable(true);
            revertButton.setDisable(true);
            statusLine.setText("");
            return;
        }
        boolean changed = !loadedName.equals(nameField.getText() == null ? "" : nameField.getText())
                || !loadedDescription.equals(descriptionText())
                || loadedCategory != categoryBox.getValue()
                || !loadedBody.equals(bodyPane.getText());
        saveButton.setDisable(active.readOnly() || !changed);
        revertButton.setDisable(!changed);
        // Every unsaved body change shows as track changes in View — hand edits as much as
        // drafting proposals (KEC 2026-08-17: a badge added in Edit shows as NEW in View).
        // A drafting review keeps its own pre-draft base; outside one, the saved body is it.
        DraftingSession session = draftingSessions.get(active.id());
        boolean reviewing = session != null && session.proposal != null;
        if (!reviewing) {
            if (!loadedBody.equals(bodyPane.getText())) {
                bodyPane.setChangeBase(loadedBody,
                        (base, revised) -> MarkdownDiff.model(base, revised, baseFontSize));
            } else {
                bodyPane.clearChangeBase();
            }
        }
        if (active.readOnly()) {
            statusLine.setText(changed
                    ? "\u25cf System default — Save as\u2026 to keep your changes"
                    : "System default — use Save as\u2026 to make your copy");
        } else {
            statusLine.setText(changed ? "\u25cf Unsaved changes" : "");
        }
    }

    /**
     * Discards every unsaved change — a drafting review included — and reloads the saved
     * set: the general revert beside Save and Save as… (KEC 2026-08-17). {@code openSet}
     * would re-apply a pending proposal, so the review ends first.
     */
    private void revertToSaved() {
        if (active == null) {
            return;
        }
        DraftingSession session = draftingSessions.get(active.id());
        if (session != null) {
            endReview(session);
        }
        openSet(active);
    }

    /**
     * The description as it will be stored: one frontmatter scalar line \u2014 line breaks typed in
     * the sentence box collapse to single spaces, surrounding whitespace stripped.
     */
    private String descriptionText() {
        String text = descriptionField.getText();
        return text == null ? "" : text.replaceAll("\\s*\\R\\s*", " ").strip();
    }

    /** Height of the description area's non-text chrome: content padding plus border. */
    private static final double DESCRIPTION_CHROME = 14;

    /** Floor under the description area: roughly two text lines plus the chrome. */
    private static final double DESCRIPTION_MIN_HEIGHT = 52;

    /**
     * The area expands a row each time its text wraps onto a new line, instead of scrolling
     * inside a fixed box (KEC 2026-08-17): its preferred height is bound to the skin's
     * laid-out text height. {@code lookupAll} because the prompt text is a {@code .text} node
     * too \u2014 the tallest node is the live content either way. Min height rides the preference
     * so an ancestor can never squeeze a scrollbar back in.
     *
     * @param area      the text area to auto-grow
     * @param minHeight the floor under the area
     */
    private static void autoGrow(TextArea area, double minHeight) {
        area.setMinHeight(Region.USE_PREF_SIZE);
        area.skinProperty().addListener((obs, was, skin) -> {
            if (skin == null) {
                return;
            }
            Platform.runLater(() -> {
                List<Text> texts = area.lookupAll(".text").stream()
                        .filter(node -> node instanceof Text)
                        .map(node -> (Text) node)
                        .toList();
                if (texts.isEmpty()) {
                    return;
                }
                DoubleBinding contentHeight = Bindings.createDoubleBinding(
                        () -> texts.stream()
                                .mapToDouble(text -> text.getLayoutBounds().getHeight())
                                .max().orElse(0),
                        texts.stream().map(Node::layoutBoundsProperty)
                                .toArray(Observable[]::new));
                area.prefHeightProperty().bind(contentHeight
                        .add(DESCRIPTION_CHROME)
                        .map(height -> Math.max(height.doubleValue(), minHeight)));
            });
        });
    }

    /** The drafting-prompt section's summary: the selected set's name, or the bundled default. */
    private String draftingPromptSummary() {
        String id = preferences().get(PREF_DRAFTING_PROMPT_SET, "");
        if (!id.isBlank()) {
            for (InstructionSet set : store.list()) {
                if (set.id().equals(id)) {
                    return set.name();
                }
            }
        }
        return "Instruction Editor default";
    }

    /**
     * The drafting-prompt section: selects which titled System Prompt set steers the Ask
     * Claude area — the seeded drafting persona by default, or any System Prompt set of this
     * tile (the Save as… path to tuning how drafting behaves).
     */
    private javafx.scene.Node draftingPromptSettingsContent(
            dev.ikm.komet.layout.controls.SettingsPanePopup pane) {
        record Choice(String id, String label) {
            @Override
            public String toString() {
                return label;
            }
        }
        java.util.List<Choice> choices = new java.util.ArrayList<>();
        choices.add(new Choice("", "Instruction Editor default (bundled)"));
        for (InstructionSet set : store.list()) {
            if (set.category() == InstructionCategory.SYSTEM_PROMPT) {
                choices.add(new Choice(set.id(), set.name()));
            }
        }
        javafx.scene.control.ComboBox<Choice> selector = new javafx.scene.control.ComboBox<>();
        selector.getItems().setAll(choices);
        selector.setMaxWidth(Double.MAX_VALUE);
        String current = preferences().get(PREF_DRAFTING_PROMPT_SET, "");
        selector.getSelectionModel().select(choices.stream()
                .filter(choice -> choice.id().equals(current))
                .findFirst().orElse(choices.get(0)));
        selector.valueProperty().addListener((obs, was, sel) -> {
            if (sel == null) {
                return;
            }
            preferences().put(PREF_DRAFTING_PROMPT_SET, sel.id());
            try {
                preferences().sync();
            } catch (Exception ex) {
                LOG.warn("Could not sync the drafting-prompt selection", ex);
            }
            pane.refreshSummaries();
        });
        Label hint = new Label("Steers the Ask Claude area below the document. Save as… a "
                + "copy of the Instruction Editor default to tune how drafting behaves.");
        hint.setWrapText(true);
        return new VBox(6, selector, hint);
    }

    /** The drafting system prompt: the selected titled set's body, else the bundled persona. */
    private String draftingSystemPrompt() {
        String id = preferences().get(PREF_DRAFTING_PROMPT_SET, "");
        if (!id.isBlank()) {
            for (InstructionSet set : store.list()) {
                if (set.id().equals(id)) {
                    Optional<Frontmatter> parsed = store.read(set);
                    if (parsed.isPresent() && !parsed.get().body().isBlank()) {
                        return parsed.get().body();
                    }
                }
            }
        }
        return bundledResource("instruction-editor-prompt.md");
    }

    /**
     * Opens the drafting popout flanking the card — the interactive surface for discussing
     * and revising the ACTIVE instruction set with Claude (KEC 2026-08-17): the pop-out
     * paradigm of concept-properties editing, holding the conversation as titled collapsible
     * exchanges. Invoking it collapses the instruction-sets rail.
     */
    /** Width of the Assistant drawer's panel. */
    private static final double ASSISTANT_PANEL_WIDTH = 380;

    /** Floor under the drafting prompt: one text line plus the chrome. */
    private static final double DRAFTING_INPUT_MIN_HEIGHT = 36;

    /**
     * Builds the Assistant drawer's panel once: the set's conversation as titled collapsible
     * exchanges — where the properties drawer shows its history cards — over the input row.
     * The drawer chrome supplies the close control; the drawer toggle carries the label.
     */
    private void buildAssistantPanel() {
        draftingTitle = new Label("");
        draftingTitle.setStyle("-fx-font-weight: bold;");
        HBox header = new HBox(8, draftingTitle);
        header.setAlignment(Pos.CENTER_LEFT);

        exchangesBox = new VBox(6);
        exchangesScroll = new ScrollPane(exchangesBox);
        exchangesScroll.setFitToWidth(true);
        exchangesScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // A bounded preferred viewport: the panel's preferred height must not track the
        // transcript, or the drawer's cross-axis minimum ratchets up with every exchange.
        exchangesScroll.setPrefViewportHeight(300);
        VBox.setVgrow(exchangesScroll, Priority.ALWAYS);

        // Multi-line prompt that grows per wrapped line (KEC 2026-08-17), mirroring the
        // compose surface: Enter sends, Shift+Enter breaks the line, and a Koncept dragged
        // in drops as its k: token at the pointer.
        draftingInput = new TextArea();
        draftingInput.setPromptText("Ask about, or direct, this instruction set…");
        draftingInput.setWrapText(true);
        draftingInput.setPrefRowCount(1);
        autoGrow(draftingInput, DRAFTING_INPUT_MIN_HEIGHT);
        draftingInput.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    draftingInput.insertText(draftingInput.getCaretPosition(), "\n");
                } else {
                    sendDraftingMessage();
                }
                e.consume();
            }
        });
        network.ike.komet.claude.ui.KonceptTokenDrop.install(draftingInput,
                () -> safeViewProperties() == null ? null : safeViewProperties().calculator());
        draftingSend = new Button("Send");
        draftingSend.setMinWidth(Region.USE_PREF_SIZE);
        draftingSend.setOnAction(e -> sendDraftingMessage());
        HBox inputRow = new HBox(6, draftingInput, draftingSend);
        inputRow.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(draftingInput, Priority.ALWAYS);

        draftingStatus = new Label("");
        draftingStatus.setWrapText(true);

        assistantPanel = new VBox(8, header, exchangesScroll, inputRow, draftingStatus);
        assistantPanel.setPadding(new Insets(8));
        assistantPanel.setPrefWidth(ASSISTANT_PANEL_WIDTH);
    }

    /** Re-binds the panel to the ACTIVE set's conversation — one set per conversation. */
    private void refreshDraftingPanel() {
        if (assistantPanel == null) {
            return;
        }
        if (active == null) {
            draftingTitle.setText("");
            exchangesBox.getChildren().clear();
            draftingInput.setDisable(true);
            draftingSend.setDisable(true);
            draftingStatus.setText("Select or create an instruction set first.");
            return;
        }
        DraftingSession session = draftingSession(active.id());
        draftingTitle.setText(active.name());
        exchangesBox.getChildren().setAll(session.exchangePanes);
        draftingInput.setDisable(session.busy);
        draftingSend.setDisable(session.busy);
        draftingStatus.setText(session.busy ? "Working…" : "");
        Platform.runLater(() -> exchangesScroll.setVvalue(1.0));
    }

    private DraftingSession draftingSession(String setId) {
        return draftingSessions.computeIfAbsent(setId, id -> new DraftingSession());
    }

    /**
     * Sends the user's message for the active set's conversation: the current document plus
     * the request, with the set's own history — never another set's. Claude discusses in
     * text; a revision arrives only through {@code propose_document}.
     */
    private void sendDraftingMessage() {
        if (active == null) {
            return;
        }
        String request = draftingInput.getText() == null ? "" : draftingInput.getText().strip();
        if (request.isBlank()) {
            return;
        }
        String key = PreferencesService.userPreferences().get(ClaudeCard.PREF_API_KEY, "");
        if (key.isBlank()) {
            draftingStatus.setText("Set the Anthropic API key in an Assistant card's settings first");
            return;
        }
        String setId = active.id();
        DraftingSession session = draftingSession(setId);
        if (session.busy) {
            return;
        }
        session.busy = true;
        String model = PreferencesService.userPreferences()
                .get(ClaudeCard.PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
        String document = InstructionSets.withFrontmatter(
                nameField.getText(), descriptionText(), categoryBox.getValue(), bodyPane.getText());
        String message = "Current document:\n\n" + document + "\n\nRequest: " + request;
        String system = draftingSystemPrompt();
        List<Map<String, Object>> history = List.copyOf(session.apiHistory);
        draftingInput.clear();
        addExchangePane(session, request);
        refreshDraftingPanel();
        Thread worker = new Thread(() -> {
            String answer;
            try {
                AnthropicClient client = new AnthropicClient(key, model, 8192);
                answer = client.ask(system, List.of(proposeDocumentTool(setId)), history, message);
            } catch (Exception ex) {
                LOG.warn("Drafting request failed", ex);
                answer = "⚠ Drafting failed: " + ex.getMessage();
            }
            String finalAnswer = answer == null || answer.isBlank() ? "(no reply text)" : answer;
            Platform.runLater(() -> completeExchange(setId, request, finalAnswer));
        }, "komet-claude-draft");
        worker.setDaemon(true);
        worker.start();
    }

    /** Adds the exchange row immediately — the full question is visible while it runs. */
    private void addExchangePane(DraftingSession session, String question) {
        ExchangeView exchange = new ExchangeView(question);
        for (ExchangeView earlier : session.exchangePanes) {
            earlier.setExpanded(false);
        }
        session.exchangePanes.add(exchange);
    }

    /** Lands the reply: history recorded, the exchange's content rendered, panel refreshed. */
    private void completeExchange(String setId, String request, String answer) {
        DraftingSession session = draftingSession(setId);
        session.busy = false;
        session.apiHistory.add(Map.of("role", "user", "content", request));
        session.apiHistory.add(Map.of("role", "assistant", "content", answer));
        if (!session.exchangePanes.isEmpty()) {
            MarkdownRichText renderer = new MarkdownRichText(safeViewProperties(), baseFontSize);
            MarkdownEditPane answerView =
                    new MarkdownEditPane(answer, false, renderer::renderMarkdown);
            answerView.useContentHeight();
            session.exchangePanes.get(session.exchangePanes.size() - 1).setAnswer(answerView);
        }
        // Fallback for a model that pasted the document as text despite the contract: a reply
        // that IS a portable-form document is treated as the proposal it clearly is.
        if (stripFence(answer).startsWith("---")) {
            receiveProposal(setId, answer);
        }
        if (active != null && active.id().equals(setId)) {
            refreshDraftingPanel();
        }
    }

    /** The structured channel for revisions: discussion stays text, documents come through here. */
    private AnthropicTool proposeDocumentTool(String setId) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_document";
            }

            @Override
            public String description() {
                return "Propose the complete revised instruction document. Call ONLY when "
                        + "proposing a change — the user reviews it as tracked changes. The "
                        + "document must be the ENTIRE portable form: frontmatter (name, "
                        + "description, category) and the full body. At most once per reply.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                        "properties", Map.of("document", Map.of(
                                "type", "string",
                                "description",
                                "The complete revised document: frontmatter plus body")),
                        "required", List.of("document"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                Object document = input == null ? null : input.get("document");
                if (document == null || String.valueOf(document).isBlank()) {
                    return "No document received — pass the complete document in 'document'.";
                }
                Platform.runLater(() -> receiveProposal(setId, String.valueOf(document)));
                return "Proposal received; it is shown to the user as tracked changes for review.";
            }
        };
    }

    /**
     * Records a proposal against ITS set's session — snapshotting the pre-review base on the
     * first proposal of a review cycle — and, when that set is the one on screen, shows it as
     * tracked changes. A proposal for a set the user has switched away from waits in the
     * session and applies when they return.
     */
    private void receiveProposal(String setId, String documentText) {
        String document = stripFence(documentText);
        Frontmatter parsed = InstructionSets.parseFrontmatter(document);
        if (parsed.body().isBlank()) {
            LOG.warn("Unusable drafting proposal for set {}: {}", setId, documentText);
            return;
        }
        DraftingSession session = draftingSession(setId);
        if (session.base == null) {
            if (active != null && active.id().equals(setId)) {
                session.base = new ProposalBase(nameField.getText(), descriptionText(),
                        categoryBox.getValue(), bodyPane.getText());
            } else {
                session.base = store.byId(setId).flatMap(store::read)
                        .map(fm -> new ProposalBase(fm.name(), fm.description(),
                                fm.category(), fm.body()))
                        .orElse(new ProposalBase("", "", InstructionCategory.SKILL, ""));
            }
        }
        session.proposal = document;
        if (active != null && active.id().equals(setId)) {
            applyProposalToEditor(session);
        }
    }

    /** Puts the proposal in the fields and turns the view into track changes against the base. */
    private void applyProposalToEditor(DraftingSession session) {
        Frontmatter parsed = InstructionSets.parseFrontmatter(session.proposal);
        if (parsed.name() != null && !parsed.name().isBlank()) {
            nameField.setText(parsed.name());
        }
        if (parsed.description() != null && !parsed.description().isBlank()) {
            descriptionField.setText(parsed.description());
        }
        categoryBox.getSelectionModel().select(parsed.category());
        bodyPane.setText(parsed.body());
        bodyPane.setChangeBase(session.base.body(),
                (base, revised) -> MarkdownDiff.model(base, revised, baseFontSize));
        revertDraftButton.setVisible(true);
        revertDraftButton.setManaged(true);
        updateChangeState();
    }

    /** Restores the pre-draft snapshot — every field — and leaves review. */
    private void revertDraft() {
        DraftingSession session = active == null ? null : draftingSessions.get(active.id());
        if (session == null || session.base == null) {
            return;
        }
        nameField.setText(session.base.name());
        descriptionField.setText(session.base.description());
        categoryBox.getSelectionModel().select(session.base.category());
        bodyPane.setText(session.base.body());
        endReview(session);
        updateChangeState();
    }

    /** Leaves track-changes review: the session forgets the proposal, the chrome retracts. */
    private void endReview(DraftingSession session) {
        session.base = null;
        session.proposal = null;
        bodyPane.clearChangeBase();
        revertDraftButton.setVisible(false);
        revertDraftButton.setManaged(false);
    }

    /** Unwraps a reply the model fenced despite the output contract; anything else passes through. */
    static String stripFence(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.startsWith("```") && stripped.endsWith("```")) {
            int firstBreak = stripped.indexOf('\n');
            int lastFence = stripped.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                return stripped.substring(firstBreak + 1, lastFence).strip();
            }
        }
        return stripped;
    }

    /** Creates a fresh set on the skill scaffold — seeding is invocation context, not a mode. */
    private void newSet() {
        String workingName = "Untitled instruction set";
        store.create(workingName, "", InstructionCategory.SKILL,
                        InstructionSets.skillScaffold(workingName))
                .ifPresent(set -> refreshSets(set));
    }

    /** Imports a portable SKILL.md-form file and selects its registration. */
    private void importSet() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import instruction set");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Markdown instruction files", "*.md"));
        File chosen = chooser.showOpenDialog(fxObject().getScene().getWindow());
        if (chosen == null) {
            return;
        }
        Optional<InstructionSet> imported = store.importFile(chosen.toPath());
        if (imported.isPresent()) {
            refreshSets(imported.get());
        } else {
            statusLine.setText("Import failed — see the log");
        }
    }

    /**
     * Saves a copy under a managed title: proposed as "<name> (copy)", user-confirmed, and
     * made unique against this tile's registrations — the explicit path off a read-only
     * system default, and a general duplicate gesture.
     */
    private void saveAsCopy() {
        if (active == null) {
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(uniqueTitle(
                        (nameField.getText() == null || nameField.getText().isBlank()
                                ? "Untitled" : nameField.getText().strip()) + " (copy)"));
        dialog.setTitle("Save as");
        dialog.setHeaderText("Save a copy under a new title.");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }
        store.create(uniqueTitle(result.get().strip()),
                        descriptionText(),
                        categoryBox.getValue() == null ? InstructionCategory.SKILL
                                : categoryBox.getValue(),
                        bodyPane.getText())
                .ifPresent(copy -> {
                    // The copy carries the drafted changes; the source set's review ends.
                    endReview(draftingSession(active.id()));
                    refreshSets(copy);
                });
    }

    /** The title, suffixed (2), (3), … until it collides with no registered set's name. */
    private String uniqueTitle(String proposed) {
        java.util.Set<String> names = store.list().stream()
                .map(InstructionSet::name)
                .collect(java.util.stream.Collectors.toSet());
        if (!names.contains(proposed)) {
            return proposed;
        }
        int n = 2;
        while (names.contains(proposed + " (" + n + ")")) {
            n++;
        }
        return proposed + " (" + n + ")";
    }

    /** Persists the editor's state into the active registration. */
    private void saveActive() {
        if (active == null || active.readOnly()) {
            return;
        }
        Optional<InstructionSet> saved = store.save(active,
                nameField.getText() == null || nameField.getText().isBlank()
                        ? "Untitled" : nameField.getText().strip(),
                descriptionText(),
                categoryBox.getValue() == null ? InstructionCategory.SKILL : categoryBox.getValue(),
                bodyPane.getText());
        if (saved.isPresent()) {
            endReview(draftingSession(saved.get().id()));
            active = saved.get();
            loadedName = active.name();
            loadedDescription = active.description();
            loadedCategory = active.category();
            loadedBody = bodyPane.getText();
            refreshSets(active);
            updateChangeState();
            statusLine.setText("Saved");
        } else {
            statusLine.setText("Save failed — see the log");
        }
    }

    /** Mints the tile's display name once: kind plus sequence, past every sibling editor tile. */
    private void ensureTileLabel() {
        String current = preferences().get(TILE_LABEL_KEY, "");
        if (!current.isBlank()) {
            return;
        }
        int next = 1;
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("Instruction Editor (\\d+)");
        try {
            KometPreferences parent = preferences().parent();
            if (parent != null) {
                for (KometPreferences child : parent.children()) {
                    if (CARD_TYPE_VALUE.equals(child.get(CARD_TYPE_KEY, ""))) {
                        java.util.regex.Matcher m = named.matcher(child.get(TILE_LABEL_KEY, ""));
                        if (m.matches()) {
                            next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate instruction-editor tiles for naming", e);
        }
        preferences().put(TILE_LABEL_KEY, "Instruction Editor " + next);
    }

    /**
     * ServiceLoader provider contributing {@link InstructionEditorCard} to the Journal workspace.
     */
    public static final class Factory implements KlCardProvider {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Factory() {
        }

        @Override
        public String cardName() {
            return CARD_NAME;
        }

        @Override
        public AbstractHostCard createCard(KometPreferences windowPreferences, UUID journalTopic) {
            KlPreferencesFactory cardPreferencesFactory =
                    KlPreferencesFactory.create(windowPreferences, InstructionEditorCard.class);
            InstructionEditorCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode =
                    windowPreferences.node(InstructionEditorCard.class.getSimpleName());
            InstructionEditorCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory building the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<InstructionEditorCard> {

        @Override
        public InstructionEditorCard restore(KometPreferences preferences) {
            return new InstructionEditorCard(preferences);
        }

        @Override
        public InstructionEditorCard create(KlPreferencesFactory preferencesFactory,
                                            AreaGridSettings areaGridSettings) {
            InstructionEditorCard card = new InstructionEditorCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
