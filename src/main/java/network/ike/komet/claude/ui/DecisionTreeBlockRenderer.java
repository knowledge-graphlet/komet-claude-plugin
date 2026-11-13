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

import network.ike.komet.document.KonceptChips;

import network.ike.komet.document.BlockRewriter;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptLabelTypography;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.Cohort;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeEdits;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.GraphletModel;
import network.ike.komet.claude.decision.GraphletModelText;
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.claude.decision.Derivations;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.Tradeoff;
import network.ike.komet.claude.decision.LinkedTrees;
import network.ike.komet.claude.decision.TreeStatements;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.Circumstance;
import network.ike.komet.claude.decision.ValuationCoordinate;
import network.ike.komet.claude.decision.ViewIdentities;
import network.ike.komet.document.DecisionFigureText;
import network.ike.komet.claude.decision.render.DecisionTreeFigures;
import network.ike.komet.document.ComponentText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The live rendering of a {@code decision-tree} block on the assistant surface
 * ({@code dev-decision-tree-blocks}): the tree drawn as decision analysts draw it, by the
 * {@link DecisionTreeDiagram} on the shared {@link network.ike.komet.claude.decision.DecisionTreeLayout},
 * with the model's parameters above it, each node's concept as the shared Koncept chip, and the
 * rolled-back value beside every glyph under a valuation coordinate, computed on the view and
 * never stored. The reader may move any stated number and the health price as a what-if: the
 * block's text is restated on that one line, read again through the same parser, and the tree
 * redrawn, so the what-if stays what the block would say if written so. It stays a what-if until
 * the reader writes it back, which hands the restated block to the surface's
 * {@link BlockRewriter}; a surface without one offers the what-if alone. A finding of the parse,
 * the profile's checks or the rollback is listed beneath the tree and shown at the glyph it sits
 * on. The one-way sensitivity is the {@code [tornado]} block's, a rendering of its own.
 *
 * <p>Since 2026-11-06 the block is a rendering of a graphlet model ({@code dev-decision-point-knowledge}):
 * {@code [decision-tree, model=<id>]} draws the model the document's {@code graphlet-model} block
 * of that id publishes on the {@link ProfileBlocks}, and holds no structure and no number of its
 * own, only how the model is viewed, the health price and the cohort. A value the reader moves on
 * such a rendering is written into the model, which every rendering that names it then redraws;
 * where the model cannot be written, the move stays a what-if of this rendering. A block with a
 * body of its own is read as it always was, the assistant's reply among them.
 *
 * <p>The block declines when it is not this profile's, when it has no root decision line and
 * names no model, or when there is no view to resolve against, so the text renders preformatted
 * as any other unrendered block does. The block's info may set the price: {@code decision-tree
 * health-price=100000}; the default is $100,000, the regulatory topics' central value.
 */
