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
package network.ike.komet.claude;

import network.ike.komet.claude.ui.KonceptChipGestures;
import dev.ikm.komet.framework.graphics.Icon;
import dev.ikm.komet.framework.view.ObservableView;
import dev.ikm.komet.framework.view.ObservableViewWithOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.controls.FilterOptionsPopup;
import dev.ikm.komet.layout.controls.SettingsPanePopup;
import dev.ikm.komet.layout.controls.ViewOptionsPopupHelper;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.CardBlueprint;
import dev.ikm.komet.layout_engine.host.AbstractHostCard;
import dev.ikm.komet.layout_engine.host.KlCardProvider;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import java.util.LinkedHashMap;
import java.util.OptionalInt;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.anthropic.AskListener;
import javafx.util.Duration;
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentSurface;
import network.ike.komet.claude.doc.DocumentSurfaceArea;
import network.ike.komet.claude.doc.JournalStore;
import network.ike.komet.claude.json.Json;
import network.ike.komet.claude.tools.GraphTools;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import dev.ikm.komet.markdown.richtext.ConceptChipTextModel;
import dev.ikm.komet.markdown.richtext.TableColumnWidths;
import network.ike.komet.claude.ui.ComposeChips;
import network.ike.komet.claude.ui.KonceptTokens;
import network.ike.komet.claude.ui.MarkdownEditPane;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.BackingStoreException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Komet Assistant as a first-class {@link AbstractHostCard}: a chat over the open knowledge base,
 * contributed to the Journal workspace via {@link Factory} (a {@code KlCardProvider}). This is the
 * card-native successor to the legacy {@code ClaudeAssistantArea} tool — it owns its chrome, coordinate
 * context, lifecycle, and storage directly rather than being hosted inside a generic shell.
 *
 * <p><b>One chrome.</b> The card's own header (themed Anthropic coral via {@code claude-card.css}) carries the
 * title, the transient controls (conversations toggle, Save), and the settings sliders holding the durable
 * preferences (text size, system prompt, API key — ike-issues#1040); the close lives in the base chrome.
 * There is no doubled tab. Exchange expand/collapse live in the transcript panel's own header strip.
 *
 * <p><b>Sandboxed per instance.</b> Conversations are written as files in <em>this card's own preferences-node
 * {@linkplain KometPreferences#directory() directory}</em> — so two Claude cards never share a rail, and the
 * conversations are removed with the card when it is deleted. The API key and model stay in shared per-OS-user
 * preferences (one key for every card), never in the knowledge base.
 *
 * <p>Each send runs the Anthropic tool-use loop ({@link AnthropicClient#ask}) on a background thread; Claude's
 * read-only {@link GraphTools} execute in-process against this card's live coordinate
 * ({@link #getCardViewProperties()}), so answers are grounded in exactly the view the user sees.
 */
public final class ClaudeCard extends AbstractHostCard {

    /** Menu label and card title. */
    static final String CARD_NAME = "Komet Assistant";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClaudeCard.class);

    /** Per-user preference keys (shared across cards, stored under {@link PreferencesService#userPreferences()}). */
    // PREF_API_KEY / PREF_MODEL are public so the headless commit narrator reads the same per-user config
    // (the key names, not the value, are shared).
    public static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    public static final String PREF_MODEL = "network.ike.komet.claude.model";
    private static final String PREF_FONT_SIZE = "network.ike.komet.claude.fontSize";
    private static final String PREF_RAIL_VISIBLE = "network.ike.komet.claude.railVisible";
    private static final String PREF_RAIL_DIVIDER = "network.ike.komet.claude.railDivider";

    /**
     * Card-node preference naming this card's edited system-prompt instruction layer's payload
     * file (ike-issues#1039) — the preferences entry IS the registration, per the preferences
     * doctrine (a dumb payload file the entry names; no discovery by hardcoded magic). Absent
     * entry: the bundled default instructions.
     */
    private static final String PREF_SYSTEM_INSTRUCTIONS_FILE =
            "network.ike.komet.claude.systemInstructionsFile";

    /** The instruction-layer payload file name within the card's preferences directory. */
    private static final String SYSTEM_INSTRUCTIONS_FILE = "system-instructions.md";

    /**
     * Card-node preference holding the id of the <em>titled instruction set</em> selected as
     * this card's system prompt (ike-issues#1044): the single-select attachment role. Blank
     * means the card-local layer — the legacy override file when present, else the bundled
     * default. Sets are resolved across the journal's Instruction Editor tiles at read time.
     */
    private static final String PREF_SYSTEM_PROMPT_SET =
            "network.ike.komet.claude.systemPromptSet";

    private static final int MAX_TOKENS = 8192;

    /** The fixed tool-contract half of the system prompt (grounding, badges, koncept-tree). */
    private String promptCore;
    private String systemPrompt;
    private List<AnthropicTool> tools;
    private final ExecutorService worker =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "komet-claude-ask");
                t.setDaemon(true);
                return t;
            });
    /** Live ref to the active conversation's Save markdown (reassigned on switch). */
    private StringBuilder transcriptMarkdown;

    /** The printable document area (#839): a first-class KlArea wrapping the block-stack surface,
     *  owning its own preferences + the "expand to full surface" affordance (KlExpandable). */
    private DocumentSurfaceArea documentArea;
    /** The block-stack Document surface (#808), hosted by {@link #documentArea}; the card drives it. */
    private DocumentSurface surface;
    /** The chronology store the conversation journal persists through (#807). */
    private JournalStore journalStore;
    /** The rich compose surface (#789): prose with live inline concept chips, over {@link #composeModel}. */
    private RichTextArea input;
    /** The compose surface's editable model; chips serialize to k: tokens on send. */
    private ConceptChipTextModel composeModel;
    /** Prompt hint overlaid on the empty compose surface (RichTextArea has no prompt text). */
    private Label composeHint;
    /** The position the caret was last moved to while a concept hovers, to de-jitter drag tracking. */
    private TextPos dropHint;
    private Button sendButton;
    private BorderPane content;
    private Label statusLabel;

    // Find-in-conversation state (🔍 / Cmd-Ctrl+F), over the document surface.
    private HBox findBar;
    private TextField findField;
    private Label findCount;
    private List<DocumentSurface.DocumentMatch> matches = List.of();
    private int matchPos = -1;

    private Button retryButton;
    /** Stops the active conversation's in-flight generation (ike-issues#1028). */
    private Button stopButton;
    /** Journal tile selector above the conversation rail (ike-issues#1032). */
    private javafx.scene.control.ComboBox<TileRef> tileSelector;
    /** 1 Hz tick that advances the elapsed clock in the status strip while a request is in flight. */
    private Timeline statusTimer;
    /** All conversations (left rail); the active one drives the transcript. */
    private final ObservableList<Conversation> conversations = FXCollections.observableArrayList();
    private Conversation active;
    private ListView<Conversation> conversationList;
    private VBox conversationRail;
    private SplitPane split;
    private boolean railVisible = true;
    private double railDivider = 0.24;
    /** Live refs to the active conversation's collections (reassigned on switch). */
    private List<MarkdownRichText.Entry> entries;
    /** Transcript base font size (px); adjustable via the A−/A+ buttons, persisted. */
    private double baseFontSize = MarkdownRichText.DEFAULT_BASE;
    /** Each conversation's <em>equivalent place</em> (scroll + selection), by conversation id —
     *  held across re-renders and rail switches, persisted on save, restored on reopen (#943). */
    private final Map<String, DocumentSurface.Viewpoint> viewpoints = new HashMap<>();
    /** Conversation ids whose stored viewpoint is authoritative but not yet re-applied to the
     *  surface — loaded at reopen, or any restore still in flight. While an id is pending,
     *  captures must NOT overwrite its map entry: restoration is asynchronous (it waits for
     *  layout to settle), and a second refresh arriving mid-rebuild — renderContent's refresh
     *  after buildBody on reopen, or a repeated coordinate-change notification mid-session —
     *  would capture the collapsed, not-yet-restored stack (block 0 = top) and clobber the real
     *  place (#943/#945). */
    private final Set<String> pendingViewpointRestore = new HashSet<>();
    /** Child preferences node holding one encoded viewpoint per conversation id (#943). */
    private static final String VIEWPOINTS_NODE = "viewpoints";
    /** Child preferences node indexing this card's conversations (ike-issues#1032):
     *  one child node per conversation id, whose keys are the authoritative record —
     *  name, an explicit payload-file pointer, last-active, journal anchor, and move
     *  provenance. Payload JSON stays dumb transcript; nothing is discovered by
     *  filename convention. */
    private static final String INDEX_NODE = "conversations";
    /** Marker key identifying an assistant card's node during tile enumeration. */
    private static final String CARD_TYPE_KEY = "card-type";
    private static final String CARD_TYPE_VALUE = "komet-assistant";
    /** Human label for this tile in other cards' tile selectors; set once at first open. */
    private static final String TILE_LABEL_KEY = "tile-label";
    // Index-entry keys (per conversation child node).
    private static final String KEY_NAME = "name";
    private static final String KEY_FILE = "file";
    private static final String KEY_LAST_ACTIVE = "last-active";
    private static final String KEY_ANCHOR = "journal-anchor";
    private static final String KEY_MOVED_TO = "moved-to";
    private static final String KEY_MOVED_FROM = "moved-from";
    /** Nothing is ever deleted (KEC principle — history/time travel):
     *  a "deleted" conversation is marked hidden; payload and index stay. */
    private static final String KEY_HIDDEN = "hidden";
    /** Child node of a conversation's index entry holding remembered table
     *  column widths (ike-issues#1034), keyed by table identity. */
    private static final String TABLE_WIDTHS_NODE = "table-widths";

    /** One named conversation: its display entries, clean API turns, and Save markdown. */
    private static final class Conversation {
        final String id;
        String name;
        boolean named;
        /** Home tile when browsed from another card's store (ike-issues#1032);
         *  null means this card owns it. An amend moves the conversation home
         *  to this card with provenance recorded both ways. */
        KometPreferences homeNode;
        Path homeDir;
        /** Claude-generated exchange titles by zero-based ordinal (KEC design:
         *  numbered named turns); persisted in the payload, transcript content. */
        final Map<Integer, String> turnTitles = new LinkedHashMap<>();
        /** True once the user renamed it — generated titles then defer. */
        boolean userNamed;
        volatile boolean busy;
        /** Live transport activity shown in the status strip while busy (set off-thread, read on FX). */
        volatile String activity;
        /** {@link System#nanoTime()} when the in-flight request started, for the elapsed clock. */
        volatile long startNanos;
        /** Settled status after a request: "✓ Replied in …" or "✕ …"; null before the first send. */
        String outcome;
        /** Whether {@link #outcome} is a failure (drives the failed styling + the Retry button). */
        boolean outcomeFailed;
        /** The held user text of a failed send, re-dispatched by Retry; null when nothing is pending. */
        String pendingRetryText;
        /** The in-flight worker task, so deleting a busy conversation can cancel it. */
        volatile java.util.concurrent.Future<?> task;
        /** Set by the Stop button before cancelling, so the interrupt reads as
         *  "■ Stopped" rather than a transport failure (ike-issues#1028);
         *  the prompt is still held for Retry. */
        volatile boolean stopRequested;
        /** Whether {@link #outcome} is a deliberate stop — neutral styling
         *  instead of the failure red, Retry still offered. */
        boolean outcomeStopped;
        final List<MarkdownRichText.Entry> entries = new ArrayList<>();
        final List<Map<String, Object>> apiMessages = new ArrayList<>();
        final StringBuilder markdown = new StringBuilder();
        /** Turn index where the current exchange (the last user question) begins; scroll anchor. */
        int turnStartTurn;
        /** The conversation's journal-anchor id (#807); set on the worker after the first
         *  persisted exchange, read on FX for save — hence volatile. Null until then. */
        volatile PublicId journalAnchor;

        Conversation(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Serialized form of a {@link Conversation} (json4j); {@code journalAnchor} is the journal's
     *  anchor UUID, or null for a conversation that predates (or never reached) the journal. */
    private record ConversationDto(String id, String name, List<Map<String, Object>> turns,
                                   String journalAnchor, Map<String, String> titles) {
    }

    private ClaudeCard(KometPreferences preferences) {
        super(preferences);
        init();
    }

    private ClaudeCard(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        init();
    }

    private void init() {
        this.promptCore = loadPromptResource("system-prompt-core.md",
                "You are a read-only terminology assistant embedded in Komet. "
                        + "Always use the provided tools to resolve concepts, identifiers, and "
                        + "relationships; never answer from memory.");
        refreshSystemPrompt();
        // Tools read the live card view each call via the method reference, so they always reflect the
        // journal's current coordinate (after bind).
        this.tools = new GraphTools(this::viewCalculator).tools();
        this.journalStore = new JournalStore(this::safeViewCalculator);
        fxObject().getStyleClass().add("claude-card");
        URL css = ClaudeCard.class.getResource("claude-card.css");
        if (css != null) {
            fxObject().getStylesheets().add(css.toExternalForm());
        }
    }

    /** Resolves the view calculator the tools query: this card's coordinate of record, once bound. */
    private ViewCalculator viewCalculator() {
        ViewProperties vp = getCardViewProperties();
        return vp != null ? vp.calculator() : null;
    }

    /** {@link #viewCalculator()} that answers {@code null} instead of throwing before bind. */
    private ViewCalculator safeViewCalculator() {
        try {
            return viewCalculator();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The card's view, or {@code null} before bind — chips then degrade to presentation (#941). */
    private ViewProperties safeViewProperties() {
        try {
            return getCardViewProperties();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /*******************************************************************************
     *  Card chrome + content                                                      *
     ******************************************************************************/

    @Override
    protected String cardTitle() {
        return CARD_NAME;
    }

    /** The assistant controls live in the card toolbar (the close is in the base chrome). */
    @Override
    protected void buildToolbarControls(HBox toolBar) {
        // Coordinate control: the standard overridable View popup, wired to this card's own coordinate of
        // record — so the assistant's view (the one the tools query) is visible and overridable, like the
        // tiles, the journal, and the knowledge base.
        MenuButton coordinateButton = new MenuButton();
        coordinateButton.getStyleClass().add("coordinate");
        coordinateButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        coordinateButton.setTooltip(new Tooltip("Coordinates"));
        ViewOptionsPopupHelper.setupViewCoordinateOptionsPopup(getCardViewProperties(),
                FilterOptionsPopup.FILTER_TYPE.CHAPTER_WINDOW, fxObject(), coordinateButton, () -> { });
        ObservableView cardView = getCardViewProperties().nodeView();
        Runnable syncOverrideIndicator = () -> {
            boolean overridden = cardView instanceof ObservableViewWithOverride overrideView
                    && !overrideView.getValue().equals(overrideView.getOriginalValue());
            coordinateButton.getStyleClass().remove("override");
            if (overridden) {
                coordinateButton.getStyleClass().add("override");
            }
        };
        syncOverrideIndicator.run();
        cardView.subscribe(syncOverrideIndicator);

        Button toggleRail = new Button("☰");
        toggleRail.setTooltip(new Tooltip("Show/hide conversations"));
        toggleRail.setOnAction(e -> setRailVisible(!railVisible));

        // Paged document print (#838/#839) is reached through the document area's hover-revealed
        // "expand to full surface" corner icon (KlExpandable); Print and print-settings live in that
        // full-surface view — no top-toolbar button. Conversation-scoped actions — transcript
        // Save, exchange expand/collapse — live in the transcript panel's own header strip
        // (ike-issues#1040), beside the conversation they act on.
        toggleRail.getStyleClass().add("claude-card-toolbar-button");
        toolBar.getChildren().addAll(coordinateButton, toggleRail, settingsButton());
    }

    /**
     * The durable-preference settings affordance (ike-issues#1040/#1043): the house sliders
     * glyph opening the standardized {@link SettingsPanePopup} — the View Options adjacent-pane
     * paradigm — with sections for text size, the system prompt, and the API key. Transient
     * actions (the conversations toggle; the strip's Save) stay as buttons.
     */
    private Button settingsButton() {
        Button settings = new Button();
        settings.setGraphic(Icon.PANEL_PREFERENCE_SLIDERS.makeIcon());
        settings.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        settings.setTooltip(new Tooltip("Assistant settings"));
        settings.getStyleClass().addAll("claude-card-toolbar-button", "claude-card-settings-button");

        SettingsPanePopup pane = new SettingsPanePopup("Assistant Settings");
        pane.addSection("Text size",
                () -> Math.round(baseFontSize) + " px",
                () -> textSizeSettingsContent(pane));
        pane.addSection("System prompt",
                () -> selectedTitledSetName().orElseGet(
                        () -> preferences().get(PREF_SYSTEM_INSTRUCTIONS_FILE, "").isBlank()
                                ? "Default" : "Edited"),
                () -> promptSettingsContent(pane));
        pane.addSection("API key",
                () -> hasApiKey() ? "Set (per-user)" : "Not set",
                () -> apiKeySettingsContent(pane));
        pane.attachTo(settings, (Pane) fxObject());
        return settings;
    }

    /** The text-size section: live ± adjustment, the card summary tracking each press. */
    private javafx.scene.Node textSizeSettingsContent(SettingsPanePopup pane) {
        Label current = new Label(Math.round(baseFontSize) + " px");
        Button fontDown = new Button("A−");
        fontDown.setOnAction(e -> {
            adjustFont(-1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        Button fontUp = new Button("A+");
        fontUp.setOnAction(e -> {
            adjustFont(1);
            current.setText(Math.round(baseFontSize) + " px");
            pane.refreshSummaries();
        });
        HBox row = new HBox(8, fontDown, fontUp, current);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * The system-prompt section (ike-issues#1039/#1044 in its #1043 home): a <em>selector</em>
     * over the attachment — the card-local layer or a titled instruction set from the
     * journal's Instruction Editor tiles — with a rendered preview of whatever is active.
     * The card-local layer edits in the escape window; a titled set edits in its own editor
     * tile (authoring never happens in a settings pane).
     */
    private javafx.scene.Node promptSettingsContent(SettingsPanePopup pane) {
        record Choice(String id, String label) {
            @Override
            public String toString() {
                return label;
            }
        }
        MarkdownRichText renderer = new MarkdownRichText(safeViewProperties(),
                MarkdownRichText.DEFAULT_BASE);
        MarkdownEditPane preview =
                new MarkdownEditPane(instructionsLayer(), false, renderer::renderMarkdown);
        preview.setPrefSize(290, 170);

        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice("",
                preferences().get(PREF_SYSTEM_INSTRUCTIONS_FILE, "").isBlank()
                        ? "Card layer (default)" : "Card layer (edited)"));
        for (KometPreferences tile : instructionEditorTiles()) {
            String tileLabel = tile.get(
                    network.ike.komet.claude.instructions.InstructionEditorCard.TILE_LABEL_KEY,
                    "Instruction Editor");
            for (network.ike.komet.claude.instructions.InstructionSets.InstructionSet set
                    : new network.ike.komet.claude.instructions.InstructionSets(tile).list()) {
                choices.add(new Choice(set.id(), set.name() + " — " + tileLabel));
            }
        }
        javafx.scene.control.ComboBox<Choice> selector = new javafx.scene.control.ComboBox<>();
        selector.getItems().setAll(choices);
        selector.setMaxWidth(Double.MAX_VALUE);
        String current = preferences().get(PREF_SYSTEM_PROMPT_SET, "");
        selector.getSelectionModel().select(choices.stream()
                .filter(choice -> choice.id().equals(current))
                .findFirst().orElse(choices.get(0)));

        Button openEditor = new Button("Open editor…");
        openEditor.setOnAction(e -> openPromptEditor(pane));
        Label titledHint = new Label("Titled sets are edited in their Instruction Editor tile.");
        titledHint.setWrapText(true);
        Runnable syncAffordances = () -> {
            boolean cardLayer = selector.getValue() != null && selector.getValue().id().isBlank();
            openEditor.setVisible(cardLayer);
            openEditor.setManaged(cardLayer);
            titledHint.setVisible(!cardLayer);
            titledHint.setManaged(!cardLayer);
        };
        syncAffordances.run();
        selector.valueProperty().addListener((obs, was, sel) -> {
            if (sel == null) {
                return;
            }
            preferences().put(PREF_SYSTEM_PROMPT_SET, sel.id());
            try {
                preferences().sync();
            } catch (BackingStoreException ex) {
                LOG.warn("Could not sync the system-prompt selection", ex);
            }
            refreshSystemPrompt();
            preview.setText(instructionsLayer());
            pane.refreshSummaries();
            syncAffordances.run();
        });

        VBox content = new VBox(6, selector, preview, openEditor, titledHint);
        return content;
    }

    /** The one open prompt-editor window per card, refocused instead of duplicated. */
    private javafx.stage.Stage promptEditorStage;

    /**
     * The system-prompt editor window: a resizable, non-modal surface at writing size — the
     * full-width view/edit Markdown pane over the instruction layer, Restore default and Save,
     * and the fixed tool contract rendered read-only beneath. The same editor surface the
     * skills increment will reuse for a registered skill's {@code SKILL.md}
     * (ike-issues#1042/#1043).
     */
    private void openPromptEditor(SettingsPanePopup pane) {
        if (promptEditorStage != null && promptEditorStage.isShowing()) {
            promptEditorStage.requestFocus();
            return;
        }
        MarkdownRichText renderer = new MarkdownRichText(safeViewProperties(),
                MarkdownRichText.DEFAULT_BASE);
        MarkdownEditPane instructions =
                new MarkdownEditPane(instructionsLayer(), true, renderer::renderMarkdown);
        instructions.setPrefSize(680, 420);
        VBox.setVgrow(instructions, Priority.ALWAYS);

        Button restoreDefault = new Button("Restore default");
        restoreDefault.setOnAction(e -> instructions.setText(defaultInstructions()));
        Button save = new Button("Save");
        save.setOnAction(e -> {
            String text = instructions.getText();
            if (text.strip().equals(defaultInstructions().strip())) {
                clearInstructionsOverride();
            } else {
                saveInstructionsOverride(text);
            }
            pane.refreshSummaries();
        });
        Region buttonSpacer = new Region();
        HBox.setHgrow(buttonSpacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, restoreDefault, buttonSpacer, save);
        buttons.setAlignment(Pos.CENTER_LEFT);

        MarkdownEditPane contract = new MarkdownEditPane(promptCore, false, renderer::renderMarkdown);

        // Tabs, not stacked scroll boxes (KEC 2026-08-17): each layer owns the whole window
        // and grows with it — the editor maximizes the space it was given.
        VBox instructionsTabContent = new VBox(8,
                new Label("Stored with this card's preferences; travels with their sync."),
                instructions, buttons);
        instructionsTabContent.setPadding(new Insets(12));
        VBox contractTabContent = new VBox(8, contract);
        contractTabContent.setPadding(new Insets(12));
        VBox.setVgrow(contract, Priority.ALWAYS);

        javafx.scene.control.Tab instructionsTab =
                new javafx.scene.control.Tab("Instructions (editable)", instructionsTabContent);
        javafx.scene.control.Tab contractTab =
                new javafx.scene.control.Tab("Tool contract (fixed)", contractTabContent);
        javafx.scene.control.TabPane content =
                new javafx.scene.control.TabPane(instructionsTab, contractTab);
        content.setTabClosingPolicy(javafx.scene.control.TabPane.TabClosingPolicy.UNAVAILABLE);

        promptEditorStage = new javafx.stage.Stage();
        promptEditorStage.setTitle("System prompt — "
                + preferences().get(TILE_LABEL_KEY, CARD_NAME));
        promptEditorStage.initOwner(fxObject().getScene().getWindow());
        promptEditorStage.setScene(new javafx.scene.Scene(content));
        promptEditorStage.setOnHidden(e -> promptEditorStage = null);
        promptEditorStage.show();
    }

    /** The API-key section: the per-user key, masked, saved to shared per-user preferences. */
    private javafx.scene.Node apiKeySettingsContent(SettingsPanePopup pane) {
        PasswordField field = new PasswordField();
        field.setPromptText("sk-ant-…");
        field.setText(apiKey());
        Label note = new Label("Stored in your per-user Komet preferences on this machine,\nnever in the knowledge base. One key for every assistant card.");
        note.setWrapText(true);
        Button save = new Button("Save");
        save.setOnAction(e -> {
            userPreferences().put(PREF_API_KEY, field.getText() == null ? "" : field.getText().trim());
            try {
                userPreferences().sync();
            } catch (BackingStoreException ex) {
                LOG.warn("Could not sync the API key preference", ex);
            }
            pane.refreshSummaries();
        });
        VBox content = new VBox(6, field, note, save);
        return content;
    }

    @Override
    protected void renderContent() {
        // Build the chat UI once (it is not re-realized on a coordinate change); re-render the transcript on
        // each refresh so concept chips re-resolve against the current coordinate.
        if (split == null) {
            buildBody();
        }
        refreshTranscript();
    }

    /**
     * Re-renders the document area's promoted paged view after the active conversation's turns
     * change, so the full-surface preview never lags the live surface.
     */
    private void refreshPreviewIfOpen() {
        if (documentArea != null) {
            documentArea.refreshExpandedView();
        }
    }

    @Override
    protected void subCardSave() {
        // Print settings persist on the document area's own preferences node now (#839).
        if (documentArea != null) {
            documentArea.saveSettings();
        }
        saveViewpoints();
    }

    @Override
    protected void subCardRestore() {
        // The document area restores its own print settings when created in buildBody (#839).
        loadViewpoints();
    }

    /**
     * Stores every conversation's equivalent place on leaving, so a reopened card returns
     * exactly as it was left (#943). The active conversation's live viewpoint is captured first;
     * entries for conversations that no longer exist are pruned.
     */
    private void saveViewpoints() {
        // A still-pending restore means the reader never returned to the active conversation's
        // loaded place this session — persist that loaded value untouched, not the surface's
        // unrestored position.
        if (surface != null && active != null && !pendingViewpointRestore.contains(active.id)) {
            DocumentSurface.Viewpoint current = surface.viewpoint();
            if (current != null) {
                viewpoints.put(active.id, current);
            }
        }
        try {
            KometPreferences node = preferences().node(VIEWPOINTS_NODE);
            for (String key : node.keys()) {
                if (conversations.stream().noneMatch(conv -> conv.id.equals(key))) {
                    node.remove(key);
                }
            }
            for (Map.Entry<String, DocumentSurface.Viewpoint> entry : viewpoints.entrySet()) {
                node.put(entry.getKey(), entry.getValue().encode());
            }
            node.flush();
        } catch (BackingStoreException e) {
            LOG.warn("Could not persist conversation viewpoints", e);
        }
    }

    /** Loads the persisted equivalent places (#943); each applies as its conversation activates.
     *  Every loaded id is marked restore-pending, so no capture can overwrite it before the
     *  reader has actually been returned there. */
    private void loadViewpoints() {
        try {
            KometPreferences node = preferences().node(VIEWPOINTS_NODE);
            for (String key : node.keys()) {
                DocumentSurface.Viewpoint viewpoint =
                        DocumentSurface.Viewpoint.decode(node.get(key, ""));
                if (viewpoint != null) {
                    viewpoints.put(key, viewpoint);
                    pendingViewpointRestore.add(key);
                }
            }
        } catch (BackingStoreException e) {
            LOG.warn("Could not load conversation viewpoints", e);
        }
    }

    /** Builds the chat body (conversations rail | document surface, over the input bar) as the card content. */
    private void buildBody() {
        baseFontSize = readFontSizePref();

        // The block-stack Document surface (#808), first-classed as a KlArea (#839): the area owns
        // its own preferences node and the hover-revealed "expand to full surface" affordance
        // (KlExpandable), whose full-surface view is the paged print layout with the preferences
        // toggle. The card keeps the conversation and drives the surface through the area.
        // A STABLE child node (deterministic by class), so print settings persist across sessions —
        // the create factory would mint a fresh sequentially-unique node each launch.
        documentArea = DocumentSurfaceArea.restore(preferences().node(DocumentSurfaceArea.class));
        documentArea.setRunningHeadSupplier(() -> active == null || active.name == null ? "" : active.name);
        documentArea.restoreSettings();
        surface = documentArea.surface();

        composeModel = new ConceptChipTextModel();
        input = new RichTextArea(composeModel);
        // Chips drag on a single gesture (knowledge-graphlet/komet-claude-plugin#5).
        KonceptChipGestures.install(input);
        input.setWrapText(true);
        // The compose surface grows with its content up to the cap, then
        // scrolls — longer prompts stay readable while typing
        // (ike-issues#1029) instead of scrolling inside a fixed strip.
        input.setUseContentHeight(true);
        input.setMinHeight(56);
        input.setMaxHeight(160);
        applyComposeFontSize();   // input prose and the compose chips share one readable size
        // Selected chips paint the token-field fill (accent pill, white label): the control's own
        // selection highlight draws behind the opaque pill, so the chip carries its own state.
        composeModel.setChipSelectionHandler(ComposeChips::setSelected);
        input.selectionProperty().addListener((obs, was, sel) ->
                composeModel.updateSelection(input.getAnchorPosition(), input.getCaretPosition()));
        // Enter sends; Shift+Enter inserts a newline. The newline is inserted HERE: the incubator
        // binds only plain ENTER to its line break, so a shifted press would otherwise do nothing.
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    input.insertLineBreak();
                } else {
                    onSend();
                }
                e.consume();
            }
        });
        installKonceptDrop(input);

        // RichTextArea has no prompt text; overlay a hint on the empty model instead.
        composeHint = new Label(
                "Ask about the concepts in your open knowledge base… (Drop a concept to add it as a Komet Badge)");
        composeHint.setMouseTransparent(true);
        composeHint.setStyle("-fx-text-fill: #9a9a9a;");
        composeHint.setPadding(new Insets(4, 8, 0, 8));
        composeModel.addListener(change -> composeHint.setVisible(composeModel.isEmpty()));
        StackPane composeStack = new StackPane(input, composeHint);
        StackPane.setAlignment(composeHint, Pos.TOP_LEFT);
        HBox.setHgrow(composeStack, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.setOnAction(e -> onSend());

        Button findButton = new Button("🔍");
        findButton.setTooltip(new Tooltip("Find in conversation (⌘F)"));
        findButton.setOnAction(e -> toggleFind());

        HBox inputBar = new HBox(6, findButton, composeStack, sendButton);
        inputBar.setAlignment(Pos.BOTTOM_LEFT);
        inputBar.setPadding(new Insets(6));

        // Status strip: transient transport state (working / tool / retrying / elapsed / done / failed)
        // lives here, NOT in the conversation transcript. A Retry re-sends the held request in place.
        statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 11px;");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        retryButton = new Button("Retry");
        retryButton.setOnAction(e -> retryActive());
        setRetryVisible(false);
        stopButton = new Button("Stop");
        stopButton.setTooltip(new Tooltip("Stop generating"));
        stopButton.setOnAction(e -> stopActive());
        stopButton.setVisible(false);
        stopButton.setManaged(false);
        HBox statusBar = new HBox(8, statusLabel, statusSpacer, stopButton, retryButton);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(2, 8, 0, 8));
        VBox bottom = new VBox(statusBar, inputBar);

        statusTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateStatusArea()));
        statusTimer.setCycleCount(Animation.INDEFINITE);

        Label railTitle = new Label("Conversations");
        railTitle.setStyle("-fx-font-weight: bold;");
        Region railSpacer = new Region();
        HBox.setHgrow(railSpacer, Priority.ALWAYS);
        Button newButton = new Button("+ New");
        newButton.setTooltip(new Tooltip("Start a new conversation"));
        newButton.setOnAction(e -> newConversation());
        HBox railHeader = new HBox(6, railTitle, railSpacer, newButton);
        railHeader.setAlignment(Pos.CENTER_LEFT);
        railHeader.setPadding(new Insets(6));

        // Tile selector (ike-issues#1032): this journal's assistant tiles,
        // discovered through the preferences node tree by marker. Switching
        // browses that tile's conversations; an amend moves one home here.
        tileSelector = new javafx.scene.control.ComboBox<>();
        tileSelector.setMaxWidth(Double.MAX_VALUE);
        tileSelector.setTooltip(new Tooltip(
                "Browse conversations from this journal's other assistant tiles"));
        refreshTileSelector();
        tileSelector.setOnShowing(e -> refreshTileSelector());
        tileSelector.getSelectionModel().selectedItemProperty()
                .addListener((obs, was, sel) -> {
                    // User-driven switches only: the construction-time
                    // initial selection must not preempt init's own load.
                    if (was != null && sel != null && !was.equals(sel)) {
                        showTile(sel);
                    }
                });
        HBox tileRow = new HBox(6, tileSelector);
        HBox.setHgrow(tileSelector, Priority.ALWAYS);
        tileRow.setPadding(new Insets(6, 6, 0, 6));
        conversationList = new ListView<>(conversations);
        conversationList.setPrefWidth(190);
        // Rail titles WRAP instead of running under the edge, and the list shows no scrollbar
        // chrome (KEC 2026-08-17) — trackpad scrolling still works when the rail overfills.
        conversationList.getStyleClass().add("conversation-rail-list");
        conversationList.getSelectionModel().selectedItemProperty().addListener((o, prev, sel) -> {
            if (sel != null && sel != active) {
                activate(sel);
            }
        });
        conversationList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                renameActive();
            }
        });
        // Per-conversation "thinking" spinner so parallel conversations are visible.
        conversationList.setCellFactory(lv -> new ListCell<>() {
            private final ProgressIndicator spinner = new ProgressIndicator();
            private final Label ordinalLabel = new Label();
            private final Label titleLabel = new Label();
            private final HBox row = new HBox(4, ordinalLabel, titleLabel);
            {
                spinner.setPrefSize(14, 14);
                spinner.setMaxSize(14, 14);
                // Hanging indent, like a numbered list (KEC 2026-08-17): the ordinal sits in
                // its own leading column and the title wraps in its own label, so continuation
                // lines align under the title's first character — never back under the number.
                ordinalLabel.setMinWidth(Region.USE_PREF_SIZE);
                titleLabel.setWrapText(true);
                titleLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(titleLabel, Priority.ALWAYS);
                row.setAlignment(Pos.TOP_LEFT);
                // The cell must not report its full single-line pref width, or the list
                // scrolls horizontally instead of wrapping.
                setPrefWidth(0);
                maxWidthProperty().bind(lv.widthProperty().subtract(16));
            }
            @Override
            protected void updateItem(Conversation c, boolean empty) {
                super.updateItem(c, empty);
                if (empty || c == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                // The rail mirrors the exchange headers (KEC design): a
                // chronological ordinal plus the best title — the first
                // exchange's generated title unless the user renamed it.
                int ordinal = conversations.indexOf(c) + 1;
                String generated = c.turnTitles.get(0);
                String label = (generated != null && !c.userNamed)
                        ? generated : c.name;
                ordinalLabel.setText(ordinal + " ·");
                titleLabel.setText(label);
                row.getChildren().setAll(ordinalLabel, titleLabel);
                if (c.busy) {
                    row.getChildren().add(spinner);
                }
                setText(null);
                setGraphic(row);
            }
        });
        MenuItem renameItem = new MenuItem("Rename…");
        renameItem.setOnAction(e -> renameActive());
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteActive());
        conversationList.setContextMenu(new ContextMenu(renameItem, deleteItem));

        // The tile is the overarching scope, so its selector sits ABOVE the conversations
        // header (KEC 2026-08-17); "+ New" creates within THIS tile only, so it disables while
        // browsing a sibling tile's conversations.
        newButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> tileSelector.getValue() != null && !tileSelector.getValue().own(),
                tileSelector.valueProperty()));
        conversationRail = new VBox(tileRow, railHeader, conversationList);
        VBox.setVgrow(conversationList, Priority.ALWAYS);
        conversationRail.setMinWidth(140);

        railVisible = readRailVisiblePref();
        railDivider = readRailDividerPref();
        split = new SplitPane(transcriptPanel());
        SplitPane.setResizableWithParent(conversationRail, Boolean.FALSE);

        content = new BorderPane();
        content.setCenter(split);
        content.setBottom(bottom);
        content.setPrefSize(900, 680);
        buildFindBar();
        // Cmd/Ctrl+F opens the transcript find bar.
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.F) {
                showFind();
                e.consume();
            }
        });
        setCardContent(content);

        setRailVisible(railVisible);
        // SplitPane ignores a divider position set before layout; re-apply once shown.
        Platform.runLater(() -> {
            if (railVisible) {
                split.setDividerPositions(railDivider);
            }
        });

        // Tile identity (ike-issues#1032): the marker makes this node
        // discoverable by sibling cards' selectors; the label is written
        // once so it survives as this tile's display name after close.
        preferences().put(CARD_TYPE_KEY, CARD_TYPE_VALUE);
        ensureTileLabel();
        // The selector rendered at construction, before the identity block could migrate a
        // pre-convention label — re-read so the minted name shows immediately.
        refreshTileSelector();
        loadConversations();
        if (conversations.isEmpty()) {
            newConversation();
        } else {
            activate(conversations.get(conversations.size() - 1));
        }
    }

    /** Rebuilds the document surface from the accumulated entries (chips re-resolve on the
     *  current coordinate; font-size changes re-render), returning to the equivalent place. */
    private void refreshTranscript() {
        refreshTranscript(true);
    }

    /**
     * Rebuilds the document surface from the accumulated entries. The rebuild returns to the
     * equivalent place (#943): the active conversation's viewpoint is captured first — unless the
     * surface still shows a <em>different</em> conversation ({@code captureFirst} false, the
     * {@link #activate} switch, which has already captured the conversation being left) — and
     * restored after. A conversation seen for the first time opens at its latest turn.
     */
    private void refreshTranscript(boolean captureFirst) {
        if (surface == null || entries == null) {
            return;
        }
        // Capture only a place the reader has actually returned to: while a loaded viewpoint is
        // still pending application, the surface's current position is not the reader's place.
        if (captureFirst && active != null && !pendingViewpointRestore.contains(active.id)) {
            DocumentSurface.Viewpoint viewpoint = surface.viewpoint();
            if (viewpoint != null) {
                viewpoints.put(active.id, viewpoint);
            }
        }
        // No usable view yet → chips fall back to bare identicons until one is available.
        // With the card's view, chips carry their status cluster and definition popout (#941).
        surface.setBlockFactory(new BlockFactory(safeViewProperties(), baseFontSize));
        Conversation rendering = active;
        surface.setExchangeTitleProvider(rendering == null ? null
                : ordinal -> rendering.turnTitles.get(ordinal));
        surface.setTableColumnWidths(rendering == null ? null : tableWidthsFor(rendering));
        surface.setTurns(entries);
        if (active != null) {
            DocumentSurface.Viewpoint stored = viewpoints.get(active.id);
            if (stored != null) {
                final String conversationId = active.id;
                // In flight from now until actually applied: a refresh landing in this window
                // skips its capture and re-schedules this same (correct) viewpoint instead.
                pendingViewpointRestore.add(conversationId);
                surface.restoreViewpoint(stored,
                        () -> pendingViewpointRestore.remove(conversationId));
            } else if (surface.turnCount() > 0) {
                surface.scrollToTurn(surface.turnCount() - 1);
            }
        }
        refreshMatchesIfFinding();
        refreshPreviewIfOpen();
    }

    /** Re-runs the find search when the find bar is open — every surface mutation (rebuild,
     *  append, truncate) must refresh, or match indexes point at stale blocks. */
    private void refreshMatchesIfFinding() {
        if (findBar != null && content != null && content.getTop() == findBar) {
            updateMatches();
        }
    }

    /*******************************************************************************
     *  Concept drop + Find                                                        *
     ******************************************************************************/

    /**
     * The composed message: the compose model's token-text projection — prose verbatim, each live
     * chip as its id-bearing {@code k:} token — so the assistant grounds it and the echoed turn
     * re-chips through the transcript decorator's identical grammar.
     */
    private String composeMessage() {
        return composeModel.toTokenText().trim();
    }

    private void installKonceptDrop(RichTextArea area) {
        // Filters run before the RichTextArea's own drag handling, so a koncept drop lands as a
        // live chip instead of the dragboard's plain-text PublicId being typed in.
        area.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (hasKoncept(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
                // Track the pointer with the caret so the insertion point is visible while hovering,
                // but only when it actually MOVES to a new position — re-selecting the same spot on
                // every DRAG_OVER (they fire continuously) makes the caret jitter.
                TextPos under = area.getTextPosition(e.getScreenX(), e.getScreenY());
                if (under != null && !under.equals(dropHint)) {
                    dropHint = under;
                    area.select(under);
                }
                e.consume();
            }
        });
        // Drag left without dropping — forget the tracked position so the next drag's first move is
        // never suppressed by a stale one.
        area.addEventFilter(DragEvent.DRAG_EXITED, e -> dropHint = null);
        area.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (!hasKoncept(e.getDragboard())) {
                return;
            }
            // A multi-concept drag (navigator multi-select) carries the whole set; a single drag
            // carries one component proxy. Accept either.
            int[] nids = KometClipboard.conceptNidsFrom(e.getDragboard());
            if (nids.length == 0) {
                OptionalInt nid = KometClipboard.conceptNid(e.getDragboard());
                if (nid.isEmpty()) {
                    nid = KometClipboard.entityNidFrom(e.getDragboard());
                }
                if (nid.isPresent()) {
                    nids = new int[]{nid.getAsInt()};
                }
            }
            if (nids.length > 0) {
                // The chips land where they were DROPPED — mid-text, at the end, wherever the pointer
                // is — not at whatever position the caret last held (which, on an unfocused compose
                // box, was the start).
                TextPos at = area.getTextPosition(e.getScreenX(), e.getScreenY());
                insertKonceptsAt(at, nids);
                e.setDropCompleted(true);
            }
            dropHint = null;
            e.consume();
        });
    }

    /**
     * The dragboard formats a prompt accepts: a multi-concept drag from the navigator, plus the
     * single-component proxies for a concept or a pattern.
     *
     * <p>Deliberately narrower than {@code KometClipboard}'s full proxy set, which also covers
     * semantics and stamps: whether those belong in a prompt is its own question, not a side
     * effect of admitting patterns (knowledge-graphlet/komet-claude-plugin#4).
     */
    private static final Set<DataFormat> DROPPABLE_FORMATS = Stream.concat(
                    Stream.of(KometClipboard.KOMET_CONCEPT_LIST),
                    Stream.concat(KometClipboard.CONCEPT_TYPES.stream(),
                            KometClipboard.PATTERN_TYPES.stream()))
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Whether {@code contentTypes} names something this prompt takes as a koncept chip.
     *
     * <p>Separated from the {@link Dragboard} so the acceptance rule is testable on its own — a
     * real dragboard exists only mid-gesture, which is exactly when it can't be asserted against.
     *
     * @param contentTypes the dragboard's content types
     * @return {@code true} if any is droppable
     */
    static boolean acceptsKoncept(Set<DataFormat> contentTypes) {
        return contentTypes != null && contentTypes.stream().anyMatch(DROPPABLE_FORMATS::contains);
    }

    private static boolean hasKoncept(Dragboard dragboard) {
        return dragboard != null && acceptsKoncept(dragboard.getContentTypes());
    }

    /**
     * Inserts one or more dropped koncepts — concepts or patterns — at {@code at} as live chips
     * (#789, knowledge-graphlet/komet-claude-plugin#4). Several are joined as a natural-language
     * list with the Oxford comma — {@code A}; {@code A and B}; {@code A, B, and C} — and a trailing
     * space so the user keeps typing. Each chip renders via the shared transcript pill and
     * serializes on send as its id-bearing {@code k:} token.
     *
     * @param at   where the chips land (the drop point); {@code null} falls back to the caret, then
     *             the document end
     * @param nids the dropped koncepts, in order
     */
    private void insertKonceptsAt(TextPos at, int[] nids) {
        if (at == null) {
            at = input.getCaretPosition();
        }
        if (at == null) {
            at = composeModel.getDocumentEnd();
        }
        TextPos pos = at;
        for (int i = 0; i < nids.length; i++) {
            if (i > 0) {
                pos = composeModel.insertText(pos, listSeparator(i, nids.length));
            }
            TextPos afterChip = insertChipReturning(pos, nids[i]);
            if (afterChip != null) {
                pos = afterChip;
            }
        }
        pos = composeModel.insertText(pos, " ");
        input.select(pos);
        input.requestFocus();
    }

    /**
     * The separator before item {@code i} of {@code n} in an Oxford-comma list join: {@code ", "}
     * between earlier items, {@code " and "} before the second of two, {@code ", and "} before the
     * last of three or more.
     */
    private static String listSeparator(int i, int n) {
        if (i == n - 1) {
            return n == 2 ? " and " : ", and ";
        }
        return ", ";
    }

    /**
     * Inserts one live chip at {@code at} and returns the position just after it, or {@code null}
     * when the concept has no resolvable identity (nothing composed). The supplier re-reads the live
     * view and font size per render, so a chip re-renders current after a view or font change.
     */
    private TextPos insertChipReturning(TextPos at, int nid) {
        PublicId pid;
        try {
            pid = PrimitiveData.publicId(nid);
        } catch (RuntimeException e) {
            return null;
        }
        return composeModel.insertChip(at, konceptToken(nid),
                () -> ComposeChips.chip(pid, safeViewProperties(), baseFontSize));
    }

    /** The id-bearing {@code k:} token for a concept: UUID-keyed, labelled with the resolved name. */
    private String konceptToken(int nid) {
        String label = conceptName(nid);
        try {
            String uuid = PrimitiveData.publicId(nid).asUuidArray()[0].toString();
            return KonceptTokens.token("uuid", uuid, label);
        } catch (RuntimeException e) {
            return KonceptTokens.token("nid", Integer.toString(nid), label);
        }
    }

    private String conceptName(int nid) {
        try {
            ViewCalculator vc = viewCalculator();
            if (vc != null) {
                // The coordinate-preferred description — the same name the rendered chip shows,
                // so the token label written into the markdown matches the display (#942).
                return vc.getDescriptionTextOrNid(nid);
            }
        } catch (RuntimeException e) {
            // No usable view — fall back to the nid marker.
        }
        return "nid=" + nid;
    }

    private void buildFindBar() {
        findField = new TextField();
        findField.setPromptText("Find in conversation…");
        HBox.setHgrow(findField, Priority.ALWAYS);
        findCount = new Label("");

        Button prev = new Button("▲");
        Button next = new Button("▼");
        Button close = new Button("✕");
        prev.setOnAction(e -> step(-1));
        next.setOnAction(e -> step(1));
        close.setOnAction(e -> hideFind());

        findField.textProperty().addListener((obs, old, val) -> updateMatches());
        findField.setOnAction(e -> step(1));
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideFind();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
                step(-1);
                e.consume();
            }
        });

        findBar = new HBox(6, findField, findCount, prev, next, close);
        findBar.setAlignment(Pos.CENTER_LEFT);
        findBar.setPadding(new Insets(6));
    }

    /** Toggles the find bar (the 🔍 button); mirror of Cmd/Ctrl+F. */
    private void toggleFind() {
        if (content != null && content.getTop() == findBar) {
            hideFind();
        } else {
            showFind();
        }
    }

    private void showFind() {
        if (content == null) {
            return;
        }
        if (content.getTop() != findBar) {
            content.setTop(findBar);
        }
        findField.requestFocus();
        findField.selectAll();
        updateMatches();
    }

    private void hideFind() {
        if (content != null) {
            content.setTop(null);
        }
        if (surface != null) {
            surface.clearSelection();
        }
        matches = List.of();
        matchPos = -1;
        if (input != null) {
            input.requestFocus();
        }
    }

    /** Re-searches the document surface for the find text and selects the first match. */
    private void updateMatches() {
        if (surface == null) {
            return;
        }
        matches = surface.findAll(findField.getText());
        if (matches.isEmpty()) {
            matchPos = -1;
            String query = findField.getText();
            findCount.setText(query == null || query.isEmpty() ? "" : "No results");
            surface.clearSelection();
        } else {
            matchPos = 0;
            selectCurrent();
        }
    }

    /** Moves to the next ({@code +1}) or previous ({@code -1}) match, wrapping around. */
    private void step(int delta) {
        if (matches.isEmpty()) {
            return;
        }
        matchPos = (matchPos + delta + matches.size()) % matches.size();
        selectCurrent();
    }

    private void selectCurrent() {
        surface.select(matches.get(matchPos));
        findCount.setText((matchPos + 1) + " / " + matches.size());
    }

    /** Adjusts the transcript font size by {@code delta} px (clamped), persists it, and re-renders. */
    private void adjustFont(double delta) {
        baseFontSize = Math.max(9, Math.min(28, baseFontSize + delta));
        userPreferences().put(PREF_FONT_SIZE, Double.toString(baseFontSize));
        applyComposeFontSize();
        refreshTranscript();
    }

    /**
     * Renders the compose input's prose at {@code baseFontSize} — the transcript body size — so the
     * input text and its inline chips (built at the same size via {@link ComposeChips#chip}) read as
     * one, rather than the incubator's small default clashing with the chips. The prose resizes at
     * once; a chip already in the box (a cached segment) re-materializes at the new size only when
     * its cell next rebuilds — the following keystroke or insert — which is acceptable for the rare
     * mid-compose font change.
     */
    private void applyComposeFontSize() {
        if (input != null) {
            input.setStyle("-fx-font-size: " + Math.round(baseFontSize) + "px;");
        }
    }

    private double readFontSizePref() {
        try {
            return Double.parseDouble(
                    userPreferences().get(PREF_FONT_SIZE, Double.toString(MarkdownRichText.DEFAULT_BASE)));
        } catch (RuntimeException e) {
            return MarkdownRichText.DEFAULT_BASE;
        }
    }

    /** Shows or hides the conversations rail (left of the transcript), persisting the choice + width. */
    /**
     * The transcript panel: the document surface under its own slim, non-scrolling header strip
     * (ike-issues#1040) carrying the exchange-scoped controls — expand all / collapse all live
     * beside the conversation they act on, not in the card toolbar. The strip sits outside the
     * surface's one scroll axis, so it never scrolls away.
     */
    private BorderPane transcriptPanel() {
        Button saveButton = new Button("Save…");
        saveButton.setTooltip(new Tooltip("Save this conversation's transcript"));
        saveButton.setOnAction(e -> saveTranscript());
        Button expandAll = new Button("⊞");
        expandAll.setTooltip(new Tooltip("Expand all exchanges"));
        expandAll.setOnAction(e -> surface.setAllExchangesCollapsed(false));
        Button collapseAll = new Button("⊟");
        collapseAll.setTooltip(new Tooltip("Collapse all exchanges"));
        collapseAll.setOnAction(e -> surface.setAllExchangesCollapsed(true));
        HBox strip = new HBox(4, saveButton, expandAll, collapseAll);
        strip.setAlignment(Pos.CENTER_RIGHT);
        strip.setPadding(new Insets(2, 8, 2, 8));

        BorderPane panel = new BorderPane();
        panel.setTop(strip);
        panel.setCenter(documentArea.fxObject());
        return panel;
    }

    private void setRailVisible(boolean visible) {
        railVisible = visible;
        if (split == null) {
            return;   // applied by buildBody once the body exists
        }
        boolean present = split.getItems().contains(conversationRail);
        if (visible && !present) {
            split.getItems().add(0, conversationRail);
            split.setDividerPositions(railDivider);
            if (!split.getDividers().isEmpty()) {
                split.getDividers().get(0).positionProperty().addListener((o, ov, nv) -> {
                    railDivider = nv.doubleValue();
                    userPreferences().put(PREF_RAIL_DIVIDER, Double.toString(railDivider));
                });
            }
        } else if (!visible && present) {
            if (split.getDividerPositions().length > 0) {
                railDivider = split.getDividerPositions()[0];
            }
            split.getItems().remove(conversationRail);
        }
        userPreferences().put(PREF_RAIL_VISIBLE, Boolean.toString(visible));
    }

    private boolean readRailVisiblePref() {
        return !"false".equals(userPreferences().get(PREF_RAIL_VISIBLE, "true"));
    }

    private double readRailDividerPref() {
        try {
            double d = Double.parseDouble(userPreferences().get(PREF_RAIL_DIVIDER, "0.24"));
            return (d > 0.05 && d < 0.9) ? d : 0.24;
        } catch (RuntimeException e) {
            return 0.24;
        }
    }

    /*******************************************************************************
     *  Send / tool-use loop                                                       *
     ******************************************************************************/

    private void onSend() {
        String text = composeMessage();
        if (text.isEmpty()) {
            return;
        }
        String key = hasApiKey() ? apiKey() : promptForApiKey();
        if (key == null || key.isBlank()) {
            return;
        }
        Conversation conv = active;
        if (conv == null || conv.busy) {
            return;
        }

        if (!conv.named) {
            // Rail names show the human projection: chips read as their labels, not raw k: tokens.
            String display = KonceptTokens.display(text);
            conv.name = display.length() > 40 ? display.substring(0, 40).trim() + "…" : display;
            conv.named = true;
        }
        // The greeting is chrome, not history: it leaves when the first
        // real prompt arrives (KEC review), keeping display == committed
        // history from the first exchange on.
        if (conv.apiMessages.isEmpty() && conv.entries.size() == 1
                && conv.entries.get(0).role() == MarkdownRichText.Role.ASSISTANT) {
            conv.entries.clear();
            conv.markdown.setLength(0);
            if (conv == active) {
                surface.setTurns(List.of());
            }
        }
        composeModel.clear();
        dispatch(conv, text, key, currentModel());
    }

    /**
     * Re-sends the active conversation's held failed request (the Retry button). The failed send's
     * optimistic user turn was popped by the rollback, so the re-dispatch renders it afresh —
     * exactly like a first send.
     */
    private void retryActive() {
        Conversation conv = active;
        if (conv == null || conv.busy || conv.pendingRetryText == null) {
            return;
        }
        String key = hasApiKey() ? apiKey() : promptForApiKey();
        if (key == null || key.isBlank()) {
            return;
        }
        dispatch(conv, conv.pendingRetryText, key, currentModel());
    }

    /** The configured model id for a new request. */
    private String currentModel() {
        return userPreferences().get(PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
    }

    /**
     * Sends (or re-sends) {@code text} on {@code conv}: flips it busy, drives the status strip from the
     * exchange's live progress (turn / tool / retry / elapsed), and on completion records the assistant
     * turn — or, on failure, holds the text for Retry and shows the failure as <em>status</em>, never as
     * a transcript message.
     */
    private void dispatch(Conversation conv, String text, String key, String model) {
        // Amending a conversation browsed from another tile moves it home to
        // this card first, with provenance recorded both ways (ike-issues#1032).
        moveConversationHome(conv);
        // Render the user's message optimistically, remembering where to pop it back to if the send
        // fails — a failed turn must not linger in the transcript (or diverge from the saved history).
        int entryMark = conv.entries.size();
        int markdownMark = conv.markdown.length();
        renderUser(conv, text);

        conv.busy = true;
        conv.activity = "Working";
        conv.startNanos = System.nanoTime();
        conv.outcome = null;
        conv.outcomeFailed = false;
        conv.outcomeStopped = false;
        conv.stopRequested = false;
        conv.pendingRetryText = null;
        conversationList.refresh();
        if (conv == active) {
            updateInputState();
            updateStatusArea();
        }
        startStatusTimer();

        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS);
        List<Map<String, Object>> history = List.copyOf(conv.apiMessages);
        AskListener listener = new AskListener() {
            @Override
            public void onTurnStart(int turn) {
                setActivity(conv, "Working");
            }

            @Override
            public void onToolCall(int turn, String tool, Map<String, Object> args) {
                setActivity(conv, "Calling " + tool + "…");
            }

            @Override
            public void onRetry(int turn, int attempt, int maxAttempts, long waitMillis, String reason) {
                setActivity(conv, reason + " — retrying " + attempt + "/" + maxAttempts
                        + " in " + Math.max(1, Math.round(waitMillis / 1000.0)) + "s…");
            }
        };

        conv.task = worker.submit(() -> {
            String reply = null;
            String errorMessage = null;
            try {
                reply = client.ask(systemPrompt, tools, history, text, listener);
                // Chronology persistence (#807), write-after-success and off the FX thread: the
                // completed exchange becomes two prose elements + one new manifest version
                // (appendExchange bootstraps the vocabulary itself). A store failure degrades to
                // JSON-only — the conversation itself must never be lost to it.
                try {
                    conv.journalAnchor =
                            journalStore.appendExchange(conv.journalAnchor, conv.name, text, reply);
                } catch (Throwable je) {
                    LOG.warn("Journal write failed; conversation persists as JSON only", je);
                }
            } catch (Throwable t) {
                // Catch Throwable: a non-runtime failure (e.g. class-init / ServiceConfigurationError)
                // must still settle the status strip, not wedge the conversation busy.
                LOG.error("Claude request failed", t);
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                errorMessage = (root != t && root.getMessage() != null)
                        ? msg + " (" + root.getMessage() + ")" : msg;
            }
            String finalReply = reply;
            String finalError = errorMessage;
            long elapsedMillis = (System.nanoTime() - conv.startNanos) / 1_000_000L;
            Platform.runLater(() -> {
                conv.busy = false;
                conv.activity = null;
                conv.task = null;
                // The conversation may have been deleted while in flight — do not render or persist it
                // (a save here would resurrect the deleted file on disk).
                if (!conversations.contains(conv)) {
                    stopStatusTimerIfIdle();
                    return;
                }
                if (finalError != null) {
                    // A failed turn is not part of the conversation: pop the optimistic user bubble so the
                    // transcript stays equal to the committed (and persisted) history. Hold it for Retry.
                    truncateEntries(conv, entryMark, markdownMark);
                    conv.outcomeStopped = conv.stopRequested;
                    conv.outcome = conv.outcomeStopped
                            ? "■ Stopped" : "✕ " + finalError;
                    conv.stopRequested = false;
                    conv.outcomeFailed = true;
                    conv.pendingRetryText = text;
                } else {
                    renderAssistant(conv, finalReply);
                    conv.apiMessages.add(Map.of("role", "user", "content", text));
                    conv.apiMessages.add(Map.of("role", "assistant", "content", finalReply));
                    saveConversation(conv);
                    conv.outcome = "✓ Replied in " + formatElapsed(elapsedMillis);
                    conv.outcomeFailed = false;
                    conv.pendingRetryText = null;
                    requestExchangeTitle(conv, text, finalReply, key, model);
                }
                conversationList.refresh();
                if (conv == active) {
                    updateInputState();
                    updateStatusArea();
                    input.requestFocus();
                }
                stopStatusTimerIfIdle();
            });
        });
    }

    /**
     * Asks for a short generated title naming the completed exchange (KEC
     * design: numbered named turns) — a one-shot, tool-free call off the
     * FX thread. The title is transcript content: it persists in the
     * conversation payload and renders as the exchange header. Best-effort;
     * a failed title leaves the numbered question preview in place.
     */
    private void requestExchangeTitle(Conversation conv, String question,
                                      String answer, String key, String model) {
        long userTurns = conv.apiMessages.stream()
                .filter(m -> "user".equals(String.valueOf(m.get("role")))).count();
        int ordinal = (int) userTurns - 1;
        requestExchangeTitleFor(conv, ordinal, question, answer, key, model);
    }

    private void requestExchangeTitleFor(Conversation conv, int ordinal,
                                         String question, String answer,
                                         String key, String model) {
        if (ordinal < 0 || conv.turnTitles.containsKey(ordinal)) {
            return;
        }
        worker.submit(() -> {
            try {
                AnthropicClient titler = new AnthropicClient(key, model, 64);
                String prompt = "Title this exchange in 3 to 6 words. Reply with"
                        + " the title only — no quotes, no punctuation at the"
                        + " end.\n\nQuestion: " + clip(question, 500)
                        + "\n\nAnswer: " + clip(answer, 800);
                String raw = titler.ask("You title conversation exchanges"
                        + " concisely.", List.of(), prompt);
                String title = raw == null ? "" : raw.strip()
                        .replaceAll("^[\"']+|[\"'.]+$", "")
                        .replaceAll("\\s+", " ");
                if (title.isBlank()) {
                    return;
                }
                String settled = clip(title, 60);
                Platform.runLater(() -> {
                    conv.turnTitles.put(ordinal, settled);
                    saveConversation(conv);
                    if (conv == active) {
                        surface.setExchangeTitle(ordinal, settled);
                    }
                });
            } catch (Exception e) {
                LOG.debug("Exchange title generation failed", e);
            }
        });
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** Pops entries/markdown back to a mark — removes a failed turn's optimistic user bubble. */
    private void truncateEntries(Conversation conv, int entryMark, int markdownMark) {
        while (conv.entries.size() > entryMark) {
            conv.entries.remove(conv.entries.size() - 1);
        }
        if (conv.markdown.length() > markdownMark) {
            conv.markdown.setLength(markdownMark);
        }
        if (conv == active) {
            surface.truncateTurns(entryMark);
            refreshMatchesIfFinding();
            refreshPreviewIfOpen();
        }
    }

    /** Sets the live activity for {@code conv} (off-thread safe) and refreshes the strip if it is active. */
    private void setActivity(Conversation conv, String activity) {
        conv.activity = activity;
        Platform.runLater(() -> {
            if (conv == active) {
                updateStatusArea();
            }
        });
    }

    /** Renders the status strip for the active conversation (the working clock, or the settled outcome). */
    private void updateStatusArea() {
        if (active != null && active.busy) {
            long seconds = Math.max(0, (System.nanoTime() - active.startNanos) / 1_000_000_000L);
            String base = (active.activity != null) ? active.activity : "Working";
            statusLabel.setText(base + " · " + seconds + "s");
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-base-color;");
            statusLabel.setTooltip(new Tooltip(statusLabel.getText()));
            setRetryVisible(false);
            setStopVisible(true);
        } else if (active != null && active.outcome != null) {
            statusLabel.setText(active.outcome);
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                    + (active.outcomeStopped ? "-fx-text-base-color"
                            : active.outcomeFailed ? "#c62828" : "#2e7d32")
                    + ";");
            // The failure message (cause + status-page URL) can outrun the strip width; the tooltip keeps
            // the full text reachable since failures are not in the transcript.
            statusLabel.setTooltip(new Tooltip(active.outcome));
            setStopVisible(false);
            boolean canRetry = active.outcomeFailed && active.pendingRetryText != null;
            setRetryVisible(canRetry);
            if (canRetry) {
                String preview = active.pendingRetryText.length() > 60
                        ? active.pendingRetryText.substring(0, 60).trim() + "…" : active.pendingRetryText;
                retryButton.setTooltip(new Tooltip("Resend: " + preview));
            }
        } else {
            statusLabel.setText("");
            statusLabel.setStyle("-fx-font-size: 11px;");
            statusLabel.setTooltip(null);
            setRetryVisible(false);
            setStopVisible(false);
        }
    }

    private void setRetryVisible(boolean visible) {
        retryButton.setVisible(visible);
        retryButton.setManaged(visible);
    }

    private void setStopVisible(boolean visible) {
        stopButton.setVisible(visible);
        stopButton.setManaged(visible);
    }

    /**
     * Stops the active conversation's in-flight generation: marks the
     * stop as deliberate so the completion handler settles the strip at
     * "■ Stopped" (prompt held for Retry, optimistic bubble popped —
     * the transcript stays equal to committed history), then cancels
     * the interrupt-aware worker. No-op when nothing is in flight.
     */
    private void stopActive() {
        if (active == null || !active.busy || active.task == null) {
            return;
        }
        active.stopRequested = true;
        active.task.cancel(true);
    }

    /** Starts the 1 Hz elapsed clock if it is not already running. */
    private void startStatusTimer() {
        if (statusTimer != null && statusTimer.getStatus() != Animation.Status.RUNNING) {
            statusTimer.play();
        }
    }

    /** Stops the elapsed clock once no conversation is in flight. */
    private void stopStatusTimerIfIdle() {
        if (statusTimer != null && conversations.stream().noneMatch(c -> c.busy)) {
            statusTimer.stop();
        }
    }

    /** Formats an elapsed duration compactly: {@code "820 ms"} or {@code "2.3 s"}. */
    private static String formatElapsed(long millis) {
        return (millis < 1000) ? millis + " ms" : String.format("%.1f s", millis / 1000.0);
    }

    /**
     * Reflects only the <em>active</em> conversation's in-flight state on the shared input. Other
     * conversations keep running in parallel; the rail spinner shows which ones are thinking.
     */
    private void updateInputState() {
        boolean busy = active != null && active.busy;
        input.setDisable(busy);
        sendButton.setDisable(busy);
        sendButton.setText(busy ? "Working…" : "Send");
    }

    /*******************************************************************************
     *  Conversations                                                              *
     ******************************************************************************/

    /** Makes {@code conv} active: repoints the live refs and re-renders the transcript. */
    /**
     * Retrofits generated titles onto exchanges that predate the titling
     * convention (KEC discipline: modernize existing data to new
     * conventions when the chance arises). Best-effort and quiet — no
     * key, no work; already-titled exchanges are skipped.
     */
    private void retrofitExchangeTitles(Conversation conv) {
        if (!hasApiKey()) {
            return;
        }
        String key = apiKey();
        String model = currentModel();
        int ordinal = -1;
        String question = null;
        for (Map<String, Object> message : List.copyOf(conv.apiMessages)) {
            String content = String.valueOf(message.get("content"));
            if ("user".equals(String.valueOf(message.get("role")))) {
                ordinal++;
                question = content;
            } else if (question != null
                    && !conv.turnTitles.containsKey(ordinal)) {
                requestExchangeTitleFor(conv, ordinal, question, content,
                        key, model);
            }
        }
    }

    private void activate(Conversation conv) {
        retrofitExchangeTitles(conv);
        // Leaving the current conversation: remember its equivalent place, so switching back
        // returns exactly where the reader left it (#943). A still-pending restore means the
        // reader never returned there this session — its loaded viewpoint stays authoritative.
        if (active != null && surface != null && !pendingViewpointRestore.contains(active.id)) {
            DocumentSurface.Viewpoint viewpoint = surface.viewpoint();
            if (viewpoint != null) {
                viewpoints.put(active.id, viewpoint);
            }
        }
        active = conv;
        entries = conv.entries;
        transcriptMarkdown = conv.markdown;
        if (conversationList.getSelectionModel().getSelectedItem() != conv) {
            conversationList.getSelectionModel().select(conv);
        }
        refreshTranscript(false);
        updateInputState();
        updateStatusArea();
    }

    /** Creates a fresh conversation (with the intro) and makes it active. */
    private void newConversation() {
        // New conversations always belong to this card: leave any browsed
        // tile and return the rail to the home store, then create as usual.
        if (tileSelector != null && tileSelector.getValue() != null
                && !tileSelector.getValue().own()) {
            tileSelector.getSelectionModel().select(0);
        }
        Conversation conv = new Conversation(UUID.randomUUID().toString(), "New conversation");
        conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT,
                "Ask about the concepts in your open knowledge base. "
                        + "I answer by running read-only queries against the active view — "
                        + "I won't invent codes or relationships. "
                        + (hasApiKey()
                                ? "Type a question below to begin."
                                : "Set your Anthropic API key (the \"API key…\" button) to begin."),
                false));
        conversations.add(conv);
        activate(conv);
    }

    /** Renames the active conversation (double-click on the rail). */
    private void renameActive() {
        if (active == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(active.name);
        dialog.setTitle("Rename conversation");
        dialog.setHeaderText(null);
        dialog.setContentText("Name:");
        dialog.initOwner(ownerWindow());
        dialog.showAndWait().ifPresent(name -> {
            if (!name.isBlank()) {
                active.name = name.trim();
                active.named = true;
                active.userNamed = true;
                conversationList.refresh();
                saveConversation(active);
            }
        });
    }

    /** Confirms, then removes the active conversation (rail + file) and activates another. */
    private void deleteActive() {
        if (active == null) {
            return;
        }
        Conversation toDelete = active;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete conversation \"" + toDelete.name + "\"? This cannot be undone.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.initOwner(ownerWindow());
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        int idx = conversations.indexOf(toDelete);
        conversations.remove(toDelete);
        // Cancel any in-flight request: the worker is interrupt-aware, and the completion handler's
        // liveness guard already skips persistence for a conversation no longer in the list.
        if (toDelete.task != null) {
            toDelete.task.cancel(true);
        }
        deleteConversationFile(toDelete);
        if (conversations.isEmpty()) {
            newConversation();
        } else {
            activate(conversations.get(Math.min(idx, conversations.size() - 1)));
        }
    }

    /*******************************************************************************
     *  Persistence — this card's OWN prefs-node directory (per-instance sandbox)  *
     ******************************************************************************/

    /** This card's preferences-node directory, created on demand; null if the store is not directory-backed. */
    private Path conversationsDir() {
        Optional<Path> dir = preferences().directory();
        if (dir.isEmpty()) {
            return null;
        }
        try {
            Files.createDirectories(dir.get());
            return dir.get();
        } catch (IOException e) {
            LOG.warn("Could not create conversations dir {}", dir.get(), e);
            return null;
        }
    }

    /** Persists one conversation (skips empties); files are named by id, flat in the node directory. */
    private void saveConversation(Conversation conv) {
        if (conv == null || conv.apiMessages.isEmpty()) {
            return;
        }
        Path dir = conv.homeDir != null ? conv.homeDir : conversationsDir();
        if (dir == null) {
            return;
        }
        KometPreferences home =
                conv.homeNode != null ? conv.homeNode : preferences();
        writeIndexEntry(home, conv, "conversation-" + conv.id + ".json");
        try {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", conv.id);
            dto.put("name", conv.name);
            dto.put("turns", conv.apiMessages);
            if (!conv.turnTitles.isEmpty()) {
                Map<String, String> titles = new LinkedHashMap<>();
                conv.turnTitles.forEach((k, v) -> titles.put(String.valueOf(k), v));
                dto.put("titles", titles);
            }
            PublicId anchor = conv.journalAnchor;
            if (anchor != null) {
                dto.put("journalAnchor", anchor.asUuidArray()[0].toString());
            }
            String json = Json.stringify(dto);
            Files.writeString(dir.resolve("conversation-" + conv.id + ".json"), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Could not save conversation {}", conv.id, e);
        }
    }

    /** One selectable tile in the selector; label is the display identity. */
    private record TileRef(KometPreferences node, String label, boolean own) {
        @Override
        public String toString() {
            return own ? label + " (this tile)" : label;
        }
    }

    /**
     * Mints this tile's durable display name once, per the naming convention (KEC 2026-08-17):
     * <em>kind of card + sequence</em> — "Assistant Card N", the next sequence past every
     * sibling's. A pre-convention label ("Tile · <timestamp>") migrates forward on first open —
     * the forward-compatibility discipline: modernize to the new convention when we can. The
     * broader knowledge-base / journal / card / conversation naming model is a wishlist design
     * item; this is its card-tile corner.
     */
    private void ensureTileLabel() {
        String current = preferences().get(TILE_LABEL_KEY, "");
        if (!current.isBlank() && !current.startsWith("Tile · ")) {
            return;
        }
        int next = 1;
        java.util.regex.Pattern named = java.util.regex.Pattern.compile("Assistant Card (\\d+)");
        for (KometPreferences tile : assistantTiles()) {
            java.util.regex.Matcher m = named.matcher(tile.get(TILE_LABEL_KEY, ""));
            if (m.matches()) {
                next = Math.max(next, Integer.parseInt(m.group(1)) + 1);
            }
        }
        preferences().put(TILE_LABEL_KEY, "Assistant Card " + next);
    }

    /** Rebuilds the selector's items: this tile first, then siblings by label. */
    private void refreshTileSelector() {
        TileRef selected = tileSelector.getValue();
        List<TileRef> items = new ArrayList<>();
        items.add(new TileRef(preferences(),
                preferences().get(TILE_LABEL_KEY, "This tile"), true));
        for (KometPreferences tile : assistantTiles()) {
            items.add(new TileRef(tile,
                    tile.get(TILE_LABEL_KEY, "Tile " + tile.name()), false));
        }
        tileSelector.getItems().setAll(items);
        if (selected == null || items.stream().noneMatch(selected::equals)) {
            tileSelector.getSelectionModel().select(0);
        } else {
            tileSelector.getSelectionModel().select(selected);
        }
    }

    /** Replaces the conversation list with the selected tile's store. */
    private void showTile(TileRef tile) {
        conversations.clear();
        if (tile.own()) {
            loadConversations();
        } else {
            Optional<Path> dir = tile.node().directory();
            dir.ifPresent(path -> loadTile(tile.node(), path, true));
        }
        if (!conversations.isEmpty()) {
            activate(conversations.get(conversations.size() - 1));
        }
    }

    /**
     * Writes/refreshes the authoritative index record for a conversation
     * into the given card's index — name, the explicit payload-file
     * pointer, last-active, and the journal anchor. The index is the
     * only discovery path; the payload file is reached through
     * {@link #KEY_FILE}, never by naming convention.
     */
    private static void writeIndexEntry(KometPreferences cardNode,
                                        Conversation conv, String fileName) {
        KometPreferences entry = cardNode.node(INDEX_NODE).node(conv.id);
        entry.put(KEY_NAME, conv.name == null ? "" : conv.name);
        entry.put(KEY_FILE, fileName);
        entry.put(KEY_LAST_ACTIVE, Long.toString(System.currentTimeMillis()));
        PublicId anchor = conv.journalAnchor;
        if (anchor != null) {
            entry.put(KEY_ANCHOR, anchor.asUuidArray()[0].toString());
        }
        entry.remove(KEY_MOVED_TO);
    }

    /**
     * Loads the conversations a tile's index declares, most recently
     * active first, skipping records marked moved elsewhere. Own-tile
     * loads adopt any pre-index payload files exactly once — the one
     * sanctioned convention read, existing to write explicit pointers
     * for history created before the index existed.
     *
     * @param cardNode the tile's preferences node
     * @param dir      the tile's payload directory
     * @param foreign  whether the tile is another card's (sets home)
     */
    private void loadTile(KometPreferences cardNode, Path dir, boolean foreign) {
        if (dir == null) {
            return;
        }
        if (!foreign) {
            adoptPreIndexPayloads(cardNode, dir);
        }
        record Row(String id, String file, long lastActive) {}
        List<Row> rows = new ArrayList<>();
        try {
            KometPreferences index = cardNode.node(INDEX_NODE);
            for (String id : index.childrenNames()) {
                KometPreferences entry = index.node(id);
                if (!entry.get(KEY_MOVED_TO, "").isBlank()
                        || "true".equals(entry.get(KEY_HIDDEN, ""))) {
                    continue;
                }
                String file = entry.get(KEY_FILE, "");
                if (file.isBlank()) {
                    continue;
                }
                long last = Long.parseLong(entry.get(KEY_LAST_ACTIVE, "0"));
                rows.add(new Row(id, file, last));
            }
        } catch (Exception e) {
            LOG.warn("Could not read conversation index of {}", cardNode, e);
            return;
        }
        // Chronological, newest LAST (KEC design): the rail reads in the
        // order the work happened, and a repeated prompt lands at the end.
        rows.sort(java.util.Comparator.comparingLong(Row::lastActive));
        for (Row row : rows) {
            Path payload = dir.resolve(row.file());
            if (!Files.isRegularFile(payload)) {
                LOG.warn("Indexed conversation {} points at missing payload {}",
                        row.id(), payload);
                continue;
            }
            Conversation conv = loadConversation(payload);
            if (conv != null && foreign) {
                conv.homeNode = cardNode;
                conv.homeDir = dir;
            }
        }
    }

    /** One-time adoption: index own payload files created before the index existed. */
    private void adoptPreIndexPayloads(KometPreferences cardNode, Path dir) {
        try (var paths = Files.list(dir)) {
            KometPreferences index = cardNode.node(INDEX_NODE);
            Set<String> indexed = Set.of(index.childrenNames());
            paths.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith("conversation-") && n.endsWith(".json");
            }).forEach(p -> {
                String n = p.getFileName().toString();
                String id = n.substring("conversation-".length(),
                        n.length() - ".json".length());
                if (!indexed.contains(id)) {
                    KometPreferences entry = index.node(id);
                    entry.put(KEY_NAME, "");
                    entry.put(KEY_FILE, n);
                    entry.put(KEY_LAST_ACTIVE,
                            Long.toString(p.toFile().lastModified()));
                }
            });
        } catch (Exception e) {
            LOG.warn("Could not adopt pre-index payloads in {}", dir, e);
        }
    }

    /** Sibling assistant tiles in this journal window, discovered by marker — never by path magic. */
    private List<KometPreferences> assistantTiles() {
        List<KometPreferences> tiles = new ArrayList<>();
        try {
            KometPreferences parent = preferences().parent();
            if (parent == null) {
                return tiles;
            }
            for (KometPreferences child : parent.children()) {
                if (CARD_TYPE_VALUE.equals(child.get(CARD_TYPE_KEY, ""))
                        && !child.name().equals(preferences().name())) {
                    tiles.add(child);
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate assistant tiles", e);
        }
        return tiles;
    }

    /**
     * Moves a browsed conversation home to this card before an amend
     * (ike-issues#1032): payload file and index entry transfer here
     * (recording the origin), the origin's index keeps a moved-to
     * marker, and the #943 viewpoint travels along. No conversation
     * ever has two live homes.
     */
    /**
     * The preferences-backed table-column-width store for a conversation (ike-issues#1034):
     * widths live under the conversation's index entry — {@code conversations/<id>/table-widths},
     * one key per table identity, the widths as a comma-separated list. The payload JSON stays a
     * dumb transcript; everything persistent about the card's rendering is preferences.
     *
     * @param conv the conversation whose widths to recall and remember
     * @return a store over the conversation's index-entry preferences node
     */
    private TableColumnWidths tableWidthsFor(Conversation conv) {
        KometPreferences home = conv.homeNode != null ? conv.homeNode : preferences();
        KometPreferences widths = home.node(INDEX_NODE).node(conv.id).node(TABLE_WIDTHS_NODE);
        return new TableColumnWidths() {
            @Override
            public double[] recall(String tableKey, int columnCount) {
                String csv = widths.get(tableKey, "");
                if (csv.isBlank()) {
                    return null;
                }
                String[] parts = csv.split(",");
                if (parts.length != columnCount) {
                    return null;
                }
                double[] out = new double[parts.length];
                try {
                    for (int i = 0; i < parts.length; i++) {
                        out[i] = Double.parseDouble(parts[i]);
                    }
                } catch (NumberFormatException e) {
                    return null;
                }
                return out;
            }

            @Override
            public void remember(String tableKey, double[] columnWidths) {
                StringBuilder csv = new StringBuilder();
                for (double width : columnWidths) {
                    if (!csv.isEmpty()) {
                        csv.append(',');
                    }
                    csv.append(Math.round(width));
                }
                widths.put(tableKey, csv.toString());
                try {
                    widths.flush();
                } catch (java.util.prefs.BackingStoreException e) {
                    LOG.warn("Could not persist table column widths for {}", conv.id, e);
                }
            }
        };
    }

    private void moveConversationHome(Conversation conv) {
        if (conv.homeNode == null || conv.homeDir == null) {
            return;
        }
        Path ownDir = conversationsDir();
        if (ownDir == null) {
            return;
        }
        String fileName = "conversation-" + conv.id + ".json";
        try {
            Files.move(conv.homeDir.resolve(fileName), ownDir.resolve(fileName),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.warn("Could not move conversation {} home", conv.id, e);
            return;
        }
        writeIndexEntry(preferences(), conv, fileName);
        preferences().node(INDEX_NODE).node(conv.id)
                .put(KEY_MOVED_FROM, conv.homeNode.name());
        KometPreferences originEntry =
                conv.homeNode.node(INDEX_NODE).node(conv.id);
        originEntry.put(KEY_MOVED_TO, preferences().name());
        String viewpoint = conv.homeNode.node(VIEWPOINTS_NODE).get(conv.id, "");
        if (!viewpoint.isBlank()) {
            preferences().node(VIEWPOINTS_NODE).put(conv.id, viewpoint);
            conv.homeNode.node(VIEWPOINTS_NODE).remove(conv.id);
        }
        // Remembered table column widths (ike-issues#1034) follow the
        // conversation to its new home, like the viewpoint above.
        try {
            KometPreferences originWidths = originEntry.node(TABLE_WIDTHS_NODE);
            KometPreferences movedWidths =
                    preferences().node(INDEX_NODE).node(conv.id).node(TABLE_WIDTHS_NODE);
            for (String tableKey : originWidths.keys()) {
                movedWidths.put(tableKey, originWidths.get(tableKey, ""));
                originWidths.remove(tableKey);
            }
        } catch (java.util.prefs.BackingStoreException e) {
            LOG.warn("Could not move table column widths for {}", conv.id, e);
        }
        conv.homeNode = null;
        conv.homeDir = null;
    }

    /** Loads this card's conversations, most recently active first. */
    private void loadConversations() {
        loadTile(preferences(), conversationsDir(), false);
    }

    private Conversation loadConversation(Path file) {
        try {
            ConversationDto dto = Json.parse(Files.readString(file, StandardCharsets.UTF_8), ConversationDto.class);
            Conversation conv = new Conversation(dto.id(), dto.name());
            conv.named = true;
            if (dto.titles() != null) {
                dto.titles().forEach((k, v) -> {
                    try {
                        conv.turnTitles.put(Integer.parseInt(k), v);
                    } catch (NumberFormatException ignored) {
                        // A malformed ordinal names nothing.
                    }
                });
            }
            // apiMessages (the LLM context) always come from the JSON, journal or not.
            if (dto.turns() != null) {
                conv.apiMessages.addAll(dto.turns());
            }
            if (dto.journalAnchor() != null && !dto.journalAnchor().isBlank()) {
                try {
                    conv.journalAnchor = PublicIds.of(UUID.fromString(dto.journalAnchor()));
                } catch (RuntimeException e) {
                    LOG.warn("Conversation {} carries an unparseable journal anchor '{}'",
                            dto.id(), dto.journalAnchor(), e);
                }
            }
            // JSON-first display (fast, store-free), then the journal reconcile: the journal is the
            // document of record (#807), but its per-element chronology reads happen OFF the FX
            // thread, and it only replaces the display when it covers at least the JSON history —
            // a journal missing turns (one failed append) must not hide them.
            for (Map<String, Object> turn : conv.apiMessages) {
                String content = String.valueOf(turn.get("content"));
                boolean user = "user".equals(String.valueOf(turn.get("role")));
                appendLoadedEntry(conv, content, !user);
            }
            conversations.add(conv);
            reconcileFromJournalAsync(conv);
            return conv;
        } catch (Exception e) {
            LOG.warn("Could not load conversation {}", file, e);
            return null;
        }
    }

    /**
     * Rebuilds {@code conv}'s display entries from its journal chronologies, asynchronously: the
     * store reads run on the worker; the swap runs on FX and only when the journal covers at least
     * the JSON-derived history and nothing changed the conversation in the meantime (no in-flight
     * send, no new turns, not deleted).
     */
    private void reconcileFromJournalAsync(Conversation conv) {
        if (conv.journalAnchor == null) {
            return;
        }
        int entriesAtLoad = conv.entries.size();
        worker.submit(() -> {
            Optional<List<JournalStore.TurnRecord>> turns;
            try {
                turns = journalStore.load(conv.journalAnchor);
            } catch (RuntimeException e) {
                LOG.warn("Journal rebuild failed for conversation {}; keeping JSON turns", conv.id, e);
                return;
            }
            if (turns.isEmpty() || turns.get().size() < entriesAtLoad) {
                if (turns.isPresent()) {
                    LOG.warn("Journal {} covers {} turns but JSON has {}; keeping JSON display",
                            conv.journalAnchor.idString(), turns.get().size(), entriesAtLoad);
                }
                return;
            }
            List<JournalStore.TurnRecord> records = turns.get();
            Platform.runLater(() -> {
                if (!conversations.contains(conv) || conv.busy
                        || conv.entries.size() != entriesAtLoad) {
                    return;   // deleted, mid-send, or already grown — the live state wins
                }
                conv.entries.clear();
                conv.markdown.setLength(0);
                for (JournalStore.TurnRecord turn : records) {
                    appendLoadedEntry(conv, turn.markdown(), turn.assistantAuthored());
                }
                if (conv == active) {
                    refreshTranscript();
                }
                LOG.info("Rebuilt conversation '{}' from journal {} ({} turns)",
                        conv.name, conv.journalAnchor.idString(), records.size());
            });
        });
    }

    /** Appends one restored turn to the conversation's entries and Save markdown. */
    private void appendLoadedEntry(Conversation conv, String content, boolean assistant) {
        if (assistant) {
            conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, content, true));
            conv.markdown.append("**Komet Assistant:** ").append(content).append("\n\n");
        } else {
            conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.USER, content, false));
            conv.markdown.append("**You:** ").append(content).append("\n\n");
        }
    }

    private void deleteConversationFile(Conversation conv) {
        Path dir = conv.homeDir != null ? conv.homeDir : conversationsDir();
        if (dir == null) {
            return;
        }
        try {
            // Nothing is ever deleted (KEC principle): the payload and its
            // index record stay for history/time travel; the conversation
            // just leaves visibility.
            KometPreferences home =
                    conv.homeNode != null ? conv.homeNode : preferences();
            home.node(INDEX_NODE).node(conv.id).put(KEY_HIDDEN, "true");
        } catch (Exception e) {
            LOG.warn("Could not hide conversation {}", conv.id, e);
        }
    }

    /*******************************************************************************
     *  Transcript rendering (FX thread)                                           *
     ******************************************************************************/

    private void renderUser(Conversation conv, String text) {
        // The exchange begins at the turn this entry is about to occupy (the current end).
        conv.turnStartTurn = conv.entries.size();
        MarkdownRichText.Entry entry =
                new MarkdownRichText.Entry(MarkdownRichText.Role.USER, text, false);
        conv.entries.add(entry);
        conv.markdown.append("**You:** ").append(text).append("\n\n");
        if (conv == active) {
            surface.appendTurn(entry);
            surface.scrollToTurn(conv.turnStartTurn);
            refreshMatchesIfFinding();
            refreshPreviewIfOpen();
        }
    }

    private void renderAssistant(Conversation conv, String markdown) {
        MarkdownRichText.Entry entry =
                new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, markdown, true);
        conv.entries.add(entry);
        conv.markdown.append("**Komet Assistant:** ").append(markdown).append("\n\n");
        if (conv == active) {
            surface.appendTurn(entry);
            // Keep the exchange's first block (the user's question) at the top, so the reply reads
            // top-down from there rather than the view snapping to the very end.
            surface.scrollToTurn(conv.turnStartTurn);
            refreshMatchesIfFinding();
            refreshPreviewIfOpen();
        }
    }

    private void saveTranscript() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save conversation");
        chooser.setInitialFileName("komet-assistant-chat.md");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown", "*.md"));
        File file = chooser.showSaveDialog(ownerWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), transcriptMarkdown.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ERROR,
                    "Could not save: " + e.getMessage(), false));
            refreshTranscript();
        }
    }

    private Window ownerWindow() {
        BorderPane pane = fxObject();
        return pane.getScene() == null ? null : pane.getScene().getWindow();
    }

    /*******************************************************************************
     *  API key (shared per-user preferences)                                      *
     ******************************************************************************/

    private static KometPreferences userPreferences() {
        return PreferencesService.userPreferences();
    }

    private static boolean hasApiKey() {
        return !apiKey().isBlank();
    }

    private static String apiKey() {
        return userPreferences().get(PREF_API_KEY, "");
    }

    /**
     * Prompts for and stores the Anthropic API key in shared per-user preferences.
     *
     * @return the saved key, or {@code null} if the user cancelled or cleared it
     */
    private String promptForApiKey() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Anthropic API key");
        dialog.setHeaderText("""
                Enter your Anthropic API key.
                It is stored in your per-user Komet preferences on this machine,
                never in the knowledge base.""");
        ButtonType saveType = new ButtonType("Save", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        PasswordField field = new PasswordField();
        field.setPromptText("sk-ant-…");
        field.setText(apiKey());
        field.setPrefColumnCount(36);
        dialog.getDialogPane().setContent(field);
        Platform.runLater(field::requestFocus);
        dialog.setResultConverter(bt -> bt == saveType ? field.getText() : null);

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() == null) {
            return null;
        }
        String key = result.get().trim();
        userPreferences().put(PREF_API_KEY, key);
        try {
            userPreferences().sync();
        } catch (BackingStoreException e) {
            // Non-fatal: the key is still set for this session.
        }
        return key.isBlank() ? null : key;
    }

    /*******************************************************************************
     *  System prompt (ike-issues#1039): fixed tool-contract core + editable        *
     *  instruction layer, persisted as a card-preferences-named payload file       *
     ******************************************************************************/

    /** Loads a bundled prompt resource, falling back for a broken packaging. */
    private static String loadPromptResource(String name, String fallback) {
        try (InputStream in = ClaudeCard.class.getResourceAsStream(name)) {
            if (in == null) {
                return fallback;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load prompt resource " + name, e);
        }
    }

    /** The bundled default instruction layer (persona, how to work, style). */
    private static String defaultInstructions() {
        return loadPromptResource("system-prompt-instructions.md", "");
    }

    /**
     * The active instruction layer, in attachment order (ike-issues#1044): the selected titled
     * instruction set's body when one is selected and resolvable across the journal's
     * Instruction Editor tiles; else this card's edited override when its preferences entry
     * names a readable payload file; else the bundled default. Every failure degrades down the
     * chain — a broken selection or override must never silence the assistant.
     */
    private String instructionsLayer() {
        Optional<network.ike.komet.claude.instructions.InstructionSets.Frontmatter> titled =
                selectedTitledSet();
        if (titled.isPresent()) {
            return titled.get().body();
        }
        try {
            String name = preferences().get(PREF_SYSTEM_INSTRUCTIONS_FILE, "");
            if (name.isBlank()) {
                return defaultInstructions();
            }
            Optional<Path> dir = preferences().directory();
            if (dir.isEmpty() || !Files.isRegularFile(dir.get().resolve(name))) {
                return defaultInstructions();
            }
            return Files.readString(dir.get().resolve(name), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read the system-instructions override; using the default", e);
            return defaultInstructions();
        }
    }

    /** The journal's Instruction Editor tiles, discovered by their card-type marker. */
    private List<KometPreferences> instructionEditorTiles() {
        List<KometPreferences> tiles = new ArrayList<>();
        try {
            KometPreferences parent = preferences().parent();
            if (parent == null) {
                return tiles;
            }
            for (KometPreferences child : parent.children()) {
                if (network.ike.komet.claude.instructions.InstructionEditorCard.CARD_TYPE_VALUE
                        .equals(child.get(
                                network.ike.komet.claude.instructions.InstructionEditorCard.CARD_TYPE_KEY,
                                ""))) {
                    tiles.add(child);
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate instruction-editor tiles", e);
        }
        return tiles;
    }

    /** The selected titled set's parsed document, resolved across editor tiles; empty when none. */
    private Optional<network.ike.komet.claude.instructions.InstructionSets.Frontmatter> selectedTitledSet() {
        String id = preferences().get(PREF_SYSTEM_PROMPT_SET, "");
        if (id.isBlank()) {
            return Optional.empty();
        }
        for (KometPreferences tile : instructionEditorTiles()) {
            network.ike.komet.claude.instructions.InstructionSets sets =
                    new network.ike.komet.claude.instructions.InstructionSets(tile);
            Optional<network.ike.komet.claude.instructions.InstructionSets.InstructionSet> match =
                    sets.byId(id);
            if (match.isPresent()) {
                return sets.read(match.get());
            }
        }
        LOG.warn("Selected system-prompt set {} not found in any Instruction Editor tile", id);
        return Optional.empty();
    }

    /** The selected titled set's display name for the settings summary, if resolvable. */
    private Optional<String> selectedTitledSetName() {
        String id = preferences().get(PREF_SYSTEM_PROMPT_SET, "");
        if (id.isBlank()) {
            return Optional.empty();
        }
        for (KometPreferences tile : instructionEditorTiles()) {
            Optional<network.ike.komet.claude.instructions.InstructionSets.InstructionSet> match =
                    new network.ike.komet.claude.instructions.InstructionSets(tile).byId(id);
            if (match.isPresent()) {
                return Optional.of(match.get().name());
            }
        }
        return Optional.of("Missing set");
    }

    /**
     * Assembles the system prompt: the editable instruction layer first, the fixed tool
     * contract last — the grounding rule and rendering grammars keep the recency position, and
     * no edit can sever them. Package-visible for the store-free assembly test.
     *
     * @param instructions the instruction layer (may be blank)
     * @param core         the tool-contract core (may be blank)
     * @return the assembled prompt, never {@code null}
     */
    static String assembleSystemPrompt(String instructions, String core) {
        String editable = instructions == null ? "" : instructions.strip();
        String contract = core == null ? "" : core.strip();
        if (editable.isEmpty()) {
            return contract;
        }
        if (contract.isEmpty()) {
            return editable;
        }
        return editable + "\n\n" + contract;
    }

    /** Recomputes {@link #systemPrompt} from the current layers; the next send uses it. */
    private void refreshSystemPrompt() {
        this.systemPrompt = assembleSystemPrompt(instructionsLayer(), promptCore);
    }

    /**
     * Persists an edited instruction layer: the payload file in this card's preferences
     * directory, named by the preferences entry (the registration — no discovery by hardcoded
     * magic), so it travels with the preferences git sync.
     */
    private void saveInstructionsOverride(String text) {
        try {
            Optional<Path> dir = preferences().directory();
            if (dir.isEmpty()) {
                LOG.warn("No preferences directory; the system-instructions edit is session-only");
            } else {
                Files.createDirectories(dir.get());
                Files.writeString(dir.get().resolve(SYSTEM_INSTRUCTIONS_FILE), text,
                        StandardCharsets.UTF_8);
                preferences().put(PREF_SYSTEM_INSTRUCTIONS_FILE, SYSTEM_INSTRUCTIONS_FILE);
                preferences().sync();
            }
        } catch (IOException | BackingStoreException e) {
            LOG.warn("Could not save the system-instructions override", e);
        }
        refreshSystemPrompt();
    }

    /** Removes the instruction-layer override: entry and payload; the default is back. */
    private void clearInstructionsOverride() {
        try {
            preferences().remove(PREF_SYSTEM_INSTRUCTIONS_FILE);
            preferences().sync();
            Optional<Path> dir = preferences().directory();
            if (dir.isPresent()) {
                Files.deleteIfExists(dir.get().resolve(SYSTEM_INSTRUCTIONS_FILE));
            }
        } catch (IOException | BackingStoreException e) {
            LOG.warn("Could not clear the system-instructions override", e);
        }
        refreshSystemPrompt();
    }

    /*******************************************************************************
     *  Lifecycle                                                                  *
     ******************************************************************************/

    @Override
    public void knowledgeLayoutUnbind() {
        super.knowledgeLayoutUnbind();
        worker.shutdownNow();
        if (statusTimer != null) {
            statusTimer.stop();
        }
        // Collapse the document area's full-surface overlay if promoted, and tear the area down.
        if (documentArea != null) {
            documentArea.knowledgeLayoutUnbind();
        }
    }

    /*******************************************************************************
     *  Factory                                                                    *
     ******************************************************************************/

    /**
     * ServiceLoader provider contributing {@link ClaudeCard} to the Journal workspace. Registered via
     * {@code provides KlCardProvider with ClaudeCard.Factory} in {@code module-info}.
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
                    KlPreferencesFactory.create(windowPreferences, ClaudeCard.class);
            ClaudeCard card = new CardFactory().create(cardPreferencesFactory);
            card.setJournalTopic(journalTopic);
            return card;
        }

        @Override
        public AbstractHostCard restoreCard(KometPreferences windowPreferences) {
            KometPreferences cardNode = windowPreferences.node(ClaudeCard.class.getSimpleName());
            ClaudeCard card = new CardFactory().restore(cardNode);
            card.revert();
            return card;
        }
    }

    /** Blueprint factory used internally to build the card shell (the {@code KlArea.Factory} the base needs). */
    private static final class CardFactory implements CardBlueprint.Factory<ClaudeCard> {

        @Override
        public ClaudeCard restore(KometPreferences preferences) {
            return new ClaudeCard(preferences);
        }

        @Override
        public ClaudeCard create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            ClaudeCard card = new ClaudeCard(preferencesFactory, this);
            card.setAreaLayout(areaGridSettings.with(this.getClass()));
            return card;
        }
    }
}
