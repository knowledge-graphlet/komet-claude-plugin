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

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.Polyline;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeFeedback;
import network.ike.komet.claude.decision.DecisionTreeLayout;
import network.ike.komet.claude.decision.DecisionTreeLayout.Kind;
import network.ike.komet.claude.decision.DecisionTreeLayout.Placed;
import network.ike.komet.claude.decision.DecisionTreeLayout.Run;
import network.ike.komet.claude.decision.Derivations;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.Rollback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A decision tree drawn as decision analysts draw it, on the {@link DecisionTreeLayout}: left to
 * right from the root, squares, circles and triangles, every branch an elbow carrying its name
 * and probability above the run and its costs below, the rolled-back expected cost beside every
 * glyph, the chosen alternative drawn heavy and each rejected one barred where it leaves the
 * square. The drawing is live: a stated number is a chip the reader can click and retype, which
 * asks the host to restate the block's line and redraw; a circle or a square folds on a click;
 * resting on a branch lights the path from it back to the root. The diagram is built once per
 * tree and rollback, and the host builds a new one when either changes.
 */
final class DecisionTreeDiagram extends Region {

    /** What the diagram asks of the surface it is drawn on. */
    interface Host {
        /** The chip for a concept, or empty when nothing names it. */
        Optional<Node> concept(OptionalLong nid);

        /** Whether a stated measure can be moved as a what-if. */
        boolean editable(int measureVertex);

        /** Restates a stated measure as other bounds. */
        void edit(int measureVertex, Bounds bounds);

        /** Folds or opens a node. */
        void toggle(int nodeVertex);

        /** The pointer entered or left a measure's chip. */
        default void hover(int measureVertex, boolean on) {
        }
    }

    private static final Color INK = Color.web("#333");
    private static final Color CHOSEN = Color.web("#1f3b73");
    private static final Color GAP = Color.web("#b5541a");
    private static final Color HOT = Color.web("#d98b1a");
    /** The room the two bars of a rejected alternative take on its run, before its labels. */
    private static final double BAR_ROOM = 16;

    private final DecisionTree tree;
    private final Rollback rollback;
    private final Derivations derivations;
    private final Identities ids;
    private final Host host;
    private final Set<Integer> collapsed;
    private final double base;
    private final double lineHeight;
    private final double glyph;

    private final Map<Integer, Node> branchAbove = new HashMap<>();
    private final Map<Integer, Node> branchBelow = new HashMap<>();
    private final Map<Integer, Polyline> runs = new HashMap<>();
    private final Map<Integer, Node> bars = new HashMap<>();
    private final Map<Integer, Node> glyphs = new HashMap<>();
    private final Map<Integer, MeasureChip> chips = new HashMap<>();
    private final Map<Integer, Node> valueLabels = new HashMap<>();
    private final Map<Integer, Node> nameLabels = new HashMap<>();
    private final Map<Integer, List<String>> findingsAt = new HashMap<>();
    private final ReadOnlyDoubleWrapper drawnWidth = new ReadOnlyDoubleWrapper();
    private final ReadOnlyDoubleWrapper drawnHeight = new ReadOnlyDoubleWrapper();
    private DecisionTreeLayout layout;

    DecisionTreeDiagram(DecisionTree tree, Rollback rollback, Identities ids, List<ProfileFinding> findings,
                        Set<Integer> collapsed, double base, Host host) {
        this.tree = tree;
        this.rollback = rollback;
        this.derivations = rollback.derivations();
        this.ids = ids;
        this.host = host;
        this.collapsed = collapsed;
        this.base = base;
        this.lineHeight = Math.ceil(base * 1.35);
        this.glyph = Math.round(base * 1.1);
        getStyleClass().add("decision-tree-diagram");
        setCursor(Cursor.DEFAULT);
        for (ProfileFinding finding : findings) {
            if (finding.vertexIndex() >= 0) {
                findingsAt.computeIfAbsent(nearestNode(finding.vertexIndex()), k -> new ArrayList<>())
                        .add(DecisionTreeFeedback.describe(tree, ids, finding));
            }
        }
        for (ProfileFinding finding : rollback.findings()) {
            if (finding.vertexIndex() >= 0) {
                findingsAt.computeIfAbsent(nearestNode(finding.vertexIndex()), k -> new ArrayList<>())
                        .add(DecisionTreeFeedback.describe(tree, ids, finding));
            }
        }
        build();
    }

