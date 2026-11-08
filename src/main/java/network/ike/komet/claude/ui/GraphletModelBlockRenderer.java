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

import network.ike.komet.claude.stamp.KonceptChips;

import network.ike.komet.claude.stamp.BlockRewriter;

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptLabelTypography;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.komet.markdown.richtext.BlockRenderer;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
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
import network.ike.komet.claude.decision.StatementMeasures;
import network.ike.komet.claude.stamp.DecisionFigureText;
import network.ike.komet.claude.stamp.ComponentText;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * The live rendering of a {@code graphlet-model} block ({@code dev-decision-point-knowledge},
 * settled with KEC 2026-11-06): the model of a decision, structure and parameters together, as
 * one versioned property graph. The block draws what the model holds and nothing it computes:
 * its parameters, each with the statement it reads, the value the tree runs at and the range the
 * statement determined; and a sentence on the structure, which the document's
 * {@code [decision-tree, model=…]} rendering draws. It publishes the model on the document's
 * {@link ProfileBlocks}, as an {@code anf} block publishes its statement, so the renderings that
 * name it draw it and redraw when it moves.
 *
 * <p>A value edited here, or committed from a rendering with "Use this value", restates the one
 * line of the block that holds it, as an ordinary measure with equal bounds, and hands the text to
 * the surface's {@link BlockRewriter}: a committed value is a new version of the model. A surface
 * without a rewriter shows the model read-only.
 */
