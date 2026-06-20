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

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
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
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
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
    private TextField input;
    private Button sendButton;
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
        toolBar.getChildren().addAll(toggleRail, fontDown, fontUp, saveButton, keyButton);
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

        input = new TextField();
        input.setPromptText("Ask about the concepts in your open knowledge base…");
        input.setOnAction(e -> onSend());
        HBox.setHgrow(input, Priority.ALWAYS);
        sendButton = new Button("Send");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> onSend());
        HBox inputBar = new HBox(6, input, sendButton);
        inputBar.setPadding(new Insets(6));

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

        BorderPane content = new BorderPane();
        content.setCenter(split);
        content.setBottom(inputBar);
        content.setPrefSize(900, 680);
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
        String text = input.getText() == null ? "" : input.getText().trim();
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

        renderUser(conv, text);
        if (!conv.named) {
            conv.name = text.length() > 40 ? text.substring(0, 40).trim() + "…" : text;
            conv.named = true;
        }
        input.clear();
        conv.busy = true;
        conversationList.refresh();
        updateInputState();

        String model = userPreferences().get(PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS);
        List<Map<String, Object>> history = List.copyOf(conv.apiMessages);

        worker.submit(() -> {
            String reply;
            boolean error = false;
            try {
                reply = client.ask(systemPrompt, tools, history, text);
            } catch (Throwable t) {
                // Catch Throwable, not just RuntimeException: a non-runtime failure in the ask path (e.g. a
                // class-init / ServiceConfigurationError) must still clear busy and surface in the transcript.
                LOG.error("Claude request failed", t);
                Throwable root = t;
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                reply = (root != t) ? msg + "  (cause: " + root + ")" : msg;
                error = true;
            }
            String finalReply = reply;
            boolean finalError = error;
            Platform.runLater(() -> {
                renderAssistant(conv, finalReply, finalError);
                if (!finalError) {
                    conv.apiMessages.add(Map.of("role", "user", "content", text));
                    conv.apiMessages.add(Map.of("role", "assistant", "content", finalReply));
                    saveConversation(conv);
                }
                conv.busy = false;
                conversationList.refresh();
                if (conv == active) {
                    updateInputState();
                    input.requestFocus();
                }
            });
        });
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

    private void renderAssistant(Conversation conv, String markdown, boolean error) {
        if (error) {
            String text = markdown == null ? "Unknown error" : markdown;
            conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ERROR, text, false));
            conv.markdown.append("**Error:** ").append(text).append("\n\n");
        } else {
            conv.entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, markdown, true));
            conv.markdown.append("**Komet Assistant:** ").append(markdown).append("\n\n");
        }
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
