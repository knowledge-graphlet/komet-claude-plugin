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
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.GraphletModel;
import network.ike.komet.claude.decision.GraphletModelText;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.claude.decision.render.DecisionFigureText;
import network.ike.komet.claude.decision.render.SweptQuantity;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * The {@code [tornado, model=<id>]} block: a rendering of a graphlet model as its one-way
 * sensitivity, the {@link TornadoChart} that once hung beneath the tree block, now a block of
 * its own ({@code dev-decision-point-knowledge}, settled 2026-11-06). It holds no structure and
 * no number: it reads the model the document's {@code graphlet-model} block publishes on the
 * {@link BlockContext}, sweeps it off the application thread, and draws the bars. A parameter
 * that reads a statement swings over the statement's range; the tree runs at the model's value.
 *
 * <p>Values are in the model, views on the rendering, trials in the view: a slider's preview is
 * drawn here alone, as a line beneath the chart saying what the choice would cost at that value,
 * and goes when the control closes; "Use this value" writes the model, and every rendering that
 * names the model redraws. The health price is a view attribute of this block,
 * {@code health-price=}, as it is of the tree block.
 */
final class TornadoBlockRenderer implements BlockRenderer {

    /** The block's style and language tag. */
    static final String TAG = "tornado";

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final BlockContext context;
    private BlockRewriter rewriter;

