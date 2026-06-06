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
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.tools.GraphTools;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    private static final int MAX_TOKENS = 8192;

    private final String systemPrompt;
    private final List<AnthropicTool> tools;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "komet-claude-ask");
                t.setDaemon(true);
                return t;
            });
    /** Raw Markdown of the whole conversation, for Save / copy. */
    private final StringBuilder transcriptMarkdown = new StringBuilder();

    /** Journal view injected by the host before display; tools query against it. */
    private volatile ViewProperties toolViewProperties;
    /** Callback the host wires to close + remove this window. */
    private volatile Runnable onCloseRequest;

    private RichTextArea transcript;
    private TextField input;
    private Button sendButton;
    /** The conversation, rebuilt into the transcript's view-only model each turn. */
    private final List<MarkdownRichText.Entry> entries = new ArrayList<>();
    /** Transcript base font size (px); adjustable via the A−/A+ buttons, persisted. */
    private double baseFontSize = MarkdownRichText.DEFAULT_BASE;

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

        transcript = new RichTextArea();
        transcript.setEditable(false);
        transcript.setWrapText(true);

        javafx.scene.control.Label title = new javafx.scene.control.Label(TOOL_NAME);
        title.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button fontDown = new Button("A−");
        fontDown.setTooltip(new javafx.scene.control.Tooltip("Smaller text"));
        fontDown.setOnAction(e -> adjustFont(-1));
        Button fontUp = new Button("A+");
        fontUp.setTooltip(new javafx.scene.control.Tooltip("Larger text"));
        fontUp.setOnAction(e -> adjustFont(1));
        Button clearButton = new Button("Clear");
        clearButton.setOnAction(e -> clearTranscript());
        Button saveButton = new Button("Save…");
        saveButton.setOnAction(e -> saveTranscript());
        Button keyButton = new Button("API key…");
        keyButton.setOnAction(e -> promptForApiKey());
        Button closeButton = new Button("✕");
        closeButton.setOnAction(e -> requestClose());
        HBox header = new HBox(6, title, fontDown, fontUp, spacer, clearButton, saveButton, keyButton, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6));

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
        pane.setTop(header);
        pane.setCenter(transcript);
        pane.setBottom(inputBar);

        entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT,
                "Ask about the concepts in your open knowledge base. "
                        + "I answer by running read-only queries against the active view — "
                        + "I won't invent codes or relationships. "
                        + (hasApiKey()
                                ? "Type a question below to begin."
                                : "Set your Anthropic API key (the \"API key…\" button) to begin."),
                false));
        refreshTranscript();
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

        renderUser(text);
        input.clear();
        setBusy(true);

        String model = userPreferences().get(PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
        AnthropicClient client = new AnthropicClient(key, model, MAX_TOKENS);

        worker.submit(() -> {
            String reply;
            boolean error = false;
            try {
                reply = client.ask(systemPrompt, tools, text);
            } catch (Throwable t) {
                // Catch Throwable, not just RuntimeException: a non-runtime failure in the
                // ask path (e.g. a class-init / ServiceConfigurationError) must still clear
                // the busy state and surface in the transcript, never leave the panel hung
                // on "Working…".
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
                renderAssistant(finalReply, finalError);
                setBusy(false);
                input.requestFocus();
            });
        });
    }

    private void setBusy(boolean busy) {
        input.setDisable(busy);
        sendButton.setDisable(busy);
        sendButton.setText(busy ? "Working…" : "Send");
    }

    // ---- Transcript rendering (FX thread) ----------------------------------

    private void renderUser(String text) {
        entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.USER, text, false));
        transcriptMarkdown.append("**You:** ").append(text).append("\n\n");
        refreshTranscript();
    }

    private void renderAssistant(String markdown, boolean error) {
        if (error) {
            String text = markdown == null ? "Unknown error" : markdown;
            entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ERROR, text, false));
            transcriptMarkdown.append("**Error:** ").append(text).append("\n\n");
        } else {
            entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT, markdown, true));
            transcriptMarkdown.append("**Komet Assistant:** ").append(markdown).append("\n\n");
        }
        refreshTranscript();
    }

    private void clearTranscript() {
        entries.clear();
        transcriptMarkdown.setLength(0);
        entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ASSISTANT,
                "Cleared. Ask a new question below.", false));
        refreshTranscript();
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
