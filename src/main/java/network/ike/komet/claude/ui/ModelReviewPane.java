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
package network.ike.komet.claude.ui;

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.GraphletModel;
import network.ike.komet.claude.decision.ModelReview;
import network.ike.komet.claude.decision.render.SweptQuantity;
import network.ike.komet.document.DecisionFigureText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The review of a graphlet model ({@code dev-grow-model-review}): what a decision's model, its
 * tornado and its tree grow into. A line at the top has the decision's question and how many
 * values differ from the document's. Beneath it the model's values are one table with the
 * tornado, a row for each value with its slider, its field and its bar, and the tree is beside
 * the table, or above it where the review is narrow, so the tree is in sight while a slider moves.
 *
 * <p>The review has a copy of the model, a {@link ModelReview}, in a context of its own made by
 * {@link ProfileBlocks#forReview}. A value moved changes the copy and nothing of the document, so
 * no card reads its document again and every movement redraws: the tree at once, on the same
 * pulse, and the bars when their sweep returns from the sweep's thread. The tree is the tree
 * block's own drawing over the review's context, told that it is a part of a review.
 *
 * <p>A value moved writes nothing. {@link #save()} hands the copy's text to the document's model
 * as one edit, when the person presses Save in document or answers Save on closing.
 */
final class ModelReviewPane extends BorderPane {

    /** The width, in ems of the surface's text, from which the tree is beside the values and not above them. */
    static final double WIDE_EMS = 74;
    /** The width the table of values is given beside the tree, in ems. */
    private static final double VALUES_EMS = 32;
    /** The space on either side of the tree in its part. */
    private static final double TREE_PAD = 10;

    private static final Color AHEAD = Color.web("#378add");
    private static final Color BEHIND = Color.web("#c9822b");
    private static final Color CHANGED = Color.web("#7b3fa0");
    private static final Color QUIET = Color.web("#666");

    private final double base;
    private final String modelId;
    /** The document's context, which has the model as the document's block wrote it. */
    private final ProfileBlocks document;
    /** The review's own context: the document's as it was when the review opened, and the review's copy of the model. */
    private final ProfileBlocks review;
    private final DecisionTerms terms = DecisionTerms.fromBindings();
    private final Identities ids;
    /** The copy; null when the document has no such model, or its text is no tree. */
    private final ModelReview state;
    private Identities shown;
    private String moneySymbol = "";
    private final Map<Integer, ValueRow> rows = new LinkedHashMap<>();
    private final Label changed = new Label();
    private final Label note = new Label();
    private final Button reset = new Button("Reset values");
    private final Button save = new Button("Save in document");
    /** Told after the changed values have been saved in the document; null when no one asked. */
    private Runnable onSaved;
    /** Told when the document's model is changed by something other than this review; null when no one asked. */
    private Runnable onDocumentChanged;
    /** Whether this review is saving: the model it then sees change in the document is its own. */
    private boolean saving;
    /** Listens to the document's context for as long as the review lives; the context holds it weakly. */
    private final Runnable onDocument = this::documentMoved;
    /** The tree's column, which knows how much room its drawing takes at full size. */
    private Node treeColumn;
    private VBox valuesTable;
    private final ViewProperties viewProperties;
    private DecisionTree tree;
    /** The value selected, by its vertex, or null; and the node or branch of the tree selected, or null. One at most. */
    private Integer selectedValue;
    private Integer selectedNode;
    /** The statements of what is selected, beneath the table of values. */
    private final VBox statements = new VBox(4);
    private final Label axisTitle = new Label();
    /** What the bars are about: the net value of an alternative, or the expected cost where no alternative is chosen. */
    private String barsAbout = "";
    /** The sweep the bars were last drawn from. */
    private DecisionTreeSensitivity drawn;
    /** Counts the texts swept, so a sweep that a later movement has overtaken draws nothing. */
    private volatile int generation;
    /** Whether a redraw is waiting for the next pulse: every movement before it is one redraw. */
    private boolean pending;
    private final Parts parts = new Parts();

    /**
     * @param viewCalc       the view the document's blocks are read under
     * @param viewProperties the surface's interactive view, for the tree's concepts; may be null
     * @param base           the surface's base font size
     * @param document       the document's context
     * @param modelId        the model to review, by its id
     * @param treeInfo       the tree block's info string the review opens with: the number counted
     *                       out for and the price of a healthy year of the block grown from, or
     *                       null for neither
     */
    ModelReviewPane(ViewCalculator viewCalc, ViewProperties viewProperties, double base, ProfileBlocks document,
                    String modelId, String treeInfo) {
        this.base = base;
        this.viewProperties = viewProperties;
        this.document = document;
        this.modelId = modelId;
        this.review = document.forReview(viewCalc);
        this.ids = review.identities(viewCalc);
        this.shown = ids;
        setId("model-review");
        setStyle("-fx-background-color: white;");
        String info = treeInfo == null || treeInfo.isBlank() ? "decision-tree model=" + modelId : treeInfo;
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        Optional<GraphletModel> model = document.model(modelId);
        this.state = model.flatMap(m -> ModelReview.open(m.body(), terms, ids, price, review.externals(), review.trees(),
                review.statementMeasures(ids, terms))).orElse(null);
        if (state == null) {
            Label none = new Label(model.isEmpty()
                    ? "This document has no model named “" + modelId + "”."
                    : "The model “" + modelId + "” can't be read as a decision tree, so there is nothing to review.");
            none.setId("model-review-nothing");
            none.setFont(Font.font(base));
            none.setWrapText(true);
            none.setPadding(new Insets(12));
            setCenter(none);
            return;
        }
        shown = state.read().labelled(ids);
        tree = state.read().tree().orElseThrow();
        moneySymbol = DecisionFigureText.symbolFor(moneyUnit(tree));
        publish();

        setTop(line(tree));
        VBox values = values(tree);
        valuesTable = values;
        ScrollPane valuesScroll = scroll(values, "model-review-values");
        Node treeNode = tree(viewCalc, viewProperties, info);
        treeColumn = treeNode;
        // a node or a branch clicked on the tree is selected here
        treeNode.getProperties().put(DecisionTreeBlockRenderer.ON_SELECT, (java.util.function.IntConsumer) this::selectNode);
        VBox treeHolder = new VBox(treeNode);
        treeHolder.setFillWidth(true);
        treeHolder.setPadding(new Insets(4, TREE_PAD, 8, TREE_PAD));
        document.listen(onDocument);
        ScrollPane treeScroll = scroll(treeHolder, "model-review-tree");
        parts.set(valuesScroll, treeScroll);
        setCenter(parts);
        state.opened().ifPresent(this::drawBars);
        redrawLine();
        showSelection();
    }

    // ── What the review is asked from outside ───────────────────────────────

    /** Whether the review has a model to show. */
    boolean reviewing() {
        return state != null;
    }

    /** How many values differ from the document's. */
    int changed() {
        return state == null ? 0 : state.changed();
    }

    /** Whether the changed values can be saved: false where the document cannot be edited here. */
    boolean writable() {
        return document.model(modelId).map(GraphletModel::writable).orElse(false);
    }

    /** The copy's text: the document's with every changed value restated on its line. */
    String body() {
        return state == null ? "" : state.body();
    }

    /**
     * Saves the changed values in the document: the copy's text is handed to the document's model,
     * which writes it to the document as one edit. The saved values are then the document's, and
     * the review has no change.
     *
     * @return whether the document took it: false when nothing differs, or the model cannot be written here
     */
    boolean save() {
        if (state == null || state.changed() == 0) {
            return false;
        }
        boolean written;
        saving = true;
        try {
            written = document.model(modelId).map(model -> model.write(state.body())).orElse(false);
        } finally {
            saving = false;
        }
        if (written) {
            state.saved();
            redraw();
            if (onSaved != null) {
                onSaved.run();
            }
        }
        return written;
    }

    /** Asks to be told after the changed values have been saved in the document. */
    void onSaved(Runnable told) {
        this.onSaved = told;
    }

    /** Asks to be told when the document's model is changed by something other than this review. */
    void onDocumentChanged(Runnable told) {
        this.onDocumentChanged = told;
    }

    /** The document's context moved: when its model no longer has the text this review's copy began from, someone else changed it. */
    private void documentMoved() {
        if (saving || state == null || onDocumentChanged == null) {
            return;
        }
        String now = document.model(modelId).map(GraphletModel::body).orElse(null);
        if (now == null || !now.stripTrailing().equals(state.documentBody().stripTrailing())) {
            onDocumentChanged.run();
        }
    }

    /**
     * Whether the whole review fits a place: the values beside the tree, the tree drawn left to
     * right at full size, and no part scrolling (settled with KEC, 2026-11-12).
     *
     * <p>The sizes are read in a scene. A review that has not been shown is in none, and its
     * labels have no skins yet: asked then, the tree answers a fraction of its width and the parts a
     * fraction of their heights, and a place too small for the review would pass.
     *
     * @param width    the width the place gives the review
     * @param height   the height the place gives the review
     * @param styledAs the scene whose stylesheets the review will be shown with, or null
     */
    boolean fitsIn(double width, double height, javafx.scene.Scene styledAs) {
        if (state == null || treeColumn == null) {
            return false;
        }
        javafx.geometry.Dimension2D tree = DecisionTreeBlockRenderer.wholeSize(treeColumn, styledAs);
        if (tree == null) {
            return false;
        }
        double valuesWidth = base * VALUES_EMS;
        double treeWidth = tree.getWidth() + 2 * TREE_PAD;
        if (width < valuesWidth + treeWidth) {
            return false;
        }
        // wide enough: the heights are read with the review laid out at the place's size, in a scene of its own
        javafx.scene.Scene measuring = null;
        if (getScene() == null && getParent() == null) {
            measuring = new javafx.scene.Scene(this, width, height);
            if (styledAs != null) {
                measuring.getStylesheets().setAll(styledAs.getStylesheets());
            }
            applyCss();
            layout();
        }
        try {
            double header = getTop() == null ? 0 : getTop().prefHeight(width);
            double treeHeight = treeColumn.prefHeight(tree.getWidth());
            double valuesHeight = valuesTable == null ? 0 : valuesTable.prefHeight(valuesWidth);
            return height >= header + Math.max(treeHeight, valuesHeight) + 2 * TREE_PAD;
        } finally {
            if (measuring != null) {
                measuring.setRoot(new javafx.scene.Group());             // the review is free to be shown where it opens
            }
        }
    }

    /** Moves a value, as its slider or its field does. */
    void move(int vertex, double value) {
        if (state != null && state.move(vertex, value)) {
            redrawSoon();
        }
    }

    /** Resets one value to the document's, as a click on its open circle does. */
    void reset(int vertex) {
        if (state != null && state.reset(vertex)) {
            redrawSoon();
        }
    }

    /** Resets every value to the document's. */
    void resetAll() {
        if (state != null && state.resetAll()) {
            redrawSoon();
        }
    }

    /** Selects a value, as a click on its row does: its row and its bar are marked, and the measures of the tree that read it. */
    void selectValue(int vertex) {
        if (state == null || state.value(vertex).isEmpty() || Integer.valueOf(vertex).equals(selectedValue)) {
            return;
        }
        selectedValue = vertex;
        selectedNode = null;
        showSelection();
    }

    /** Selects a node or a branch of the tree, as a click on it does: it is marked, and the rows of the values it reads. */
    void selectNode(int vertex) {
        if (state == null || Integer.valueOf(vertex).equals(selectedNode)) {
            return;
        }
        selectedNode = vertex;
        selectedValue = null;
        showSelection();
    }

    /** The rows, the tree and the statements, from what is selected. */
    private void showSelection() {
        List<Integer> read = selectedNode == null ? List.of() : state.valuesAt(selectedNode);
        rows.forEach((vertex, row) -> row.mark(vertex.equals(selectedValue), read.contains(vertex)));
        if (treeColumn != null && treeColumn.getProperties().get(DecisionTreeBlockRenderer.MARKS) instanceof DecisionTreeBlockRenderer.Marks marks) {
            marks.show(selectedValue == null ? List.of() : state.readers(selectedValue), selectedNode == null ? -1 : selectedNode);
        }
        statements.getChildren().clear();
        Label heading = new Label("Statement");
        heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.95));
        Label about = new Label();
        about.setId("model-review-statement-about");
        about.setFont(Font.font(base * 0.85));
        about.setTextFill(QUIET);
        about.setWrapText(true);
        statements.getChildren().addAll(heading, about);
        if (selectedValue != null) {
            Optional<String> name = state.read().parameterStatement(selectedValue);
            if (name.isEmpty()) {
                about.setText("This value is written in the model itself, not in a statement.");
            } else {
                about.setText("This value comes from the statement " + name.get() + ".");
                statement(name.get());
            }
        } else if (selectedNode != null) {
            List<String> names = state.read().documentStatements(selectedNode);
            String label = "“" + tree.node(selectedNode).flatMap(node -> node.concept().isPresent()
                    ? Optional.of(shown.label(node.concept().getAsLong())) : Optional.<String>empty()).orElse("This part of the tree") + "”";
            if (names.isEmpty()) {
                about.setText(label + " has no statement.");
            } else {
                about.setText(label + (names.size() == 1 ? " has this statement." : " has these statements."));
                names.forEach(this::statement);
            }
        } else {
            about.setText("Click a value, or a part of the tree, to see its statement.");
        }
    }

    /** One statement of the document, by the name its anf block gives it, as Komet's own statement card shows it, read-only. */
    private void statement(String name) {
        Optional<network.ike.komet.claude.anf.AnfStatement> stated = review.statement(name);
        if (stated.isEmpty()) {
            Label none = new Label("The document has no statement named " + name + " yet.");
            none.setFont(Font.font(base * 0.85));
            none.setTextFill(Color.web("#8a5a00"));
            none.setWrapText(true);
            statements.getChildren().add(none);
            return;
        }
        Node card = network.ike.komet.claude.anf.AnfStatementView.render(stated.get(), viewProperties, null);
        card.getStyleClass().add("model-review-statement");
        statements.getChildren().add(card);
    }

    /** The value selected, or null; for a test. */
    Integer selectedValue() {
        return selectedValue;
    }

    /** The node or branch of the tree selected, or null; for a test. */
    Integer selectedNode() {
        return selectedNode;
    }

    /** The rows marked as read by the selected node, by their values' vertices; for a test. */
    List<Integer> rowsRead() {
        return rows.entrySet().stream().filter(row -> row.getValue().read).map(Map.Entry::getKey).toList();
    }

    /** How many statement cards are shown, and what the line above them reads; for a test. */
    int statementCards() {
        return (int) statements.getChildren().stream().filter(node -> node.getStyleClass().contains("model-review-statement")).count();
    }

    String statementAbout() {
        return statements.lookup("#model-review-statement-about") instanceof Label about ? about.getText() : "";
    }

    /** The copy and what reads what in it; for a test. */
    ModelReview state() {
        return state;
    }

    /** The review's values, in the order of its rows; for a test. */
    List<ModelReview.Value> values() {
        return state == null ? List.of() : state.values();
    }

    /** The sweep the bars are drawn from; for a test. */
    Optional<DecisionTreeSensitivity> drawnSweep() {
        return Optional.ofNullable(drawn);
    }

    /** A value's field as it reads; for a test. */
    String fieldText(int vertex) {
        return rows.get(vertex).field.getText();
    }

    /** Where a value's slider stands; for a test. */
    double sliderValue(int vertex) {
        return rows.get(vertex).slider.getValue();
    }

    /** Whether a value's slider has the open circle at the document's value; for a test. */
    boolean documentMarkShown(int vertex) {
        return rows.get(vertex).marks.documentShown();
    }

    /** How many marks a value's slider has where the choice changes; for a test. */
    int thresholdsShown(int vertex) {
        return rows.get(vertex).marks.thresholdsShown();
    }

    /** The least and greatest amount a value's bar spans, and where the values stand along it; for a test. */
    double[] barSpan(int vertex) {
        return rows.get(vertex).bar.span();
    }

    /** What the bars' axis is about; for a test. */
    String axisTitle() {
        return barsAbout;
    }

    /** The header's two sentences: how many changes there are, and what can be done with them; for a test. */
    String lineText() {
        return changed.getText() + ". " + note.getText();
    }

    /** Whether the tree is beside the values at the review's present width, and not above them. */
    boolean wide() {
        return parts.wide;
    }

    // ── The copy, published to the review's own drawings ────────────────────

    /** The copy as a model of the review's context, so the tree drawn over that context draws it. */
    private void publish() {
        GraphletModel written = document.model(modelId).orElseThrow();
        // a value edited on a node of the tree is the copy's: the tree hands its restated text here, not to the document
        review.defineModel(new GraphletModel(modelId, written.info(), state.body(), Optional.of(this::taken),
                Optional.of(this::taken)), 1);
    }

    private void taken(String restated) {
        if (state.take(restated)) {
            redrawSoon();
        }
    }

    /** Asks for one redraw on the next pulse, however many movements arrive before it. */
    private void redrawSoon() {
        if (pending) {
            return;
        }
        pending = true;
        Platform.runLater(this::redraw);
    }

    /** Every part redrawn from the copy: the tree and the fields at once, the bars when their sweep returns. */
    void redraw() {
        pending = false;
        publish();
        redrawLine();
        rows.values().forEach(ValueRow::showValue);
        String text = state.body();
        int mine = ++generation;
        TornadoBlockRenderer.sweepExecutor.execute(() -> {
            if (mine != generation) {
                return;
            }
            Optional<DecisionTreeSensitivity> swept;
            try {
                swept = state.sweep(text, BlockSweep.DEFAULT_STEPS);
            } catch (RuntimeException e) {
                swept = Optional.empty();
            }
            Optional<DecisionTreeSensitivity> landed = swept;
            Platform.runLater(() -> {
                if (mine == generation) {
                    landed.ifPresent(this::drawBars);
                }
            });
        });
    }

    // ── The line at the top ─────────────────────────────────────────────────

    private Node line(DecisionTree tree) {
        Label question = new Label(tree.root().map(root -> shown.label(root.concept().orElse(0L))).orElse(modelId));
        question.setId("model-review-question");
        question.setFont(Font.font(null, FontWeight.BOLD, base * 1.15));
        question.setWrapText(true);

        changed.setId("model-review-changed");
        changed.setFont(Font.font(null, FontWeight.BOLD, base * 0.95));
        note.setId("model-review-note");
        note.setFont(Font.font(base * 0.85));
        note.setTextFill(QUIET);
        reset.setId("model-review-reset");
        reset.setMinWidth(Region.USE_PREF_SIZE);
        reset.setFont(Font.font(base * 0.9));
        reset.setTooltip(new Tooltip("Set every value back to the value in the document"));
        reset.setOnAction(e -> resetAll());
        save.setId("model-review-save");
        save.setMinWidth(Region.USE_PREF_SIZE);
        save.setFont(Font.font(base * 0.9));
        save.setTooltip(new Tooltip("Save the changed values in the document, as one edit you can commit or discard"));
        save.setOnAction(e -> save());
        // under the question and from the left: a journal window keeps a button of its own in its top right corner
        FlowPane acts = new FlowPane(10, 4, changed, note, reset, save);
        acts.setAlignment(Pos.CENTER_LEFT);
        VBox line = new VBox(4, question, acts);
        line.setId("model-review-line");
        line.setPadding(new Insets(8, 12, 8, 12));
        line.setStyle("-fx-border-color: transparent transparent #d8d0e4 transparent; -fx-border-width: 0 0 1 0;");
        return line;
    }

    private void redrawLine() {
        int n = state.changed();
        boolean writable = writable();
        if (!writable) {
            changed.setText(n == 0 ? "Read-only" : n == 1 ? "1 change" : n + " changes");
            changed.setTextFill(n == 0 ? QUIET : CHANGED);
            note.setText("This document can't be edited here, so changes can't be saved.");
        } else if (n == 0) {
            changed.setText("No changes");
            changed.setTextFill(QUIET);
            note.setText("Move a slider to see how the choice changes.");
        } else {
            changed.setText(n == 1 ? "1 unsaved change" : n + " unsaved changes");
            changed.setTextFill(CHANGED);
            note.setText("You will be asked to save when you close.");
        }
        reset.setDisable(n == 0);
        save.setDisable(n == 0 || !writable);
    }

    // ── The values, with the tornado ────────────────────────────────────────

    private VBox values(DecisionTree tree) {
        Label title = new Label("Values");
        title.setFont(Font.font(null, FontWeight.BOLD, base * 0.95));
        axisTitle.setId("model-review-axis");
        axisTitle.setFont(Font.font(base * 0.78));
        axisTitle.setTextFill(QUIET);
        axisTitle.setWrapText(true);
        VBox table = new VBox(2, title, axisTitle);
        table.setPadding(new Insets(8, 12, 8, 12));
        table.setFillWidth(true);
        for (ModelReview.Value value : state.values()) {
            ValueRow row = new ValueRow(value.vertex(), SweptQuantity.name(tree, shown, value.vertex()));
            rows.put(value.vertex(), row);
            table.getChildren().add(row);
        }
        if (rows.isEmpty()) {
            Label none = new Label("Nothing can be changed until the model can be calculated.");
            none.setId("model-review-no-values");
            none.setFont(Font.font(base * 0.9));
            none.setTextFill(Color.web("#8a5a00"));
            none.setWrapText(true);
            table.getChildren().add(none);
        }
        Label legend = new Label("On a slider, the open circle is the value in the document. Click it to go back to that value. "
                + "The purple line is where the choice changes.");
        legend.setFont(Font.font(base * 0.78));
        legend.setTextFill(QUIET);
        legend.setWrapText(true);
        legend.setPadding(new Insets(6, 0, 0, 0));
        table.getChildren().add(legend);
        statements.setId("model-review-statements");
        statements.setPadding(new Insets(10, 0, 0, 0));
        statements.setStyle("-fx-border-color: #d8d0e4 transparent transparent transparent; -fx-border-width: 1 0 0 0;");
        VBox.setMargin(statements, new Insets(8, 0, 0, 0));
        table.getChildren().add(statements);
        return table;
    }

    /** The bars, the marks where the choice changes, and the axis they share, from a sweep of the copy. */
    private void drawBars(DecisionTreeSensitivity sweep) {
        drawn = sweep;
        boolean net = sweep.netValued();
        double least = net ? Math.min(0, sweep.baseNet()) : sweep.baseRoot();
        double greatest = net ? Math.max(0, sweep.baseNet()) : sweep.baseRoot();
        for (int vertex : rows.keySet()) {
            Optional<DecisionTreeSensitivity.Swing> swing = sweep.swing(vertex);
            if (swing.isPresent()) {
                least = Math.min(least, net ? swing.get().leastNet() : swing.get().least());
                greatest = Math.max(greatest, net ? swing.get().greatestNet() : swing.get().greatest());
            }
        }
        double pad = (greatest - least) * 0.04;
        double from = least - pad;
        double to = greatest + pad;
        barsAbout = net && sweep.reference().isPresent()
                ? "Net value of “" + alternativeName(sweep.tree(), sweep.reference().getAsInt()) + "”"
                : "Expected cost";
        axisTitle.setText(net
                ? "Each bar shows how much better or worse “" + alternativeName(sweep.tree(), sweep.reference().orElse(-1))
                + "” is than the next best alternative, as that value goes from the low end of its range to the high end."
                + " Blue is better, amber is worse, and the dashed line is break-even."
                : "Each bar shows the expected cost as that value goes from the low end of its range to the high end.");
        for (ValueRow row : rows.values()) {
            row.showSweep(sweep, net, from, to);
        }
    }

    private String alternativeName(DecisionTree tree, int alternativeVertex) {
        return tree.node(alternativeVertex).flatMap(n -> n.concept().isPresent()
                ? Optional.of(shown.label(n.concept().getAsLong())) : Optional.<String>empty()).orElse("an alternative");
    }

    private String moneyUnit(DecisionTree tree) {
        ImmutableList<DecisionElement.MoneyCost> costs = tree.nodesOfType(DecisionElement.MoneyCost.class);
        long semantic = costs.isEmpty() ? terms.moneySemantic() : costs.get(0).measure().semanticNid();
        String label = semantic == 0 ? null : ids.label(semantic);
        return label == null ? "" : label;
    }

    private String money(double amount) {
        if (Double.isNaN(amount)) {
            return "?";
        }
        return (amount < 0 ? "−" : "") + moneySymbol + DecisionTreeBlockRenderer.money(Bounds.point(Math.abs(amount)));
    }

    /** One value: its name, its slider with the marks on it, its field, and its bar. */
    private final class ValueRow extends VBox {
        final int vertex;
        final Slider slider;
        final TextField field = new TextField();
        final Marks marks = new Marks();
        final Bar bar = new Bar();
        final Label low = new Label();
        final Label high = new Label();
        /** Whether the slider is being set from the copy, and not by the hand. */
        boolean setting;
        /** Whether the node selected on the tree reads this value. */
        boolean read;
        /** How the value is printed: as a share, as money, or with its unit's name. */
        SweptQuantity quantity = new SweptQuantity(DecisionFigureText.Unit.PLAIN, "");

        ValueRow(int vertex, String name) {
            super(1);
            this.vertex = vertex;
            setId("model-review-value-" + vertex);
            setPadding(new Insets(5, 4, 5, 4));
            ModelReview.Value value = state.value(vertex).orElseThrow();
            state.opened().ifPresent(sweep -> quantity = SweptQuantity.of(sweep, shown, vertex, moneySymbol));

            Label title = new Label(name);
            title.setId("model-review-name-" + vertex);
            title.setFont(Font.font(base * 0.92));
            title.setWrapText(true);
            state.read().parameterStatement(vertex).ifPresent(statement -> {
                Tooltip reads = new Tooltip("From the statement " + statement);
                title.setTooltip(reads);
            });

            slider = new Slider(value.range().lower(), value.range().upper(), clamp(value.now(), value.range()));
            slider.setId("model-review-slider-" + vertex);
            slider.setMinWidth(base * 9);
            slider.valueProperty().addListener((obs, was, now) -> {
                if (!setting) {
                    selectValue(vertex);                          // the value under the hand is the one selected
                    move(vertex, snapped(now.doubleValue()));
                }
            });
            setOnMouseClicked(e -> selectValue(vertex));
            Pane sliderHost = new Pane(slider, marks) {
                @Override
                protected void layoutChildren() {
                    double w = getWidth();
                    slider.resizeRelocate(0, 0, w, slider.prefHeight(w));
                    marks.resizeRelocate(0, 0, w, slider.prefHeight(w));
                }

                @Override
                protected double computePrefHeight(double width) {
                    return slider.prefHeight(width);
                }
            };
            sliderHost.setMinWidth(base * 9);
            low.setFont(Font.font(base * 0.72));
            low.setTextFill(QUIET);
            high.setFont(Font.font(base * 0.72));
            high.setTextFill(QUIET);
            Region between = new Region();
            HBox.setHgrow(between, Priority.ALWAYS);
            HBox ends = new HBox(low, between, high);
            VBox track = new VBox(0, sliderHost, ends);
            HBox.setHgrow(track, Priority.ALWAYS);

            field.setId("model-review-field-" + vertex);
            field.setFont(Font.font(base * 0.88));
            field.setAlignment(Pos.CENTER_RIGHT);
            field.setPrefColumnCount(7);
            field.setMinWidth(Region.USE_PREF_SIZE);
            field.setOnAction(e -> typed());
            field.focusedProperty().addListener((obs, was, focused) -> {
                if (focused) {
                    selectValue(vertex);
                } else {
                    typed();
                }
            });

            bar.setId("model-review-bar-" + vertex);
            bar.setMinWidth(base * 8);
            bar.setPrefWidth(base * 11);
            bar.setMaxWidth(base * 11);
            HBox controls = new HBox(8, track, field, bar);
            controls.setAlignment(Pos.TOP_LEFT);
            getChildren().addAll(title, controls);
            low.setText(quantity.print(value.range().lower()));
            high.setText(quantity.print(value.range().upper()));
            showValue();
        }

        /** Marks the row as the value selected, or as a value the node selected on the tree reads. */
        void mark(boolean selected, boolean readByNode) {
            read = readByNode;
            setStyle(selected ? "-fx-background-color: #e6eef8; -fx-background-radius: 5;"
                    : readByNode ? "-fx-background-color: #f3f6fb; -fx-background-radius: 5;"
                    + " -fx-border-color: transparent transparent transparent #9db3d6; -fx-border-width: 0 0 0 3;"
                    : "");
        }

        /** The value the pointer set, held to a tidy step of the slider's track, so 2% is not 2.0312%. */
        private double snapped(double raw) {
            double pixels = Math.max(1, slider.getWidth() - 16);
            double step = MeasureControl.niceStep((slider.getMax() - slider.getMin()) / pixels);
            double value = Math.round(raw / step) * step;
            value = Math.max(slider.getMin(), Math.min(slider.getMax(), value));
            try {
                return Double.parseDouble(DecisionFigureText.atStep(value, step).replace(",", ""));
            } catch (NumberFormatException e) {
                return value;
            }
        }

        /** The field read as a value: a share as a percentage, anything else as the number typed. */
        private void typed() {
            ModelReview.Value value = state.value(vertex).orElseThrow();
            if (field.getText().equals(quantity.print(value.now()))) {
                return;
            }
            Optional<Bounds> read = DecisionTreeEdits.typed(field.getText()).filter(Bounds::isPoint);
            if (read.isEmpty()) {
                field.setStyle("-fx-border-color: #c0392b;");
                return;
            }
            double number = read.get().lower();
            move(vertex, quantity.unit() == DecisionFigureText.Unit.SHARE ? number / 100 : number);
            showValue();
        }

        /** The slider, the field and the document's mark, from the copy's value. */
        void showValue() {
            ModelReview.Value value = state.value(vertex).orElseThrow();
            setting = true;
            try {
                slider.setValue(clamp(value.now(), value.range()));
            } finally {
                setting = false;
            }
            if (!field.isFocused() || !DecisionTreeEdits.typed(field.getText()).isPresent()) {
                field.setText(quantity.print(value.now()));
            }
            field.setStyle(value.changed() ? "-fx-border-color: #7b3fa0; -fx-border-radius: 3;" : "");
            marks.document(value.changed() ? OptionalDouble.of(value.document()) : OptionalDouble.empty(),
                    "In the document: " + quantity.print(value.document()) + ". Click to go back to it.");
        }

        /** The bar and the marks where the choice changes, from a sweep of the copy. */
        void showSweep(DecisionTreeSensitivity sweep, boolean net, double from, double to) {
            quantity = SweptQuantity.of(sweep, shown, vertex, moneySymbol);
            Optional<DecisionTreeSensitivity.Swing> swing = sweep.swing(vertex);
            List<Double> thresholds = ModelReview.thresholds(sweep, vertex);
            marks.thresholds(thresholds, thresholds.stream().map(at -> "The choice changes at " + quantity.print(at)).toList());
            if (swing.isEmpty()) {
                bar.clear("No bar: this value can't be varied.");
                return;
            }
            double atLow = net ? swing.get().netAtLow() : swing.get().rootAtLow();
            double atHigh = net ? swing.get().netAtHigh() : swing.get().rootAtHigh();
            double least = net ? swing.get().leastNet() : swing.get().least();
            double greatest = net ? swing.get().greatestNet() : swing.get().greatest();
            double here = net ? sweep.baseNet() : sweep.baseRoot();
            String words = barsAbout + ": " + money(atLow) + " at "
                    + quantity.print(swing.get().range().lower()) + ", " + money(atHigh) + " at "
                    + quantity.print(swing.get().range().upper()) + ", " + money(here) + " now.";
            bar.show(least, greatest, here, from, to, net, words);
        }

        private double clamp(double value, Bounds range) {
            return Math.max(range.lower(), Math.min(range.upper(), value));
        }

        /** The marks over a slider: an open circle at the document's value, a line where the choice changes. */
        private final class Marks extends Pane {
            private final Circle was = new Circle(3.8, Color.WHITE);
            private final Tooltip wasTip = new Tooltip();
            private double document = Double.NaN;
            private List<Double> changes = List.of();
            private List<String> changeWords = List.of();

            Marks() {
                setPickOnBounds(false);
                was.setStroke(Color.web("#8a7fa0"));
                was.setStrokeWidth(1.3);
                was.setCursor(Cursor.HAND);
                was.setId("model-review-document-mark-" + vertex);
                Tooltip.install(was, wasTip);
                was.setOnMouseClicked(e -> {
                    reset(vertex);
                    e.consume();
                });
            }

            void document(OptionalDouble value, String words) {
                document = value.orElse(Double.NaN);
                wasTip.setText(words);
                requestLayout();
            }

            void thresholds(List<Double> at, List<String> words) {
                changes = at;
                changeWords = words;
                requestLayout();
            }

            /** Where a value is along the slider's track: the thumb's centre travels from half a thumb in to half a thumb short. */
            private double x(double value) {
                Node thumb = slider.lookup(".thumb");
                double thumbWidth = thumb == null ? 14 : thumb.getLayoutBounds().getWidth();
                double start = slider.snappedLeftInset() + thumbWidth / 2;
                double length = getWidth() - slider.snappedLeftInset() - slider.snappedRightInset() - thumbWidth;
                double share = (value - slider.getMin()) / (slider.getMax() - slider.getMin());
                return start + Math.max(0, Math.min(1, share)) * length;
            }

            @Override
            protected void layoutChildren() {
                getChildren().clear();
                double mid = getHeight() / 2;
                for (int i = 0; i < changes.size(); i++) {
                    double at = changes.get(i);
                    if (at < slider.getMin() || at > slider.getMax()) {
                        continue;
                    }
                    Line change = new Line(x(at), mid - 8, x(at), mid + 8);
                    change.setStroke(CHANGED);
                    change.setStrokeWidth(2);
                    change.getStyleClass().add("model-review-threshold");
                    Tooltip.install(change, new Tooltip(changeWords.get(i)));
                    getChildren().add(change);
                }
                if (!Double.isNaN(document)) {
                    was.setCenterX(x(document));
                    was.setCenterY(mid);
                    getChildren().add(was);
                }
            }

            /** How many marks stand where the choice changes; for a test. */
            int thresholdsShown() {
                return (int) changes.stream().filter(at -> at >= slider.getMin() && at <= slider.getMax()).count();
            }

            boolean documentShown() {
                return !Double.isNaN(document);
            }
        }

        /** A value's bar on the axis the rows share: how far the result moves as the value crosses its range. */
        private final class Bar extends Region {
            private double least = Double.NaN;
            private double greatest;
            private double here;
            private double from;
            private double to;
            private boolean net;
            private final Tooltip tip = new Tooltip();

            Bar() {
                Tooltip.install(this, tip);
                setPrefHeight(base * 1.5);
            }

            void clear(String words) {
                least = Double.NaN;
                tip.setText(words);
                requestLayout();
            }

            void show(double least, double greatest, double here, double from, double to, boolean net, String words) {
                this.least = least;
                this.greatest = greatest;
                this.here = here;
                this.from = from;
                this.to = to;
                this.net = net;
                tip.setText(words);
                requestLayout();
            }

            private double x(double amount) {
                return to == from ? 0 : (amount - from) / (to - from) * getWidth();
            }

            @Override
            protected void layoutChildren() {
                getChildren().clear();
                if (Double.isNaN(least)) {
                    return;
                }
                double h = getHeight();
                double top = h / 2 - base * 0.42;
                double thick = base * 0.84;
                if (net) {
                    // the part where the alternative the bars are about is behind, and the part where it is ahead
                    if (least < 0) {
                        Rectangle behind = new Rectangle(x(least), top, Math.max(1, x(Math.min(0, greatest)) - x(least)), thick);
                        behind.setFill(BEHIND);
                        getChildren().add(behind);
                    }
                    if (greatest > 0) {
                        double start = x(Math.max(0, least));
                        Rectangle ahead = new Rectangle(start, top, Math.max(1, x(greatest) - start), thick);
                        ahead.setFill(AHEAD);
                        getChildren().add(ahead);
                    }
                    Line even = new Line(x(0), 0, x(0), h);
                    even.setStroke(QUIET);
                    even.getStrokeDashArray().addAll(2.0, 2.0);
                    getChildren().add(even);
                } else {
                    Rectangle swing = new Rectangle(x(least), top, Math.max(1, x(greatest) - x(least)), thick);
                    swing.setFill(AHEAD);
                    getChildren().add(swing);
                }
                Line now = new Line(x(here), top - 3, x(here), top + thick + 3);
                now.setStroke(Color.web("#282040"));
                now.setStrokeWidth(1.8);
                getChildren().add(now);
            }

            /** The amounts the bar spans, and where the values stand along it; for a test. */
            double[] span() {
                return new double[] {least, greatest, here};
            }
        }
    }

    // ── The tree ────────────────────────────────────────────────────────────

    /** The tree block's own drawing, over the review's context, so it draws the copy and redraws when the copy moves. */
    private Node tree(ViewCalculator viewCalc, ViewProperties viewProperties, String info) {
        DecisionTreeBlockRenderer renderer = new DecisionTreeBlockRenderer(viewCalc, viewProperties, base, review);
        renderer.setReviewing(true);
        List<BlockPiece> pieces = renderer.render(info, "", null);
        if (pieces.isEmpty()) {
            Label none = new Label("The tree of this model can't be drawn here.");
            none.setFont(Font.font(base * 0.9));
            return none;
        }
        return pieces.getFirst().node().get();
    }

    private static ScrollPane scroll(Region content, String id) {
        ScrollPane scroll = new ScrollPane(content);
        scroll.setId(id);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: white;");
        return scroll;
    }

    /**
     * The two parts of the review: the tree beside the values where there is room for both, and
     * above them where there is not, the tree staying in place while the values scroll beneath it.
     */
    private final class Parts extends Region {
        private Region values;
        private Region tree;
        boolean wide = true;

        void set(Region values, Region tree) {
            this.values = values;
            this.tree = tree;
            getChildren().setAll(values, tree);
        }

        @Override
        protected void layoutChildren() {
            if (values == null) {
                return;
            }
            double w = getWidth();
            double h = getHeight();
            wide = w >= base * WIDE_EMS;
            if (wide) {
                double valuesWidth = Math.min(base * VALUES_EMS, w * 0.46);
                values.resizeRelocate(0, 0, valuesWidth, h);
                tree.resizeRelocate(valuesWidth, 0, w - valuesWidth, h);
            } else {
                double treeHeight = Math.min(h * 0.55, Math.max(base * 12, tree.prefHeight(w)));
                tree.resizeRelocate(0, 0, w, treeHeight);
                values.resizeRelocate(0, treeHeight, w, Math.max(0, h - treeHeight));
            }
        }

        @Override
        protected double computePrefWidth(double height) {
            return base * (WIDE_EMS + 8);
        }

        @Override
        protected double computePrefHeight(double width) {
            return base * 44;
        }
    }
}
