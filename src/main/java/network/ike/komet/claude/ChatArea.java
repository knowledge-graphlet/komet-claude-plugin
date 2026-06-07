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

import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.tools.GraphTools;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A slim, embeddable Claude chat area: a transcript plus an input row, sized to sit inside a
 * section grid alongside other areas. It is the lightweight counterpart to the full
 * {@code ClaudeAssistantArea} (no conversations rail, no header chrome, in-memory history only).
 *
 * <p>It reuses the plugin's {@link AnthropicClient} multi-turn tool-use loop, the read-only
 * {@link GraphTools} grounded in the live {@link ViewCalculator}, and {@link MarkdownRichText}
 * for rendering replies. The Anthropic API key and model come from the shared per-OS-user Komet
 * preferences (set once via the Claude Assistant).
 */
public final class ChatArea extends SupplementalAreaBlueprint {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChatArea.class);

    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    private static final String PREF_MODEL = "network.ike.komet.claude.model";
    private static final int MAX_TOKENS = 4096;

    private static final String SYSTEM_PROMPT = """
            You are a terminology assistant embedded in Komet, answering questions about the open
            knowledge base. Ground every factual claim by calling the read-only graph tools —
            never rely on memory for concept identity, parents, children, or axioms. Be concise.""";

    private final List<AnthropicTool> tools = new GraphTools(this::viewCalculator).tools();
    private final List<MarkdownRichText.Entry> entries = new ArrayList<>();
    private final List<Map<String, Object>> apiMessages = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "komet-chat-area");
        t.setDaemon(true);
        return t;
    });

    private RichTextArea transcript;
    private TextField input;
    private Button sendButton;

    /**
     * Restore constructor.
     *
     * @param preferences the preferences node backing this area
     */
    public ChatArea(KometPreferences preferences) {
        super(preferences);
        buildUi();
    }

    /**
     * Create constructor.
     *
     * @param preferencesFactory factory for this area's preferences node
     * @param areaFactory        the factory creating this area
     */
    public ChatArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        buildUi();
    }

    private ViewCalculator viewCalculator() {
        return calculatorForContext();
    }

    private void buildUi() {
        BorderPane pane = fxObject();
        transcript = new RichTextArea();
        transcript.setEditable(false);

        input = new TextField();
        input.setPromptText("Ask about the open knowledge base…");
        HBox.setHgrow(input, Priority.ALWAYS);
        sendButton = new Button("Send");

        input.setOnAction(event -> send());
        sendButton.setOnAction(event -> send());

        HBox bottom = new HBox(6, input, sendButton);
        bottom.setPadding(new Insets(6));

        pane.setCenter(transcript);
        pane.setBottom(bottom);
        refreshTranscript();
    }

    private void send() {
        String text = (input.getText() == null) ? "" : input.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.ERROR,
                    "No Anthropic API key configured (set it in the Claude Assistant).", false));
            refreshTranscript();
            return;
        }
        String model = PreferencesService.userPreferences().get(PREF_MODEL, AnthropicClient.DEFAULT_MODEL);

        entries.add(new MarkdownRichText.Entry(MarkdownRichText.Role.USER, text, false));
        refreshTranscript();
        input.clear();
        setBusy(true);

        List<Map<String, Object>> history = List.copyOf(apiMessages);
        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS);

        worker.submit(() -> {
            String reply;
            boolean error = false;
            try {
                reply = client.ask(SYSTEM_PROMPT, tools, history, text);
            } catch (Throwable t) {
                LOG.error("Chat request failed", t);
                reply = (t.getMessage() != null) ? t.getMessage() : t.toString();
                error = true;
            }
            String finalReply = reply;
            boolean finalError = error;
            Platform.runLater(() -> {
                entries.add(new MarkdownRichText.Entry(
                        finalError ? MarkdownRichText.Role.ERROR : MarkdownRichText.Role.ASSISTANT,
                        finalReply, !finalError));
                if (!finalError) {
                    apiMessages.add(Map.of("role", "user", "content", text));
                    apiMessages.add(Map.of("role", "assistant", "content", finalReply));
                }
                refreshTranscript();
                setBusy(false);
            });
        });
    }

    private void setBusy(boolean busy) {
        input.setDisable(busy);
        sendButton.setDisable(busy);
    }

    private void refreshTranscript() {
        transcript.setModel(new MarkdownRichText(viewCalculator(), MarkdownRichText.DEFAULT_BASE).toModel(entries));
    }

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        // In-memory transcript only; nothing persisted.
    }

    @Override
    protected void subAreaRevert() {
        // Nothing to revert.
    }

    @Override
    protected void subAreaSave() {
        // Nothing persisted.
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
     * Returns a new factory for this area.
     *
     * @return a {@link Factory}
     */
    public static Factory factory() {
        return new Factory();
    }

    /**
     * Restores an area from preferences.
     *
     * @param preferences the preferences node
     * @return the restored area
     */
    public static ChatArea restore(KometPreferences preferences) {
        return factory().restore(preferences);
    }

    /**
     * Creates a new area with the given grid settings.
     *
     * @param preferencesFactory factory for the area's preferences node
     * @param areaGridSettings   the grid placement for the new area
     * @return the new area
     */
    public static ChatArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
        return factory().create(preferencesFactory, areaGridSettings);
    }

    /**
     * {@code ServiceLoader}-discoverable factory for {@link ChatArea}.
     */
    public static final class Factory implements SupplementalAreaBlueprint.Factory<ChatArea> {

        /**
         * Restores an area from preferences.
         *
         * @param preferences the preferences node
         * @return the restored area
         */
        @Override
        public ChatArea restore(KometPreferences preferences) {
            return new ChatArea(preferences);
        }

        /**
         * Creates a new area with the given grid settings.
         *
         * @param preferencesFactory factory for the area's preferences node
         * @param areaGridSettings   the grid placement for the new area
         * @return the new area
         */
        @Override
        public ChatArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            ChatArea area = new ChatArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
