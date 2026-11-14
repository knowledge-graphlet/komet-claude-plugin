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


import network.ike.komet.document.BlockRewriter;

import dev.ikm.komet.framework.controls.KonceptLabelTypography;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.Derivations;
import network.ike.komet.claude.decision.GraphletModel;
import network.ike.komet.claude.decision.GraphletModelText;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.ModelValues;
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.document.DecisionFigureText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Consumer;

/**
 * The live rendering of a {@code graphlet-model} block ({@code dev-decision-point-knowledge},
 * settled with KEC 2026-11-06): the model of a decision, structure and parameters together, as
 * one versioned property graph. It publishes the model on the document's {@link ProfileBlocks},
 * as an {@code anf} block publishes its statement, so the renderings that name it draw it and
 * redraw when it moves.
 *
 * <p>The model is the model of model, view and controller, so a reader meets it as one line
 * (KEC, 2026-11-13): a mark, how many values stand behind the decision, and the Expand button
 * that opens the model's review. The line opens in place to the values, each under what the tree
 * reads it for ({@link ModelValues}): the chances of a chance node's events, the costs, the
 * counts. A value worked out from others stands above them; a chance has a bar for its range on
 * one scale from nothing to the whole; a name has no outline. Pointing at a value marks the
 * values it goes with and, on the trees that draw the model, the branches that read it; a node
 * picked on such a tree marks the values it reads. The structure is the tree rendering's to draw.
 *
 * <p>A value edited here, or set in a rendering by its slider or a click on its bar, restates the one
 * line of the block that holds it, as an ordinary measure with equal bounds, and hands the text to
 * the surface's {@link BlockRewriter}: a committed value is a new version of the model. A surface
 * without a rewriter shows the model read-only.
 */
