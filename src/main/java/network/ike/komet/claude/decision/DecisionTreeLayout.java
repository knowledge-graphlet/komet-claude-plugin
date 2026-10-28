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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * The drawing of a decision tree in the decision-analytic form, as geometry alone: the tree reads
 * left to right from the root, a square where someone chooses, a circle where the world decides,
 * a triangle where the consequences are counted, and each branch an elbow from its node to the
 * node it leads to, with room along its horizontal run for what the branch says. That is the
 * form of Raiffa and of Howard, and of the health-economic tools that followed them, and the form
 * the lab's regulatory topics draw by hand. The layout is pure: it reads the tree's structure and
 * the widths a surface reports for its labels, and places glyphs and branch runs in that
 * surface's units, so a JavaFX diagram, an SVG for the documentation build and a Typst figure
 * draw the same picture from one computation.
 *
 * <p>Every terminal takes a row: an outcome, a branch that leads nowhere, or a node the reader has
 * collapsed. A node sits at the midpoint of the rows its branches reach. A column holds the nodes
 * at one depth, counting decisions, chances and outcomes and not the branches between them, and
 * is as wide as its widest value label plus its widest branch label, so nothing overlaps whatever
 * the labels say.
 */
public final class DecisionTreeLayout {

    /** What a glyph stands for in the drawing. */
    public enum Kind { DECISION, CHANCE, OUTCOME, END }

    /**
     * One glyph: a node of the tree at the centre {@code (x, y)}, in its column. An {@code END}
     * is a branch that leads to no node, drawn at the end of its run; a collapsed node is drawn
     * as a terminal with what is folded beneath it hidden.
     */
    public record Placed(int vertexIndex, Kind kind, int column, double x, double y, boolean collapsed) {
    }

    /**
     * One branch as an elbow from the node it leaves to the node it reaches: a stub right from
     * {@code (x0, y0)} to {@code xBend}, down or up to the branch's row {@code y}, then right to
     * {@code x1}, where the next glyph's left edge is. {@code toVertex} is the vertex of the
     * glyph at the run's end: the node the branch leads to, or the branch itself when it ends.
     */
    public record Run(int vertexIndex, int fromVertex, int toVertex, double x0, double y0, double xBend, double y,
                      double x1) {
        /** The horizontal run's length, where the branch's labels go. */
        public double runLength() {
            return x1 - xBend;
        }
    }

    /** The sizes a surface draws by, in its own units. */
    public interface Sizing {
        /** The height of one terminal's row: room for a label above the run and one below. */
        double rowHeight();

        /** The side of a square, the diameter of a circle. */
        double glyph();

        /** The clear space between a branch's run and the next glyph. */
        double gap();

        /** The margin around the whole drawing. */
        double margin();

        /** The width of what a branch says along its run, labels above and below taken together. */
        double branchLabelWidth(int branchVertex);

        /** The width of the value drawn beside a node's glyph. */
        double nodeLabelWidth(int nodeVertex);
    }

    private static final double LABEL_PAD = 6;

    private final List<Placed> placed;
    private final List<Run> runs;
    private final double width;
    private final double height;
    private final int rows;
    private final int columns;

    private DecisionTreeLayout(List<Placed> placed, List<Run> runs, double width, double height, int rows,
                               int columns) {
        this.placed = Collections.unmodifiableList(placed);
        this.runs = Collections.unmodifiableList(runs);
        this.width = width;
        this.height = height;
        this.rows = rows;
        this.columns = columns;
    }

    /**
     * Lays a tree out.
     *
     * @param tree      the tree, whose root is drawn first
     * @param sizing    the surface's sizes
     * @param collapsed which nodes the reader has folded: drawn as terminals, nothing beneath them
     */
    public static DecisionTreeLayout of(DecisionTree tree, Sizing sizing, IntPredicate collapsed) {
        Builder builder = new Builder(tree, sizing, collapsed);
        Optional<DecisionElement> root = tree.root();
        if (root.isPresent() && root.get() instanceof Node node) {
            builder.place(node, 0);
        }
        return builder.build();
    }

    /** Every glyph, root first, in depth-first order. */
    public List<Placed> placed() {
        return placed;
    }

    /** Every branch, in the order the tree holds them. */
    public List<Run> runs() {
        return runs;
    }

    /** The glyph of a vertex, when it has one. */
    public Optional<Placed> placed(int vertexIndex) {
        return placed.stream().filter(p -> p.vertexIndex() == vertexIndex).findFirst();
    }

