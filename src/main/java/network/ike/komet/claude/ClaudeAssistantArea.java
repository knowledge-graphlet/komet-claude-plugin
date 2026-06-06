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
import dev.ikm.komet.layout.area.KlToolArea;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.BackingStoreException;

/**
 * The Claude Assistant as a {@link KlToolArea}: a chat transcript over the open knowledge
 * base, summoned as a self-contained tool window in the Journal workspace.
 *
 * <p>The area is <em>outbound-only</em>. On each send it runs the Anthropic tool-use loop
 * ({@link AnthropicClient#ask}) on a background thread; Claude's read-only {@link GraphTools}
 * execute in-process against the {@linkplain #setToolViewProperties(ViewProperties) injected}
 * journal view, so answers are grounded in exactly the coordinate the user sees. The reply
 * (Markdown) is rendered into a {@link RichTextArea} on the FX thread. The Anthropic API key
 * lives in per-OS-user Komet preferences — never in the knowledge base.
 *
 * <p>This is the next-generation replacement for the legacy {@code KometNodeFactory} panel:
 * it is contributed via {@link Factory} as a {@code KlToolArea.Factory} / {@code KlArea.Factory}
 * service and rendered inside the modern Journal workspace rather than the classic tab UI.
 */
public final class ClaudeAssistantArea extends SupplementalAreaBlueprint implements KlToolArea<BorderPane> {

    /** Menu label and window title for the assistant. */
    static final String TOOL_NAME = "Claude Assistant";

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(ClaudeAssistantArea.class);

    /** Per-user preference keys (stored under {@link PreferencesService#userPreferences()}). */
    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    private static final String PREF_MODEL = "network.ike.komet.claude.model";
    private static final String PREF_FONT_SIZE = "network.ike.komet.claude.fontSize";
    private static final String PREF_RAIL_VISIBLE = "network.ike.komet.claude.railVisible";
    private static final String PREF_RAIL_DIVIDER = "network.ike.komet.claude.railDivider";

    private static final int MAX_TOKENS = 8192;

