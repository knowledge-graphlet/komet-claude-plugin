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
package network.ike.komet.claude.anf;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.dnd.KonceptDragSource;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.area.KlToolArea;
import dev.ikm.komet.layout.controls.KlConceptField;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.tools.GraphTools;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The narrative lift surface: a placeable {@code SupplementalArea} where a clinician
 * types or dictates a clinical narrative and it is lifted into Analysis Normal Form,
 * grounded against the open knowledge base and rendered as identicon Koncepts.
 *
 * <p>It is the first slice of the journal authoring surface — a region a perspective
 * can proportion, not a card. The grounding view comes from the host context and the
 * API key from the shared per-OS-user Komet preferences (set once via the Claude
 * Assistant); the lift itself runs on the more capable model. The headless work is
 * {@link AnfLift}; this area is only the two-pane shell over it (narrative in, formal
 * ANF out). Render-only in v1 — nothing is written back to the store.
 */
public final class AnfArea extends SupplementalAreaBlueprint implements KlToolArea<BorderPane> {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AnfArea.class);

    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    /** The lift runs on the more capable model for the structural reasoning. */
    private static final String ANF_MODEL = "claude-opus-4-8";

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "komet-anf-lift");
        t.setDaemon(true);
        return t;
    });

    /** A separate thread for editable-field type-ahead searches, so they never queue behind a lift. */
    private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "komet-anf-search");
        t.setDaemon(true);
        return t;
    });

    private KlConceptField.Completer conceptCompleter;

    private TextArea narrative;
    private Button liftButton;
    private ScrollPane formalPane;
    private VBox cards;
    private Label progressLabel;
    private ListView<AnfSlot> inventoryView;
    private final ObservableList<AnfSlot> inventory = FXCollections.observableArrayList();
    private final Set<Integer> seenNids = new HashSet<>();
    private final List<AnfStatement> statements = new ArrayList<>();
    private ComboBox<LiftRecord> recallBox;
    private final ObservableList<LiftRecord> history = FXCollections.observableArrayList();
    /** The history index of the lift currently shown/edited, so a field edit re-persists the right file. */
    private int currentLiftIndex = -1;
    /** Suppresses the recall listener while we update the history list in place (avoids a reload mid-edit). */
    private boolean suppressRecall = false;
    private ScrollPane detailPane;
    private Timeline elapsed;
    private long liftStartNanos;
    private String activity = "";
    private ViewProperties toolViewProperties;
    private Runnable onCloseRequest;

    /**
     * The drop-substitution editor for the statement card at {@code index}: dropping a Komet
     * concept on one of its slots replaces that concept and re-renders only that card.
     *
     * @param index the statement's position in {@link #statements}
     * @return an editor bound to that card
     */
    private AnfStatementView.Editor editorFor(int index) {
        return new AnfStatementView.Editor() {
            @Override
            public AnfSlot.Grounded resolve(int conceptNid) {
                ViewCalculator view = viewCalculator();
                // Active-only grounding (#739): dropping a retired concept onto a slot is rejected.
                if (view == null || !GraphTools.isActive(view, conceptNid)) {
                    return null;
                }
                return GraphTools.groundedOf(view, conceptNid);
            }

            @Override
            public void onEdited(AnfStatement updated) {
                if (index >= 0 && index < statements.size()) {
                    statements.set(index, updated);
                    cards.getChildren().set(index, card(updated, index));
                    repersistCurrent();
                }
            }

            @Override
            public void onFieldEdited(AnfStatement updated) {
                // Update the in-memory model only — the field already shows its own new state, so a
                // card re-render here would destroy sibling fields that are mid-edit (BLOCKER-1).
                if (index >= 0 && index < statements.size()) {
                    statements.set(index, updated);
                    repersistCurrent();
                }
            }

            @Override
            public KlConceptField.Completer completer() {
                return conceptCompleter();
            }

            @Override
            public void showDetail(AnfSlot slot) {
                AnfArea.this.showDetail(slot);
            }
        };
    }

    /** The shared type-ahead completer for editable fields (search runs off the FX thread). */
    private KlConceptField.Completer conceptCompleter() {
        if (conceptCompleter == null) {
            conceptCompleter = (query, max, onResults) -> {
                ViewCalculator view = viewCalculator();
                if (view == null) {
                    onResults.accept(List.of());
                    return;
                }
                searchExecutor.submit(() -> {
                    List<KlConceptField.Completer.Result> rows = new ArrayList<>();
                    for (AnfSlot.Grounded grounded : GraphTools.searchConcepts(query, view, max)) {
                        rows.add(new KlConceptField.Completer.Result(grounded.nid(), grounded.label()));
                    }
                    Platform.runLater(() -> onResults.accept(rows));
                });
            };
        }
        return conceptCompleter;
    }

    /**
     * Restore constructor.
     *
     * @param preferences the preferences node backing this area
     */
    public AnfArea(KometPreferences preferences) {
        super(preferences);
        buildUi();
    }

    /**
     * Create constructor.
     *
     * @param preferencesFactory factory for this area's preferences node
     * @param areaFactory        the factory creating this area
     */
    public AnfArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
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

        // Recall: a compact dropdown of past lifts, so the narrative keeps full width.
        recallBox = new ComboBox<>(history);
        recallBox.setPromptText("Recall a past lift…");
        recallBox.setMaxWidth(260);
        recallBox.setVisibleRowCount(12);
        // Dropdown rows show the rich cell (title + count); the selected value shows as a single
        // line of title text via the converter — a rich VBox button cell grows to fill the bar.
        recallBox.setCellFactory(view -> new HistoryCell());
        recallBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(LiftRecord record) {
                return record == null ? "" : record.title();
            }

            @Override
            public LiftRecord fromString(String string) {
                return null;
            }
        });
        recallBox.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, record) -> {
                    if (record != null && !suppressRecall) {
                        loadLift(record);
                    }
                });

        narrative = new TextArea();
        narrative.setPromptText("Paste or dictate a clinical narrative, then Lift…");
        narrative.setWrapText(true);
        narrative.setPrefRowCount(4);

        liftButton = new Button("Lift to ANF");
        liftButton.setOnAction(event -> lift());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(8, recallBox, spacer, liftButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        progressLabel = new Label();
        progressLabel.setStyle("-fx-text-fill: #666666;");

        // TOP: narrative full width (input and recall), with the recall + lift controls above it.
        VBox top = new VBox(6, controls, narrative, progressLabel);
        top.setPadding(new Insets(8));

        // MIDDLE-LEFT: grounded/proposed concepts.
        inventoryView = new ListView<>(inventory);
        inventoryView.setCellFactory(view -> new ConceptCell());
        inventoryView.setPlaceholder(new Label("Concepts appear here as they are grounded."));
        inventoryView.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, slot) -> {
                    if (slot != null) {
                        showDetail(slot);
                    }
                });
        VBox conceptsPane = new VBox(2, sectionTitle("Concepts"), inventoryView);
        VBox.setVgrow(inventoryView, Priority.ALWAYS);

        // MIDDLE-RIGHT: the performance statement(s).
        cards = new VBox(8);
        cards.setPadding(new Insets(4));
        formalPane = new ScrollPane();
        formalPane.setFitToWidth(true);
        setStatus("Lift a narrative to see its Analysis Normal Form.");
        VBox statementPane = new VBox(2, sectionTitle("Performance statement"), formalPane);
        VBox.setVgrow(formalPane, Priority.ALWAYS);

        SplitPane middle = new SplitPane(conceptsPane, statementPane);
        middle.setDividerPositions(0.34);

        // BOTTOM: detail of the selected concept (from the Concepts list or a statement chip).
        detailPane = new ScrollPane();
        detailPane.setFitToWidth(true);
        detailPane.setContent(detailPlaceholder());
        VBox detailWrap = new VBox(2, sectionTitle("Concept detail"), detailPane);
        VBox.setVgrow(detailPane, Priority.ALWAYS);

        SplitPane center = new SplitPane(middle, detailWrap);
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.6);

        pane.setTop(top);
        pane.setCenter(center);
        pane.setBottom(legend());

        restoreHistory();
    }

    private static Label sectionTitle(String text) {
        Label title = new Label(text);
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: #666666; -fx-font-size: 11;");
        title.setPadding(new Insets(4, 0, 0, 4));
        return title;
    }

    /** Loads the persisted lift history into the list, once (idempotent across the restore hooks). */
    private void restoreHistory() {
        if (!history.isEmpty()) {
            return;
        }
        List<LiftRecord> restored = AnfHistoryStore.restore(preferences());
        if (!restored.isEmpty()) {
            history.setAll(restored);
        }
    }

    /** Opens the inline detail (descriptions + axioms for grounded; provisional data for proposed). */
    private void showDetail(AnfSlot slot) {
        detailPane.setContent(ConceptDetailPane.render(slot, toolViewProperties));
    }

    /** Re-resolves a written identifier against the current view, for parsing a history lift's blocks. */
    private AnfAdoc.ConceptResolver resolver() {
        ViewCalculator view = viewCalculator();
        return (view == null)
                ? id -> java.util.Optional.empty()
                : id -> GraphTools.resolveConcept(id, view);
    }

    /** Reloads a persisted lift: re-parses its adoc blocks and rebuilds the cards + inventory. */
    private void loadLift(LiftRecord record) {
        inventory.clear();
        seenNids.clear();
        statements.clear();
        cards.getChildren().clear();
        detailPane.setContent(detailPlaceholder());
        AnfAdoc.ConceptResolver resolver = resolver();
        for (String block : record.anfBlocks()) {
            AnfStatement statement = AnfAdoc.parse(block, resolver);
            if (statement != null) {
                appendCard(statement);
                for (AnfSlot slot : slotsOf(statement)) {
                    addConcept(slot);
                }
            }
        }
        formalPane.setContent(cards);
        narrative.setText(record.narrative());
        currentLiftIndex = history.indexOf(record);
        int n = statements.size();
        setActivity(n + (n == 1 ? " statement" : " statements") + " · from history");
    }

    private Node detailPlaceholder() {
        Label l = new Label("Select a concept to see its descriptions and axioms.");
        l.setWrapText(true);
        l.setPadding(new Insets(10));
        l.setStyle("-fx-text-fill: #999999;");
        return l;
    }

    /** The grounded/candidate/clarify key, as a single footer row across the bottom of the tile. */
    private Node legend() {
        HBox box = new HBox(20,
                legendRow("●", "#2a5a8a", "grounded — exists"),
                legendRow("◌", "#b5651d", "candidate — proposed"),
                legendRow("?", "#6a4c93", "clarify — question"));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 10, 4, 10));
        box.setStyle("-fx-border-color: #eeeeee; -fx-border-width: 1 0 0 0; -fx-background-color: #fafafa;");
        return box;
    }

    private Node legendRow(String glyph, String color, String text) {
        Label g = new Label(glyph);
        g.setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
        Label t = new Label(text);
        t.setStyle("-fx-text-fill: #888888; -fx-font-size: 10;");
        return new HBox(5, g, t);
    }

    /** All concept slots of a statement, for repopulating the inventory when a lift is reloaded. */
    private static List<AnfSlot> slotsOf(AnfStatement s) {
        List<AnfSlot> slots = new ArrayList<>();
        addSlot(slots, s.topic());
        addSlot(slots, s.subjectOfInformation());
        switch (s.circumstance()) {
            case Circumstance.Performance p -> {
                addMeasure(slots, p.result());
                addSlot(slots, p.status());
                addSlot(slots, p.healthRisk());
                addMeasure(slots, p.normalRange());
                addSlot(slots, p.bodySite());
                addSlot(slots, p.method());
                addSlot(slots, p.laterality());
                slots.addAll(p.purpose());
                addMeasure(slots, p.timing());
            }
            case Circumstance.Request r -> {
                addMeasure(slots, r.requestedResult());
                addSlot(slots, r.priority());
                addSlot(slots, r.method());
                slots.addAll(r.conditionalTrigger());
                slots.addAll(r.purpose());
                addMeasure(slots, r.timing());
                if (r.repetition() != null) {
                    addMeasure(slots, r.repetition().periodStart());
                    addMeasure(slots, r.repetition().periodDuration());
                    addMeasure(slots, r.repetition().eventSeparation());
                    addMeasure(slots, r.repetition().eventDuration());
                    addMeasure(slots, r.repetition().eventFrequency());
                }
            }
            case Circumstance.Narrative n -> {
                slots.addAll(n.purpose());
                addMeasure(slots, n.timing());
            }
        }
        return slots;
    }

    private static void addSlot(List<AnfSlot> slots, AnfSlot slot) {
        if (slot != null) {
            slots.add(slot);
        }
    }

    private static void addMeasure(List<AnfSlot> slots, AnfStatement.Result result) {
        if (result != null) {
            slots.add(result.measureSemantic());
        }
    }

    private void lift() {
        String text = (narrative.getText() == null) ? "" : narrative.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        ViewCalculator view = viewCalculator();
        if (view == null) {
            setStatus("No knowledge-base view is available to ground the lift.");
            return;
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            setStatus("No Anthropic API key configured (set it in the Claude Assistant).");
            return;
        }
        setBusy(true);
        inventory.clear();
        seenNids.clear();
        statements.clear();
        currentLiftIndex = -1;
        cards.getChildren().clear();
        formalPane.setContent(cards);
        startElapsed();

        AnfLiftListener listener = new AnfLiftListener() {
            @Override
            public void onTurnStart(int turn) {
                Platform.runLater(() -> setActivity("turn " + (turn + 1) + " · thinking…"));
            }

            @Override
            public void onToolCall(int turn, String tool, Map<String, Object> input) {
                Platform.runLater(() -> setActivity(activityFor(tool, input)));
            }

            @Override
            public void onSlotDiscovered(AnfSlot slot) {
                Platform.runLater(() -> addConcept(slot));
            }

            @Override
            public void onStatementEmitted(AnfStatement statement, int index) {
                Platform.runLater(() -> appendCard(statement));
            }

            @Override
            public void onDone(String stopReason, int turns, long totalMillis) {
                Platform.runLater(AnfArea.this::stopElapsed);
            }

            @Override
            public void onError(Throwable error, int turns, long totalMillis) {
                Platform.runLater(() -> {
                    stopElapsed();
                    setActivity("failed");
                });
            }
        };

        worker.submit(() -> {
            AnfLift.Result result;
            try {
                result = new AnfLift(view, apiKey, ANF_MODEL).lift(text, listener);
            } catch (Throwable t) {
                LOG.error("ANF lift failed", t);
                String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
                Platform.runLater(() -> {
                    stopElapsed();
                    setStatus("Lift failed: " + msg);
                    setBusy(false);
                });
                return;
            }
            AnfLift.Result finalResult = result;
            Platform.runLater(() -> {
                stopElapsed();
                if (!finalResult.lifted()) {
                    // The cards streamed in via onStatementEmitted; nothing landed, so show the
                    // model's text (a clarification or an error) in place of the empty card list.
                    String t = finalResult.assistantText();
                    setStatus((t == null || t.isBlank()) ? "No statement was produced." : t);
                } else {
                    int n = statements.size();
                    setActivity(n + (n == 1 ? " statement lifted" : " statements lifted"));
                    if (finalResult.truncated()) {
                        Label note = new Label("⚠ Lift stopped at the turn cap — more statements "
                                + "may have been intended.");
                        note.setWrapText(true);
                        note.setStyle("-fx-text-fill: #b5651d; -fx-padding: 6;");
                        cards.getChildren().add(note);
                    }
                    persistLift(text);
                }
                setBusy(false);
            });
        });
    }

    /** Appends a streamed statement as a card, in emit order. */
    private void appendCard(AnfStatement statement) {
        int index = statements.size();
        statements.add(statement);
        cards.getChildren().add(card(statement, index));
    }

    /** Renders one statement card, wired to its per-index drop-substitution editor. */
    private Node card(AnfStatement statement, int index) {
        Node node = AnfStatementView.render(statement, toolViewProperties, editorFor(index));
        if (node instanceof Region region) {
            region.setStyle("-fx-border-color: #eeeeee; -fx-border-width: 0 0 1 0;");
        }
        return node;
    }

    /**
     * Records the just-completed lift in the history and persists it as ANF-in-adoc. This is a
     * preference-node write (the area's own state), not a knowledge-graph write, so it is within
     * the v1 render-only rule. Persistence failure (e.g. no backing directory) keeps the lift in
     * memory for the session.
     */
    private void persistLift(String narrativeText) {
        List<String> blocks = new ArrayList<>(statements.size());
        for (AnfStatement statement : statements) {
            blocks.add(AnfAdoc.toAdoc(statement));
        }
        LiftRecord record = new LiftRecord(narrativeText, blocks, System.currentTimeMillis(),
                LiftRecord.titleFrom(narrativeText));
        int index = history.size();
        history.add(record);
        currentLiftIndex = index;
        if (!AnfHistoryStore.append(preferences(), record, index)) {
            LOG.info("ANF history kept in memory only (no backing directory for this node)");
        }
    }

    /**
     * Re-persists the currently shown lift after an edit: re-serializes the in-memory statements to
     * ANF-in-adoc and overwrites the lift's file (and its in-memory record), so a slot edit survives a
     * restart. The recall listener is suppressed while the history list is updated so the in-place
     * replacement does not reload the card and discard a sibling field mid-edit.
     */
    private void repersistCurrent() {
        if (currentLiftIndex < 0 || currentLiftIndex >= history.size()) {
            return;
        }
        LiftRecord previous = history.get(currentLiftIndex);
        List<String> blocks = new ArrayList<>(statements.size());
        for (AnfStatement statement : statements) {
            blocks.add(AnfAdoc.toAdoc(statement));
        }
        LiftRecord updated = new LiftRecord(previous.narrative(), blocks,
                previous.epochMillis(), previous.title());
        suppressRecall = true;
        try {
            history.set(currentLiftIndex, updated);
        } finally {
            suppressRecall = false;
        }
        AnfHistoryStore.append(preferences(), updated, currentLiftIndex);
    }

    /** A history row: the lift's title (truncated narrative) over its statement count. */
    private final class HistoryCell extends ListCell<LiftRecord> {
        @Override
        protected void updateItem(LiftRecord item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label title = new Label(item.title());
            title.setWrapText(true);
            int n = item.anfBlocks().size();
            Label subtitle = new Label(n + (n == 1 ? " statement" : " statements"));
            subtitle.setStyle("-fx-text-fill: #999999; -fx-font-size: 10;");
            setText(null);
            setGraphic(new VBox(1, title, subtitle));
        }
    }

    private void setStatus(String message) {
        Label l = new Label(message);
        l.setWrapText(true);
        l.setPadding(new Insets(8));
        l.setStyle("-fx-text-fill: #666666;");
        formalPane.setContent(l);
    }

    private void setBusy(boolean busy) {
        narrative.setDisable(busy);
        liftButton.setDisable(busy);
    }

    /** Sets the current activity word shown in the progress strip and refreshes it. */
    private void setActivity(String text) {
        this.activity = (text == null) ? "" : text;
        updateProgress();
    }

    /** Starts the elapsed-time ticker and shows the strip. */
    private void startElapsed() {
        liftStartNanos = System.nanoTime();
        activity = "starting…";
        if (elapsed == null) {
            elapsed = new Timeline(new KeyFrame(Duration.seconds(0.5), e -> updateProgress()));
            elapsed.setCycleCount(Animation.INDEFINITE);
        }
        updateProgress();
        elapsed.playFromStart();
    }

    /** Stops the elapsed-time ticker (on completion, error, or unbind) and freezes the strip. */
    private void stopElapsed() {
        if (elapsed != null) {
            elapsed.stop();
        }
        updateProgress();
    }

    private void updateProgress() {
        if (liftStartNanos == 0) {
            progressLabel.setText("");
            return;
        }
        long seconds = (System.nanoTime() - liftStartNanos) / 1_000_000_000L;
        progressLabel.setText(seconds + "s · " + (activity == null ? "" : activity));
    }

    /** Adds a discovered slot to the inventory, de-duplicating grounded concepts by nid. */
    private void addConcept(AnfSlot slot) {
        if (slot instanceof AnfSlot.Grounded grounded) {
            if (seenNids.add(grounded.nid())) {
                inventory.add(slot);
            }
        } else {
            inventory.add(slot);
        }
    }

    /** A short progress phrase for a tool call, e.g. {@code "search diabetes…"}. */
    private static String activityFor(String tool, Map<String, Object> input) {
        Object arg = null;
        if (input != null) {
            arg = input.containsKey("query") ? input.get("query") : input.get("id");
        }
        return (arg == null) ? tool + "…" : tool + " " + arg + "…";
    }

    /**
     * Renders an inventory row as the slot's shared Koncept badge, draggable on the
     * FIRST click. A KonceptBadge graphic inside a ListView only receives DRAG_DETECTED
     * after the row is first selected (the cell swallows the initial press), so the drag
     * is wired at the CELL with a capturing event filter. The filter also runs — and
     * consumes — ahead of the badge's own drag handler, so every drag (first click or
     * repeat) takes this one path with a single, consistent pointer offset, identical to
     * how the card chips drag: the {@link KonceptBadge} convention is a no-offset drag
     * view, which sits below and right of the pointer so it never hides the cursor.
     */
    private final class ConceptCell extends ListCell<AnfSlot> {
        ConceptCell() {
            addEventFilter(MouseEvent.DRAG_DETECTED, this::startConceptDrag);
        }

        /**
         * Starts a concept drag via the shared {@link KonceptDragSource} (scene-guarded,
         * unconditional payload, right-of-identicon drag view). The drag is wired at the CELL
         * because a {@link KonceptBadge} graphic only receives {@code DRAG_DETECTED} after the
         * row is first selected; the cell's filter runs ahead of that on the first click.
         */
        private void startConceptDrag(MouseEvent event) {
            if (getItem() instanceof AnfSlot.Grounded grounded
                    && getGraphic() instanceof KonceptBadge badge) {
                KonceptDragSource.start(this, badge, grounded.nid(), event);
            }
        }

        @Override
        protected void updateItem(AnfSlot item, boolean empty) {
            AnfSlot previous = getItem();
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
            } else if (item != previous || getGraphic() == null) {
                // Rebuild the badge ONLY when the row's concept actually changes (or the cell was empty) —
                // not on every updateItem (scroll/layout/selection, and the list growing as a lift streams
                // in). Recreating it each call churns nodes needlessly and lets a drag's press-target badge
                // be swapped out mid-gesture.
                setGraphic(AnfStatementView.chipFor(item, toolViewProperties));
            }
        }
    }

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        // Restore the persisted lift history (ANF-in-adoc files in this node's backing directory).
        // The history list is populated; no lift is auto-loaded until the user selects one.
        // (Also attempted in buildUi; idempotent.)
        Platform.runLater(this::restoreHistory);
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
        if (elapsed != null) {
            elapsed.stop();
        }
        worker.shutdownNow();
        searchExecutor.shutdownNow();
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
    public static AnfArea restore(KometPreferences preferences) {
        return factory().restore(preferences);
    }

    /**
     * Creates a new area with the given grid settings.
     *
     * @param preferencesFactory factory for the area's preferences node
     * @param areaGridSettings   the grid placement for the new area
     * @return the new area
     */
    public static AnfArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
        return factory().create(preferencesFactory, areaGridSettings);
    }

    /**
     * {@code ServiceLoader}-discoverable factory for {@link AnfArea}.
     */
    public static final class Factory implements SupplementalAreaBlueprint.Factory<AnfArea>,
            KlToolArea.Factory<BorderPane, AnfArea> {

        /**
         * The label shown for this tool on the Journal "+" (add) menu.
         *
         * @return the menu label
         */
        @Override
        public String toolName() {
            return "Narrative Lift to ANF";
        }

        /**
         * Restores an area from preferences.
         *
         * @param preferences the preferences node
         * @return the restored area
         */
        @Override
        public AnfArea restore(KometPreferences preferences) {
            return new AnfArea(preferences);
        }

        /**
         * Creates a new area with the given grid settings.
         *
         * @param preferencesFactory factory for the area's preferences node
         * @param areaGridSettings   the grid placement for the new area
         * @return the new area
         */
        @Override
        public AnfArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            AnfArea area = new AnfArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
