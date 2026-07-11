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

import dev.ikm.komet.framework.view.ObservableView;
import dev.ikm.komet.framework.view.ObservableViewWithOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.controls.FilterOptionsPopup;
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
import network.ike.komet.claude.ui.ComposeChips;
import network.ike.komet.claude.ui.KonceptTokens;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.BackingStoreException;

/**
 * The Claude Assistant as a first-class {@link AbstractHostCard}: a chat over the open knowledge base,
 * contributed to the Journal workspace via {@link Factory} (a {@code KlCardProvider}). This is the
 * card-native successor to the legacy {@code ClaudeAssistantArea} tool — it owns its chrome, coordinate
 * context, lifecycle, and storage directly rather than being hosted inside a generic shell.
 *
 * <p><b>One chrome.</b> The card's own header (themed Anthropic coral via {@code claude-card.css}) carries the
 * title and the assistant controls (conversations toggle, font ±, Save, API key); the close lives in the base
 * chrome. There is no doubled tab.
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
    static final String CARD_NAME = "Claude Assistant";

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ClaudeCard.class);

    /** Per-user preference keys (shared across cards, stored under {@link PreferencesService#userPreferences()}). */
    // PREF_API_KEY / PREF_MODEL are public so the headless commit narrator reads the same per-user config
    // (the key names, not the value, are shared).
    public static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    public static final String PREF_MODEL = "network.ike.komet.claude.model";
    private static final String PREF_FONT_SIZE = "network.ike.komet.claude.fontSize";
    private static final String PREF_RAIL_VISIBLE = "network.ike.komet.claude.railVisible";
    private static final String PREF_RAIL_DIVIDER = "network.ike.komet.claude.railDivider";

    private static final int MAX_TOKENS = 8192;

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

    /** One named conversation: its display entries, clean API turns, and Save markdown. */
    private static final class Conversation {
        final String id;
        String name;
        boolean named;
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
                                   String journalAnchor) {
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
        this.systemPrompt = loadSystemPrompt();
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
        Button fontDown = new Button("A−");
        fontDown.setTooltip(new Tooltip("Smaller text"));
        fontDown.setOnAction(e -> adjustFont(-1));
        Button fontUp = new Button("A+");
        fontUp.setTooltip(new Tooltip("Larger text"));
        fontUp.setOnAction(e -> adjustFont(1));
        Button saveButton = new Button("Save…");
        saveButton.setOnAction(e -> saveTranscript());
        Button keyButton = new Button("API key…");
        keyButton.setOnAction(e -> promptForApiKey());

        // Paged document print (#838/#839) is reached through the document area's hover-revealed
        // "expand to full surface" corner icon (KlExpandable); Print and print-settings live in that
        // full-surface view — no top-toolbar button.
        for (Button b : List.of(toggleRail, fontDown, fontUp, saveButton, keyButton)) {
            b.getStyleClass().add("claude-card-toolbar-button");
        }
        toolBar.getChildren().addAll(coordinateButton, toggleRail, fontDown, fontUp,
                saveButton, keyButton);
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
    }

    @Override
    protected void subCardRestore() {
        // The document area restores its own print settings when created in buildBody (#839).
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
        input.setWrapText(true);
        input.setPrefHeight(56);
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
        installConceptDrop(input);

        // RichTextArea has no prompt text; overlay a hint on the empty model instead.
        composeHint = new Label(
                "Ask about the concepts in your open knowledge base… (drop a concept to add it as a chip)");
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
        HBox statusBar = new HBox(8, statusLabel, statusSpacer, retryButton);
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
        conversationList = new ListView<>(conversations);
        conversationList.setPrefWidth(190);
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
            {
                spinner.setPrefSize(14, 14);
                spinner.setMaxSize(14, 14);
                setContentDisplay(ContentDisplay.RIGHT);
            }
            @Override
            protected void updateItem(Conversation c, boolean empty) {
                super.updateItem(c, empty);
                setText(empty || c == null ? null : c.name);
                setGraphic(!empty && c != null && c.busy ? spinner : null);
            }
        });
        MenuItem renameItem = new MenuItem("Rename…");
        renameItem.setOnAction(e -> renameActive());
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteActive());
        conversationList.setContextMenu(new ContextMenu(renameItem, deleteItem));

        conversationRail = new VBox(railHeader, conversationList);
        VBox.setVgrow(conversationList, Priority.ALWAYS);
        conversationRail.setMinWidth(140);

        railVisible = readRailVisiblePref();
        railDivider = readRailDividerPref();
        split = new SplitPane(documentArea.fxObject());
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

        loadConversations();
        if (conversations.isEmpty()) {
            newConversation();
        } else {
            activate(conversations.get(0));
        }
    }

    /** Rebuilds the document surface from the accumulated entries (chips re-resolve on the
     *  current coordinate; font-size changes re-render). */
    private void refreshTranscript() {
        if (surface == null || entries == null) {
            return;
        }
        // No usable view yet → chips fall back to bare identicons until one is available.
        surface.setBlockFactory(new BlockFactory(safeViewCalculator(), baseFontSize));
        surface.setTurns(entries);
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

    private void installConceptDrop(RichTextArea area) {
        // Filters run before the RichTextArea's own drag handling, so a concept drop lands as a
        // live chip instead of the dragboard's plain-text PublicId being typed in.
        area.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (hasConcept(e.getDragboard())) {
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
            if (!hasConcept(e.getDragboard())) {
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
                insertConceptsAt(at, nids);
                e.setDropCompleted(true);
            }
            dropHint = null;
            e.consume();
        });
    }

    private static boolean hasConcept(Dragboard dragboard) {
        if (dragboard.hasContent(KometClipboard.KOMET_CONCEPT_LIST)) {
            return true;
        }
        for (DataFormat format : KometClipboard.CONCEPT_TYPES) {
            if (dragboard.hasContent(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inserts one or more dropped concepts at {@code at} as live chips (#789). Several concepts are
     * joined as a natural-language list with the Oxford comma — {@code A}; {@code A and B};
     * {@code A, B, and C} — and a trailing space so the user keeps typing. Each chip renders via the
     * shared transcript pill and serializes on send as its id-bearing {@code k:} token.
     *
     * @param at   where the chips land (the drop point); {@code null} falls back to the caret, then
     *             the document end
     * @param nids the dropped concepts, in order
     */
    private void insertConceptsAt(TextPos at, int[] nids) {
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
                () -> ComposeChips.chip(pid, safeViewCalculator(), baseFontSize));
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
                return vc.getFullyQualifiedNameText(nid)
                        .orElseGet(() -> vc.getPreferredDescriptionTextWithFallbackOrNid(nid));
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
                    conv.outcome = "✕ " + finalError;
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
        } else if (active != null && active.outcome != null) {
            statusLabel.setText(active.outcome);
            statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: "
                    + (active.outcomeFailed ? "#c62828" : "#2e7d32") + ";");
            // The failure message (cause + status-page URL) can outrun the strip width; the tooltip keeps
            // the full text reachable since failures are not in the transcript.
            statusLabel.setTooltip(new Tooltip(active.outcome));
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
        }
    }

    private void setRetryVisible(boolean visible) {
        retryButton.setVisible(visible);
        retryButton.setManaged(visible);
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
    private void activate(Conversation conv) {
        active = conv;
        entries = conv.entries;
        transcriptMarkdown = conv.markdown;
        if (conversationList.getSelectionModel().getSelectedItem() != conv) {
            conversationList.getSelectionModel().select(conv);
        }
        refreshTranscript();
        updateInputState();
        updateStatusArea();
    }

    /** Creates a fresh conversation (with the intro) and makes it active. */
    private void newConversation() {
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
        Path dir = conversationsDir();
        if (dir == null) {
            return;
        }
        try {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", conv.id);
            dto.put("name", conv.name);
            dto.put("turns", conv.apiMessages);
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

    /** Loads this card's conversations, most-recent first. */
    private void loadConversations() {
        Path dir = conversationsDir();
        if (dir == null) {
            return;
        }
        try (var paths = Files.list(dir)) {
            paths.filter(p -> p.getFileName().toString().startsWith("conversation-")
                            && p.toString().endsWith(".json"))
                    .sorted(java.util.Comparator
                            .comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                    .forEach(this::loadConversation);
        } catch (IOException e) {
            LOG.warn("Could not list conversations dir {}", dir, e);
        }
    }

    private void loadConversation(Path file) {
        try {
            ConversationDto dto = Json.parse(Files.readString(file, StandardCharsets.UTF_8), ConversationDto.class);
            Conversation conv = new Conversation(dto.id(), dto.name());
            conv.named = true;
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
        } catch (Exception e) {
            LOG.warn("Could not load conversation {}", file, e);
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
        Path dir = conversationsDir();
        if (dir == null) {
            return;
        }
        try {
            Files.deleteIfExists(dir.resolve("conversation-" + conv.id + ".json"));
        } catch (IOException e) {
            LOG.warn("Could not delete conversation {}", conv.id, e);
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

    private static String loadSystemPrompt() {
        try (InputStream in = ClaudeCard.class.getResourceAsStream("system-prompt.md")) {
            if (in == null) {
                return "You are a read-only terminology assistant embedded in Komet. "
                        + "Always use the provided tools to resolve concepts, identifiers, and "
                        + "relationships; never answer from memory.";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load system prompt", e);
        }
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