    /** The run of a branch vertex, when it has one. */
    public Optional<Run> run(int branchVertex) {
        return runs.stream().filter(r -> r.vertexIndex() == branchVertex).findFirst();
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    /** How many terminal rows the drawing has. */
    public int rows() {
        return rows;
    }

    /** How many columns of glyphs the drawing has. */
    public int columns() {
        return columns;
    }

    // ── The two passes: rows by traversal, then columns by width ─────────────

    private static final class Builder {
        private final DecisionTree tree;
        private final Sizing sizing;
        private final IntPredicate collapsed;
        private final List<Pending> pendingNodes = new ArrayList<>();
        private final List<PendingRun> pendingRuns = new ArrayList<>();
        private int rows;

        private record Pending(int vertexIndex, Kind kind, int column, double y, boolean collapsed) {
        }

        private record PendingRun(int vertexIndex, int fromVertex, int toVertex, int column, double y) {
        }

        Builder(DecisionTree tree, Sizing sizing, IntPredicate collapsed) {
            this.tree = tree;
            this.sizing = sizing;
            this.collapsed = collapsed;
        }

        /** Places a node and everything beneath it, and answers the node's row centre. */
        double place(Node node, int column) {
            int index = node.vertexIndex();
            boolean folded = collapsed.test(index);
            ImmutableList<Branch> branches = tree.children(index, Branch.class);
            Kind kind = switch (node) {
                case Decision d -> Kind.DECISION;
                case Chance c -> Kind.CHANCE;
                case Outcome o -> Kind.OUTCOME;
                case Branch b -> Kind.END;
            };
            if (node instanceof Outcome || folded || branches.isEmpty()) {
                double y = rowCentre(rows++);
                pendingNodes.add(new Pending(index, kind, column, y, folded && !branches.isEmpty()));
                return y;
            }
            int own = pendingNodes.size();
            pendingNodes.add(null); // the node precedes what is beneath it, in depth-first order
            double first = Double.NaN;
            double last = Double.NaN;
            for (Branch branch : branches) {
                Optional<Node> next = branch.next();
                double y;
                int to;
                if (next.isPresent()) {
                    y = place(next.get(), column + 1);
                    to = next.get().vertexIndex();
                } else {
                    y = rowCentre(rows++);
                    to = branch.vertexIndex();
                    pendingNodes.add(new Pending(to, Kind.END, column + 1, y, false));
                }
                pendingRuns.add(new PendingRun(branch.vertexIndex(), index, to, column, y));
                if (Double.isNaN(first)) {
                    first = y;
                }
                last = y;
            }
            double y = (first + last) / 2;
            pendingNodes.set(own, new Pending(index, kind, column, y, false));
            return y;
        }

        private double rowCentre(int row) {
            return sizing.margin() + row * sizing.rowHeight() + sizing.rowHeight() / 2;
        }

        DecisionTreeLayout build() {
            int columns = pendingNodes.stream().mapToInt(Pending::column).max().orElse(-1) + 1;
            double[] stub = new double[Math.max(columns, 1)];
            double[] branchLabel = new double[Math.max(columns, 1)];
            for (Pending node : pendingNodes) {
                stub[node.column()] = Math.max(stub[node.column()], sizing.nodeLabelWidth(node.vertexIndex()) + LABEL_PAD);
            }
            for (PendingRun run : pendingRuns) {
                branchLabel[run.column()] = Math.max(branchLabel[run.column()],
                        sizing.branchLabelWidth(run.vertexIndex()) + LABEL_PAD);
            }
            double glyph = sizing.glyph();
            double[] x = new double[Math.max(columns, 1)];
            x[0] = sizing.margin() + glyph / 2;
            for (int c = 1; c < columns; c++) {
                x[c] = x[c - 1] + glyph / 2 + stub[c - 1] + branchLabel[c - 1] + sizing.gap() + glyph / 2;
            }
            List<Placed> placed = new ArrayList<>();
            for (Pending node : pendingNodes) {
                placed.add(new Placed(node.vertexIndex(), node.kind(), node.column(), x[node.column()], node.y(),
                        node.collapsed()));
            }
            List<Run> runs = new ArrayList<>();
            for (PendingRun run : pendingRuns) {
                double y0 = pendingNodes.stream().filter(n -> n.vertexIndex() == run.fromVertex())
                        .mapToDouble(Pending::y).findFirst().orElse(run.y());
                double x0 = x[run.column()] + glyph / 2;
                double xBend = x0 + stub[run.column()];
                double x1 = x[run.column() + 1] - glyph / 2;
                runs.add(new Run(run.vertexIndex(), run.fromVertex(), run.toVertex(), x0, y0, xBend, run.y(), x1));
            }
            double width = columns == 0 ? 2 * sizing.margin()
                    : x[columns - 1] + glyph / 2 + stub[columns - 1] + sizing.margin();
            double height = 2 * sizing.margin() + Math.max(rows, 0) * sizing.rowHeight();
            return new DecisionTreeLayout(placed, runs, width, height, rows, columns);
        }
    }
}
