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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import javafx.scene.control.TextArea;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.komet.markdown.richtext.RichTextSearch;
import dev.ikm.tinkar.common.service.PrimitiveData;
import java.util.HashMap;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.anthropic.AskListener;
import javafx.util.Duration;
import network.ike.komet.claude.json.Json;
import network.ike.komet.claude.tools.GraphTools;
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

    private RichTextArea transcript;
    private TextArea input;
    private Button sendButton;
    private BorderPane content;
    private Label statusLabel;

    // Find-in-conversation state (🔍 / Cmd-Ctrl+F), over the transcript.
    private HBox findBar;
    private TextField findField;
    private Label findCount;
    private List<RichTextSearch.Match> matches = List.of();
    private int matchPos = -1;

    // Concept-drop tokens: a dropped Koncept inserts «name» at the caret; the token maps to its
    // component nid, resolved to the concept UUID on send so the transcript renders the real chip.
    private final Map<String, Integer> tokenToNid = new HashMap<>();
    private static final Pattern CONCEPT_TOKEN = Pattern.compile("«([^«»]*)»");
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

        Conversation(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** Serialized form of a {@link Conversation} (json4j). */
    private record ConversationDto(String id, String name, List<Map<String, Object>> turns) {
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
        for (Button b : List.of(toggleRail, fontDown, fontUp, saveButton, keyButton)) {
            b.getStyleClass().add("claude-card-toolbar-button");
        }
        toolBar.getChildren().addAll(coordinateButton, toggleRail, fontDown, fontUp, saveButton, keyButton);
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

    /** Builds the chat body (conversations rail | transcript, over the input bar) as the card content. */
    private void buildBody() {
        baseFontSize = readFontSizePref();

        transcript = new RichTextArea();
        transcript.setEditable(false);
        transcript.setWrapText(true);

        input = new TextArea();
        input.setPromptText("Ask about the concepts in your open knowledge base… (drop a concept to insert it)");
        input.setWrapText(true);
        input.setPrefRowCount(2);
        input.setPrefHeight(56);
        input.setMaxHeight(160);
        // Enter sends; Shift+Enter inserts a newline (the input is multi-line and accepts concept drops).
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                onSend();
                e.consume();
            }
        });
        installConceptDrop(input);
        HBox.setHgrow(input, Priority.ALWAYS);

        sendButton = new Button("Send");
        sendButton.setOnAction(e -> onSend());

        Button findButton = new Button("🔍");
        findButton.setTooltip(new Tooltip("Find in conversation (⌘F)"));
        findButton.setOnAction(e -> toggleFind());

        HBox inputBar = new HBox(6, findButton, input, sendButton);
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
        split = new SplitPane(transcript);
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

    /** Rebuilds the transcript's view-only model from the accumulated entries. */
    private void refreshTranscript() {
        if (transcript == null || entries == null) {
            return;
        }
        ViewCalculator vc;
        try {
            vc = viewCalculator();
        } catch (RuntimeException e) {
            // No usable view yet — chips fall back to bare identicons until one is available.
            vc = null;
        }
        transcript.setModel(new MarkdownRichText(vc, baseFontSize).toModel(entries));
        if (findBar != null && content != null && content.getTop() == findBar) {
            updateMatches();
        }
    }

    /*******************************************************************************
     *  Concept drop + Find                                                        *
     ******************************************************************************/

    /** The composed message: input text with each «name» concept token replaced by the concept's
     *  UUID, so the assistant grounds it and the transcript renders the chip inline. */
    private String composeMessage() {
        String raw = input.getText() == null ? "" : input.getText();
        Matcher m = CONCEPT_TOKEN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Integer nid = tokenToNid.get(m.group());
            String replacement = (nid != null) ? (" " + uuidToken(nid) + " ") : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    private static String uuidToken(int nid) {
        try {
            return PrimitiveData.publicId(nid).asUuidArray()[0].toString();
        } catch (RuntimeException e) {
            return "nid=" + nid;
        }
    }

    private void installConceptDrop(TextArea area) {
        // Filters run before the TextArea's own drag handling, so a concept drop inserts a token
        // instead of the dragboard's plain-text PublicId being typed in.
        area.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (hasConcept(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        area.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (!hasConcept(e.getDragboard())) {
                return;
            }
            OptionalInt nid = KometClipboard.conceptNid(e.getDragboard());
            if (nid.isEmpty()) {
                nid = KometClipboard.entityNidFrom(e.getDragboard());
            }
            if (nid.isPresent()) {
                insertConceptToken(nid.getAsInt());
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    private static boolean hasConcept(Dragboard dragboard) {
        for (DataFormat format : KometClipboard.CONCEPT_TYPES) {
            if (dragboard.hasContent(format)) {
                return true;
            }
        }
        return false;
    }

    private void insertConceptToken(int nid) {
        String token = "«" + conceptName(nid) + "»";
        tokenToNid.put(token, nid);
        int pos = input.getCaretPosition();
        input.insertText(pos, token);
        input.requestFocus();
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
        if (transcript != null) {
            transcript.clearSelection();
        }
        matches = List.of();
        matchPos = -1;
        if (input != null) {
            input.requestFocus();
        }
    }

    /** Re-searches the current transcript for the find text and selects the first match. */
    private void updateMatches() {
        if (transcript == null) {
            return;
        }
        matches = RichTextSearch.findAll(transcript.getModel(), findField.getText());
        if (matches.isEmpty()) {
            matchPos = -1;
            String query = findField.getText();
            findCount.setText(query == null || query.isEmpty() ? "" : "No results");
            transcript.clearSelection();
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
        RichTextSearch.Match match = matches.get(matchPos);
        StyledTextModel model = transcript.getModel();
        int len = model.getParagraphLength(match.paragraphIndex());
        TextPos anchor = TextPos.ofLeading(match.paragraphIndex(), Math.min(match.start(), len));
        TextPos caret = TextPos.ofLeading(match.paragraphIndex(), Math.min(match.end(), len));
        transcript.select(anchor, caret);
        findCount.setText((matchPos + 1) + " / " + matches.size());
    }

    /** Adjusts the transcript font size by {@code delta} px (clamped), persists it, and re-renders. */
    private void adjustFont(double delta) {
        baseFontSize = Math.max(9, Math.min(28, baseFontSize + delta));
        userPreferences().put(PREF_FONT_SIZE, Double.toString(baseFontSize));
        refreshTranscript();
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
            conv.name = text.length() > 40 ? text.substring(0, 40).trim() + "…" : text;
            conv.named = true;
        }
        input.clear();
        tokenToNid.clear();
        dispatch(conv, text, key, currentModel());
    }

    /**
     * Re-sends the active conversation's held failed request (the Retry button). The user's message is
     * already in the transcript from the original send, so this only re-dispatches — nothing is added.
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
            refreshTranscript();
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
            String json = Json.stringify(Map.of("id", conv.id, "name", conv.name, "turns", conv.apiMessages));
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
            if (dto.turns() != null) {
                conv.apiMessages.addAll(dto.turns());
            }
            for (Map<String, Object> turn : conv.apiMessages) {
                String content = String.valueOf(turn.get("content"));
                if ("user".equals(String.valueOf(turn.get("role")))) {
                    conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.USER, content, false));
                    conv.markdown.append("**You:** ").append(content).append("\n\n");
                } else {
                    conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, content, true));
                    conv.markdown.append("**Komet Assistant:** ").append(content).append("\n\n");
                }
            }
            conversations.add(conv);
        } catch (Exception e) {
            LOG.warn("Could not load conversation {}", file, e);
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
        conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.USER, text, false));
        conv.markdown.append("**You:** ").append(text).append("\n\n");
        if (conv == active) {
            refreshTranscript();
        }
    }

    private void renderAssistant(Conversation conv, String markdown) {
        conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, markdown, true));
        conv.markdown.append("**Komet Assistant:** ").append(markdown).append("\n\n");
        if (conv == active) {
            refreshTranscript();
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
