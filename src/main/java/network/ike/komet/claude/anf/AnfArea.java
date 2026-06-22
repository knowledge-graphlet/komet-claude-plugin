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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

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
public final class AnfArea extends SupplementalAreaBlueprint {

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
        return calculatorForContext();
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

        VBox top = new VBox(4, narrative, controls);
        top.setPadding(new Insets(8));

        formalPane = new ScrollPane();
        formalPane.setFitToWidth(true);
        setStatus("Lift a narrative to see its Analysis Normal Form.");

        pane.setTop(top);
        pane.setCenter(formalPane);
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
        setStatus("Lifting…");

        worker.submit(() -> {
            AnfLift.Result result;
            try {
                result = new AnfLift(view, apiKey, ANF_MODEL).lift(text);
            } catch (Throwable t) {
                LOG.error("ANF lift failed", t);
                String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
                Platform.runLater(() -> {
                    setStatus("Lift failed: " + msg);
                    setBusy(false);
                });
                return;
            }
            AnfLift.Result finalResult = result;
            Platform.runLater(() -> {
                if (finalResult.lifted()) {
                    formalPane.setContent(AnfStatementView.render(finalResult.statement()));
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
    public static final class Factory implements SupplementalAreaBlueprint.Factory<AnfArea> {

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
