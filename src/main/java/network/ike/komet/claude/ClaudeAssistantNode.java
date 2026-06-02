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

import dev.ikm.komet.framework.ExplorationNodeAbstract;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.terms.EntityFacade;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.tools.GraphTools;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.eclipse.collections.api.list.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.BackingStoreException;

/**
 * The Claude Assistant panel: a chat transcript over the open knowledge base.
 *
 * <p>The node is <em>outbound-only</em>. On each send it runs the Anthropic
 * tool-use loop ({@link AnthropicClient#ask}) on a background thread; Claude's
 * read-only {@link GraphTools} execute in-process against this window's live
 * {@link #viewCalculator() ViewCalculator}, so answers are grounded in exactly
 * the view the user sees. The reply (Markdown) is rendered into a
 * {@link RichTextArea} on the FX thread. The Anthropic API key lives in
 * per-OS-user Komet preferences — never in the knowledge base.
 */
public final class ClaudeAssistantNode extends ExplorationNodeAbstract {

    protected static final String STYLE_ID = "claude-assistant-node";
    protected static final String TITLE = "Claude Assistant";

    /** Per-user preference keys (stored under {@link PreferencesService#userPreferences()}). */
    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    private static final String PREF_MODEL = "network.ike.komet.claude.model";

    private static final int MAX_TOKENS = 8192;

    private static final Color USER_COLOR = Color.web("#1a56db");
    private static final Color ASSISTANT_COLOR = Color.web("#b15c00");
    private static final Color ERROR_COLOR = Color.web("#b00020");

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

    private BorderPane root;
    private RichTextArea transcript;
    private TextField input;
    private Button sendButton;

    public ClaudeAssistantNode(ViewProperties viewProperties, KometPreferences nodePreferences) {
        super(viewProperties, nodePreferences);
        this.systemPrompt = loadSystemPrompt();
        // Tools read the live view each call via the method reference, so they
        // always reflect the user's current coordinate.
        this.tools = new GraphTools(this::viewCalculator).tools();
    }

    // ---- UI construction ---------------------------------------------------

    @Override
    public Node getNode() {
        if (root == null) {
            root = buildUi();
        }
        return root;
    }

    private BorderPane buildUi() {
        transcript = new RichTextArea();
        transcript.setEditable(false);
        transcript.setWrapText(true);

        Button clearButton = new Button("Clear");
        clearButton.setOnAction(e -> clearTranscript());
        Button saveButton = new Button("Save…");
        saveButton.setOnAction(e -> saveTranscript());
        Button keyButton = new Button("API key…");
        keyButton.setOnAction(e -> promptForApiKey());
        ToolBar toolBar = new ToolBar(clearButton, saveButton, keyButton);

        input = new TextField();
        input.setPromptText("Ask about the concepts in your open knowledge base…");
        input.setOnAction(e -> onSend());
        HBox.setHgrow(input, Priority.ALWAYS);
        sendButton = new Button("Send");
        sendButton.setDefaultButton(true);
        sendButton.setOnAction(e -> onSend());
        HBox inputBar = new HBox(6, input, sendButton);
        inputBar.setPadding(new Insets(6));

        BorderPane pane = new BorderPane();
        pane.setTop(toolBar);
        pane.setCenter(transcript);
        pane.setBottom(inputBar);

        appendLabel("Komet Assistant", ASSISTANT_COLOR);
        appendBody("Ask about the concepts in your open knowledge base. "
                + "I answer by running read-only queries against the active view — "
                + "I won't invent codes or relationships. "
                + (hasApiKey()
                        ? "Type a question below to begin."
                        : "Set your Anthropic API key (the \"API key…\" button) to begin."));
        return pane;
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
            } catch (RuntimeException e) {
                reply = e.getMessage() == null ? e.toString() : e.getMessage();
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
        appendLabel("You", USER_COLOR);
        appendBody(text);
        transcriptMarkdown.append("**You:** ").append(text).append("\n\n");
    }

    private void renderAssistant(String markdown, boolean error) {
        if (error) {
            appendLabel("Error", ERROR_COLOR);
            transcript.appendText((markdown == null ? "Unknown error" : markdown) + "\n\n",
                    StyleAttributeMap.builder().setFontSize(13).setItalic(true).setTextColor(ERROR_COLOR).build());
            transcriptMarkdown.append("**Error:** ").append(markdown).append("\n\n");
            return;
        }
        appendLabel("Komet Assistant", ASSISTANT_COLOR);
        MarkdownRichText.append(transcript, markdown);
        transcript.appendText("\n", StyleAttributeMap.EMPTY);
        transcriptMarkdown.append("**Komet Assistant:** ").append(markdown).append("\n\n");
    }

    private void appendLabel(String who, Color color) {
        transcript.appendText(who + "\n",
                StyleAttributeMap.builder().setBold(true).setFontSize(13).setTextColor(color).build());
    }

    private void appendBody(String text) {
        transcript.appendText(text + "\n\n", StyleAttributeMap.builder().setFontSize(13).build());
    }

    private void clearTranscript() {
        transcript.clear();
        transcriptMarkdown.setLength(0);
        appendLabel("Komet Assistant", ASSISTANT_COLOR);
        appendBody("Cleared. Ask a new question below.");
    }

    private void saveTranscript() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save conversation");
        chooser.setInitialFileName("komet-assistant-chat.md");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("Markdown", "*.md"));
        File file = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            Files.writeString(file.toPath(), transcriptMarkdown.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            appendLabel("Error", ERROR_COLOR);
            appendBody("Could not save: " + e.getMessage());
        }
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
        try (InputStream in = ClaudeAssistantNode.class.getResourceAsStream("system-prompt.md")) {
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

    // ---- KometNode / ExplorationNodeAbstract contract ----------------------

    @Override
    public String getDefaultTitle() {
        return TITLE;
    }

    @Override
    public void handleActivity(ImmutableList<EntityFacade> entities) {
        // The assistant is driven by the chat box, not the activity stream.
    }

    @Override
    public void revertAdditionalPreferences() {
        // Chat is ephemeral; model/key live in user preferences, not node prefs.
    }

    @Override
    protected void saveAdditionalPreferences() {
        // Nothing node-scoped to persist.
    }

    @Override
    public String getStyleId() {
        return STYLE_ID;
    }

    @Override
    public Node getMenuIconGraphic() {
        return new javafx.scene.control.Label("✦");
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    @Override
    public boolean canClose() {
        return true;
    }

    @Override
    public Class<ClaudeAssistantNodeFactory> factoryClass() {
        return ClaudeAssistantNodeFactory.class;
    }
}