final class GraphletModelBlockRenderer implements network.ike.komet.claude.stamp.StructuredBlockKind.FenceRenderer {

    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final ProfileBlocks context;
    private KonceptLabelTypography typography = KonceptLabelTypography.DEFAULT;
    private BlockRewriter rewriter;

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
        return List.of(new BlockPiece(() -> new Live(info, body, id.orElse(null)).column, body.stripTrailing()));
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
            if (restated.equals(body)) {
                return;
            }
            String was = written;
            body = restated;
            rebuild();
            if (rewriter != null) {
                rewriter.rewrite(info, was, info, restated);
                written = restated;
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
                context.defineModel(new GraphletModel(id, info, body, rewriter == null ? Optional.empty() : Optional.of(this::take)), order);
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
            column.getChildren().add(header());
            Derivations derivations = Derivations.of(tree);
            Node parameters = parameters(derivations);
            if (parameters != null) {
                column.getChildren().add(parameters);
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

        /** The title row: what the model is, its id, its root, and the structure in a sentence. */
        private Node header() {
            FlowPane header = new FlowPane(8, 4);
            header.setAlignment(Pos.CENTER_LEFT);
            header.prefWrapLengthProperty().bind(column.widthProperty().subtract(4));
            Label title = new Label("Graphlet model");
            title.setFont(Font.font(null, FontWeight.BOLD, base));
            header.getChildren().add(title);
            if (id != null) {
                Label name = new Label(id);
                name.setId("graphlet-model-id");
                name.setFont(Font.font("Menlo", base * 0.85));
                name.setStyle("-fx-text-fill: #666;");
                header.getChildren().add(name);
            }
            tree.root().ifPresent(root -> concept(root.concept()).ifPresent(header.getChildren()::add));
            Label structure = new Label(structure());
            structure.setId("graphlet-model-structure");
            structure.setFont(Font.font(base * 0.85));
            structure.setStyle("-fx-text-fill: #666;");
            header.getChildren().add(structure);
            return header;
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

        /** The model's parameters, each its name and the value the tree runs at, a statement's beside the range it determined. */
        private Node parameters(Derivations derivations) {
            ImmutableList<Parameter> all = tree.nodesOfType(Parameter.class);
            if (all.isEmpty()) {
                return null;
            }
            VBox rows = new VBox(3);
            rows.setId("graphlet-model-parameters");
            rows.setPadding(new Insets(2, 0, 2, 0));
            long money = moneySemantic();
            for (Parameter parameter : all) {
                HBox row = new HBox(6);
                row.setAlignment(Pos.CENTER_LEFT);
                int index = parameter.vertexIndex();
                Optional<String> statement = parsed.parameterStatement(index);
                row.getChildren().add(statement.isPresent()
                        ? statementName(parameter, statement.get())
                        : concept(parameter.quantity()).orElseGet(() -> plain("parameter")));
                Optional<Bounds> bounds = derivations.bounds(index);
                Measure measure = parameter.measure();
                boolean computed = measure.derived() || !measure.stated();
                boolean share = measure.semanticNid() == terms.shareAtNode()
                        && bounds.map(b -> b.lower() >= 0 && b.upper() <= 1).orElse(false);
                boolean isMoney = measure.semanticNid() != 0 && measure.semanticNid() == money;
                String symbol = DecisionFigureText.symbolFor(money == 0 ? "" : shown.label(money));
                String display = share || isMoney
                        ? bounds.map(b -> DecisionFigureText.chip(b.lower(), b.upper(), b.lowerOpen(), b.upperOpen(), computed,
                                share ? DecisionFigureText.Unit.SHARE : DecisionFigureText.Unit.MONEY, symbol)).orElse(null)
                        : null;
                boolean editable = parsed.lineOf(index).isPresent() && (measure.stated() || statement.isPresent()) && rewriter != null;
                MeasureChip chip = new MeasureChip("", bounds, measure, shown, base, editable ? b -> edit(index, b) : null, display);
                chip.setId("model-value-" + index);
                chip.explain(DecisionTreeDiagram.explain("", measure, bounds, derivations, shown, terms.shareAtNode()));
                row.getChildren().add(chip);
                // the statement's range, which a tornado sweeps, and whether the value is the model's or the midpoint
                parsed.range(index).ifPresent(range -> {
                    boolean held = statement.isPresent() && measure.stated()
                            && parsed.lineOf(index).isPresent() && lineStatesValue(parsed.lineOf(index).getAsInt());
                    String printed = range.isPoint()
                            ? "the statement states " + print(range.lower(), share, isMoney, symbol)
                            : "the statement's range " + print(range.lower(), share, isMoney, symbol) + " to " + print(range.upper(), share, isMoney, symbol)
                            + (held ? "" : ", run at its midpoint");
                    Label words = new Label(printed);
                    words.setFont(Font.font(base * 0.8));
                    words.setStyle("-fx-text-fill: #666;");
                    row.getChildren().add(words);
                });
                // source=<id> on the line: a citation mark naming what the document defines in its prose, a
                // footnote or a bibliography entry; the block stores only the reference
                tree.conceptProperty(index, terms.basisSource()).ifPresent(nid -> {
                    String cited = ids.identifier(nid).orElse(ids.label(nid));
                    Label mark = new Label("[" + cited + "]");
                    mark.setId("model-source-" + index);
                    mark.setFont(Font.font(base * 0.75));
                    mark.setStyle("-fx-text-fill: #1f5fa8;");
                    mark.setTooltip(new Tooltip("Cites " + cited + ", a footnote or bibliography entry the document defines"));
                    row.getChildren().add(mark);
                });
                rows.getChildren().add(row);
            }
            return rows;
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

        /** The name of a parameter that reads a statement: what the statement is about, the statement's name on rest. */
        private Node statementName(Parameter parameter, String statement) {
            String label = parameter.quantity().isPresent() ? shown.label(parameter.quantity().getAsLong()) : statement;
            Label name = new Label(label);
            name.setFont(Font.font(base * 0.95));
            name.getStyleClass().add("graphlet-model-statement");
            name.setPadding(new Insets(1, 7, 1, 7));
            name.setStyle("-fx-background-color: #e6eef8; -fx-background-radius: 9; -fx-text-fill: #1f3b73;"
                    + " -fx-border-color: #9db3d6; -fx-border-radius: 9; -fx-border-width: 1;");
            name.setWrapText(true);
            name.setMaxWidth(base * 28);
            Tooltip tip = new Tooltip("Reads the statement anf:" + statement
                    + (context.statement(statement).isPresent() ? "" : ", which no anf block of the document states yet"));
            tip.setWrapText(true);
            name.setTooltip(tip);
            name.setCursor(Cursor.HAND);
            name.setOnMouseClicked(e -> context.statementCard(statement).ifPresent(card -> {
                javafx.geometry.Bounds at = name.localToScreen(name.getBoundsInLocal());
                double x = at == null ? 0 : at.getMinX() + at.getWidth() / 2;
                double y = at == null ? 0 : at.getMaxY();
                dev.ikm.komet.framework.controls.PeekPopover.show(card.get(), dev.ikm.komet.kview.controls.KLWorkspace.DEFAULT_WINDOW_WIDTH,
                        "ANF statement", name, x, y, null, () -> { });
                e.consume();
            }));
            return name;
        }

        /** A concept as a chip: the store's, or a dashed label for one local to the block. */
        private Optional<Node> concept(OptionalLong nid) {
            if (nid.isEmpty()) {
                return Optional.empty();
            }
            if (ids.isLocal(nid.getAsLong())) {
                Label local = new Label(ids.label(nid.getAsLong()));
                local.setFont(Font.font(base * 0.95));
                local.getStyleClass().add("decision-local-concept");
                local.setPadding(new Insets(1, 7, 1, 7));
                local.setStyle("-fx-background-color: #f4f2ec; -fx-background-radius: 9;"
                        + " -fx-text-fill: #333; -fx-border-color: #b9b3a3; -fx-border-radius: 9; -fx-border-style: dashed;"
                        + " -fx-border-width: 1;");
                local.setTooltip(new Tooltip("Not in the knowledge base: read as a concept local to this block"));
                return Optional.of(local);
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
            return Optional.of(chip);
        }
    }

    private Node findingsOnly(List<ProfileFinding> findings) {
        VBox column = new VBox(2);
        Label heading = new Label("Graphlet model not read");
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
}