final class DecisionTreeBlockRenderer implements network.ike.komet.document.StructuredBlockKind.FenceRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
        private BlockRewriter rewriter;
    private final ProfileBlocks context;

    /** Gives each block its {@link Live#order}. */
    private static final java.util.concurrent.atomic.AtomicLong ORDERS = new java.util.concurrent.atomic.AtomicLong();


        DecisionTreeBlockRenderer(ViewCalculator viewCalc, double base, ProfileBlocks context) {
        this(viewCalc, null, base, context);
    }

    DecisionTreeBlockRenderer(ViewProperties viewProperties, double base, ProfileBlocks context) {
        this(viewProperties == null ? null : viewProperties.calculator(), viewProperties, base, context);
    }

    DecisionTreeBlockRenderer(ViewCalculator viewCalc, ViewProperties viewProperties, double base, ProfileBlocks context) {
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.context = context == null ? new ProfileBlocks() : context;
    }

    public void setChipTypography(KonceptLabelTypography typography) {
        this.typography = typography == null ? KonceptLabelTypography.DEFAULT : typography;
    }

    /** Where a what-if goes when the reader writes it back; null offers the what-if alone. */
    public void setRewriter(BlockRewriter rewriter) {
        this.rewriter = rewriter;
    }

    /** Where a tree's column keeps what builds its drawing at full size, left to right, for whoever asks how much room that takes. */
    static final Object WHOLE = new Object();
    /** Where a tree's column keeps an {@link java.util.function.IntConsumer} to tell of a node or branch clicked on it. */
    static final Object ON_SELECT = new Object();
    /** Where a tree's column keeps its {@link Marks}, for whoever marks a selection on the tree. */
    static final Object MARKS = new Object();

    /**
     * How large a tree is when it is drawn whole, left to right at full size. The drawing is measured
     * in a scene of its own: a label that is in no scene has no skin and measures as nothing, so a
     * drawing asked for its size before it is shown answers a fraction of it.
     *
     * @param column   a tree block's column, which keeps what draws the tree whole under {@link #WHOLE}
     * @param styledAs the scene whose stylesheets the drawing will be shown with, or null
     * @return the drawing's width and height, or null when the block has not drawn its tree
     */
    static javafx.geometry.Dimension2D wholeSize(Node column, javafx.scene.Scene styledAs) {
        if (!(column.getProperties().get(WHOLE) instanceof java.util.function.Supplier<?> whole)
                || !(whole.get() instanceof javafx.scene.layout.Region drawing)) {
            return null;
        }
        javafx.scene.Group root = new javafx.scene.Group(drawing);
        javafx.scene.Scene measuring = new javafx.scene.Scene(root);
        if (styledAs != null) {
            measuring.getStylesheets().setAll(styledAs.getStylesheets());
        }
        root.applyCss();
        root.layout();
        return new javafx.geometry.Dimension2D(drawing.prefWidth(-1), drawing.prefHeight(-1));
    }

    /** A selection marked on a tree: the measures that read a selected value, and a selected node or branch. */
    interface Marks {
        /**
         * @param measures the measures to mark, by vertex; empty for none
         * @param selected the node or branch selected, by vertex; negative for none
         */
        void show(java.util.Collection<Integer> measures, int selected);
    }

    /**
     * Whether the tree is drawn as a part of a model's review ({@code dev-grow-model-review}).
     * The review has the room a document lacks, and has the model's values in a table of their
     * own beside the tree, so the tree is drawn whole from the start, with no alternative folded,
     * no row of parameters above it, and no Expand button, since it is grown already.
     */
    private boolean reviewing;

    void setReviewing(boolean reviewing) {
        this.reviewing = reviewing;
    }

    @Override
    public List<BlockPiece> render(String info, String literal, StyleAttributeMap baseStyle) {
        if (viewCalc == null || !accepts(info, literal)) {
            return List.of();
        }
        String body = literal == null ? "" : literal;
        double price = DecisionTreeText.healthPrice(info).orElse(DecisionTreeText.DEFAULT_HEALTH_PRICE);
        OptionalDouble cohort = DecisionTreeText.cohort(info).map(OptionalDouble::of).orElse(OptionalDouble.empty());
        String model = GraphletModelText.modelReference(info).orElse(null);
        // A tree that draws a model grows into the model's review, and a tree with its own text grows whole
        // while it is drawn reduced. The tree of a review is grown already.
        return List.of(new BlockPiece(() -> {
            Live live = new Live(info, body, price, cohort, model);
            if (reviewing) {
                return live.column;
            }
            return model != null
                    ? ModelReviewGrow.grown(live.column, viewCalc, viewProperties, base, context, model, info)
                    : WholeTreeGrow.grown(live.column, live.reduced);
        }, body.stripTrailing()));
    }

    // ── Pure decisions (no store access) ────────────────────────────────

    /**
     * Whether a block is this profile's: tagged {@code decision-tree}, or {@code property-graph}
     * with {@code profile=decision-tree}, and opening with a root decision line, or a root chance
     * line for a tree of consequences that others fold in.
     */
    static boolean accepts(String info, String literal) {
        if (!DecisionTreeText.isTagged(info)) {
            return false;
        }
        // a rendering of a model has no body of its own
        if (GraphletModelText.modelReference(info).isPresent()) {
            return true;
        }
        BlockLines.Parsed lines = BlockLines.parse(literal);
        return lines.roots().size() == 1
                && lines.roots().get(0).form() == BlockLines.Form.VERTEX
                && ("decision".equals(lines.roots().get(0).word()) || "chance".equals(lines.roots().get(0).word()));
    }

    /**
     * A rolled-back value, which is money, to the whole unit, as the regulatory topics print
     * their worked numbers: a point as one number, an interval with its two bounds.
     */
    static String money(Bounds bounds) {
        if (bounds.isPoint()) {
            return String.format(Locale.ROOT, "%,d", Math.round(bounds.lower()));
        }
        return (bounds.lowerOpen() ? "(" : "[") + String.format(Locale.ROOT, "%,d", Math.round(bounds.lower()))
                + ", " + String.format(Locale.ROOT, "%,d", Math.round(bounds.upper())) + (bounds.upperOpen() ? ")" : "]");
    }

    // ── The live block (JavaFX application thread) ───────────────────────

    /**
     * One rendering of the block: the text it was read from, the text the what-if has made of
     * it, the price, which nodes are folded, and the column that shows it all, rebuilt whenever
     * any of these move.
     */
    private final class Live implements DecisionTreeDiagram.Host {
        final String originalInfo;
        String originalBody;
        final double originalPrice;
        String body;
        double price;
        int moves;
        /** The model this block renders, by id; null when the block carries its own body. */
        final String modelId;
        /** The model as last drawn, so a move of its text is told from any other change of the context. */
        GraphletModel model;
        final Set<Integer> collapsed = new HashSet<>();
        /** Whether the rejected alternatives have been folded once, as the first drawing does; a click unfolds them. */
        private boolean foldedRejected;
        final VBox column = new VBox(6);
        final DecisionTerms terms = DecisionTerms.fromBindings();
        // The view resolves what the store holds; a symbol the store lacks, as a block written for
        // the documentation build names its concepts, is read as a concept local to the block.
                final Identities ids = context.identities(viewCalc);
                /** The identities with the block's own labels first, for every word the drawing and the readouts say. */
                private Identities shown = ids;
        /** Rebuilds when another block publishes a quantity this tree reads but states no parameter for. */
        final Runnable onContext = this::rebuildIfReadsExternal;
        boolean readsExternal;
        DecisionTreeText.Parsed parsed;
        DecisionTree tree;
        DecisionTreeDiagram diagram;
        final Map<Integer, MeasureChip> parameterChips = new HashMap<>();
        /** How the drawing fits its region: how wide a name may be, and whether the tree is an outline. */
        final DecisionTreeFit fit = new DecisionTreeFit(CHIP_EMS, MIN_CHIP_EMS);
        /** Whether the drawing is being built: only its names take the fitted cap, never the parameters above it. */
        boolean drawing;
        /** The drawing as it was last built, built again at full size for whoever shows the tree whole: its review, or its grow. */
        java.util.function.Supplier<DecisionTreeDiagram> whole;
        /** Whether the drawing is reduced where it stands: scaled down to fit, or an outline in place of the drawing left to right. */
        final javafx.beans.property.BooleanProperty reduced = new javafx.beans.property.SimpleBooleanProperty(false);

        /** When this block was made, among the blocks of every document: the latest of those restating a tree holds it. */
        final long order = ORDERS.incrementAndGet();
        /** The top of the column: header, parameters and the drawing. */
        final VBox top = new VBox(6);
        /** The rollback the block stands at. */
        Rollback committed;
        List<ProfileFinding> committedFindings = List.of();

        /** The cohort the block asks to be counted out for, when it does. */
        final OptionalDouble cohortSize;

        Live(String info, String body, double price) {
            this(info, body, price, OptionalDouble.empty(), null);
        }

        Live(String info, String body, double price, OptionalDouble cohortSize, String modelId) {
            this.originalInfo = info;
            this.originalBody = body;
            this.originalPrice = price;
            this.cohortSize = cohortSize;
            this.modelId = modelId;
            this.body = body;
            this.price = price;
            column.getStyleClass().add("decision-tree");
            column.setCursor(Cursor.DEFAULT);
            column.setFillWidth(true);
            top.setFillWidth(true);
            column.setMaxWidth(Double.MAX_VALUE);
            column.parentProperty().addListener((obs, oldParent, newParent) -> {
                column.maxWidthProperty().unbind();
                if (newParent instanceof Region host) {
                    column.maxWidthProperty().bind(host.widthProperty());
                }
            });
                        context.listen(onContext);
            column.getProperties().put(MARKS, (Marks) this::showMarks);
            rebuild();
        }

        /** The measures and the node marked on the drawing, kept so a drawing made again is marked as the last was. */
        private java.util.Collection<Integer> markedMeasures = List.of();
        private int markedNode = -1;

        private void showMarks(java.util.Collection<Integer> measures, int selected) {
            if (diagram != null) {
                markedMeasures.forEach(measure -> diagram.markMeasure(measure, false));
                if (markedNode >= 0) {
                    diagram.markSelected(markedNode, false);
                }
            }
            markedMeasures = List.copyOf(measures);
            markedNode = selected;
            applyMarks();
        }

        private void applyMarks() {
            if (diagram == null) {
                return;
            }
            markedMeasures.forEach(measure -> diagram.markMeasure(measure, true));
            if (markedNode >= 0) {
                diagram.markSelected(markedNode, true);
            }
        }

        @Override
        public void select(int vertexIndex) {
            if (column.getProperties().get(ON_SELECT) instanceof java.util.function.IntConsumer told) {
                told.accept(vertexIndex);
            }
        }

        private void rebuildIfReadsExternal() {
            if (modelId != null) {
                // the model moved: its text is this block's, so a what-if of this rendering is over
                Optional<GraphletModel> now = context.model(modelId);
                boolean moved = now.isPresent() && (model == null || !now.get().body().equals(model.body()));
                if (moved || now.isEmpty() != (model == null)) {
                    moves = 0;
                    rebuild();
                    return;
                }
            }
            if (readsExternal) {
                rebuild();
            }
        }

        /** Whether a rebuild is running, and whether another was asked for meanwhile, as a tree this one registers may ask. */
        private boolean rebuilding;
        private boolean rebuildAgain;
        /** The identifier of this tree's root, which another tree folds it in by; null when the root names none. */
        String treeId;
        /** The trees of the document an outcome of this tree may fold in. */
        LinkedTrees linked = LinkedTrees.none();

        void rebuild() {
            if (rebuilding) {
                rebuildAgain = true;
                return;
            }
            rebuilding = true;
            try {
                rebuildNow();
            } finally {
                rebuilding = false;
            }
            if (rebuildAgain) {
                rebuildAgain = false;
                rebuild();
            }
        }

        private void rebuildNow() {
            parameterChips.clear();
            column.getChildren().clear();
            if (modelId != null) {
                // a rendering: the model's text is the body, as the document holds it now
                model = context.model(modelId).orElse(null);
                if (model == null) {
                    readsExternal = true;
                    column.getChildren().add(waiting("Waiting for the model \u201C" + modelId
                            + "\u201D: no graphlet-model block of this document names it yet."));
                    return;
                }
                if (moves == 0) {
                    originalBody = model.body();
                    body = model.body();
                }
            }
            StatementMeasures statements = context.statementMeasures(ids, terms);
            parsed = DecisionTreeText.parse(body, terms, ids, context.externals(), statements);
            shown = parsed.labelled(ids);
            List<ProfileFinding> findings = new ArrayList<>(parsed.findings().castToList());
            if (parsed.tree().isEmpty()) {
                column.getChildren().add(findingsOnly(findings));
                return;
            }
            tree = parsed.tree().get();
            java.util.Set<Integer> waiting = parsed.waiting();
            tree.check().forEach(finding -> {
                if (!waiting.contains(finding.vertexIndex())) {
                    findings.add(finding);
                }
            });
            // known to the document by its root, so another tree may fold it in; then the document's trees are what this one folds in
            treeId = LinkedTrees.rootIdentifier(tree, ids).orElse(null);
            context.defineTree(treeId, body, column, order);
            linked = LinkedTrees.of(context.trees(), terms, ids, context.externals(), statements);
            Rollback rollback = Rollback.of(tree, coordinate(tree, price), linked);
            rollback.foldedAll().forEachValue(inner ->
                    LinkedTrees.rootIdentifier(inner.tree(), ids).ifPresent(folded -> context.noteFoldIn(treeId, folded)));
            // a parameter with no line was read from another block; a measure that reads a quantity no
            // parameter states is waiting for one
            // a statement a node names by an anf block not drawn yet is waiting for it, as a quantity is
            readsExternal = tree.nodesOfType(Parameter.class).anySatisfy(p -> parsed.lineOf(p.vertexIndex()).isEmpty())
                    || rollback.findings().anySatisfy(f -> f.message().contains("has no parameter for"))
                    || findings.stream().anyMatch(f -> f.message().contains("has no parameter for"))
                    || TreeStatements.held(parsed).stream().anyMatch(h -> context.statement(h.name()).isEmpty())
                    // a statement a parameter reads not stated yet
                    || !parsed.missingStatements().isEmpty()
                    // a tree it folds in not drawn yet, or a tree others fold in, which links back to them
                    || rollback.findings().anySatisfy(f -> f.message().equals(Rollback.FOLDS_IN_NOTHING))
                    || tree.root().filter(r -> !(r instanceof DecisionElement.Decision)).isPresent();
            committed = rollback;
            committedFindings = findings;
            fillTop(tree, rollback, price, Optional.empty(), findings);
            column.getChildren().add(top);

            List<String> messages = new ArrayList<>();
            // Named by the node they are on, as the feedback to the assistant names them, not by vertex index.
            // The note that only lists the block's local symbols is not a gap: the dashed chips say it.
            findings.stream().filter(finding -> !DecisionTreeFeedback.isLocalSymbolsNote(finding))
                    .forEach(finding -> messages.add(DecisionTreeFeedback.describe(tree, shown, finding)));
            rollback.findings().forEach(finding -> messages.add(DecisionTreeFeedback.describe(tree, shown, finding)));
            messages.addAll(TreeStatements.findings(tree, shown, parsed,
                    name -> context.statement(name).map(DecisionTreeBlockRenderer::kind), true));
            for (String name : parsed.missingStatements()) {
                messages.add("anf:" + name + ": no anf block of the document states it yet, so its parameter has no value");
            }
            if (!messages.isEmpty()) {
                Label gaps = new Label(String.join("\n", messages));
                gaps.setFont(Font.font(base * 0.9));
                gaps.setWrapText(true);
                gaps.setStyle("-fx-text-fill: #8a5a00;");
                column.getChildren().add(gaps);
            }
            if (moves > 0) {
                column.getChildren().add(whatIfBar());
            }
        }

        /** A line saying what the rendering waits for, in place of the drawing. */
        private Label waiting(String text) {
            Label note = new Label(text);
            note.setId("decision-tree-waiting");
            note.setFont(Font.font(base * 0.9));
            note.setWrapText(true);
            note.setStyle("-fx-text-fill: #8a5a00;");
            return note;
        }



        /** The coordinate a tree is read at: its own cost semantics, at a price. */
        private ValuationCoordinate coordinate(DecisionTree tree, double at) {
            return ValuationCoordinate.pricingHealthAt(Bounds.point(at),
                    semantic(tree, MoneyCost.class, terms.moneySemantic()), semantic(tree, HealthCost.class, terms.healthSemantic()));
        }

        /** Fills the top of the column: the header, the parameters and the drawing, for a tree at a price. */
        private void fillTop(DecisionTree drawn, Rollback rollback, double at, Optional<Rollback> reference,
                             List<ProfileFinding> findings) {
            parameterChips.clear();
            top.getChildren().clear();
            top.getChildren().add(header(drawn, rollback, at));
            Node parameters = reviewing ? null : parameters(drawn, rollback.derivations());
            if (parameters != null) {
                top.getChildren().add(parameters);
            }
            Optional<Cohort> cohort = cohortSize.isPresent()
                    ? Optional.of(Cohort.of(drawn, rollback, cohortSize.getAsDouble())) : Optional.empty();
            if (!foldedRejected && !reviewing) {
                // the first drawing folds what the rollback rejected: its run and its value stay, its subtree opens on a click
                foldedRejected = true;
                for (DecisionElement.Node decision : drawn.nodesOfType(DecisionElement.Decision.class)) {
                    if (drawn.parentIndex(decision.vertexIndex()).isPresent()) {
                        continue;
                    }
                    OptionalInt chosen = rollback.chosen(decision.vertexIndex());
                    if (chosen.isEmpty()) {
                        continue;
                    }
                    for (DecisionElement.Alternative alternative : drawn.children(decision.vertexIndex(), DecisionElement.Alternative.class)) {
                        if (alternative.vertexIndex() != chosen.getAsInt()) {
                            // a fold is on the node beneath the run: the run and its value stay in view
                            for (DecisionElement.Node beneath : drawn.children(alternative.vertexIndex(), DecisionElement.Node.class)) {
                                if (!(beneath instanceof DecisionElement.Outcome)) {
                                    collapsed.add(beneath.vertexIndex());
                                }
                            }
                        }
                    }
                }
            }
            drawing = true;
            try {
                diagram = new DecisionTreeDiagram(drawn, rollback, reference, shown, findings, collapsed, base, this, cohort,
                        fit.outline());
            } finally {
                drawing = false;
            }
            applyMarks();
            // the same drawing at full size, for the review's question of what fits and for the tree's own grow
            Set<Integer> folded = Set.copyOf(collapsed);
            whole = () -> new DecisionTreeDiagram(drawn, rollback, reference, shown, findings, folded, base, this, cohort);
            column.getProperties().put(WHOLE, whole);
            // the tree fits its region, compacted, then an outline, then scaled, and never scrolls sideways
            FittedFigure fitted = new FittedFigure(diagram, this::fitTo);
            if (fit.outline()) {
                // the outline stands in for the drawing left to right, which the block's Expand button leads to
                fitted.standsInForFuller();
            }
            reduced.unbind();
            reduced.bind(fitted.reducedProperty());
            top.getChildren().add(fitted);
            // the ledger: every alternative's consequences in their own units
            String healthUnit = unitName(rollback.coordinate().healthSemantic(), "");
            List<List<String>> table = DecisionTreeFigures.ledgerTable(drawn, rollback, shown, symbol(), healthUnit, cohort);
            if (table.size() > (drawn.decision().isPresent() ? 2 : 1)) {
                Label title = new Label(DecisionTreeFigures.ledgerTitle(cohort, drawn.decision().isPresent()));
                title.setFont(Font.font(null, FontWeight.BOLD, base * 0.85));
                title.setPadding(new Insets(4, 0, 0, 0));
                GridPane grid = new GridPane();
                grid.setId("decision-tree-ledger");
                grid.setHgap(14);
                grid.setVgap(2);
                OptionalInt chosenAlternative = rollback.chosen(drawn.rootIndex());
                String chosenName = chosenAlternative.isPresent() ? alternativeName(chosenAlternative.getAsInt()) : null;
                for (int r = 0; r < table.size(); r++) {
                    List<String> row = table.get(r);
                    boolean header = r == 0;
                    boolean chosenRow = !header && row.get(0).equals(chosenName);
                    for (int c = 0; c < row.size(); c++) {
                        Label cell = new Label(row.get(c));
                        cell.setFont(Font.font(null, chosenRow ? FontWeight.BOLD : FontWeight.NORMAL, base * 0.85));
                        cell.setStyle(header ? "-fx-text-fill: #666; -fx-font-style: italic;" : chosenRow ? "-fx-text-fill: #1f3b73;" : "");
                        // a name wraps between its words, a little over the widest since the style draws it wider;
                        // a number or a single word is read whole, or not at all
                        boolean words = (header || c == 0) && row.get(c).strip().contains(" ");
                        cell.setWrapText(words);
                        cell.setMinWidth(words ? 1.25 * KonceptChips.widestWord(row.get(c), cell.getFont()) : Region.USE_PREF_SIZE);
                        grid.add(cell, c, r);
                    }
                }
                // a table of whole names, not the column's: it takes the margins, and wraps a cell before it ellipsises one
                top.getChildren().add(new FittedFigure(new VBox(2, title, grid), 0));
            }
            // the readouts: what the choice trades, money and health apart, and where a cohort ends up
            for (String readout : DecisionTreeFigures.readoutLines(drawn, rollback, shown, symbol(), healthUnit, cohort,
                    Tradeoff.of(drawn, rollback))) {
                Label line = new Label(readout);
                line.getStyleClass().add("decision-tree-readout");
                line.setWrapText(true);
                line.setFont(Font.font(base * 0.85));
                line.setPadding(new Insets(2, 0, 0, 0));
                top.getChildren().add(line);
            }
            top.getChildren().add(legend(rollback, cohort));
        }

        /** One line beneath the drawing saying what its numbers are. */
        private Label legend(Rollback rollback, Optional<Cohort> cohort) {
            String moneyUnit = unitName(rollback.coordinate().moneySemantic(), "money");
            String healthUnit = unitName(rollback.coordinate().healthSemantic(), "healthy years");
            // a tree with no health anywhere says nothing about health
            boolean anyHealth = !tree.nodesOfType(DecisionElement.HealthCost.class).isEmpty();
            String priced = anyHealth ? ", with a year of healthy life priced at " + money(rollback.coordinate().healthPrice()) : "";
            String costs = anyHealth ? ", and what it costs, money then health in " + healthUnit : ", and what it costs";
            String beneath = cohort.isPresent()
                    ? " beneath a branch, how many of the " + DecisionFigureText.count(cohort.get().size())
                    + " go that way" + costs
                    : " beneath a branch, what it costs" + (anyHealth ? ", money then health in " + healthUnit : "");
            Label legend = new Label("⇒ is the expected cost per patient from that point on, in " + moneyUnit + priced
                    + ". A percentage is the share who go that way;" + beneath
                    + ". The heavy run is the alternative chosen, a barred one is rejected. Hover a number for its working.");
            legend.setId("decision-tree-legend");
            legend.setWrapText(true);
            legend.setFont(Font.font(base * 0.78));
            legend.setTextFill(javafx.scene.paint.Color.web("#666"));
            legend.setPadding(new Insets(2, 0, 4, 0));
            return legend;
        }

        private String unitName(long nid, String fallback) {
            String label = nid == 0 ? null : ids.label(nid);
            return label == null || label.isBlank() ? fallback : label;
        }

        /** The title row: what the tree decides, its value, and the health price the reader may move. */
        private FlowPane header(DecisionTree drawn, Rollback rollback, double at) {
            // wraps as the parameters do: on one row its parts ellipsise down to nothing in a narrow column
            FlowPane header = new FlowPane(8, 4);
            header.setAlignment(Pos.CENTER_LEFT);
            header.prefWrapLengthProperty().bind(column.widthProperty().subtract(4));
            if (!reviewing) {
                ExpandButton.leaveRoom(header);                         // the block's Expand button is over the row's end
            }
            Label title = new Label("Decision tree");
            title.setFont(Font.font(null, FontWeight.BOLD, base));
            header.getChildren().add(title);
            if (modelId != null) {
                Label modelName = new Label("model " + modelId);
                modelName.setId("decision-tree-model");
                modelName.setFont(Font.font("Menlo", base * 0.8));
                modelName.setStyle("-fx-text-fill: #666;");
                header.getChildren().add(modelName);
            }
            drawn.root().ifPresent(root -> concept(root.concept()).ifPresent(header.getChildren()::add));
            // the trees that fold this one in, each a link back to where it is folded in
            if (treeId != null) {
                for (String folding : context.foldedBy(treeId)) {
                    ids.nid(folding).ifPresent(nid -> header.getChildren().add(
                            treeLink("folded in by", shown.label(nid), folding)));
                }
            }
            Label priceLabel = new Label("healthy year at");
            priceLabel.setFont(Font.font(base * 0.9));
            TextField priceField = new TextField(Tokens.number(at));
            priceField.setId("health-price-field");
            priceField.setFont(Font.font(base * 0.9));
            priceField.setPrefColumnCount(8);
            // never squeezed below its text: a field narrower than its caret moves its text back and forth on
            // every layout, and the card is laid out and repainted on every pulse
            priceField.setMinWidth(Region.USE_PREF_SIZE);
            priceField.setOnAction(event -> {
                try {
                    double typed = Double.parseDouble(priceField.getText().replace(",", "").replace("$", "").strip());
                    if (typed != price) {
                        price = typed;
                        moves++;
                        rebuild();
                    }
                } catch (NumberFormatException e) {
                    priceField.setStyle("-fx-border-color: #c0392b;");
                }
            });
            header.getChildren().addAll(priceLabel, priceField);
            rollback.rootValue().ifPresent(value -> {
                Label expected = new Label("expected cost " + symbol() + money(value));
                expected.setFont(Font.font(null, FontWeight.BOLD, base * 0.9));
                header.getChildren().add(expected);
            });
            return header;
        }

        /** The model's parameters, each its quantity and its value, stated ones offered for a what-if. */
        private Node parameters(DecisionTree drawn, Derivations derivations) {
            ImmutableList<Parameter> all = drawn.nodesOfType(Parameter.class);
            if (all.isEmpty()) {
                return null;
            }
            FlowPane flow = new FlowPane(14, 4);
            flow.setAlignment(Pos.CENTER_LEFT);
            flow.setPadding(new Insets(0, 0, 2, 0));
            // A FlowPane reports its height for its wrap length, not its width: wrap at the column's width
            // or it asks for rows it will not fill and leaves a blank band beneath.
            flow.prefWrapLengthProperty().bind(column.widthProperty().subtract(4));
            for (Parameter parameter : all) {
                HBox item = new HBox(5);
                item.setAlignment(Pos.CENTER_LEFT);
                item.getChildren().add(concept(parameter.quantity()).orElseGet(() -> plain("parameter")));
                int index = parameter.vertexIndex();
                boolean editable = editable(index);
                // a share-valued parameter reads as a percentage, as it does on the tree; money after its symbol
                Optional<Bounds> bounds = derivations.bounds(index);
                Measure measure = parameter.measure();
                boolean computed = measure.derived() || !measure.stated();
                boolean share = measure.semanticNid() == drawn.terms().shareAtNode()
                        && bounds.map(b -> b.lower() >= 0 && b.upper() <= 1).orElse(false);
                boolean money = measure.semanticNid() != 0 && measure.semanticNid() == semantic(drawn, MoneyCost.class, terms.moneySemantic());
                String display = share || money
                        ? bounds.map(b -> DecisionFigureText.chip(b.lower(), b.upper(), b.lowerOpen(), b.upperOpen(), computed,
                                share ? DecisionFigureText.Unit.SHARE : DecisionFigureText.Unit.MONEY, symbol())).orElse(null)
                        : null;
                MeasureChip chip = new MeasureChip("", bounds, measure, shown, base,
                        editable ? b -> edit(index, b) : null, display);
                chip.explain(DecisionTreeDiagram.explain("", parameter.measure(), derivations.bounds(index), derivations, shown,
                        drawn.terms().shareAtNode()));
                chip.onHover(on -> hover(index, on));
                parameterChips.put(index, chip);
                item.getChildren().add(chip);
                flow.getChildren().add(item);
            }
            return flow;
        }

        /** What the what-if has moved, and the two ways out: back to the block, or into it. */
        private HBox whatIfBar() {
            HBox bar = new HBox(10);
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setPadding(new Insets(2, 6, 2, 6));
            bar.setStyle("-fx-background-color: #fff8e6; -fx-background-radius: 6; -fx-border-color: #e6c87a;"
                    + " -fx-border-radius: 6;");
            Label what = new Label("What-if: " + (moves == 1 ? "one value moved" : moves + " values moved")
                    + (rewriter == null || modelId != null ? (modelId != null ? "; the model is unchanged" : "; the block is unchanged") : ""));
            what.setFont(Font.font(base * 0.9));
            bar.getChildren().add(what);
            Hyperlink revert = new Hyperlink("Revert");
            revert.setFont(Font.font(base * 0.9));
            revert.setOnAction(e -> {
                body = originalBody;
                price = originalPrice;
                moves = 0;
                rebuild();
            });
            bar.getChildren().add(revert);
            if (rewriter != null && modelId == null) {
                Button write = new Button("Write back to the block");
                write.setFont(Font.font(base * 0.9));
                write.setTooltip(new Tooltip("Restates the block in the document with these values"));
                write.setOnAction(e -> {
                    String newInfo = price == originalPrice ? originalInfo
                            : DecisionTreeEdits.withHealthPrice(originalInfo, price);
                    rewriter.rewrite(originalInfo, originalBody, newInfo, body);
                });
                bar.getChildren().add(write);
            }
            return bar;
        }

        /**
         * The drawing was given a width: {@link DecisionTreeFit} says whether to draw it again with
         * narrower or wider names, or in its other form.
         */
        private void fitTo(double width) {
            if (diagram == null || committed == null || width <= 0) {
                return;
            }
            if (fit.given(width, diagram.prefWidth(-1))) {
                fillTop(tree, committed, price, Optional.empty(), committedFindings);
            }
        }

        // ── The diagram's host ──────────────────────────────────────────────

        @Override
        public Optional<Node> concept(OptionalLong nid) {
            return concept(nid, false);
        }

        @Override
        public Optional<Node> concept(OptionalLong nid, boolean wrap) {
            if (nid.isEmpty()) {
                return Optional.empty();
            }
            if (ids.isLocal(nid.getAsLong()) && context.complexConcept(nid.getAsLong()).isPresent()) {
                Node chip = KonceptChips.blockConcept(ids.label(nid.getAsLong()), viewProperties, viewCalc, base, typography);
                return Optional.of(specified(KonceptChips.capped(chip, cap(chip, ids.label(nid.getAsLong()), wrap), wrap), nid.getAsLong()));
            }
            if (ids.isLocal(nid.getAsLong())) {
                Label local = localConcept(ids.label(nid.getAsLong()));
                return Optional.of(specified(cappedLocal(local, cap(local, local.getText(), wrap), wrap), nid.getAsLong()));
            }
            Optional<PublicId> pid = ComponentText.publicId(nid.getAsLong());
            if (pid.isEmpty()) {
                return Optional.empty();
            }
            String sctid = ComponentText.sctid(viewCalc, nid.getAsLong()).orElse(null);
            Node chip = viewProperties != null
                    ? KonceptChips.chip(pid.get(), sctid, viewProperties, base, typography)
                    : KonceptChips.chip(pid.get(), sctid, viewCalc, base, typography);
            if (chip instanceof KonceptBadge badge) {
                badge.setStatusSlotReserved(true);
            }
            // a column is as wide as its widest name: a long one wraps on a run, ellipsises on a node, and opens whole on a click
            return Optional.of(specified(KonceptChips.capped(chip, cap(chip, shown.label(nid.getAsLong()), wrap), wrap), nid.getAsLong()));
        }

        /** The widest a chip may be: the fitted cap, or wider where that would stand a wrapped name in a thin column. */
        private double cap(Node chip, String name, boolean wrap) {
            double cap = base * cap();
            return wrap ? KonceptChips.balancedCap(cap, KonceptChips.naturalWidth(chip), name, base * CHIP_CHROME_EMS) : cap;
        }

        /** How wide a name may be, in ems: the fitted cap in the drawing, the full one elsewhere. */
        private double cap() {
            return drawing ? fit.ems() : CHIP_EMS;
        }

        @Override
        public Optional<Node> foldIn(int outcomeVertex) {
            return tree.node(outcomeVertex)
                    .filter(DecisionElement.Outcome.class::isInstance)
                    .map(DecisionElement.Outcome.class::cast)
                    .flatMap(outcome -> {
                        java.util.OptionalLong reference = outcome.treeReference();
                        if (reference.isEmpty()) {
                            return Optional.empty();
                        }
                        String identifier = ids.identifier(reference.getAsLong()).orElse("");
                        return Optional.of(treeLink("folds in", shown.label(reference.getAsLong()), identifier));
                    });
        }

        @Override
        public Optional<Node> statements(int vertexIndex) {
            List<String> names = parsed.documentStatements(vertexIndex);
            // a decision whose concept is a complex concept is made in the situation its prerequisites state
            OptionalLong situation = tree.node(vertexIndex)
                    .filter(DecisionElement.Decision.class::isInstance)
                    .map(DecisionElement::concept)
                    .filter(c -> c.isPresent() && context.complexConcept(c.getAsLong()).isPresent())
                    .orElse(OptionalLong.empty());
            if (names.isEmpty() && situation.isEmpty()) {
                return Optional.empty();
            }
            Node mark = statementMark(names, situation);
            if (reviewing) {
                // a review shows a node's statements in a part of its own, and does not edit them
                if (mark instanceof javafx.scene.control.Control control && control.getTooltip() != null) {
                    control.getTooltip().setText(control.getTooltip().getText()
                            .replace("Click to see and edit the statement", "Click to see the statement"));
                }
                mark.setOnMouseClicked(e -> {
                    select(vertexIndex);
                    e.consume();
                });
            }
            return Optional.of(mark);
        }

        @Override
        public boolean editable(int measureVertex) {
            return parsed.lineOf(measureVertex).isPresent()
                    && (tree.measure(measureVertex).stated() || parsed.parameterStatement(measureVertex).isPresent());
        }

        @Override
        public void edit(int measureVertex, Bounds bounds) {
            OptionalInt line = parsed.lineOf(measureVertex);
            if (line.isEmpty()) {
                return;
            }
            // a value moved on a rendering is the model's: written there, every rendering that names the model redraws
            if (model != null && moves == 0 && model.writable() && model.write(line.getAsInt(), bounds)) {
                return;
            }
            DecisionTreeEdits.restate(body, line.getAsInt(), bounds).ifPresent(restated -> {
                if (!restated.equals(body)) {
                    body = restated;
                    moves++;
                    rebuild();
                }
            });
        }

        @Override
        public void toggle(int nodeVertex) {
            if (!collapsed.remove(nodeVertex)) {
                collapsed.add(nodeVertex);
            }
            rebuild();
        }

        @Override
        public void hover(int measureVertex, boolean on) {
            // the tornado is another rendering of the model
        }

        private String moneyUnit() {
            long semantic = semantic(tree, MoneyCost.class, terms.moneySemantic());
            return unitName(semantic, "");
        }

        /** The sign the block's money is written with, when its unit has a familiar one. */
        private String symbol() {
            return DecisionFigureText.symbolFor(moneyUnit());
        }

        String alternativeName(int alternativeVertex) {
            if (alternativeVertex < 0) {
                return "no sure choice";
            }
            return tree.node(alternativeVertex).flatMap(n -> n.concept().isPresent()
                    ? Optional.of(shown.label(n.concept().getAsLong())) : Optional.empty()).orElse("an alternative");
        }



        /** The semantic a tree's costs of a kind are on, taken from the first such cost; the parent semantic otherwise. */
        private long semantic(DecisionTree of, Class<? extends Cost> kind, long fallback) {
            ImmutableList<? extends Cost> costs = of.nodesOfType(kind);
            return costs.isEmpty() ? fallback : costs.get(0).measure().semanticNid();
        }
    }

    private Node findingsOnly(List<ProfileFinding> findings) {
        VBox column = new VBox(2);
        Label heading = new Label("Decision tree not read");
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

    private Label plain(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(base));
        return label;
    }

    /**
     * A concept the knowledge base lacks, drawn as a chip with a dashed edge: the block's own
     * label, and a tooltip saying why it is not the shared Koncept chip.
     */
    /**
     * The chip with, when a complex-concept block of the document specifies the concept, what it is
     * as its tooltip and, on its menu, a peek at the card that shows and edits the specification.
     */
    private Node specified(Node chip, long nid) {
        context.complexConcept(nid).ifPresent(spec -> {
            chip.setId("complex-concept-chip");
            Tooltip tip = new Tooltip(ComplexConceptText.describe(spec));
            tip.setWrapText(true);
            tip.setMaxWidth(420);
            if (chip instanceof javafx.scene.control.Control control) {
                control.setTooltip(tip);
            } else {
                Tooltip.install(chip, tip);
            }
            if (chip instanceof KonceptBadge badge) {
                badge.setStatus(dev.ikm.komet.framework.controls.KonceptStatus.COMPLEX);
                badge.setStatusSlotReserved(true);
                badge.setOnDisclose(() -> peekItem(badge, nid).fire());
                KonceptBadge.contributeMenuItems(badge, (host, n) -> List.of(peekItem(host, n)));
            } else if (chip instanceof javafx.scene.control.Control control) {
                control.setContextMenu(new javafx.scene.control.ContextMenu(peekItem(chip, nid)));
            } else {
                javafx.scene.control.ContextMenu menu = new javafx.scene.control.ContextMenu(peekItem(chip, nid));
                chip.setOnContextMenuRequested(e -> menu.show(chip, e.getScreenX(), e.getScreenY()));
            }
        });
        return chip;
    }

    /**
     * A link to another tree of the document: the words, the tree's name, and a click that scrolls
     * the surface to it; a tree the document does not draw is named but goes nowhere.
     */
    private Node treeLink(String words, String name, String identifier) {
        Label link = new Label(words + " \u201C" + name + "\u201D \u2197");
        link.getStyleClass().add("decision-tree-link");
        link.setId("tree-link-" + identifier);
        link.setFont(Font.font(base * 0.85));
        boolean drawn = context.treeNode(identifier).isPresent();
        link.setStyle("-fx-text-fill: " + (drawn ? "#1f5fa8" : "#8a5a00") + "; -fx-underline: " + drawn + ";");
        if (drawn) {
            link.setCursor(javafx.scene.Cursor.HAND);
            link.setTooltip(new Tooltip("Go to the tree \u201C" + name + "\u201D"));
            link.setOnMouseClicked(e -> {
                context.treeNode(identifier).ifPresent(SurfaceNavigation::reveal);
                e.consume();
            });
        } else {
            link.setTooltip(new Tooltip("No block of this document draws the tree \u201C" + name + "\u201D yet"));
        }
        return link;
    }

    /** The statement type of an ANF statement. */
    static TreeStatements.Kind kind(AnfStatement statement) {
        return switch (statement.circumstance()) {
            case Circumstance.Request r -> TreeStatements.Kind.REQUEST;
            case Circumstance.Performance p -> TreeStatements.Kind.PERFORMANCE;
            case Circumstance.Narrative n -> TreeStatements.Kind.NARRATIVE;
        };
    }

    /**
     * The mark beside a node's name that it holds ANF statements: resting on it shows each
     * statement as written, a click peeks at each one's card, editable, writing back to its block.
     */
    private Node statementMark(List<String> names, OptionalLong situation) {
        Label mark = new Label("ANF");
        mark.setId("anf-statement-mark");
        mark.setFont(Font.font(null, FontWeight.BOLD, base * 0.7));
        mark.setStyle("-fx-background-color: #e6eef8; -fx-text-fill: #1f3b73; -fx-background-radius: 4;"
                + " -fx-padding: 0 4 0 4; -fx-border-color: #9db3d6; -fx-border-radius: 4; -fx-border-width: 1;");
        mark.setCursor(javafx.scene.Cursor.HAND);
        List<String> written = new ArrayList<>();
        situation.ifPresent(nid -> context.complexConcept(nid).ifPresent(spec ->
                written.add("The situation, as the complex concept's prerequisites state it:\n" + ComplexConceptText.describe(spec))));
        for (String name : names) {
            written.add(context.statement(name)
                    .map(statement -> "anf:" + name + "\n" + AnfAdoc.toAdoc(statement).strip())
                    .orElse("anf:" + name + ": no anf block before the tree names it"));
        }
        Tooltip tip = new Tooltip(String.join("\n\n", written) + "\n\nClick to see and edit the statement");
        tip.setFont(Font.font("Menlo", base * 0.8));
        mark.setTooltip(tip);
        mark.setOnMouseClicked(e -> {
            VBox cards = new VBox(6);
            situation.ifPresent(nid -> context.complexConceptCard(nid).ifPresent(card -> {
                Label heading = new Label("The situation: the complex concept's prerequisites, each a performance statement");
                heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.85));
                cards.getChildren().addAll(heading, card.get());
            }));
            for (String name : names) {
                context.statementCard(name).ifPresent(card -> {
                    Label heading = new Label("anf:" + name + ", " + context.statement(name)
                            .map(statement -> kind(statement).word() + " statement").orElse("statement"));
                    heading.setFont(Font.font(null, FontWeight.BOLD, base * 0.85));
                    cards.getChildren().addAll(heading, card.get());
                });
            }
            if (cards.getChildren().isEmpty()) {
                return;
            }
            javafx.geometry.Bounds at = mark.localToScreen(mark.getBoundsInLocal());
            double x = at == null ? 0 : at.getMinX() + at.getWidth() / 2;
            double y = at == null ? 0 : at.getMaxY();
            dev.ikm.komet.framework.controls.PeekPopover.show(cards, PEEK_WIDTH, "ANF statement", mark, x, y, null, () -> { });
            e.consume();
        });
        return mark;
    }

    /** The menu item that peeks at the complex concept's card beside the chip; the peek stays editable. */
    private javafx.scene.control.MenuItem peekItem(Node chip, long nid) {
        javafx.scene.control.MenuItem item = new javafx.scene.control.MenuItem("Edit complex concept");
        item.setOnAction(e -> context.complexConceptCard(nid).ifPresent(card -> {
            javafx.geometry.Bounds at = chip.localToScreen(chip.getBoundsInLocal());
            double x = at == null ? 0 : at.getMinX() + at.getWidth() / 2;
            double y = at == null ? 0 : at.getMaxY();
            dev.ikm.komet.framework.controls.PeekPopover.show(card.get(), PEEK_WIDTH, "Complex concept", chip, x, y, null, () -> { });
        }));
        return item;
    }

    /** The widest a concept's chip may be in the drawing, in ems of the base size; a longer name ellipsises. */
    private static final double CHIP_EMS = 16;
    /** The narrowest a name is drawn to fit a tree to its region; narrower still, the tree is scaled. */
    private static final double MIN_CHIP_EMS = 8;
    /** What a chip takes beside its name, in ems: its identicon, status mark and padding. */
    private static final double CHIP_CHROME_EMS = 3;

    /** A local concept's chip no wider than a cap: a long name wraps on a run and ellipsises on a node, as a badge does. */
    private static Label cappedLocal(Label chip, double cap, boolean wrap) {
        if (KonceptChips.naturalWidth(chip) + 2 > cap) {
            chip.setWrapText(wrap);
            chip.setPrefWidth(cap);
            chip.setMaxWidth(cap);
        }
        return chip;
    }

    /** The width a peeked card lays out at: the workspace's default window width, or the card clips. */
    private static final double PEEK_WIDTH = dev.ikm.komet.kview.controls.KLWorkspace.DEFAULT_WINDOW_WIDTH;

    private Label localConcept(String text) {
        Label chip = new Label(text);
        chip.setFont(Font.font(base * 0.95));
        chip.getStyleClass().add("decision-local-concept");
        // the padding set here, not in the style, so the chip's width is known before it is shown
        chip.setPadding(new Insets(1, 7, 1, 7));
        chip.setStyle("-fx-background-color: #f4f2ec; -fx-background-radius: 9;"
                + " -fx-text-fill: #333; -fx-border-color: #b9b3a3; -fx-border-radius: 9; -fx-border-style: dashed;"
                + " -fx-border-width: 1;");
        chip.setTooltip(new Tooltip("Not in the knowledge base: read as a concept local to this block"));
        return chip;
    }
}
