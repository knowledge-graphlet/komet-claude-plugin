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

import dev.ikm.komet.framework.dnd.DragImageMaker;
import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.area.KlToolArea;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import network.ike.komet.claude.tools.GraphTools;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.HashSet;
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

    private TextArea narrative;
    private Button liftButton;
    private ScrollPane formalPane;
    private Label progressLabel;
    private ListView<AnfSlot> inventoryView;
    private final ObservableList<AnfSlot> inventory = FXCollections.observableArrayList();
    private final Set<Integer> seenNids = new HashSet<>();
    private Timeline elapsed;
    private long liftStartNanos;
    private String activity = "";
    private AnfStatement currentStatement;
    private ViewProperties toolViewProperties;
    private Runnable onCloseRequest;

    /** Substitution editor: dropping a Komet concept on a slot replaces the lifted one. */
    private final AnfStatementView.Editor anfEditor = new AnfStatementView.Editor() {
        @Override
        public AnfSlot.Grounded resolve(int conceptNid) {
            ViewCalculator view = viewCalculator();
            return (view == null) ? null : GraphTools.groundedOf(view, conceptNid);
        }

        @Override
        public void onEdited(AnfStatement updated) {
            currentStatement = updated;
            formalPane.setContent(AnfStatementView.render(updated, toolViewProperties, this));
        }
    };

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

        narrative = new TextArea();
        narrative.setPromptText("Paste or dictate a clinical narrative, then Lift…");
        narrative.setWrapText(true);
        narrative.setPrefRowCount(3);

        liftButton = new Button("Lift to ANF");
        liftButton.setOnAction(event -> lift());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(6, spacer, liftButton);
        controls.setPadding(new Insets(6, 0, 0, 0));

        progressLabel = new Label();
        progressLabel.setStyle("-fx-text-fill: #666666;");

        VBox top = new VBox(4, narrative, controls, progressLabel);
        top.setPadding(new Insets(8));

        inventoryView = new ListView<>(inventory);
        inventoryView.setCellFactory(view -> new ConceptCell());
        inventoryView.setPlaceholder(new Label("Concepts appear here as they are grounded."));

        formalPane = new ScrollPane();
        formalPane.setFitToWidth(true);
        setStatus("Lift a narrative to see its Analysis Normal Form.");

        SplitPane split = new SplitPane(inventoryView, formalPane);
        split.setDividerPositions(0.34);

        pane.setTop(top);
        pane.setCenter(split);
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
        formalPane.setContent(null);
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
                if (finalResult.lifted()) {
                    currentStatement = finalResult.statement();
                    formalPane.setContent(AnfStatementView.render(currentStatement, toolViewProperties, anfEditor));
                } else {
                    String t = finalResult.assistantText();
                    setStatus((t == null || t.isBlank()) ? "No statement was produced." : t);
                }
                setBusy(false);
            });
        });
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

        /** Starts a Komet concept drag, mirroring KonceptBadge (same image + no-offset drag view). */
        private void startConceptDrag(MouseEvent event) {
            if (!(getItem() instanceof AnfSlot.Grounded grounded)) {
                return;
            }
            Node graphic = getGraphic();
            if (graphic == null) {
                return;
            }
            Dragboard db = startDragAndDrop(TransferMode.COPY);
            db.setDragView(new DragImageMaker(graphic).getDragImage());
            EntityHandle.get(grounded.nid()).ifPresent(entity -> db.setContent(new KometClipboard(entity)));
            event.consume();
        }

        @Override
        protected void updateItem(AnfSlot item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic((empty || item == null) ? null : AnfStatementView.chipFor(item, toolViewProperties));
        }
    }

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        // In-memory only; v1 persists nothing.
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