final class GraphletModelBlockRenderer implements network.ike.komet.document.StructuredBlockKind.FenceRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final ProfileBlocks context;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
    private BlockRewriter rewriter;

    /** The width of a chance's bar, in points: nothing at its left end, the whole at its right. */
    private static final double BAR = 64;
    /** The band behind a value's row, and the band of a row that is marked. */
    private static final String BAND = "-fx-background-color: transparent; -fx-background-radius: 4;";
    private static final String BAND_MARKED = "-fx-background-color: #e6eef8; -fx-background-radius: 4;";

    /** Gives each block its order among the blocks of every document. */
    private static final java.util.concurrent.atomic.AtomicLong ORDERS = new java.util.concurrent.atomic.AtomicLong();

    GraphletModelBlockRenderer(ViewProperties viewProperties, double base, ProfileBlocks context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    GraphletModelBlockRenderer(ViewCalculator viewCalc, double base, ProfileBlocks context) {
        this(viewCalc, null, base, context);
    }

    GraphletModelBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base, ProfileBlocks context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new ProfileBlocks() : context;
    }

    public void setChipTypography(KonceptLabelTypography typography) {
        this.typography = typography == null ? KonceptLabelTypography.DEFAULT : typography;
    }

    /** Where the model's text goes when a value is committed; null shows the model read-only. */
    public void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    /** Whether a block is this kind's: tagged {@code graphlet-model}, opening with a root decision or chance line. */
    static boolean accepts(String info, String literal) {
        if (!GraphletModelText.isTagged(info)) {
            return false;
        }
        BlockLines.Parsed lines = BlockLines.parse(literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && ("decision".equals(lines.roots().get(0).word()) || "chance".equals(lines.roots().get(0).word()));
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !accepts(info, literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        // published at once, so a rendering drawn before this block is built finds the model
        Optional<String> id = GraphletModelText.modelId(info, body);
        // a model with an id grows into its review, with the tornado and the tree that draw it
        return List.of(new BlockPiece(() -> {
            VBox column = new Live(info, body, id.orElse(null)).column;
            return id.isPresent() ? ModelReviewGrow.grown(column, viewCalc, viewProperties, base, context, id.get(), null) : column;
        }, body.stripTrailing()));
    }

    /**
     * Puts a model on the context at once, for the renderings that read it: a block's column is
     * built only when it is drawn, and a page never builds one.
     */
    static void publish(String info, String body, ProfileBlocks context, Consumer<String> writer) {
        GraphletModelText.modelId(info, body).ifPresent(id ->
                context.defineModel(new GraphletModel(id, info, body, Optional.ofNullable(writer)), ORDERS.incrementAndGet()));
    }

    /** One rendering of the block: the model's text, published, and the column that shows it. */
    private final class Live {
        final String info;
        /** The text the surface holds, which a rewrite restates. */
        String written;
        String body;
        final String id;
        final VBox column = new VBox(5);
        final DecisionTerms terms = DecisionTerms.fromBindings();
        final Identities ids = context.identities(viewCalc);
        Identities shown = ids;
        final long order = ORDERS.incrementAndGet();
        final Runnable onContext = this::onContext;
        DecisionTreeText.Parsed parsed;
        DecisionTree tree;
        /** The values under what the tree reads them for, and what each goes with. */
        ModelValues values;
        /** By value: the band behind its row, which a mark fills. */
        final Map<Integer, Region> bands = new HashMap<>();
        /** A model with no id has no state in the document's context: whether its values are shown is kept here. */
        boolean openHere;
        /** Told of a node picked on a tree that draws the model; kept here, since the context holds it weakly. */
        final java.util.function.IntConsumer onPick = this::picked;

        Live(String info, String body, String id) {
            this.info = info;
            this.written = body;
            this.body = body;
            this.id = id;
            column.getStyleClass().add("graphlet-model");
            column.setCursor(Cursor.DEFAULT);
            column.setFillWidth(true);
            column.setMaxWidth(Double.MAX_VALUE);
            column.parentProperty().addListener((obs, oldParent, newParent) -> {
                column.maxWidthProperty().unbind();
                if (newParent instanceof Region host) {
                    column.maxWidthProperty().bind(host.widthProperty());
                }
            });
            context.listen(onContext);
            if (id != null) {
                context.links(id).onPick(onPick);
            }
            rebuild();
        }

        /** A statement a parameter reads landed, or a quantity another block computes: the model reads again. */
        private void onContext() {
            // told while publishing itself, or before it has read: nothing to read again yet
            if (rebuilding || parsed == null || tree == null) {
                return;
            }
            if (!parsed.missingStatements().isEmpty()
                    || tree.nodesOfType(Parameter.class).anySatisfy(p -> parsed.lineOf(p.vertexIndex()).isEmpty())) {
                rebuild();
            }
        }

        /** Whether a rebuild is running: the context tells this block of its own publication, which is not news. */
        private boolean rebuilding;

        /** The model takes a restated text: published again, drawn again, and written to the document. */
        private void take(String restated) {
            take(restated, true);
        }

        /** The model reads at a restated text, published and drawn again, which the document does not hold yet. */
        private void hold(String restated) {
            take(restated, false);
        }

        /**
         * @param save whether the document is written: a text held before is written by a later
         *             take of the same text, which changes nothing that is drawn
         */
        private void take(String restated, boolean save) {
            if (!restated.equals(body)) {
                body = restated;
                rebuild();
            }
            if (save && rewriter != null && !body.equals(written)) {
                String was = written;
                written = body;
                rewriter.rewrite(info, was, info, body);
            }
        }

        void rebuild() {
            rebuilding = true;
            try {
                rebuildNow();
            } finally {
                rebuilding = false;
            }
        }

        private void rebuildNow() {
            StatementMeasures statements = context.statementMeasures(ids, terms);
            parsed = DecisionTreeText.parse(body, terms, ids, context.externals(), statements);
            shown = parsed.labelled(ids);
            tree = parsed.tree().orElse(null);
            List<ProfileFinding> findings = new ArrayList<>(parsed.findings().castToList());
            column.getChildren().clear();
            if (id != null) {
                context.defineModel(new GraphletModel(id, info, body, rewriter == null ? Optional.empty() : Optional.of(this::take),
                        rewriter == null ? Optional.empty() : Optional.of(this::hold)), order);
            }
            if (tree == null) {
                column.getChildren().add(findingsOnly(findings));
                return;
            }
            java.util.Set<Integer> waiting = parsed.waiting();
            tree.check().forEach(finding -> {
                if (!waiting.contains(finding.vertexIndex())) {
                    findings.add(finding);
                }
            });
            // known to the document by its root too, so a model with no rendering can still be folded in by another tree
            network.ike.komet.claude.decision.LinkedTrees.rootIdentifier(tree, ids)
                    .ifPresent(root -> context.defineTree(root, body, column, order));
            values = ModelValues.of(tree, shown::label);
            bands.clear();
            column.getChildren().add(line());
            if (open() && values.count() > 0) {
                column.getChildren().add(table(Derivations.of(tree)));
            }
            List<String> messages = new ArrayList<>();
            if (id == null) {
                messages.add("The model names itself with id= in its attribute line, or by its root's @id; without one no rendering can name it.");
            }
            for (String name : parsed.missingStatements()) {
                messages.add("anf:" + name + ": no anf block of the document states it yet, so its parameter has no value");
            }
            findings.stream().filter(finding -> !DecisionTreeFeedback.isLocalSymbolsNote(finding))
                    .forEach(finding -> messages.add(DecisionTreeFeedback.describe(tree, shown, finding)));
            if (!messages.isEmpty()) {
                Label gaps = new Label(String.join("\n", messages));
                gaps.setFont(Font.font(base * 0.9));
                gaps.setWrapText(true);
                gaps.setStyle("-fx-text-fill: #8a5a00;");
                column.getChildren().add(gaps);
            }
        }

        /** Whether the block shows its values. */
        private boolean open() {
            return id != null ? context.valuesOpen(id) : openHere;
        }

        /** Shows or hides the values, as a click on the line does. */
        private void toggle() {
            boolean now = !open();
            if (id != null) {
                context.setValuesOpen(id, now);
                if (!now) {
                    context.links(id).mark(List.of());
                }
            } else {
                openHere = now;
            }
            rebuild();
        }

        /** The block's one line: the model's mark, and how many values stand behind the decision, which a click shows. */
        private Node line() {
            HBox line = new HBox(8);
            line.setId("graphlet-model-line");
            line.setAlignment(Pos.CENTER_LEFT);
            if (id != null) {
                ExpandButton.leaveRoom(line);                           // a model with an id has the Expand button
            }
            line.getChildren().add(mark());
            int count = values.count();
            String behind = " behind this " + (tree.root().filter(DecisionElement.Decision.class::isInstance).isPresent() ? "decision" : "tree");
            if (count == 0) {
                Label none = new Label("No values" + behind);
                none.setId("graphlet-model-values");
                none.setFont(Font.font(base * 0.95));
                line.getChildren().add(none);
                return line;
            }
            boolean open = open();
            Polygon arrow = open ? new Polygon(0, 0, 8, 0, 4, 6) : new Polygon(0, 0, 6, 4, 0, 8);
            arrow.setFill(Color.web("#555555"));
            Button show = new Button(count(count, "value") + behind, arrow);
            show.setId("graphlet-model-values");
            show.setFont(Font.font(base * 0.95));
            show.setGraphicTextGap(6);
            show.setPadding(new Insets(1, 4, 1, 2));
            show.setStyle("-fx-background-color: transparent; -fx-text-fill: #333; -fx-cursor: hand;");
            show.setTooltip(new Tooltip(open ? "Hide the values" : "Show the values"));
            show.setOnAction(e -> toggle());
            line.getChildren().add(show);
            return line;
        }

        /** The model's mark: three settings, each a rail with its knob. Resting on it names the model and its structure. */
        private Node mark() {
            Group settings = new Group();
            double[] knobs = {9, 4, 11};
            for (int i = 0; i < knobs.length; i++) {
                double y = 2 + i * 4.5;
                Line rail = new Line(0, y, 14, y);
                rail.setStroke(Color.web("#666666"));
                rail.setStrokeWidth(1.2);
                Circle knob = new Circle(knobs[i], y, 1.9, Color.WHITE);
                knob.setStroke(Color.web("#666666"));
                knob.setStrokeWidth(1.2);
                settings.getChildren().addAll(rail, knob);
            }
            Label mark = new Label(null, settings);
            mark.setId("graphlet-model-mark");
            mark.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            String named = tree.root().map(root -> root.concept().isPresent() ? shown.label(root.concept().getAsLong()) : null).orElse(null);
            Tooltip tip = new Tooltip((id == null ? "A model with no id" : "Model " + id) + (named == null ? "" : ": " + named)
                    + "\n" + structure());
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            mark.setTooltip(tip);
            return mark;
        }

        /** The structure in a sentence: how many of each part, and which renderings of the document draw it. */
        private String structure() {
            int decisions = tree.nodesOfType(DecisionElement.Decision.class).size();
            int alternatives = tree.nodesOfType(DecisionElement.Alternative.class).size();
            int chances = tree.nodesOfType(DecisionElement.Chance.class).size();
            int events = tree.nodesOfType(DecisionElement.Event.class).size();
            int outcomes = tree.nodesOfType(DecisionElement.Outcome.class).size();
            return count(decisions, "decision") + ", " + count(alternatives, "alternative") + ", " + count(chances, "chance")
                    + ", " + count(events, "event") + ", " + count(outcomes, "outcome")
                    + "; drawn by the decision-tree and tornado blocks that name this model.";
        }

        private String count(int n, String word) {
            return n + " " + word + (n == 1 ? "" : "s");
        }

        /**
         * The model's values, each under what the tree reads it for: its name in plain words, a
         * bar for a chance's range, and the value the tree runs at, which a click edits.
         */
        private Node table(Derivations derivations) {
            GridPane grid = new GridPane();
            grid.setId("graphlet-model-parameters");
            grid.setHgap(10);
            grid.setVgap(1);
            grid.setPadding(new Insets(0, 0, 4, 22));
            grid.setMaxWidth(base * 46);
            ColumnConstraints names = new ColumnConstraints();
            names.setHgrow(Priority.ALWAYS);
            ColumnConstraints bars = new ColumnConstraints(BAR);
            ColumnConstraints amounts = new ColumnConstraints();
            amounts.setHalignment(HPos.RIGHT);
            amounts.setMinWidth(Region.USE_PREF_SIZE);
            grid.getColumnConstraints().addAll(names, bars, amounts);
            long money = moneySemantic();
            int at = 0;
            for (ModelValues.Group group : values.groups()) {
                Label heading = new Label(groupName(group));
                heading.getStyleClass().add("graphlet-model-group");
                heading.setFont(Font.font(base * 0.8));
                heading.setStyle("-fx-text-fill: #666;");
                heading.setWrapText(true);
                heading.setPadding(new Insets(at == 0 ? 0 : 6, 0, 1, 0));
                grid.add(heading, 0, at++, 3, 1);
                for (ModelValues.Row row : group.rows()) {
                    row(grid, at++, row, derivations, money);
                }
            }
            return grid;
        }

        /** What a group of values is read for, in a reader's words: a chance node's name, or the kind of cost. */
        private String groupName(ModelValues.Group group) {
            return switch (group.kind()) {
                case CHANCE -> group.concept().isPresent() ? shown.label(group.concept().getAsLong()) : "Chances";
                case COSTS -> "Costs";
                case HEALTH -> "Health";
                case COUNTS -> "Counts";
                case UNREAD -> "Read by no branch";
            };
        }

        /** One value's row: a band a mark fills, its name, its bar when it is a chance, and its value. */
        private void row(GridPane grid, int at, ModelValues.Row placed, Derivations derivations, long money) {
            int index = placed.vertex();
            Parameter parameter = (Parameter) tree.node(index).orElseThrow();
            Optional<String> statement = parsed.parameterStatement(index);
            Optional<Bounds> bounds = derivations.bounds(index);
            Measure measure = parameter.measure();
            boolean computed = measure.derived() || !measure.stated();
            boolean share = measure.semanticNid() == terms.shareAtNode()
                    && bounds.map(b -> b.lower() >= 0 && b.upper() <= 1).orElse(false);
            boolean isMoney = measure.semanticNid() != 0 && measure.semanticNid() == money;
            String symbol = DecisionFigureText.symbolFor(money == 0 ? "" : shown.label(money));
            List<Node> cells = new ArrayList<>();

            Region band = new Region();
            band.setId("model-row-" + index);
            band.setStyle(BAND);
            band.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            bands.put(index, band);
            grid.add(band, 0, at, 3, 1);
            cells.add(band);

            // the name in plain words, then what it is worked out from and what it cites
            Text name = new Text(parameter.quantity().isPresent() ? shown.label(parameter.quantity().getAsLong()) : statement.orElse("value"));
            name.setFont(Font.font(base * 0.95));
            name.setFill(Color.web("#222222"));
            TextFlow words = new TextFlow(name);
            words.setId("model-name-" + index);
            words.setPadding(new Insets(2, 0, 2, 4 + placed.depth() * base));
            List<Integer> from = values.inputs(index);
            if (!from.isEmpty()) {
                boolean below = values.beneath(index).size() == from.size();
                boolean product = measure.derivedFrom().isPresent() && measure.derivedFrom().getAsLong() == terms.product();
                Text how = new Text("  = " + (product ? (below ? "the values below" : "other values") + ", multiplied"
                        : "worked out from " + (below ? "the values below" : "other values")));
                how.setFont(Font.font(base * 0.8));
                how.setFill(Color.web("#666666"));
                words.getChildren().add(how);
            }
            // source=<id> on the line names a footnote or a bibliography entry the document defines; the block stores only the reference
            tree.conceptProperty(index, terms.basisSource()).ifPresent(nid -> {
                String cited = ids.identifier(nid).orElse(ids.label(nid));
                Text source = new Text("  " + cited);
                source.setId("model-source-" + index);
                source.setFont(Font.font(base * 0.8));
                source.setFill(Color.web("#1f5fa8"));
                Tooltip.install(source, new Tooltip("Cites " + cited + ", a footnote or bibliography entry the document defines"));
                words.getChildren().add(source);
            });
            statement.ifPresent(read -> {
                Tooltip tip = new Tooltip("Reads the statement anf:" + read
                        + (context.statement(read).isPresent() ? "; click to see it" : ", which no anf block of the document states yet"));
                tip.setWrapText(true);
                Tooltip.install(words, tip);
                words.setCursor(Cursor.HAND);
                words.setOnMouseClicked(e -> peek(words, read, e));
            });
            grid.add(words, 0, at);
            GridPane.setValignment(words, VPos.CENTER);
            cells.add(words);

            if (share) {
                Node bar = bar(index, bounds, statement.isPresent() && measure.stated(), isMoney, symbol);
                grid.add(bar, 1, at);
                GridPane.setValignment(bar, VPos.CENTER);
                cells.add(bar);
            }

            boolean editable = parsed.lineOf(index).isPresent() && (measure.stated() || statement.isPresent()) && rewriter != null;
            MeasureChip chip = new MeasureChip("", bounds, measure, shown, base, editable ? b -> edit(index, b) : null,
                    bounds.map(b -> amount(b, share, isMoney, computed, symbol)).orElse(null));
            chip.setId("model-value-" + index);
            chip.explain(DecisionTreeDiagram.explain("", measure, bounds, derivations, shown, terms.shareAtNode()));
            grid.add(chip, 2, at);
            GridPane.setValignment(chip, VPos.CENTER);
            cells.add(chip);

            for (Node cell : cells) {
                cell.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> point(index));
                cell.addEventHandler(MouseEvent.MOUSE_EXITED, e -> point(-1));
            }
        }

        /**
         * A chance's bar, on one scale for every chance: nothing at its left end, the whole at
         * its right. The bar spans the range a tornado sweeps, the statement's when the value
         * reads one, and a tick stands at the value the tree runs at when that is one point of it.
         */
        private Node bar(int index, Optional<Bounds> bounds, boolean stated, boolean isMoney, String symbol) {
            Pane track = new Pane();
            track.setId("model-bar-" + index);
            track.setMinSize(BAR, 6);
            track.setPrefSize(BAR, 6);
            track.setMaxSize(BAR, 6);
            track.setStyle("-fx-background-color: #e9e9e9; -fx-background-radius: 3;");
            double lower = bounds.map(Bounds::lower).orElse(Double.NaN);
            double upper = bounds.map(Bounds::upper).orElse(Double.NaN);
            var range = parsed.range(index).filter(r -> !r.isPoint());
            if (range.isPresent()) {
                boolean held = stated && parsed.lineOf(index).isPresent() && lineStatesValue(parsed.lineOf(index).getAsInt());
                Tooltip.install(track, new Tooltip("The statement's range " + print(range.get().lower(), true, isMoney, symbol) + " to "
                        + print(range.get().upper(), true, isMoney, symbol) + (held ? "" : ", run at its midpoint")));
                if (lower == upper && !Double.isNaN(lower)) {
                    track.getChildren().add(piece(lower * BAR - 1, 2, "#222222"));
                }
                lower = range.get().lower();
                upper = range.get().upper();
                track.getChildren().addFirst(span(lower, upper));
            } else if (!Double.isNaN(lower)) {
                track.getChildren().add(span(lower, upper));
            }
            return track;
        }

        /** The part of a bar from one share to another, never narrower than a point can be seen. */
        private Region span(double lower, double upper) {
            double from = Math.clamp(lower, 0, 1) * BAR;
            double width = Math.max(3, Math.clamp(upper, 0, 1) * BAR - from);
            return piece(Math.min(from, BAR - width), width, "#7a7a7a");
        }

        private Region piece(double x, double width, String colour) {
            Region piece = new Region();
            piece.setMinSize(width, 6);
            piece.setPrefSize(width, 6);
            piece.setMaxSize(width, 6);
            piece.setLayoutX(Math.clamp(x, 0, BAR - width));
            piece.setStyle("-fx-background-color: " + colour + "; -fx-background-radius: 3;");
            return piece;
        }

        /** A value in a reader's words: one number, or the two ends of a range, a share as a percentage and money after its symbol. */
        private String amount(Bounds b, boolean share, boolean isMoney, boolean computed, String symbol) {
            java.util.function.DoubleFunction<String> one = v -> share ? DecisionFigureText.percent(v, computed)
                    : isMoney ? (v < 0 ? "−" : "") + symbol + DecisionFigureText.shown(Math.abs(v), computed, true)
                    : DecisionFigureText.shown(v, computed, false);
            // en spaces about the word: the surface's face has a narrow space, which a percent sign all but closes
            return b.lower() == b.upper() ? one.apply(b.lower()) : one.apply(b.lower()) + "\u2002to\u2002" + one.apply(b.upper());
        }

        /**
         * The pointer is on a value's row, or has left it: the values that go with the value are
         * marked here, and on the trees that draw the model the branches that read it.
         *
         * @param index the value's vertex; negative when the pointer has left a row
         */
        private void point(int index) {
            List<Integer> related = index < 0 ? List.of() : values.related(index);
            bands.forEach((vertex, band) -> band.setStyle(related.contains(vertex) ? BAND_MARKED : BAND));
            if (id != null) {
                context.links(id).mark(index < 0 ? List.of() : values.readers(index));
            }
        }

        /** A node or a branch was picked on a tree that draws the model: the values it reads are marked. */
        private void picked(int node) {
            if (values == null) {
                return;
            }
            List<Integer> read = values.valuesAt(node);
            bands.forEach((vertex, band) -> band.setStyle(read.contains(vertex) ? BAND_MARKED : BAND));
        }

        /** A peek at the statement a value reads, editable, as a tree's statement mark shows it. */
        private void peek(Node from, String statement, MouseEvent e) {
            context.statementCard(statement).ifPresent(card -> {
                javafx.geometry.Bounds at = from.localToScreen(from.getBoundsInLocal());
                double x = at == null ? 0 : at.getMinX() + at.getWidth() / 2;
                double y = at == null ? 0 : at.getMaxY();
                dev.ikm.komet.framework.controls.PeekPopover.show(card.get(), dev.ikm.komet.kview.controls.KLWorkspace.DEFAULT_WINDOW_WIDTH,
                        "ANF statement", from, x, y, null, () -> { });
                e.consume();
            });
        }

        /** Whether the line that states a parameter writes a value of its own, or runs at the statement's midpoint. */
        private boolean lineStatesValue(int line) {
            String[] lines = body.split("\n", -1);
            if (line < 1 || line > lines.length) {
                return false;
            }
            String text = lines[line - 1].strip();
            int eq = text.indexOf('=');
            return eq >= 0 && text.substring(eq + 1).strip().matches("^[\\[(].*");
        }

        private String print(double value, boolean share, boolean money, String symbol) {
            return DecisionFigureText.quantity(value, share ? DecisionFigureText.Unit.SHARE : money ? DecisionFigureText.Unit.MONEY
                    : DecisionFigureText.Unit.PLAIN, money ? symbol : "");
        }

        /** The semantic the model's money is on: the first money cost's. */
        private long moneySemantic() {
            ImmutableList<DecisionElement.MoneyCost> costs = tree.nodesOfType(DecisionElement.MoneyCost.class);
            return costs.isEmpty() ? terms.moneySemantic() : costs.get(0).measure().semanticNid();
        }

        /** A value edited on the model: the line restated, and the model written. */
        private void edit(int measureVertex, Bounds bounds) {
            OptionalInt line = parsed.lineOf(measureVertex);
            if (line.isEmpty()) {
                return;
            }
            DecisionTreeEdits.restate(body, line.getAsInt(), bounds).ifPresent(this::take);
        }
    }

    private Node findingsOnly(List<ProfileFinding> findings) {
        VBox column = new VBox(2);
        Label heading = new Label("Model not read");
        heading.setFont(Font.font(null, FontWeight.BOLD, base));
        column.getChildren().add(heading);
        for (ProfileFinding finding : findings) {
            Label entry = new Label("• " + finding.message());
            entry.setFont(Font.font(base));
            entry.setWrapText(true);
            column.getChildren().add(entry);
        }
        return column;
    }
}