    /** The width the drawing needs, as last laid out. */
    ReadOnlyDoubleProperty drawnWidthProperty() {
        return drawnWidth.getReadOnlyProperty();
    }

    /** The height the drawing needs, as last laid out. */
    ReadOnlyDoubleProperty drawnHeightProperty() {
        return drawnHeight.getReadOnlyProperty();
    }

    /** The layout the diagram was last drawn on, for a test or a print. */
    DecisionTreeLayout geometry() {
        if (layout == null) {
            layout = DecisionTreeLayout.of(tree, sizing(), collapsed::contains);
        }
        return layout;
    }

    // ── Building the parts ───────────────────────────────────────────────────

    private void build() {
        Optional<DecisionElement> root = tree.root();
        if (root.isEmpty() || !(root.get() instanceof DecisionElement.Node rootNode)) {
            return;
        }
        node(rootNode);
    }

    private void node(DecisionElement.Node node) {
        int index = node.vertexIndex();
        boolean folded = collapsed.contains(index);
        List<Branch> branches = tree.children(index, Branch.class).castToList();
        boolean terminal = node instanceof Outcome || folded || branches.isEmpty();
        Shape shape = glyphFor(node, folded && !branches.isEmpty());
        glyphs.put(index, shape);
        getChildren().add(shape);

        Optional<Bounds> value = rollback.value(index);
        Label valueLabel = label(value.map(bounds -> "⇒ " + DecisionTreeBlockRenderer.money(bounds))
                .orElse("⇒ ?"), true, value.isPresent() ? INK : GAP);
        if (findingsAt.containsKey(index)) {
            valueLabel.setTooltip(new Tooltip(String.join("\n", findingsAt.get(index))));
            shape.setStroke(GAP);
            shape.getStrokeDashArray().setAll(3.0, 3.0);
        }
        HBox nameLine = new HBox(4);
        nameLine.setAlignment(Pos.CENTER_LEFT);
        if (node instanceof Branch) {
            // a branch that ends: its name is on its run already
        } else {
            host.concept(node.concept()).ifPresent(nameLine.getChildren()::add);
        }
        if (terminal) {
            // value and name on one line, the outcome's own costs beneath
            HBox top = new HBox(6, valueLabel);
            top.setAlignment(Pos.CENTER_LEFT);
            top.getChildren().addAll(nameLine.getChildren());
            valueLabels.put(index, top);
            getChildren().add(top);
            if (node instanceof Outcome outcome) {
                HBox costs = costs(outcome.costs().castToList());
                nameLabels.put(index, costs);
                getChildren().add(costs);
            } else if (folded) {
                Label hidden = label(foldedWord(index), false, Color.web("#777"));
                nameLabels.put(index, hidden);
                getChildren().add(hidden);
            }
        } else {
            valueLabels.put(index, valueLabel);
            nameLabels.put(index, nameLine);
            getChildren().addAll(valueLabel, nameLine);
        }
        if (!terminal) {
            OptionalInt chosen = rollback.chosen(index);
            for (Branch branch : branches) {
                branch(branch, chosen.isPresent(), chosen.orElse(-1) == branch.vertexIndex());
                branch.next().ifPresent(this::node);
            }
        }
        if (!(node instanceof Outcome) && !branches.isEmpty()) {
            shape.setCursor(Cursor.HAND);
            shape.setOnMouseClicked(e -> host.toggle(index));
            Tooltip.install(shape, new Tooltip(folded ? "Click to open what is folded here" : "Click to fold"));
        }
    }

