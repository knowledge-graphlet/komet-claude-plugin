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
import network.ike.komet.claude.anf.element.ClinicalElement;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MenuButton;
import design.knowledge.stamp.adoc.git.AnnotationCommit;
import dev.ikm.tinkar.common.id.PublicId;
import network.ike.komet.claude.stamp.DocumentNarratives;
import network.ike.komet.claude.stamp.GitDocumentMaterializer;
import network.ike.komet.claude.anf.element.SegmentNarrative;
import network.ike.komet.claude.anf.element.Anchoring;
import network.ike.komet.claude.anf.element.ElementIsolation;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.claude.anf.element.Span;
import network.ike.komet.claude.tools.GraphTools;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    /** The note, editable, with its elements drawn on it. */
    private NarrativeView narrative;
    /** The stamped document the narrative was read from, while the narrative is its text: spans then anchor to its sentences. */
    private SegmentNarrative segments;
    /** The stamped document the narrative is the text of, while {@link #segments} is set. */
    private PublicId documentAnchor;
    /** The narrative spans the document's own annotations mark not clinical: on the note, not in the lift. */
    private List<Span> recorded = List.of();
    private Button liftButton;
    /** Step one of the two-step lift (dev-anf-element-isolation): isolates the note's clinical elements. */
    private Button isolateButton;
    private ElementsPane elementsPane;
    /** The statements of a lift in one pass, shown only when there are some. */
    private VBox cards;
    private Label progressLabel;
    private Label statusLabel;
    /** The region's one scroll bar: the narrative, the element tree and a one-pass lift's statements scroll together. */
    private ScrollPane column;
    private final List<AnfStatement> statements = new ArrayList<>();
    private ComboBox<LiftRecord> recallBox;
    private final ObservableList<LiftRecord> history = FXCollections.observableArrayList();
    /** The history index of the lift currently shown/edited, so a field edit re-persists the right file. */
    private int currentLiftIndex = -1;
    /** Suppresses the recall listener while we update the history list in place (avoids a reload mid-edit). */
    private boolean suppressRecall = false;
    private Timeline elapsed;
    private long liftStartNanos;
    private String activity = "";
    private ViewProperties toolViewProperties;
    private Runnable onCloseRequest;

    /**
     * The drop-substitution editor for the statement card at {@code index}: dropping a Komet
     * concept on one of its values replaces that concept and re-renders only that card.
     *
     * @param index the statement's position in {@link #statements}
     * @return an editor bound to that card
     */
    private AnfStatementView.Editor editorFor(int index) {
        return new AnfStatementView.Editor() {
            @Override
            public AnfValue.Grounded resolve(long conceptNid) {
                ViewCalculator view = viewCalculator();
                // Active-only grounding (#739): dropping a retired concept onto a value is rejected.
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
                    for (AnfValue.Grounded grounded : GraphTools.searchConcepts(query, view, max)) {
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
        recallBox.setId("recall-box");
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

        narrative = new NarrativeView();

        liftButton = new Button("Lift in one pass");
        liftButton.setId("lift-one-pass-button");
        liftButton.setOnAction(event -> lift());
        isolateButton = new Button("Isolate elements");
        isolateButton.setId("isolate-button");
        isolateButton.setDefaultButton(true);
        isolateButton.setTooltip(new Tooltip("Step one: find each clinical fact of the note, as a sentence tied to its words"));
        isolateButton.setOnAction(event -> isolate());
        elementsPane = new ElementsPane(new ElementsHost());
        narrative.setOnEdited(() -> {
            String now = narrative.getText().strip();
            if (segments != null && !segments.narrative().equals(now)) {
                // edited by hand: the text is no longer the document's, so spans are quoted again
                segments = null;
                documentAnchor = null;
                recorded = List.of();
            }
            ElementSet set = elementsPane.elements();
            elementsPane.setNoteChanged(set != null && !set.narrative().equals(now));
            // restyled after the edit's own notification, never from inside it
            Platform.runLater(this::markNarrative);
        });
        narrative.setOnClicked(offset -> {
            String text = narrative.getText();
            ElementSet set = elementsPane.elements();
            if (set != null && set.narrative().equals(text.strip())) {
                elementsPane.selectAt(offset - text.indexOf(set.narrative())).ifPresent(this::reveal);
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        MenuButton fromDocument = new MenuButton("From document");
        fromDocument.setId("from-document-button");
        fromDocument.setTooltip(new Tooltip("Read a stamped document's prose as the narrative: elements then anchor to its sentences"));
        // listed now and again each time it opens, since a document may be materialized meanwhile
        fromDocument.setOnShowing(event -> listDocuments(fromDocument));
        listDocuments(fromDocument);
        HBox controls = new HBox(8, recallBox, fromDocument, spacer, isolateButton, liftButton);
        controls.setAlignment(Pos.CENTER_LEFT);

        progressLabel = new Label();
        progressLabel.setStyle("-fx-text-fill: #666666;");
        progressLabel.managedProperty().bind(progressLabel.textProperty().isNotEmpty());
        progressLabel.visibleProperty().bind(progressLabel.managedProperty());
        statusLabel = new Label();
        statusLabel.setId("lift-status");
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: #666666;");
        statusLabel.managedProperty().bind(statusLabel.textProperty().isNotEmpty());
        statusLabel.visibleProperty().bind(statusLabel.managedProperty());

        VBox elementsSection = new VBox(2, sectionTitle("Clinical elements"), elementsPane);

        // A lift in one pass has no elements: its statements list here, and the section is gone when it has none.
        cards = new VBox(8);
        cards.setPadding(new Insets(4, 0, 4, 0));
        VBox statementSection = new VBox(2, sectionTitle("ANF statements"), cards);
        statementSection.visibleProperty().bind(Bindings.isNotEmpty(cards.getChildren()));
        statementSection.managedProperty().bind(statementSection.visibleProperty());

        // One column, one scroll bar (KEC 2026-10-31): every section at its natural height.
        VBox content = new VBox(8, controls, narrative.node(), progressLabel, statusLabel, elementsSection, statementSection);
        content.setPadding(new Insets(8, 12, 12, 8));
        column = new ScrollPane(content);
        column.setId("lift-column");
        column.setFitToWidth(true);
        column.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        pane.setCenter(column);
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

    /** Scrolls the column so a node of it is in view, leaving it where it is when it already is. */
    private void reveal(Node node) {
        Platform.runLater(() -> {
            Node content = column.getContent();
            double contentHeight = content.getBoundsInLocal().getHeight();
            double viewport = column.getViewportBounds().getHeight();
            if (contentHeight <= viewport) {
                return;
            }
            javafx.geometry.Bounds bounds = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
            double top = column.getVvalue() * (contentHeight - viewport);
            if (bounds.getMinY() < top || bounds.getMaxY() > top + viewport) {
                double target = Math.max(0, Math.min(bounds.getMinY() - 24, contentHeight - viewport));
                column.setVvalue(target / (contentHeight - viewport));
            }
        });
    }

    /** Draws the current elements on the narrative, the selected one tinted. */
    private void markNarrative() {
        narrative.mark(elementsPane.elements(), elementsPane.selected().map(ClinicalElement::id));
    }

    /** Re-resolves a written identifier against the current view, for parsing a history lift's blocks. */
    private AnfAdoc.ConceptResolver resolver() {
        ViewCalculator view = viewCalculator();
        return (view == null)
                ? id -> java.util.Optional.empty()
                : id -> GraphTools.resolveConcept(id, view);
    }

    /** Reloads a persisted lift: re-parses its adoc blocks and rebuilds its elements, or its cards. */
    private void loadLift(LiftRecord record) {
        statements.clear();
        cards.getChildren().clear();
        AnfAdoc.ConceptResolver resolver = resolver();
        // a lift from a stamped document is read against the document's sentences as they are now
        Optional<PublicId> document = segmentDocumentOf(record.anfBlocks());
        Optional<SegmentNarrative> current = document.flatMap(DocumentNarratives::narrative);
        Anchoring anchoring = current.<Anchoring>map(n -> n).orElse(Anchoring.quotes(record.narrative()));
        List<String> lost = current.map(n -> ElementText.unresolved(record.anfBlocks(), n)).orElse(List.of());
        Optional<ElementSet> elements = ElementText.parse(record.anfBlocks(), anchoring, resolver);
        if (elements.isPresent()) {
            narrative.setText(anchoring.narrative());
            segments = current.orElse(null);
            documentAnchor = current.isPresent() ? document.get() : null;
            recorded = current.map(n -> DocumentNarratives.recordedNotClinical(document.get(), n)).orElse(List.of());
            elementsPane.setElements(withRecorded(elements.get()));
            markNarrative();
            currentLiftIndex = history.indexOf(record);
            setStatus(lost.isEmpty() ? ""
                    : "The document has changed: " + lost.size() + (lost.size() == 1 ? " element" : " elements")
                    + " no longer found in its sentences: " + String.join("; ", lost));
            int n = elements.get().elements().size();
            setActivity(n + (n == 1 ? " element" : " elements") + " · from history");
            return;
        }
        segments = null;
        documentAnchor = null;
        recorded = List.of();
        elementsPane.setElements(null);
        for (String block : record.anfBlocks()) {
            AnfStatement statement = AnfAdoc.parse(block, resolver);
            if (statement != null) {
                appendCard(statement);
            }
        }
        narrative.setText(record.narrative());
        setStatus("");
        currentLiftIndex = history.indexOf(record);
        int n = statements.size();
        setActivity(n + (n == 1 ? " statement" : " statements") + " · from history");
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

    private static void addMeasure(List<AnfValue> conceptValues, AnfStatement.Result result) {
        if (result != null) {
            conceptValues.add(result.measureSemantic());
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
            setStatus("No Anthropic API key configured (set it in the Komet Assistant).");
            return;
        }
        setBusy(true);
        elementsPane.setElements(null);
        markNarrative();
        setStatus("");
        statements.clear();
        currentLiftIndex = -1;
        cards.getChildren().clear();
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
                    // model's text (a clarification or an error).
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
     * ANF-in-adoc and overwrites the lift's file (and its in-memory record), so a value edit survives a
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

    // ── The two-step lift (dev-anf-element-isolation) ────────────────────────

    /** Step one: isolates the note's clinical elements, through the isolate tool, and shows them for review. */
    private void isolate() {
        String text = (narrative.getText() == null) ? "" : narrative.getText().strip();
        if (text.isEmpty()) {
            return;
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            setStatus("No Anthropic API key configured (set it in the Komet Assistant).");
            return;
        }
        // the elements' spans are offsets into exactly this text
        narrative.setText(text);
        setBusy(true);
        statements.clear();
        cards.getChildren().clear();
        currentLiftIndex = -1;
        setStatus("Isolating the note's clinical elements…");
        startElapsed();
        AnfLiftListener listener = new AnfLiftListener() {
            @Override
            public void onTurnStart(int turn) {
                Platform.runLater(() -> setActivity("turn " + (turn + 1) + " · reading the note…"));
            }

            @Override
            public void onToolCall(int turn, String tool, Map<String, Object> input) {
                Platform.runLater(() -> setActivity("isolating elements…"));
            }
        };
        worker.submit(() -> {
            ElementIsolation.Result result;
            try {
                result = new ElementIsolation(apiKey, ANF_MODEL).isolate(text, listener);
            } catch (Throwable t) {
                LOG.error("ANF isolation failed", t);
                String msg = (t.getMessage() != null) ? t.getMessage() : t.toString();
                Platform.runLater(() -> {
                    stopElapsed();
                    setStatus("Isolation failed: " + msg);
                    setBusy(false);
                });
                return;
            }
            Platform.runLater(() -> {
                stopElapsed();
                if (result.elements().isEmpty()) {
                    String t = result.assistantText();
                    setStatus((t == null || t.isBlank()) ? "No elements were isolated." : t);
                } else {
                    ElementSet set = withRecorded(result.elements().get());
                    elementsPane.setElements(set);
                    markNarrative();
                    int n = set.elements().size();
                    setActivity(n + (n == 1 ? " element isolated" : " elements isolated") + " · review them, then lift");
                    setStatus("");
                    persistElements(set, true);
                }
                setBusy(false);
            });
        });
    }

    /** Step two: lifts each named element on its own, in order, through the existing lift. */
    private void liftElements(List<String> ids) {
        ElementSet start = elementsPane.elements();
        if (start == null || ids.isEmpty()) {
            return;
        }
        ViewCalculator view = viewCalculator();
        if (view == null) {
            setStatus("No knowledge-base view is available to ground the lift.");
            return;
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            setStatus("No Anthropic API key configured (set it in the Komet Assistant).");
            return;
        }
        setBusy(true);
        startElapsed();
        String note = start.narrative();
        worker.submit(() -> {
            int done = 0;
            for (String id : ids) {
                Optional<ClinicalElement> element = start.element(id);
                if (element.isEmpty()) {
                    continue;
                }
                int position = ++done;
                AnfLiftListener listener = new AnfLiftListener() {
                    @Override
                    public void onToolCall(int turn, String tool, Map<String, Object> input) {
                        Platform.runLater(() -> setActivity(position + "/" + ids.size() + " · " + activityFor(tool, input)));
                    }

                };
                AnfLift.Result result;
                try {
                    result = new AnfLift(view, apiKey, ANF_MODEL).liftElement(note, element.get().sentence(),
                            element.get().quotes(note), listener);
                } catch (Throwable t) {
                    LOG.error("ANF element lift failed for {}", id, t);
                    continue;
                }
                if (result.lifted()) {
                    AnfStatement statement = result.statements().get(0);
                    Platform.runLater(() -> {
                        ElementSet current = elementsPane.elements();
                        if (current != null && current.element(id).isPresent()) {
                            ElementSet next = current.lifted(id, statement);
                            elementsPane.setElements(next);
                            persistElements(next, false);
                        }
                    });
                }
            }
            Platform.runLater(() -> {
                stopElapsed();
                setActivity("lifted " + ids.size() + (ids.size() == 1 ? " element" : " elements"));
                setBusy(false);
            });
        });
    }

    /** The leaf under an open element in the tree: its statement, editable in place, or why it has none. */
    private Node statementLeaf(ClinicalElement element) {
        if (element.statement().isEmpty()) {
            Label none = new Label("Not lifted yet: lift it, or lift every reviewed element.");
            none.setStyle("-fx-text-fill: #888; -fx-font-size: 11;");
            return none;
        }
        return AnfStatementView.render(element.statement().get(), toolViewProperties, elementEditor(element.id()));
    }

    /** The card editor of an element's statement: an edit writes back to the element and persists. */
    private AnfStatementView.Editor elementEditor(String id) {
        AnfStatementView.Editor base = editorFor(-1);
        return new AnfStatementView.Editor() {
            @Override
            public AnfValue.Grounded resolve(long conceptNid) {
                return base.resolve(conceptNid);
            }

            @Override
            public void onEdited(AnfStatement updated) {
                apply(updated, true);
            }

            @Override
            public void onFieldEdited(AnfStatement updated) {
                apply(updated, false);
            }

            private void apply(AnfStatement updated, boolean redraw) {
                ElementSet current = elementsPane.elements();
                if (current == null) {
                    return;
                }
                current.element(id).ifPresent(e -> {
                    ElementSet next = current.replace(e.withStatement(updated));
                    if (!redraw) {
                        // the field already shows its new value: keep the card, so a sibling field mid-edit survives
                        elementsPane.statementEditedInPlace(id, updated);
                    }
                    elementsPane.setElements(next);
                    persistElements(next, false);
                });
            }

            @Override
            public KlConceptField.Completer completer() {
                return conceptCompleter();
            }
        };
    }

    /** How an element set's spans are written: to the document's sentences while the narrative is its text, else as quotes. */
    private Anchoring anchoring(ElementSet set) {
        return segments != null && segments.narrative().equals(set.narrative()) ? segments : Anchoring.quotes(set.narrative());
    }

    /** Lists the documents stored as sentences in the From document menu. */
    private void listDocuments(MenuButton fromDocument) {
        fromDocument.getItems().clear();
        List<DocumentNarratives.Choice> choices;
        try {
            choices = DocumentNarratives.documents();
        } catch (RuntimeException e) {
            LOG.warn("ANF lift: the stored documents could not be listed", e);
            choices = List.of();
        }
        if (choices.isEmpty()) {
            MenuItem none = new MenuItem("No document stored as sentences");
            none.setDisable(true);
            fromDocument.getItems().add(none);
        }
        for (DocumentNarratives.Choice choice : choices) {
            MenuItem item = new MenuItem(choice.path());
            item.setOnAction(e -> readDocument(choice));
            fromDocument.getItems().add(item);
        }
    }

    /** Reads a stamped document's prose as the narrative, so the elements isolated from it anchor to its sentences. */
    private void readDocument(DocumentNarratives.Choice choice) {
        Optional<SegmentNarrative> read = DocumentNarratives.narrative(choice.anchor());
        if (read.isEmpty()) {
            setStatus(choice.path() + " holds no stored sentences: rebuild it from git with the current starter set.");
            return;
        }
        segments = null;
        narrative.setText(read.get().narrative());
        segments = read.get();
        documentAnchor = choice.anchor();
        recorded = DocumentNarratives.recordedNotClinical(choice.anchor(), read.get());
        elementsPane.setElements(null);
        cards.getChildren().clear();
        currentLiftIndex = -1;
        int n = read.get().sentences().size();
        setStatus(choice.path() + ": " + n + (n == 1 ? " sentence" : " sentences")
                + ". Isolate its elements: their spans anchor to its sentences.");
    }

    /** The stored document a lift's segment anchors name, when the store holds it. */
    private static Optional<PublicId> segmentDocumentOf(List<String> blocks) {
        for (String block : blocks) {
            String header = block.strip().lines().findFirst().orElse("");
            if (!header.startsWith("[") || !header.endsWith("]")) {
                continue;
            }
            for (String id : SegmentNarrative.ids(ElementText.attributes(header.substring(1, header.length() - 1)).get("from"))) {
                Optional<PublicId> found = DocumentNarratives.documentOf(id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    /** Whether a set is over the document's text, so its marks can be the document's annotations. */
    private boolean onDocument(ElementSet set) {
        return set != null && documentAnchor != null && segments != null && segments.narrative().equals(set.narrative());
    }

    /** The set with the not-clinical marks the document records joined to its own. */
    private ElementSet withRecorded(ElementSet set) {
        if (!onDocument(set) || recorded.isEmpty()) {
            return set;
        }
        List<Span> marks = new ArrayList<>(set.notClinical());
        for (Span span : recorded) {
            if (!marks.contains(span)) {
                marks.add(span);
            }
        }
        return new ElementSet(set.narrative(), set.elements(), marks);
    }

    /** The set's not-clinical marks the document does not yet record. */
    private List<Span> unrecorded(ElementSet set) {
        if (!onDocument(set)) {
            return List.of();
        }
        return set.notClinical().stream().filter(span -> !recorded.contains(span)).sorted().toList();
    }

    /**
     * Writes the set's unrecorded not-clinical marks into the document's git history as Not
     * clinical annotations, one per mark, through the person's own git, and reads them back into
     * the store: the marks then belong to the note, and the lift no longer writes them as blocks.
     */
    private void recordNotClinical() {
        ElementSet set = elementsPane.elements();
        List<Span> marks = unrecorded(set);
        if (marks.isEmpty()) {
            return;
        }
        PublicId anchor = documentAnchor;
        SegmentNarrative read = segments;
        Optional<Path> root = DocumentNarratives.workingTree(anchor);
        if (root.isEmpty()) {
            setStatus("Open the document in a Stamped Document card first: the lift writes the annotations in its working tree.");
            return;
        }
        DocumentNarratives.Recording recording;
        try {
            recording = DocumentNarratives.notClinical(anchor, root.get(), read, marks);
        } catch (IOException | RuntimeException e) {
            setStatus("Not recorded: " + e.getMessage());
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Record on the note");
        confirm.getDialogPane().setId("record-not-clinical-dialog");
        confirm.setHeaderText("Record " + marks.size() + (marks.size() == 1 ? " not-clinical mark" : " not-clinical marks")
                + " on " + recording.file() + " as an empty commit?");
        Label details = new Label(AnnotationCommit.subject(recording.annotations()) + "\n\n" + recording.trailers()
                + "\n\nEach annotation names the words it covers, so every reader of the note sees them."
                + " Signing (-S) gives the annotation non-repudiation.");
        details.setId("record-not-clinical-trailers");
        details.setWrapText(true);
        confirm.getDialogPane().setContent(details);
        ButtonType signed = new ButtonType("Signed Commit");
        ButtonType unsigned = new ButtonType("Unsigned Commit");
        confirm.getButtonTypes().setAll(signed, unsigned, ButtonType.CANCEL);
        if (statusLabel.getScene() != null) {
            confirm.initOwner(statusLabel.getScene().getWindow());
        }
        Optional<ButtonType> choice = confirm.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return;
        }
        boolean sign = choice.get() == signed;
        setStatus("Writing the annotation commit …");
        CompletableFuture.supplyAsync(() -> {
            try {
                AnnotationCommit.Result result = AnnotationCommit.write(recording.root(), recording.annotations(), "", sign);
                if (result.ok()) {
                    GitDocumentMaterializer.materialize(recording.root(), recording.root().resolve(recording.file()), true);
                }
                return result;
            } catch (IOException e) {
                return new AnnotationCommit.Result(false, "", e.getMessage());
            }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            if (failure != null || !result.ok()) {
                String why = failure != null ? failure.getMessage() : result.output();
                setStatus("Not recorded: " + why);
                LOG.warn("ANF lift: the not-clinical annotation commit failed in {}: {}", recording.root(), why);
                return;
            }
            recorded = DocumentNarratives.recordedNotClinical(anchor, read);
            ElementSet now = withRecorded(elementsPane.elements());
            elementsPane.setElements(now);
            persistElements(now, false);
            markNarrative();
            setStatus("Recorded on the note as commit " + result.sha().substring(0, Math.min(7, result.sha().length()))
                    + ": " + recorded.size() + (recorded.size() == 1 ? " span" : " spans") + " marked not clinical.");
        }));
    }

    /**
     * Records an element set in the lift history as its blocks: a new history entry for a fresh
     * isolation, the current entry rewritten for a gesture or a lift.
     */
    private void persistElements(ElementSet set, boolean fresh) {
        // marks the document records are its annotations, not the lift's blocks (decision 9 revised)
        ElementSet own = onDocument(set) && !recorded.isEmpty()
                ? new ElementSet(set.narrative(), set.elements(), set.notClinical().stream().filter(span -> !recorded.contains(span)).toList())
                : set;
        List<String> blocks = ElementText.toBlocks(own, anchoring(set));
        if (!fresh && currentLiftIndex >= 0 && currentLiftIndex < history.size()) {
            LiftRecord previous = history.get(currentLiftIndex);
            LiftRecord updated = new LiftRecord(set.narrative(), blocks, previous.epochMillis(), previous.title());
            suppressRecall = true;
            try {
                history.set(currentLiftIndex, updated);
            } finally {
                suppressRecall = false;
            }
            AnfHistoryStore.append(preferences(), updated, currentLiftIndex);
            return;
        }
        LiftRecord record = new LiftRecord(set.narrative(), blocks, System.currentTimeMillis(),
                LiftRecord.titleFrom(set.narrative()));
        int index = history.size();
        history.add(record);
        currentLiftIndex = index;
        if (!AnfHistoryStore.append(preferences(), record, index)) {
            LOG.info("ANF history kept in memory only (no backing directory for this node)");
        }
    }

    /** The region as the element pane's host. */
    private final class ElementsHost implements ElementsPane.Host {
        @Override
        public void changed(ElementSet set) {
            elementsPane.setElements(set);
            persistElements(set, false);
            markNarrative();
        }

        @Override
        public void selected(Optional<ClinicalElement> element) {
            markNarrative();
        }

        @Override
        public Node statement(ClinicalElement element) {
            return statementLeaf(element);
        }

        @Override
        public void lift(List<String> ids) {
            liftElements(ids);
        }

        @Override
        public int unrecordedNotClinical(ElementSet set) {
            return unrecorded(set).size();
        }

        @Override
        public void recordNotClinical() {
            AnfArea.this.recordNotClinical();
        }

        @Override
        public Optional<Span> selection() {
            ElementSet set = elementsPane.elements();
            String text = narrative.getText();
            if (set == null || !set.narrative().equals(text.strip())) {
                return Optional.empty();
            }
            int lead = text.indexOf(set.narrative());
            return narrative.selection()
                    .filter(span -> span.start() >= lead && span.end() <= lead + set.narrative().length())
                    .map(span -> new Span(span.start() - lead, span.end() - lead));
        }
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
            long elements = item.anfBlocks().stream().filter(b -> b.contains(",element=")).count();
            int n = item.anfBlocks().size();
            Label subtitle = new Label(elements > 0 ? elements + (elements == 1 ? " element" : " elements")
                    : n + (n == 1 ? " statement" : " statements"));
            subtitle.setStyle("-fx-text-fill: #999999; -fx-font-size: 10;");
            setText(null);
            setGraphic(new VBox(1, title, subtitle));
        }
    }

    /** A line of status under the narrative; an empty message hides it. */
    private void setStatus(String message) {
        statusLabel.setText(message == null ? "" : message);
    }

    private void setBusy(boolean busy) {
        narrative.setDisable(busy);
        liftButton.setDisable(busy);
        isolateButton.setDisable(busy);
        elementsPane.setBusy(busy);
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

    /** A short progress phrase for a tool call, e.g. {@code "search diabetes…"}. */
    private static String activityFor(String tool, Map<String, Object> input) {
        Object arg = null;
        if (input != null) {
            arg = input.containsKey("query") ? input.get("query") : input.get("id");
        }
        return (arg == null) ? tool + "…" : tool + " " + arg + "…";
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
