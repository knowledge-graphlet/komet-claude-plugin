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
 * holds no x of its own: a node's stub is as wide as its own value label, and its children stand
 * just past the widest label of its own branches, so siblings share the bend they hang from while
 * a short run is never stretched to match a long one elsewhere at the same depth, and nothing
 * overlaps whatever the labels say.
 *
 * <p>That drawing grows wider with every level, and a narrow surface would have to scale it until
 * nothing in it can be read. The {@linkplain #outline outline} is the same tree for such a surface:
 * every node takes a row of its own beneath the node it hangs from, a step to the right of it, so
 * the drawing grows downward instead, and its width is the deepest step and the widest row.
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

        /** How far beneath the top of its row a run lies: a surface with tall labels above stands it below them. */
        default double runOffset() {
            return rowHeight() / 2;
        }

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

        /** In an outline, how far right of the node it hangs from a node stands. */
        default double indent() {
            return glyph() * 2;
        }

        /**
         * In an outline, the height of a glyph's row.
         *
         * @param glyphVertex  the vertex of the row's glyph
         * @param branchVertex the branch that reaches it, or -1 for the root
         */
        default double rowHeight(int glyphVertex, int branchVertex) {
            return rowHeight();
        }

        /** In an outline, how far beneath the top of its row a glyph's centre lies. */
        default double runOffset(int glyphVertex, int branchVertex) {
            return runOffset();
        }

        /** In an outline, the width of what a row says to the right of its glyph: the branch that reaches it and the node itself. */
        default double rowWidth(int glyphVertex, int branchVertex) {
            return (branchVertex < 0 ? 0 : branchLabelWidth(branchVertex)) + nodeLabelWidth(glyphVertex);
        }
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

    /**
     * Lays a tree out as an outline, for a surface too narrow for the drawing {@link #of} makes.
     * Every glyph takes a row, in depth-first order, a node {@link Sizing#indent() one step} to the
     * right of the node it hangs from. A branch drops from the foot of the node it leaves and
     * turns right into the glyph it reaches, so a {@link Run} has no stub: {@code x0} and
     * {@code xBend} are the leaving node's centre, and what the branch says goes to the right of
     * the glyph at its end, on that glyph's row.
     *
     * @param tree      the tree, whose root takes the first row
     * @param sizing    the surface's sizes
     * @param collapsed which nodes the reader has folded: drawn as terminals, nothing beneath them
     */
    public static DecisionTreeLayout outline(DecisionTree tree, Sizing sizing, IntPredicate collapsed) {
        Outline outline = new Outline(tree, sizing, collapsed);
        Optional<DecisionElement> root = tree.root();
        if (root.isPresent() && root.get() instanceof Node node) {
            outline.place(node, 0, -1);
        }
        return outline.build();
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

    /** How many rows the drawing has: one for each terminal, or in an outline one for each glyph. */
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
            return sizing.margin() + row * sizing.rowHeight() + sizing.runOffset();
        }

        DecisionTreeLayout build() {
            int columns = pendingNodes.stream().mapToInt(Pending::column).max().orElse(-1) + 1;
            double glyph = sizing.glyph();
            // ragged: a node's stub is its own label's width, and its children stand just past the widest
            // of its own branches' labels, so a short run is never stretched by a long one elsewhere at its depth;
            // siblings still share one x, the bend they hang from
            java.util.Map<Integer, Double> xOf = new java.util.HashMap<>();
            java.util.Map<Integer, Double> stubOf = new java.util.HashMap<>();
            java.util.Map<Integer, Double> runRoomOf = new java.util.HashMap<>();
            for (PendingRun run : pendingRuns) {
                runRoomOf.merge(run.fromVertex(), sizing.branchLabelWidth(run.vertexIndex()) + LABEL_PAD, Math::max);
            }
            double right = sizing.margin();
            for (Pending node : pendingNodes) {
                double stub = sizing.nodeLabelWidth(node.vertexIndex()) + LABEL_PAD;
                stubOf.put(node.vertexIndex(), stub);
                double x = xOf.computeIfAbsent(node.vertexIndex(), v -> sizing.margin() + glyph / 2);
                right = Math.max(right, x + glyph / 2 + stub);
                Double room = runRoomOf.get(node.vertexIndex());
                if (room != null) {
                    double childX = x + glyph / 2 + stub + room + sizing.gap() + glyph / 2;
                    for (PendingRun run : pendingRuns) {
                        if (run.fromVertex() == node.vertexIndex()) {
                            xOf.put(run.toVertex(), childX);
                        }
                    }
                }
            }
            List<Placed> placed = new ArrayList<>();
            for (Pending node : pendingNodes) {
                placed.add(new Placed(node.vertexIndex(), node.kind(), node.column(), xOf.get(node.vertexIndex()), node.y(),
                        node.collapsed()));
            }
            List<Run> runs = new ArrayList<>();
            for (PendingRun run : pendingRuns) {
                double y0 = pendingNodes.stream().filter(n -> n.vertexIndex() == run.fromVertex())
                        .mapToDouble(Pending::y).findFirst().orElse(run.y());
                double x0 = xOf.get(run.fromVertex()) + glyph / 2;
                double xBend = x0 + stubOf.get(run.fromVertex());
                double x1 = xOf.get(run.toVertex()) - glyph / 2;
                runs.add(new Run(run.vertexIndex(), run.fromVertex(), run.toVertex(), x0, y0, xBend, run.y(), x1));
            }
            double width = columns == 0 ? 2 * sizing.margin() : right + sizing.margin();
            double height = 2 * sizing.margin() + Math.max(rows, 0) * sizing.rowHeight();
            return new DecisionTreeLayout(placed, runs, width, height, rows, columns);
        }
    }

    // ── The outline: a row for every glyph, a step right for every level ─────

    private static final class Outline {
        private final DecisionTree tree;
        private final Sizing sizing;
        private final IntPredicate collapsed;
        private final List<Placed> placed = new ArrayList<>();
        private final List<Run> runs = new ArrayList<>();
        private double top;
        private double right;
        private int columns;

        Outline(DecisionTree tree, Sizing sizing, IntPredicate collapsed) {
            this.tree = tree;
            this.sizing = sizing;
            this.collapsed = collapsed;
            this.top = sizing.margin();
            this.right = sizing.margin();
        }

        /** Gives a glyph the next row, and answers where it stands. */
        private Placed row(int vertex, Kind kind, boolean folded, int depth, int branchVertex) {
            double x = sizing.margin() + sizing.glyph() / 2 + depth * sizing.indent();
            double y = top + sizing.runOffset(vertex, branchVertex);
            top += sizing.rowHeight(vertex, branchVertex);
            right = Math.max(right, x + sizing.glyph() / 2 + sizing.rowWidth(vertex, branchVertex));
            columns = Math.max(columns, depth + 1);
            Placed row = new Placed(vertex, kind, depth, x, y, folded);
            placed.add(row);
            return row;
        }

        /** Places a node on its row and everything beneath it on the rows after. */
        Placed place(Node node, int depth, int branchVertex) {
            int index = node.vertexIndex();
            boolean folded = collapsed.test(index);
            ImmutableList<Branch> branches = tree.children(index, Branch.class);
            Kind kind = switch (node) {
                case Decision d -> Kind.DECISION;
                case Chance c -> Kind.CHANCE;
                case Outcome o -> Kind.OUTCOME;
                case Branch b -> Kind.END;
            };
            boolean terminal = node instanceof Outcome || folded || branches.isEmpty();
            Placed own = row(index, kind, folded && !branches.isEmpty(), depth, branchVertex);
            if (terminal) {
                return own;
            }
            for (Branch branch : branches) {
                Optional<Node> next = branch.next();
                Placed reached = next.isPresent()
                        ? place(next.get(), depth + 1, branch.vertexIndex())
                        : row(branch.vertexIndex(), Kind.END, false, depth + 1, branch.vertexIndex());
                runs.add(new Run(branch.vertexIndex(), index, reached.vertexIndex(), own.x(), own.y() + sizing.glyph() / 2,
                        own.x(), reached.y(), reached.x() - sizing.glyph() / 2));
            }
            return own;
        }

        DecisionTreeLayout build() {
            double width = placed.isEmpty() ? 2 * sizing.margin() : right + sizing.margin();
            double height = top + sizing.margin();
            return new DecisionTreeLayout(placed, runs, width, height, placed.size(), columns);
        }
    }
}