    private void branch(Branch branch, boolean decided, boolean chosen) {
        int index = branch.vertexIndex();
        Polyline run = new Polyline();
        run.setStroke(chosen ? CHOSEN : INK);
        run.setStrokeWidth(chosen ? 2.6 : 1.2);
        run.setStrokeLineJoin(StrokeLineJoin.ROUND);
        run.setStrokeLineCap(StrokeLineCap.ROUND);
        run.setFill(null);
        if (rollback.value(index).isEmpty()) {
            run.getStrokeDashArray().setAll(4.0, 4.0);
            run.setStroke(GAP);
        }
        runs.put(index, run);
        getChildren().add(run);

        HBox above = new HBox(5);
        above.setAlignment(Pos.CENTER_LEFT);
        host.concept(branch.concept()).ifPresent(above.getChildren()::add);
        if (branch instanceof Event event) {
            Optional<DecisionElement.Probability> probability = event.probability();
            if (probability.isPresent()) {
                above.getChildren().add(number("p", probability.get().vertexIndex(), probability.get().measure()));
            } else {
                above.getChildren().add(label(event.residual() ? "p (residual)" : "p ?", false,
                        event.residual() ? Color.web("#666") : GAP));
            }
        }
        if (branch instanceof Alternative) {
            Optional<Bounds> value = rollback.value(index);
            above.getChildren().add(label(value.map(bounds -> "⇒ " + DecisionTreeBlockRenderer.money(bounds))
                    .orElse("⇒ ?"), true, value.isPresent() ? (chosen ? CHOSEN : INK) : GAP));
        }
        if (chosen) {
            above.getChildren().add(label("✓ chosen", true, CHOSEN));
        }
        branchAbove.put(index, above);
        getChildren().add(above);

        HBox below = costs(branch.costs().castToList());
        branchBelow.put(index, below);
        getChildren().add(below);

        if (branch instanceof Alternative && decided && !chosen) {
            Line one = new Line(0, 0, 0, 12);
            Line two = new Line(5, 0, 5, 12);
            one.setStroke(INK);
            two.setStroke(INK);
            one.setStrokeWidth(1.6);
            two.setStrokeWidth(1.6);
            StackPane barred = new StackPane();
            barred.getChildren().addAll(one, two);
            barred.setAlignment(Pos.TOP_LEFT);
            Tooltip.install(barred, new Tooltip("Rejected: the rollback chose another alternative"));
            bars.put(index, barred);
            getChildren().add(barred);
        }
        Consumer<Boolean> hot = on -> light(index, on);
        for (Node part : List.of(run, above, below)) {
            part.setOnMouseEntered(e -> hot.accept(true));
            part.setOnMouseExited(e -> hot.accept(false));
        }
    }

    /** The costs a branch or an outcome carries, each a chip the reader may move when stated. */
    private HBox costs(List<Cost> costs) {
        HBox below = new HBox(8);
        below.setAlignment(Pos.CENTER_LEFT);
        for (Cost cost : costs) {
            String word = cost instanceof MoneyCost ? "money" : "health";
            below.getChildren().add(number(word, cost.vertexIndex(), cost.measure()));
        }
        return below;
    }

    /** A measure as a chip, offered for a what-if when the host says it is stated and editable. */
    private Node number(String word, int vertexIndex, Measure measure) {
        Optional<Bounds> bounds = derivations.bounds(vertexIndex);
        boolean editable = !measure.derived() && host.editable(vertexIndex);
        MeasureChip chip = new MeasureChip(word, bounds, measure, ids, base, editable ? b -> host.edit(vertexIndex, b) : null);
        chip.onHover(on -> host.hover(vertexIndex, on));
        chips.put(vertexIndex, chip);
        return chip;
    }

