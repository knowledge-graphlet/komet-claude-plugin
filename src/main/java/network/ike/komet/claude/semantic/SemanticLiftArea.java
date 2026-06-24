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
package network.ike.komet.claude.semantic;

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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import jfx.incubator.scene.control.richtext.RichTextArea;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The semantic-lift authoring surface — a placeable {@code SupplementalArea}, modelled on the
 * narrative-lift {@code AnfArea}, that composes candidate semantics for a graph of patterns. Top to
 * bottom: the {@link PatternGraphPanel pattern-graph} substrate; the three prompt panes (fixed
 * invariants read-only, editable general guidance, the session request); a Lift action; and a
 * proposed-semantics preview.
 *
 * <p>State persistence: the general guidance persists per OS-user via {@link SemanticPrompts}; the
 * session prompt and the pattern graph persist in this area's own preferences node and are restored
 * on reopen. The Lift action is the quick single-pattern path (IKE-Network/ike-issues#762): it runs
 * the headless {@link SemanticLift} against the first pattern in the graph and the session request,
 * grounding against the live store, and previews the result. The full graph→forest engine, the
 * candidate/editable panels, and meaning/purpose refinement are the remaining Phase-1 children.
 */
public final class SemanticLiftArea extends SupplementalAreaBlueprint implements KlToolArea<BorderPane> {

    private static final double BASE_FONT = MarkdownRichText.DEFAULT_BASE;
    /** The lift runs on the more capable model for the structural reasoning. */
    private static final String LIFT_MODEL = "claude-opus-4-8";
    /** Per-OS-user key the Claude Assistant / ANF lift store the Anthropic key under. */
    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    /** Area-node keys for this tile's own restorable state. */
    private static final String PREF_SESSION = "semanticLift.session";
    private static final String PREF_GRAPH = "semanticLift.graph";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "komet-semantic-lift");
        t.setDaemon(true);
        return t;
    });

    private ViewProperties toolViewProperties;
    private Runnable onCloseRequest;

    private PatternGraphPanel patternPanel;
    private TextArea guidanceEditor;
    private TextArea sessionEditor;
    private RichTextArea sessionPreview;
    private Button liftButton;
    private Label status;
    private VBox results;
    private boolean restored;

    /**
     * Restore constructor.
     *
     * @param preferences the preferences node backing this area
     */
    public SemanticLiftArea(KometPreferences preferences) {
        super(preferences);
        buildUi();
    }

    /**
     * Create constructor.
     *
     * @param preferencesFactory factory for this area's preferences node
     * @param areaFactory        the factory creating this area
     */
    public SemanticLiftArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        buildUi();
    }

    private ViewCalculator viewCalculator() {
        if (toolViewProperties != null) {
            return toolViewProperties.calculator();
        }
        return calculatorForContext();
    }

    @Override
    public void setToolViewProperties(ViewProperties viewProperties) {
        this.toolViewProperties = viewProperties;
    }

    @Override
    public void setOnCloseRequest(Runnable onCloseRequest) {
        this.onCloseRequest = onCloseRequest;
    }

    private void buildUi() {
        BorderPane pane = fxObject();

        // 0. Pattern graph — the substrate: drag patterns in and arrange how their semantics attach.
        patternPanel = new PatternGraphPanel(this::viewCalculator);
        patternPanel.setOnChange(this::persistState);
        TitledPane patternsPane = new TitledPane(
                "Pattern graph — the substrate (drag patterns in)", patternPanel);
        patternsPane.setExpanded(true);

        // 1. System prompt — fixed invariants, read-only rendered Markdown, collapsed by default.
        RichTextArea systemView = renderedView(SemanticPrompts.invariants());
        systemView.setPrefHeight(240);
        TitledPane systemPane = new TitledPane(
                "System prompt — fixed invariants (read-only)", systemView);
        systemPane.setExpanded(false);

        // 2. Intermediate prompt — user-editable general guidance (persisted globally), source + preview.
        guidanceEditor = sourceEditor(SemanticPrompts.effectiveGuidance(), null);
        RichTextArea guidancePreview = renderedView(guidanceEditor.getText());
        guidanceEditor.focusedProperty().addListener((obs, was, focused) -> {
            if (Boolean.FALSE.equals(focused)) {
                SemanticPrompts.saveGuidance(guidanceEditor.getText());
                render(guidancePreview, guidanceEditor.getText());
            }
        });
        TitledPane guidancePane = new TitledPane(
                "Intermediate prompt — general guidance (editable)",
                editPlusPreview(guidanceEditor, guidancePreview));
        guidancePane.setExpanded(true);

        // 3. Session prompt — this run's request (persisted per area), source + preview.
        sessionEditor = sourceEditor("",
                "Describe the semantic to create for the bound pattern, e.g. "
                        + "“a semantic for type 2 diabetes in a patient on ozempic”…");
        sessionPreview = renderedView("");
        sessionEditor.focusedProperty().addListener((obs, was, focused) -> {
            if (Boolean.FALSE.equals(focused)) {
                render(sessionPreview, sessionEditor.getText());
                persistState();
            }
        });
        TitledPane sessionPane = new TitledPane(
                "Session prompt — this request (editable)",
                editPlusPreview(sessionEditor, sessionPreview));
        sessionPane.setExpanded(true);

        // 4. Lift action + status.
        liftButton = new Button("Lift → propose semantics");
        liftButton.setOnAction(event -> runLift());
        status = new Label("Lifts the first pattern in the graph against the session prompt "
                + "(single-pattern preview; full graph forest is next).");
        status.setWrapText(true);
        status.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox liftBar = new HBox(8, liftButton, status);
        liftBar.setAlignment(Pos.CENTER_LEFT);

        // 5. Proposed semantics (text preview for now; the editable card panel is a later child).
        results = new VBox(6);
        results.setPadding(new Insets(4));
        TitledPane resultPane = new TitledPane("Proposed semantics (preview)", results);
        resultPane.setExpanded(true);

        VBox stack = new VBox(8, patternsPane, systemPane, guidancePane, sessionPane, liftBar, resultPane);
        stack.setPadding(new Insets(8));
        ScrollPane scroll = new ScrollPane(stack);
        scroll.setFitToWidth(true);
        pane.setCenter(scroll);
    }

    // ── Lift action ───────────────────────────────────────────────────────────

    private void runLift() {
        String text = (sessionEditor.getText() == null) ? "" : sessionEditor.getText().strip();
        if (text.isEmpty()) {
            status("Enter a session prompt (the request) first.");
            return;
        }
        ViewCalculator view = viewCalculator();
        if (view == null) {
            status("No knowledge-base view is available to ground the lift.");
            return;
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            status("No Anthropic API key configured (set it in the Claude Assistant).");
            return;
        }
        if (patternPanel.nodes().isEmpty()) {
            status("Drag at least one pattern into the graph first.");
            return;
        }
        PatternGraphPanel.PatternNode target = patternPanel.nodes().get(0);
        int patternNid = target.patternNid;

        liftButton.setDisable(true);
        results.getChildren().clear();
        status("Lifting against “" + target.label + "”…");
        worker.submit(() -> {
            SemanticLift.Result result;
            try {
                result = new SemanticLift(view, apiKey, LIFT_MODEL, patternNid).lift(text);
            } catch (Throwable t) {
                String message = (t.getMessage() != null) ? t.getMessage() : t.toString();
                Platform.runLater(() -> {
                    status("Lift failed: " + message);
                    liftButton.setDisable(false);
                });
                return;
            }
            SemanticLift.Result done = result;
            Platform.runLater(() -> {
                renderResult(done);
                status(done.lifted()
                        ? done.instances().size() + " semantic(s) proposed"
                        : "No semantic was produced.");
                liftButton.setDisable(false);
            });
        });
    }

    private void renderResult(SemanticLift.Result result) {
        results.getChildren().clear();
        if (result == null || !result.lifted()) {
            String text = (result == null) ? null : result.assistantText();
            results.getChildren().add(wrap((text == null || text.isBlank())
                    ? "No semantic was produced." : text));
            return;
        }
        for (SemanticInstance instance : result.instances()) {
            Label card = wrap(instance.describe());
            card.setStyle("-fx-border-color: #eeeeee; -fx-border-width: 0 0 1 0; -fx-padding: 4 0 6 0;");
            results.getChildren().add(card);
        }
        if (result.truncated()) {
            Label note = wrap("⚠ Stopped at the turn cap — more may have been intended.");
            note.setStyle("-fx-text-fill: #b5651d; -fx-font-size: 10;");
            results.getChildren().add(note);
        }
    }

    private void status(String message) {
        status.setText(message);
    }

    private static Label wrap(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    // ── State persistence (this area's own node) ──────────────────────────────

    /** Persists the session prompt and the pattern graph to this area's preferences node. */
    private void persistState() {
        try {
            KometPreferences prefs = preferences();
            prefs.put(PREF_SESSION, sessionEditor == null ? "" : sessionEditor.getText());
            prefs.put(PREF_GRAPH, patternPanel == null ? "" : patternPanel.serialize());
            prefs.flush();
        } catch (Exception persistFailed) {
            // best-effort; an unavailable store just means this session's state is not restored
        }
    }

    /** Restores the session prompt and pattern graph from this area's preferences node (once). */
    private void restoreState() {
        if (restored) {
            return;
        }
        restored = true;
        try {
            KometPreferences prefs = preferences();
            String session = prefs.get(PREF_SESSION, "");
            sessionEditor.setText(session);
            render(sessionPreview, session);
            patternPanel.restoreFrom(prefs.get(PREF_GRAPH, ""));
        } catch (Exception restoreFailed) {
            // a missing/unreadable node just leaves the defaults in place
        }
    }

    // ── Prompt-pane helpers ───────────────────────────────────────────────────

    private static TextArea sourceEditor(String text, String promptText) {
        TextArea editor = new TextArea(text == null ? "" : text);
        editor.setWrapText(true);
        editor.setPrefRowCount(8);
        if (promptText != null) {
            editor.setPromptText(promptText);
        }
        return editor;
    }

    private static VBox editPlusPreview(TextArea editor, RichTextArea preview) {
        preview.setPrefHeight(160);
        VBox box = new VBox(4,
                caption("Markdown source"), editor,
                caption("Preview"), preview);
        VBox.setVgrow(editor, Priority.ALWAYS);
        VBox.setVgrow(preview, Priority.ALWAYS);
        return box;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #888888; -fx-font-size: 10; -fx-font-weight: bold;");
        return label;
    }

    private RichTextArea renderedView(String markdown) {
        RichTextArea view = new RichTextArea();
        view.setEditable(false);
        render(view, markdown);
        return view;
    }

    /**
     * Renders Markdown into a RichTextArea (no role label — a plain prompt, not a transcript turn).
     * {@code viewCalculator()} may be null during {@code buildUi} (before the host injects the view);
     * the renderer degrades gracefully. A render failure must never break a prompt pane.
     */
    private void render(RichTextArea view, String markdown) {
        try {
            view.setModel(new MarkdownRichText(viewCalculator(), BASE_FONT).renderMarkdown(markdown));
        } catch (RuntimeException renderFailure) {
            // a malformed-Markdown render must not take down the pane
        }
    }

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        // The view + preferences node are ready by now; restore the session + graph off the FX queue.
        Platform.runLater(this::restoreState);
    }

    @Override
    protected void subAreaRevert() {
        // Nothing to revert.
    }

    @Override
    protected void subAreaSave() {
        persistState();
    }

    @Override
    public void knowledgeLayoutBind() {
        Platform.runLater(() -> this.lifecycleState.set(LifecycleState.BOUND));
    }

    @Override
    public void knowledgeLayoutUnbind() {
        // Flush any in-progress edits (a focus-lost save never fires if closed mid-edit) and stop the worker.
        if (guidanceEditor != null) {
            SemanticPrompts.saveGuidance(guidanceEditor.getText());
        }
        persistState();
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
    public static SemanticLiftArea restore(KometPreferences preferences) {
        return factory().restore(preferences);
    }

    /**
     * Creates a new area with the given grid settings.
     *
     * @param preferencesFactory factory for the area's preferences node
     * @param areaGridSettings   the grid placement for the new area
     * @return the new area
     */
    public static SemanticLiftArea create(KlPreferencesFactory preferencesFactory,
                                          AreaGridSettings areaGridSettings) {
        return factory().create(preferencesFactory, areaGridSettings);
    }

    /**
     * {@code ServiceLoader}-discoverable factory for {@link SemanticLiftArea}.
     */
    public static final class Factory implements SupplementalAreaBlueprint.Factory<SemanticLiftArea>,
            KlToolArea.Factory<BorderPane, SemanticLiftArea> {

        /**
         * The label shown for this tool on the Journal "+" (add) menu.
         *
         * @return the menu label
         */
        @Override
        public String toolName() {
            return "Semantic Lift";
        }

        /**
         * Restores an area from preferences.
         *
         * @param preferences the preferences node
         * @return the restored area
         */
        @Override
        public SemanticLiftArea restore(KometPreferences preferences) {
            return new SemanticLiftArea(preferences);
        }

        /**
         * Creates a new area with the given grid settings.
         *
         * @param preferencesFactory factory for the area's preferences node
         * @param areaGridSettings   the grid placement for the new area
         * @return the new area
         */
        @Override
        public SemanticLiftArea create(KlPreferencesFactory preferencesFactory,
                                       AreaGridSettings areaGridSettings) {
            SemanticLiftArea area = new SemanticLiftArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