    /** Where a sweep runs: one thread behind the surface; a test may run it in place. */
    static Executor sweepExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "tornado-sweep");
        thread.setDaemon(true);
        return thread;
    });

    /** The narrowest the chart is laid out, in ems: narrower, its bars have no room between names and values, and it scales. */
    private static final double TORNADO_LEAST_EMS = 38;

    TornadoBlockRenderer(ViewProperties viewProperties, double base, BlockContext context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    TornadoBlockRenderer(ViewCalculator viewCalc, double base, BlockContext context) {
        this(viewCalc, null, base, context);
    }

    TornadoBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base, BlockContext context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new BlockContext() : context;
    }

    void setChipTypography(dev.ikm.komet.framework.controls.KonceptLabelTypography typography) {
        // the chart draws no chips
    }

    /** Where a changed view attribute goes when written back; the model is written through its own block. */
    void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    /** Whether a block is this kind's: tagged {@code tornado} and naming a model. */
    static boolean accepts(String info) {
        String tag = info == null ? "" : Tokens.words(info).stream().findFirst().orElse("");
        return TAG.equals(tag) && GraphletModelText.modelReference(info).isPresent();
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !accepts(info)) {
            return List.of();
        }
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        String modelId = GraphletModelText.modelReference(info).orElseThrow();
        return List.of(new BlockPiece(() -> new Live(info, modelId, price).column, literal == null ? "" : literal.stripTrailing()));
    }

    /** One rendering of the block: the model as the document holds it, swept at a price. */
    private final class Live implements TornadoChart.Host {
        final String info;
        final String modelId;
        double price;
        final VBox column = new VBox(4);
        final DecisionTerms terms = DecisionTerms.fromBindings();
        final Identities ids = context.identities(viewCalc);
        Identities shown = ids;
        final Runnable onContext = this::onContext;
        /** The model's text as last drawn, so a context change that leaves it as it was draws nothing again. */
        String drawnBody;
        DecisionTreeText.Parsed parsed;
        DecisionTree tree;
        DecisionTreeSensitivity lastSweep;
        BlockSweep mover;
        TornadoChart tornado;
        final Label trial = new Label();
        volatile int generation;

        Live(String info, String modelId, double price) {
            this.info = info;
            this.modelId = modelId;
            this.price = price;
            column.getStyleClass().add("tornado-block");
            column.setCursor(Cursor.DEFAULT);
            column.setFillWidth(true);
            column.setMaxWidth(Double.MAX_VALUE);
            column.parentProperty().addListener((obs, oldParent, newParent) -> {
                column.maxWidthProperty().unbind();
                if (newParent instanceof Region host) {
                    column.maxWidthProperty().bind(host.widthProperty());
                }
            });
            trial.setId("tornado-trial");
            trial.setFont(Font.font(base * 0.85));
            trial.setWrapText(true);
            trial.setStyle("-fx-text-fill: #8a5a00;");
            context.listen(onContext);
            rebuild();
        }

        private void onContext() {
            String now = context.model(modelId).map(GraphletModel::body).orElse(null);
            // the model's text moved, a statement a parameter reads landed, or the model itself did
            if (!java.util.Objects.equals(now, drawnBody) || parsed == null || !parsed.missingStatements().isEmpty()
                    || tree == null) {
                rebuild();
            }
        }

        void rebuild() {
            generation++;
            lastSweep = null;
            mover = null;
            tornado = null;
            column.getChildren().clear();
            Label heading = new Label("Sensitivity of the choice, one value at a time");
            heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
            column.getChildren().add(heading);
            Optional<GraphletModel> model = context.model(modelId);
            if (model.isEmpty()) {
                drawnBody = null;
                column.getChildren().add(note("Waiting for the model “" + modelId + "”: no graphlet-model block of this document names it yet."));
                return;
            }
            String body = model.get().body();
            drawnBody = body;
            StatementMeasures statements = context.statementMeasures(ids, terms);
            parsed = DecisionTreeText.parse(body, terms, ids, context.externals(), statements);
            shown = parsed.labelled(ids);
            if (parsed.tree().isEmpty()) {
                column.getChildren().add(note("The model “" + modelId + "” does not read as a tree."));
                return;
            }
            tree = parsed.tree().get();
            Rollback rollback = Rollback.of(tree, BlockSweep.coordinate(tree, price),
                    network.ike.komet.claude.decision.LinkedTrees.of(context.trees(), terms, ids, context.externals(), statements));
            if (!parsed.missingStatements().isEmpty()) {
                column.getChildren().add(note("Waiting for the statements " + String.join(", ",
                        parsed.missingStatements().stream().map(n -> "anf:" + n).toList()) + " the model's parameters read."));
            }
            if (!rollback.determinable()) {
                column.getChildren().add(note("Nothing to sweep until the model is determinable."));
                return;
            }
            Label wait = new Label("computing…");
            wait.setFont(Font.font(base * 0.85));
            wait.setStyle("-fx-text-fill: #666;");
            column.getChildren().add(wait);
            int mine = generation;
            double at = price;
            java.util.Map<String, String> trees = context.trees();
            sweepExecutor.execute(() -> {
                if (mine != generation) {
                    return;
                }
                Optional<DecisionTreeSensitivity> swept;
                Optional<BlockSweep> opened;
                try {
                    opened = BlockSweep.open(body, terms, ids, at, context.externals(), trees, statements);
                    swept = BlockSweep.sweep(body, terms, ids, at, BlockSweep.DEFAULT_STEPS, context.externals(), trees, statements);
                } catch (RuntimeException e) {
                    swept = Optional.empty();
                    opened = Optional.empty();
                }
                Optional<DecisionTreeSensitivity> landed = swept;
                Optional<BlockSweep> moverLanded = opened;
                Platform.runLater(() -> {
                    if (mine != generation) {
                        return;
                    }
                    column.getChildren().remove(wait);
                    if (landed.isEmpty()) {
                        column.getChildren().add(note("The model could not be swept."));
                        return;
                    }
                    lastSweep = landed.get();
                    mover = moverLanded.orElse(null);
                    tornado = new TornadoChart(landed.get(), base, this);
                    tornado.setId("tornado-chart");
                    column.getChildren().add(new FittedFigure(tornado, base, base * TORNADO_LEAST_EMS));
                    trial.setText("");
                    trial.setVisible(false);
                    trial.setManaged(false);
                    column.getChildren().add(trial);
                    if (!model.get().writable()) {
                        column.getChildren().add(note("The model cannot be written from here, so a value tried stays a trial."));
                    }
                });
            });
        }

        private Label note(String text) {
            Label note = new Label(text);
            note.setFont(Font.font(base * 0.85));
            note.setWrapText(true);
            note.setStyle("-fx-text-fill: #8a5a00;");
            note.setPadding(new Insets(2, 0, 2, 0));
            return note;
        }

        // ── The chart's host ────────────────────────────────────────────────

        @Override
        public String nameOf(int measureVertex) {
            return lastSweep != null ? SweptQuantity.name(lastSweep, shown, measureVertex) : SweptQuantity.name(tree, shown, measureVertex);
        }

        @Override
        public String quantity(int measureVertex, double value) {
            if (lastSweep == null) {
                return DecisionFigureText.shown(value, true, false);
            }
            return SweptQuantity.of(lastSweep, shown, measureVertex, symbol()).print(value);
        }

        @Override
        public String moneyUnit() {
            long semantic = semantic(tree, MoneyCost.class, terms.moneySemantic());
            String label = semantic == 0 ? null : ids.label(semantic);
            return label == null ? "" : label;
        }

        private String symbol() {
            return DecisionFigureText.symbolFor(moneyUnit());
        }

        @Override
        public String alternativeName(int alternativeVertex) {
            if (alternativeVertex < 0) {
                return "no sure choice";
            }
            return tree.node(alternativeVertex).flatMap(n -> n.concept().isPresent()
                    ? Optional.of(shown.label(n.concept().getAsLong())) : Optional.empty()).orElse("an alternative");
        }

        @Override
        public void light(int measureVertex, boolean on) {
            // the tree is another rendering: a trial is seen here alone
        }

        /** The trial drawn beneath the chart: the choice and its cost at the value, seen in this rendering alone. */
        @Override
        public void preview(int measureVertex, OptionalDouble value) {
            if (value.isEmpty() || mover == null || lastSweep == null) {
                trial.setText("");
                trial.setVisible(false);
                trial.setManaged(false);
                return;
            }
            Optional<DecisionTreeSensitivity.FoldedValue> folded = lastSweep.folded(measureVertex);
            Optional<Rollback> at = measureVertex == DecisionTreeSensitivity.HEALTH_PRICE ? mover.priced(value.getAsDouble())
                    : folded.isPresent() ? mover.movedIn(folded.get().treeRoot(), folded.get().vertex(), Bounds.point(value.getAsDouble()))
                    : mover.moved(measureVertex, Bounds.point(value.getAsDouble()));
            String words = at.map(rollback -> {
                OptionalInt chosen = rollback.chosen(rollback.tree().rootIndex());
                String choice = chosen.isPresent() ? "“" + alternativeName(chosen.getAsInt()) + "”" : "no sure choice";
                return "Trial, seen here only: at " + quantity(measureVertex, value.getAsDouble()) + " the expected cost is "
                        + rollback.rootValue().map(v -> symbol() + DecisionTreeBlockRenderer.money(v)).orElse("not determinable")
                        + ", choosing " + choice + ".";
            }).orElse("Trial, seen here only: the model is not determinable at " + quantity(measureVertex, value.getAsDouble()) + ".");
            trial.setText(words);
            trial.setVisible(true);
            trial.setManaged(true);
        }

        /** "Use this value": the model takes it, and every rendering that names the model redraws. */
        @Override
        public void tryValue(int measureVertex, double value) {
            if (measureVertex == DecisionTreeSensitivity.HEALTH_PRICE) {
                if (value != price) {
                    price = value;
                    rebuild();
                }
                return;
            }
            Optional<DecisionTreeSensitivity.FoldedValue> folded = lastSweep == null ? Optional.empty() : lastSweep.folded(measureVertex);
            if (folded.isPresent()) {
                ids.identifier(folded.get().treeRoot()).flatMap(context::treeNode).ifPresent(SurfaceNavigation::reveal);
                return;
            }
            OptionalInt line = parsed == null ? OptionalInt.empty() : parsed.lineOf(measureVertex);
            Optional<GraphletModel> model = context.model(modelId);
            if (line.isEmpty() || model.isEmpty()) {
                return;
            }
            if (!model.get().write(line.getAsInt(), Bounds.point(value))) {
                trial.setText("The model “" + modelId + "” cannot be written from here; the value stays a trial.");
                trial.setVisible(true);
                trial.setManaged(true);
            }
        }

        /** The semantic a tree's costs of a kind are on, taken from the first such cost; the parent semantic otherwise. */
        private long semantic(DecisionTree of, Class<? extends Cost> kind, long fallback) {
            ImmutableList<? extends Cost> costs = of.nodesOfType(kind);
            return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
        }

        /** The chart while one is drawn; for a test. */
        Optional<TornadoChart> chart() {
            return Optional.ofNullable(tornado);
        }
    }
}