    private Shape glyphFor(DecisionElement.Node node, boolean folded) {
        Shape shape = switch (node) {
            case DecisionElement.Decision d -> {
                Rectangle square = new Rectangle(glyph, glyph);
                square.setFill(Color.web("#e8eef9"));
                yield square;
            }
            case DecisionElement.Chance c -> {
                Circle circle = new Circle(glyph / 2);
                circle.setFill(Color.web("#fff3e0"));
                yield circle;
            }
            case Outcome o -> {
                Polygon triangle = new Polygon(0, 0, glyph, glyph / 2, 0, glyph);
                triangle.setFill(Color.web("#eaf4e8"));
                yield triangle;
            }
            case Branch b -> {
                Line bar = new Line(0, 0, 0, glyph);
                yield bar;
            }
        };
        shape.setStroke(INK);
        shape.setStrokeWidth(1.4);
        if (folded) {
            shape.setFill(Color.web("#d9d9d9"));
        }
        return shape;
    }

    private String foldedWord(int index) {
        int count = countBeneath(index) - 1;
        return count == 1 ? "1 node folded" : count + " nodes folded";
    }

    private int countBeneath(int index) {
        int count = 1;
        for (DecisionElement.Node child : tree.children(index, DecisionElement.Node.class)) {
            count += countBeneath(child.vertexIndex());
        }
        return count;
    }

    /** The node a finding on a measure sits under, so it shows at a glyph. */
    private int nearestNode(int vertexIndex) {
        int index = vertexIndex;
        while (tree.node(index).map(n -> !(n instanceof DecisionElement.Node)).orElse(false)) {
            OptionalInt parent = tree.parentIndex(index);
            if (parent.isEmpty()) {
                break;
            }
            index = parent.getAsInt();
        }
        return index;
    }

    /** The chip a measure is drawn as, when it is drawn on a branch or an outcome. */
    Optional<MeasureChip> chip(int measureVertex) {
        return Optional.ofNullable(chips.get(measureVertex));
    }

    /** Lights a measure: its chip, and the path from the branch that carries it back to the root. */
    void lightMeasure(int measureVertex, boolean on) {
        MeasureChip chip = chips.get(measureVertex);
        if (chip != null) {
            chip.setHot(on);
        }
        int index = measureVertex;
        while (tree.node(index).map(n -> !(n instanceof Branch)).orElse(true)) {
            OptionalInt parent = tree.parentIndex(index);
            if (parent.isEmpty()) {
                return;
            }
            index = parent.getAsInt();
        }
        light(index, on);
    }

    private void light(int branchVertex, boolean on) {
        int index = branchVertex;
        Set<Integer> seen = new HashSet<>();
        while (seen.add(index)) {
            Polyline run = runs.get(index);
            if (run != null) {
                boolean chosen = tree.parentIndex(index).isPresent()
                        && rollback.chosen(tree.parentIndex(index).getAsInt()).orElse(-1) == index;
                run.setStroke(on ? HOT : rollback.value(index).isEmpty() ? GAP : chosen ? CHOSEN : INK);
                run.setStrokeWidth(on ? 3 : chosen ? 2.6 : 1.2);
            }
            OptionalInt parent = tree.parentIndex(index);
            if (parent.isEmpty()) {
                break;
            }
            index = parent.getAsInt();
        }
    }

    private Label label(String text, boolean bold, Color colour) {
        Label label = new Label(text);
        label.setFont(Font.font(null, bold ? FontWeight.BOLD : FontWeight.NORMAL, base * 0.9));
        label.setTextFill(colour);
        return label;
    }

    // ── Laying the parts out on the geometry ─────────────────────────────────

    private DecisionTreeLayout.Sizing sizing() {
        return new DecisionTreeLayout.Sizing() {
            @Override
            public double rowHeight() {
                return 2 * lineHeight + 10;
            }

            @Override
            public double glyph() {
                return glyph;
            }

            @Override
            public double gap() {
                return 10;
            }

            @Override
            public double margin() {
                return 6;
            }

            @Override
            public double branchLabelWidth(int branchVertex) {
                return Math.max(widthOf(branchAbove.get(branchVertex)), widthOf(branchBelow.get(branchVertex))) + 14
                        + (bars.containsKey(branchVertex) ? BAR_ROOM : 0);
            }

            @Override
            public double nodeLabelWidth(int nodeVertex) {
                return Math.max(widthOf(valueLabels.get(nodeVertex)), widthOf(nameLabels.get(nodeVertex))) + 4;
            }
        };
    }