    private final String systemPrompt;
    private final List<AnthropicTool> tools;
    private final ExecutorService worker =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "komet-claude-ask");
                t.setDaemon(true);
                return t;
            });
    /** Live ref to the active conversation's Save markdown (reassigned on switch). */
    private StringBuilder transcriptMarkdown;

    /** Journal view injected by the host before display; tools query against it. */
    private volatile ViewProperties toolViewProperties;
    /** Callback the host wires to close + remove this window. */
    private volatile Runnable onCloseRequest;

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
        final List<java.util.Map<String, Object>> apiMessages = new ArrayList<>();
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
    private record ConversationDto(String id, String name, List<java.util.Map<String, Object>> turns) {
    }

    /** Restore constructor (see {@link Factory#restore}). */
    public ClaudeAssistantArea(KometPreferences preferences) {
        super(preferences);
        this.systemPrompt = loadSystemPrompt();
        // Tools read the live view each call via the method reference, so they always
        // reflect the journal's current coordinate.
        this.tools = new GraphTools(this::viewCalculator).tools();
        buildUi();
    }

    /** Create constructor (see {@link Factory#create}). */
    public ClaudeAssistantArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        this.systemPrompt = loadSystemPrompt();
        this.tools = new GraphTools(this::viewCalculator).tools();
        buildUi();
    }

    // ---- KlToolArea injection points ---------------------------------------

    @Override
    public void setToolViewProperties(ViewProperties viewProperties) {
        this.toolViewProperties = viewProperties;
    }

    @Override
    public void setOnCloseRequest(Runnable onCloseRequest) {
        this.onCloseRequest = onCloseRequest;
    }

    /**
     * Resolves the view calculator the tools should query: the injected journal view if
     * present, otherwise the knowledge-layout context view as a fallback.
     */
    private ViewCalculator viewCalculator() {
        ViewProperties vp = this.toolViewProperties;
        return vp != null ? vp.calculator() : calculatorForContext();
    }

    // ---- UI construction (into the supplemental area's BorderPane) ----------

    private void buildUi() {
        BorderPane pane = fxObject();
        baseFontSize = readFontSizePref();
        // Solid background + Anthropic-coral frame so the tool window reads as a
        // branded panel rather than a transparent border.
        pane.setStyle("-fx-background-color: -fx-background; -fx-border-color: #d97757; -fx-border-width: 1.5;"
                + " -fx-background-radius: 10; -fx-border-radius: 10;");
        // Clip to a rounded rect so every corner (header, input) is cleanly rounded
        // like the other journal windows, which the workspace frames for us.
        javafx.scene.shape.Rectangle paneClip = new javafx.scene.shape.Rectangle();
        paneClip.setArcWidth(20);
        paneClip.setArcHeight(20);
        paneClip.widthProperty().bind(pane.widthProperty());
        paneClip.heightProperty().bind(pane.heightProperty());
        pane.setClip(paneClip);
        pane.setPrefSize(900, 700);  // open at a size consistent with other journal windows

        transcript = new RichTextArea();
        transcript.setEditable(false);
        transcript.setWrapText(true);

        javafx.scene.control.Label title = new javafx.scene.control.Label(TOOL_NAME);
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: white; -fx-font-size: 13px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button fontDown = new Button("A−");
        fontDown.setTooltip(new javafx.scene.control.Tooltip("Smaller text"));
        fontDown.setOnAction(e -> adjustFont(-1));
        Button fontUp = new Button("A+");
        fontUp.setTooltip(new javafx.scene.control.Tooltip("Larger text"));
        fontUp.setOnAction(e -> adjustFont(1));
        Button saveButton = new Button("Save…");
        saveButton.setOnAction(e -> saveTranscript());
        Button keyButton = new Button("API key…");
        keyButton.setOnAction(e -> promptForApiKey());
        Region closeIcon = new Region();
        closeIcon.getStyleClass().add("close-window");      // Komet's window-close X shape (kview.css)
        closeIcon.setStyle("-fx-background-color: white;");  // white to read on the coral bar
        Button closeButton = new Button();
        closeButton.setGraphic(closeIcon);
        closeButton.setOnAction(e -> requestClose());
        Button toggleRail = new Button("☰");
        toggleRail.setTooltip(new javafx.scene.control.Tooltip("Show/hide conversations"));
        toggleRail.setOnAction(e -> setRailVisible(!railVisible));
        HBox header = new HBox(6, toggleRail, title, fontDown, fontUp, spacer, saveButton, keyButton, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6));
        header.setStyle("-fx-background-color: #d97757; -fx-background-radius: 10 10 0 0;");  // Anthropic coral title bar
        String headerBtn = "-fx-background-color: rgba(255,255,255,0.20); -fx-text-fill: white;"
                + " -fx-background-radius: 4; -fx-font-weight: bold;";
        for (Button b : List.of(toggleRail, fontDown, fontUp, saveButton, keyButton)) {
            b.setStyle(headerBtn);
        }
        // Close reads as a window control (Komet's .close-window X), consistent with other windows.
        closeButton.setStyle("-fx-background-color: transparent; -fx-padding: 6 8;");

        input = new TextField();
        input.setPromptText("Ask about the concepts in your open knowledge base…");
        input.setOnAction(e -> onSend());
        HBox.setHgrow(input, Priority.ALWAYS);
        sendButton = new Button("Send");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> onSend());
        HBox inputBar = new HBox(6, input, sendButton);
        inputBar.setPadding(new Insets(6));

        // The blueprint puts a children GridPane in the center; this tool has no child
        // areas, so its chat transcript becomes the center content instead.
        javafx.scene.control.Label railTitle = new javafx.scene.control.Label("Conversations");
        railTitle.setStyle("-fx-font-weight: bold;");
        Region railSpacer = new Region();
        HBox.setHgrow(railSpacer, Priority.ALWAYS);
        Button newButton = new Button("+ New");
        newButton.setTooltip(new javafx.scene.control.Tooltip("Start a new conversation"));
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
        conversationList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            private final javafx.scene.control.ProgressIndicator spinner =
                    new javafx.scene.control.ProgressIndicator();
            {
                spinner.setPrefSize(14, 14);
                spinner.setMaxSize(14, 14);
                setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
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

        pane.setTop(header);
        pane.setCenter(split);
        pane.setBottom(inputBar);

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
        ViewCalculator vc;
        try {
            vc = viewCalculator();
        } catch (RuntimeException e) {
            // No view yet (e.g. the intro before the host injects one) — chips
            // fall back to bare identicons until a view is available.
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

    private void requestClose() {
        Runnable r = this.onCloseRequest;
        if (r != null) {
            r.run();
        }
    }

    // ---- Send / tool-use loop ----------------------------------------------

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
        List<java.util.Map<String, Object>> history = List.copyOf(conv.apiMessages);

        worker.submit(() -> {
            String reply;
            boolean error = false;
            try {
                reply = client.ask(systemPrompt, tools, history, text);
            } catch (Throwable t) {
                // Catch Throwable, not just RuntimeException: a non-runtime failure in the
                // ask path (e.g. a class-init / ServiceConfigurationError) must still clear
                // the busy state and surface in the transcript.
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
                    // Append this turn's clean user/assistant pair so the conversation continues.
                    conv.apiMessages.add(java.util.Map.of("role", "user", "content", text));
                    conv.apiMessages.add(java.util.Map.of("role", "assistant", "content", finalReply));
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
     * Reflects only the <em>active</em> conversation's in-flight state on the shared
     * input. Other conversations keep running in parallel and can be switched to or
     * added to freely — the rail spinner shows which ones are thinking.
     */
    private void updateInputState() {
        boolean busy = active != null && active.busy;
        input.setDisable(busy);
        sendButton.setDisable(busy);
        sendButton.setText(busy ? "Working…" : "Send");
    }

    // ---- Conversations -----------------------------------------------------

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
        javafx.scene.control.TextInputDialog dialog =
                new javafx.scene.control.TextInputDialog(active.name);
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

    // ---- Persistence (per-user folder under the KB datastore) --------------

    /** {@code <DATA_STORE_ROOT>/claude-conversations/<os-user>/}, created on demand; null if no datastore. */
    private static Path conversationsDir() {
        Optional<File> root = ServiceProperties.get(ServiceKeys.DATA_STORE_ROOT);
        if (root.isEmpty()) {
            return null;
        }
        String user = System.getProperty("user.name", "default").replaceAll("[^a-zA-Z0-9._-]", "_");
        Path dir = root.get().toPath().resolve("claude-conversations").resolve(user);
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            LOG.warn("Could not create conversations dir {}", dir, e);
            return null;
        }
    }

    /** Persists one conversation (skips empties); files are named by id. */
    private void saveConversation(Conversation conv) {
        if (conv == null || conv.apiMessages.isEmpty()) {
            return;
        }
        Path dir = conversationsDir();
        if (dir == null) {
            return;
        }
        try {
            String json = Json.stringify(
                    java.util.Map.of("id", conv.id, "name", conv.name, "turns", conv.apiMessages));
            Files.writeString(dir.resolve(conv.id + ".json"), json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            LOG.warn("Could not save conversation {}", conv.id, e);
        }
    }

    /** Loads this user's conversations for the open KB, most-recent first. */
    private void loadConversations() {
        Path dir = conversationsDir();
        if (dir == null) {
            return;
        }
        try (var paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".json"))
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
            for (java.util.Map<String, Object> turn : conv.apiMessages) {
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
            Files.deleteIfExists(dir.resolve(conv.id + ".json"));
        } catch (IOException e) {
            LOG.warn("Could not delete conversation {}", conv.id, e);
        }
    }

    // ---- Transcript rendering (FX thread) ----------------------------------

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
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save conversation");
        chooser.setInitialFileName("komet-assistant-chat.md");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md"));
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

    // ---- API key (per-user preferences) ------------------------------------

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
     * Prompts for and stores the Anthropic API key in per-user preferences.
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
        try (InputStream in = ClaudeAssistantArea.class.getResourceAsStream("system-prompt.md")) {
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

    // ---- AreaBlueprint / KlView lifecycle ----------------------------------

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        // Chat is ephemeral; nothing area-scoped to restore.
    }

    @Override
    protected void subAreaRevert() {
        // Nothing to revert.
    }

    @Override
    protected void subAreaSave() {
        // Nothing area-scoped to persist.
    }

    @Override
    public void knowledgeLayoutBind() {
        Platform.runLater(() -> this.lifecycleState.set(LifecycleState.BOUND));
    }

    @Override
    public void knowledgeLayoutUnbind() {
        worker.shutdownNow();
    }

    /**
     * ServiceLoader factory contributing {@link ClaudeAssistantArea} as a summonable
     * Journal tool. Registered via {@code provides KlToolArea.Factory} (and
     * {@code KlArea.Factory}) in {@code module-info}.
     */
    public static final class Factory implements KlToolArea.Factory<BorderPane, ClaudeAssistantArea> {

        /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
        public Factory() {
            super();
        }

        @Override
        public String toolName() {
            return TOOL_NAME;
        }

        @Override
        public ClaudeAssistantArea restore(KometPreferences preferences) {
            return new ClaudeAssistantArea(preferences);
        }

        @Override
        public ClaudeAssistantArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            ClaudeAssistantArea area = new ClaudeAssistantArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