    private static double widthOf(Node node) {
        return node == null ? 0 : Math.max(0, node.prefWidth(-1));
    }

    @Override
    protected double computePrefWidth(double height) {
        layout = DecisionTreeLayout.of(tree, sizing(), collapsed::contains);
        return layout.width();
    }

    @Override
    protected double computePrefHeight(double width) {
        layout = DecisionTreeLayout.of(tree, sizing(), collapsed::contains);
        return layout.height();
    }

    @Override
    protected double computeMinWidth(double height) {
        return computePrefWidth(height);
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void layoutChildren() {
        layout = DecisionTreeLayout.of(tree, sizing(), collapsed::contains);
        for (Run run : layout.runs()) {
            Polyline line = runs.get(run.vertexIndex());
            if (line != null) {
                line.getPoints().setAll(run.x0(), run.y0(), run.xBend(), run.y0(), run.xBend(), run.y(), run.x1(), run.y());
            }
            Node barred = bars.get(run.vertexIndex());
            double labelX = run.xBend() + 8 + (barred == null ? 0 : BAR_ROOM);
            place(branchAbove.get(run.vertexIndex()), labelX, run.y() - lineHeight - 2);
            place(branchBelow.get(run.vertexIndex()), labelX, run.y() + 3);
            if (barred != null) {
                place(barred, run.xBend() + 9, run.y() - 6);
            }
        }
        for (Placed placed : layout.placed()) {
            Node shape = glyphs.get(placed.vertexIndex());
            if (shape == null) {
                continue;
            }
            double left = placed.x() - glyph / 2;
            double top = placed.y() - glyph / 2;
            if (shape instanceof Circle circle) {
                circle.setCenterX(placed.x());
                circle.setCenterY(placed.y());
            } else if (shape instanceof Polygon || shape instanceof Rectangle) {
                shape.relocate(left, top);
            } else {
                shape.relocate(placed.x(), top);
            }
            boolean terminal = placed.kind() == Kind.OUTCOME || placed.kind() == Kind.END || placed.collapsed();
            Node value = valueLabels.get(placed.vertexIndex());
            Node name = nameLabels.get(placed.vertexIndex());
            double textLeft = placed.x() + glyph / 2 + 4;
            if (terminal) {
                place(value, textLeft, placed.y() - lineHeight);
                place(name, textLeft, placed.y() + 2);
            } else {
                place(value, textLeft, placed.y() - lineHeight);
                place(name, textLeft, placed.y() + 1);
            }
        }
        drawnWidth.set(layout.width());
        drawnHeight.set(layout.height());
    }

    private static void place(Node node, double x, double y) {
        if (node == null) {
            return;
        }
        double w = node.prefWidth(-1);
        double h = node.prefHeight(w);
        node.resizeRelocate(x, y, w, h);
    }

    /** The glyph of a vertex, for a test. */
    Optional<Node> glyph(int vertexIndex) {
        return Optional.ofNullable(glyphs.get(vertexIndex));
    }

    /** The run of a branch vertex, for a test. */
    Optional<Polyline> run(int branchVertex) {
        return Optional.ofNullable(runs.get(branchVertex));
    }

    /** Whether a rejected alternative is barred, for a test. */
    boolean barred(int branchVertex) {
        return bars.containsKey(branchVertex);
    }

    /** The chip of a measure's value line, for a test. */
    Optional<Node> labelOf(int branchVertex, boolean above) {
        return Optional.ofNullable(above ? branchAbove.get(branchVertex) : branchBelow.get(branchVertex));
    }

}
