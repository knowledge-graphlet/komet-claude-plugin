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
package network.ike.komet.claude.decision.render;

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.Cohort;
import network.ike.komet.claude.decision.DecisionElement;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeLayout;
import network.ike.komet.claude.decision.DecisionTreeLayout.Kind;
import network.ike.komet.claude.decision.DecisionTreeLayout.Placed;
import network.ike.komet.claude.decision.DecisionTreeLayout.Run;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Flip;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Unswept;
import network.ike.komet.claude.decision.Derivations;
import network.ike.komet.claude.decision.Measure;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.Tradeoff;
import network.ike.komet.claude.decision.render.DecisionFigure.Circle;
import network.ike.komet.claude.decision.render.DecisionFigure.Mark;
import network.ike.komet.claude.decision.render.DecisionFigure.Polygon;
import network.ike.komet.claude.decision.render.DecisionFigure.Polyline;
import network.ike.komet.claude.decision.render.DecisionFigure.Rect;
import network.ike.komet.claude.decision.render.DecisionFigure.Stroke;
import network.ike.komet.claude.decision.render.DecisionFigure.Text;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Bar;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Choice;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Segment;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Tick;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Tornado;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The figures of a decision tree over the pure model: the tree on the {@link DecisionTreeLayout},
 * drawn as the live {@code DecisionTreeDiagram} draws it, and the tornado from a
 * {@link DecisionTreeSensitivity}, read into the shared {@link DecisionFigureTornado} builder.
 * Labels are composed first, their widths estimated by {@link DecisionFigureText}, and the
 * layout is then computed from those widths, so the geometry never depends on a font engine.
 */
public final class DecisionTreeFigures {

    private DecisionTreeFigures() {
    }

    /** The surface's base font size; every other size follows as it does on the live diagram. */
    static final double BASE = 12;
    /** The room the two bars of a rejected alternative take on its run, before its labels. */
    private static final double BAR_ROOM = 16;
    private static final double SPAN_GAP = 5;

    /** One piece of a label row. */
    record Span(String text, double size, boolean bold, boolean italic, String colour, String role) {
    }

    // ── The tree ─────────────────────────────────────────────────────────────

    static DecisionFigure tree(DecisionTree tree, Rollback rollback, Identities ids, IntPredicate collapsed,
                               Optional<DecisionTreeSensitivity> sweep, String symbol) {
        return tree(tree, rollback, ids, collapsed, sweep, symbol, OptionalDouble.empty());
    }

    /** The tree, counted out for a cohort when one is given: how many go each way beneath every run, and where they end up. */
    static DecisionFigure tree(DecisionTree tree, Rollback rollback, Identities ids, IntPredicate collapsed,
                               Optional<DecisionTreeSensitivity> sweep, String symbol, OptionalDouble cohort) {
        return new TreeBuilder(tree, rollback, ids, collapsed, sweep, symbol, cohort).build();
    }

    private static final class TreeBuilder {
        private final DecisionTree tree;
        private final Rollback rollback;
        private final Derivations derivations;
        private final Identities ids;
        private final IntPredicate collapsed;
        private final Set<Integer> flipping = new HashSet<>();
        private final String symbol;
        private final String healthUnit;
        private final Optional<Cohort> cohort;
        private final Optional<Tradeoff> tradeoff;
        private final double lineHeight = DecisionFigureText.lineHeight(BASE);
        private final double glyph = Math.round(BASE * 1.1);
        private final double size = BASE * 0.9;

        private final Map<Integer, List<Span>> nodeTop = new HashMap<>();
        private final Map<Integer, List<Span>> nodeBottom = new HashMap<>();
        private final Map<Integer, List<Span>> branchAbove = new HashMap<>();
        private final Map<Integer, List<Span>> branchBelow = new HashMap<>();
        private final Set<Integer> barred = new HashSet<>();
        private final Set<Integer> flagged = new HashSet<>();
        private final Set<Integer> chosenBranches = new HashSet<>();

        TreeBuilder(DecisionTree tree, Rollback rollback, Identities ids, IntPredicate collapsed,
                    Optional<DecisionTreeSensitivity> sweep, String symbol, OptionalDouble cohortSize) {
            this.tree = tree;
            this.rollback = rollback;
            this.derivations = rollback.derivations();
            this.ids = ids;
            this.collapsed = collapsed;
            this.symbol = symbol == null ? "" : symbol;
            this.cohort = cohortSize.isPresent() ? Optional.of(Cohort.of(tree, rollback, cohortSize.getAsDouble())) : Optional.empty();
            this.tradeoff = Tradeoff.of(tree, rollback);
            long health = rollback.coordinate().healthSemantic();
            String unit = health == 0 || ids == null ? null : ids.label(health);
            this.healthUnit = unit == null ? "" : unit;
            sweep.ifPresent(s -> {
                for (Swing swing : s.swings()) {
                    if (!swing.flipsAt(tree.rootIndex()).isEmpty()) {
                        flipping.add(swing.measureVertex());
                    }
                }
            });
            for (ProfileFinding finding : rollback.findings()) {
                if (finding.vertexIndex() >= 0) {
                    flagged.add(nearestNode(finding.vertexIndex()));
                }
            }
        }

        DecisionFigure build() {
            Optional<DecisionElement> root = tree.root();
            if (root.isEmpty() || !(root.get() instanceof Node rootNode)) {
                return new DecisionFigure(2 * 6, 2 * 6, "decision-tree", List.of());
            }
            node(rootNode);
            DecisionTreeLayout layout = DecisionTreeLayout.of(tree, sizing(), collapsed);
            List<Mark> marks = new ArrayList<>();
            for (Run run : layout.runs()) {
                int index = run.vertexIndex();
                boolean chosen = chosenBranches.contains(index);
                boolean gap = rollback.value(index).isEmpty();
                Stroke stroke = gap ? Stroke.dashed(DecisionFigureText.GAP, 1.2)
                        : Stroke.solid(chosen ? DecisionFigureText.CHOSEN : DecisionFigureText.INK, chosen ? 2.6 : 1.2);
                marks.add(new Polyline(new double[] {run.x0(), run.y0(), run.xBend(), run.y0(), run.xBend(), run.y(),
                        run.x1(), run.y()}, stroke, "run" + (chosen ? " chosen" : "") + (gap ? " gap" : "")));
                boolean isBarred = barred.contains(index);
                double labelX = run.xBend() + 8 + (isBarred ? BAR_ROOM : 0);
                row(marks, branchAbove.get(index), labelX, run.y() - lineHeight - 2);
                row(marks, branchBelow.get(index), labelX, run.y() + 3);
                if (isBarred) {
                    double bx = run.xBend() + 9;
                    marks.add(new Polyline(new double[] {bx, run.y() - 6, bx, run.y() + 6},
                            Stroke.solid(DecisionFigureText.INK, 1.6), "barred"));
                    marks.add(new Polyline(new double[] {bx + 5, run.y() - 6, bx + 5, run.y() + 6},
                            Stroke.solid(DecisionFigureText.INK, 1.6), "barred"));
                }
            }
            for (Placed placed : layout.placed()) {
                marks.add(glyph(placed));
                boolean terminal = placed.kind() == Kind.OUTCOME || placed.kind() == Kind.END || placed.collapsed();
                double textLeft = placed.x() + glyph / 2 + 4;
                row(marks, nodeTop.get(placed.vertexIndex()), textLeft, placed.y() - lineHeight);
                row(marks, nodeBottom.get(placed.vertexIndex()), textLeft, placed.y() + (terminal ? 2 : 1));
            }
            // the readouts: what the choice trades, and where a cohort ends up; then the legend
            double legendSize = BASE * 0.78;
            double legendLine = DecisionFigureText.lineHeight(legendSize);
            double wrapWidth = Math.max(200, layout.width() - 12);
            double y = layout.height() + 2;
            for (String readout : readouts()) {
                for (String line : DecisionFigureText.wrap(readout, legendSize, wrapWidth)) {
                    marks.add(new Text(6, y, line, legendSize, false, false, DecisionFigureText.INK, "readout"));
                    y += legendLine;
                }
            }
            if (!readouts().isEmpty()) {
                y += 3;
            }
            for (String line : DecisionFigureText.wrap(legend(), legendSize, wrapWidth)) {
                marks.add(new Text(6, y, line, legendSize, false, false, DecisionFigureText.CAPTION, "legend"));
                y += legendLine;
            }
            return new DecisionFigure(layout.width(), y + 4, "decision-tree", marks);
        }

        /** The sentences beneath the drawing: the trade at the root, and a tally per alternative when a cohort is counted. */
        List<String> readouts() {
            return readoutLines(tree, rollback, ids, symbol, healthUnit, cohort, tradeoff);
        }

        /** One or two sentences saying what the numbers are, as the live surface says beneath its drawing. */
        private String legend() {
            String unit = symbol.isEmpty() ? "" : ", in " + symbol;
            Bounds price = rollback.coordinate().healthPrice();
            String beneath = cohort.isPresent()
                    ? " beneath a branch, how many of the " + DecisionFigureText.count(cohort.get().size()) + " go that way, and what it costs."
                    : " beneath a branch, what it costs.";
            return "\u21D2 is the expected cost from that point on" + unit + ", a year of healthy life priced at "
                    + money(Bounds.point(price.midpoint()), symbol) + ". A percentage is the share who go that way;"
                    + beneath + " The heavy run is the alternative chosen, a barred one is"
                    + " rejected, and \u25B2 marks a value whose range flips the root's choice.";
        }

        private Mark glyph(Placed placed) {
            int index = placed.vertexIndex();
            boolean folded = placed.collapsed();
            Stroke stroke = flagged.contains(index) ? Stroke.dashed(DecisionFigureText.GAP, 1.4)
                    : Stroke.solid(DecisionFigureText.INK, 1.4);
            double left = placed.x() - glyph / 2;
            double top = placed.y() - glyph / 2;
            String role = "glyph " + placed.kind().name().toLowerCase(Locale.ROOT) + (folded ? " folded" : "");
            return switch (placed.kind()) {
                case DECISION -> new Rect(left, top, glyph, glyph,
                        folded ? DecisionFigureText.FOLDED_FILL : DecisionFigureText.DECISION_FILL, 1, stroke, role);
                case CHANCE -> new Circle(placed.x(), placed.y(), glyph / 2,
                        folded ? DecisionFigureText.FOLDED_FILL : DecisionFigureText.CHANCE_FILL, stroke, role);
                case OUTCOME -> new Polygon(new double[] {left, top, left + glyph, placed.y(), left, top + glyph},
                        folded ? DecisionFigureText.FOLDED_FILL : DecisionFigureText.OUTCOME_FILL, stroke, role);
                case END -> new Polyline(new double[] {placed.x(), top, placed.x(), top + glyph}, stroke, role);
            };
        }

        private void row(List<Mark> marks, List<Span> spans, double x, double top) {
            if (spans == null) {
                return;
            }
            double at = x;
            for (Span span : spans) {
                marks.add(new Text(at, top + (lineHeight - DecisionFigureText.lineHeight(span.size())) / 2, span.text(),
                        span.size(), span.bold(), span.italic(), span.colour(), span.role()));
                at += DecisionFigureText.width(span.text(), span.size(), span.bold()) + SPAN_GAP;
            }
        }

        private void node(Node node) {
            int index = node.vertexIndex();
            boolean folded = collapsed.test(index);
            List<Branch> branches = tree.children(index, Branch.class).castToList();
            boolean terminal = node instanceof Outcome || folded || branches.isEmpty();
            Optional<Bounds> value = rollback.value(index);
            Span valueSpan = new Span(value.map(b -> "⇒ " + money(b, symbol)).orElse("⇒ ?"), size, true, false,
                    value.isPresent() ? DecisionFigureText.INK : DecisionFigureText.GAP, "value");
            List<Span> name = new ArrayList<>();
            if (!(node instanceof Branch)) {
                concept(node).ifPresent(label -> name.add(new Span(label, size, false, false, DecisionFigureText.INK, "name")));
            }
            if (terminal) {
                List<Span> top = new ArrayList<>();
                top.add(valueSpan);
                top.addAll(name);
                nodeTop.put(index, top);
                if (node instanceof Outcome outcome) {
                    nodeBottom.put(index, costs(outcome.costs().castToList()));
                } else if (folded) {
                    nodeBottom.put(index, List.of(new Span(foldedWord(index), size, false, false, "#777777", "folded")));
                }
            } else {
                nodeTop.put(index, List.of(valueSpan));
                nodeBottom.put(index, name);
            }
            if (!terminal) {
                OptionalInt chosen = rollback.chosen(index);
                for (Branch branch : branches) {
                    branch(branch, chosen.isPresent(), chosen.orElse(-1) == branch.vertexIndex());
                    branch.next().ifPresent(this::node);
                }
            }
        }

        private void branch(Branch branch, boolean decided, boolean chosen) {
            int index = branch.vertexIndex();
            if (chosen) {
                chosenBranches.add(index);
            }
            List<Span> above = new ArrayList<>();
            concept(branch).ifPresent(label -> above.add(new Span(label, size, false, false, DecisionFigureText.INK, "name")));
            if (branch instanceof Event event) {
                Optional<Probability> probability = event.probability();
                if (probability.isPresent()) {
                    above.addAll(chip("p", probability.get().vertexIndex(), probability.get().measure()));
                } else if (event.residual()) {
                    // the residual's share is what the other events leave, printed so that no one subtracts
                    String rest = residual(event).map(b -> DecisionFigureText.chip(b.lower(), b.upper(), b.lowerOpen(),
                            b.upperOpen(), true, DecisionFigureText.Unit.SHARE, "")).orElse("?");
                    above.add(new Span(rest + " (residual)", size, false, true, DecisionFigureText.CAPTION, "measure"));
                } else {
                    above.add(new Span("p ?", size, false, false, DecisionFigureText.GAP, "measure"));
                }
            }
            if (branch instanceof Alternative) {
                Optional<Bounds> value = rollback.value(index);
                above.add(new Span(value.map(b -> "⇒ " + money(b, symbol)).orElse("⇒ ?"), size, true, false,
                        value.isPresent() ? (chosen ? DecisionFigureText.CHOSEN : DecisionFigureText.INK)
                                : DecisionFigureText.GAP, "value"));
            }
            branchAbove.put(index, above);
            List<Span> below = new ArrayList<>();
            cohort.ifPresent(c -> c.count(index).ifPresent(count -> below.add(new Span(
                    DecisionFigureText.ofCohort(count, c.size()), size, false, false, DecisionFigureText.CAPTION, "count"))));
            List<Span> costs = costs(branch.costs().castToList());
            if (!below.isEmpty() && !costs.isEmpty()) {
                below.add(new Span("\u00B7", size, false, false, DecisionFigureText.CAPTION, "plus"));
            }
            below.addAll(costs);
            branchBelow.put(index, below);
            if (branch instanceof Alternative && decided && !chosen) {
                barred.add(index);
            }
        }

        /** The share the residual event takes: one less the other events' shares, when they are all determinable. */
        private Optional<Bounds> residual(Event event) {
            OptionalInt chanceIndex = tree.parentIndex(event.vertexIndex());
            if (chanceIndex.isEmpty()) {
                return Optional.empty();
            }
            Bounds others = Bounds.point(0);
            for (Event sibling : tree.children(chanceIndex.getAsInt(), Event.class)) {
                Optional<Probability> p = sibling.probability();
                if (p.isEmpty()) {
                    continue;
                }
                Optional<Bounds> share = derivations.bounds(p.get().vertexIndex());
                if (share.isEmpty()) {
                    return Optional.empty();
                }
                others = others.plus(share.get());
            }
            return Optional.of(others.complement().clip(0, 1));
        }

        /** A branch's or an outcome's costs: money then health, each in its unit, joined by a plus. */
        private List<Span> costs(List<Cost> costs) {
            List<Span> below = new ArrayList<>();
            for (Cost cost : costs) {
                if (!below.isEmpty()) {
                    below.add(new Span("+", size, false, false, DecisionFigureText.CAPTION, "plus"));
                }
                below.addAll(chip(cost instanceof MoneyCost ? "money" : "health", cost.vertexIndex(), cost.measure()));
            }
            return below;
        }

        /**
         * A measure as its chip reads: the bounds in the measure's unit, a share as a percentage,
         * money after its symbol, health with its unit's name, the kind's word only when there is
         * no symbol or name to say it; and a mark when its range holds a flip of the root's choice.
         */
        private List<Span> chip(String word, int vertexIndex, Measure measure) {
            Optional<Bounds> bounds = derivations.bounds(vertexIndex);
            boolean computed = measure.derived() || !measure.stated();
            DecisionFigureText.Unit unit = switch (word) {
                case "p" -> DecisionFigureText.Unit.SHARE;
                case "money" -> DecisionFigureText.Unit.MONEY;
                default -> DecisionFigureText.Unit.PLAIN;
            };
            String unitSymbol = unit == DecisionFigureText.Unit.MONEY ? symbol : "health".equals(word) ? healthUnit : "";
            boolean worded = unit != DecisionFigureText.Unit.SHARE && unitSymbol.isEmpty();
            String shown = bounds.map(b -> DecisionFigureText.chip(b.lower(), b.upper(), b.lowerOpen(), b.upperOpen(),
                    computed, unit, unitSymbol)).orElse("?");
            String colour = bounds.isEmpty() ? DecisionFigureText.GAP
                    : measure.derived() ? DecisionFigureText.DERIVED : DecisionFigureText.INK;
            List<Span> spans = new ArrayList<>();
            spans.add(new Span(worded ? word + " " + shown : shown, size, false, measure.derived(), colour, "measure"));
            if (flipping.contains(vertexIndex)) {
                spans.add(new Span("▲", size * 0.75, false, false, DecisionFigureText.HOT, "flip-mark"));
            }
            return spans;
        }

        private Optional<String> concept(DecisionElement element) {
            return element.concept().isPresent() ? Optional.of(ids.label(element.concept().getAsLong())) : Optional.empty();
        }

        private String foldedWord(int index) {
            int count = countBeneath(index) - 1;
            return count == 1 ? "1 node folded" : count + " nodes folded";
        }

        private int countBeneath(int index) {
            int count = 1;
            for (Node child : tree.children(index, Node.class)) {
                count += countBeneath(child.vertexIndex());
            }
            return count;
        }

        /** The node a finding on a measure sits under, so it shows at a glyph. */
        private int nearestNode(int vertexIndex) {
            int index = vertexIndex;
            while (tree.node(index).map(n -> !(n instanceof Node)).orElse(false)) {
                OptionalInt parent = tree.parentIndex(index);
                if (parent.isEmpty()) {
                    break;
                }
                index = parent.getAsInt();
            }
            return index;
        }

        private double widthOf(List<Span> spans) {
            if (spans == null || spans.isEmpty()) {
                return 0;
            }
            double width = 0;
            for (Span span : spans) {
                width += DecisionFigureText.width(span.text(), span.size(), span.bold()) + SPAN_GAP;
            }
            return width - SPAN_GAP;
        }

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
                            + (barred.contains(branchVertex) ? BAR_ROOM : 0);
                }

                @Override
                public double nodeLabelWidth(int nodeVertex) {
                    return Math.max(widthOf(nodeTop.get(nodeVertex)), widthOf(nodeBottom.get(nodeVertex))) + 4;
                }
            };
        }
    }

    // ── The readouts ─────────────────────────────────────────────────────────

    /**
     * The sentences beneath a drawing, as the live surface prints them too: what the choice at
     * the root trades, in money and health kept apart, and, when a cohort is counted, where the
     * cohort that takes each alternative ends up.
     */
    public static List<String> readoutLines(DecisionTree tree, Rollback rollback, Identities ids, String symbol, String healthUnit,
                                            Optional<Cohort> cohort, Optional<Tradeoff> tradeoff) {
        List<String> lines = new ArrayList<>();
        tradeoff.ifPresent(t -> {
            String moneyText = money(Bounds.point(Math.abs(t.money())), symbol);
            String healthText = DecisionFigureText.quantity(Math.abs(t.health()), DecisionFigureText.Unit.PLAIN, healthUnit);
            String priceText = Double.isNaN(t.pricePerHealthyYear()) ? "" : money(Bounds.point(t.pricePerHealthyYear()), symbol);
            lines.add(DecisionFigureText.tradeoff(nodeName(tree, ids, t.chosen()), nodeName(tree, ids, t.nextBest()),
                    t.money(), moneyText, t.health(), healthText, priceText, money(Bounds.point(t.priced()), symbol)));
        });
        cohort.ifPresent(c -> {
            for (Cohort.Tally tally : c.tallies()) {
                if (tally.leaves().isEmpty()) {
                    continue;
                }
                // like outcomes read alike: two leaves named "clear" are one entry, whatever their ids
                Map<String, Double> byName = new java.util.LinkedHashMap<>();
                for (Cohort.Leaf leaf : tally.leaves()) {
                    String name = leaf.concept().isPresent() ? ids.label(leaf.concept().getAsLong()) : "reach the end";
                    byName.merge(name, leaf.count(), Double::sum);
                }
                List<Map.Entry<String, Double>> entries = new ArrayList<>(byName.entrySet());
                entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
                lines.add(DecisionFigureText.tally(nodeName(tree, ids, tally.alternativeVertex()), tally.count(),
                        entries.stream().map(Map.Entry::getKey).toList(), entries.stream().map(Map.Entry::getValue).toList()));
            }
        });
        return lines;
    }

    private static String nodeName(DecisionTree tree, Identities ids, int vertexIndex) {
        return tree.node(vertexIndex).flatMap(n -> n.concept().isPresent()
                ? Optional.of(ids.label(n.concept().getAsLong())) : Optional.empty()).orElse("an alternative");
    }

    // ── The tornado ──────────────────────────────────────────────────────────

    /**
     * The sweep read into the shared tornado builder's terms, named as the live chart names
     * things: on the net value of the base's choice where the sweep carries one, else on the
     * root's expected cost, every caption and threshold printed in the measure's own unit.
     */
    static Tornado tornado(DecisionTreeSensitivity sweep, Identities ids, String symbol) {
        DecisionTree tree = sweep.tree();
        int rootDecision = tree.rootIndex();
        int baseChosen = sweep.baseChosen().orElse(-1);
        boolean net = sweep.netValued();
        String chosen = baseChosen < 0 ? null : tree.node(baseChosen).flatMap(n -> n.concept().isPresent()
                ? Optional.of(ids.label(n.concept().getAsLong())) : Optional.empty()).orElse("the chosen alternative");
        List<Bar> bars = new ArrayList<>();
        for (Swing swing : sweep.swings()) {
            if ((net ? swing.netWidth() : swing.width()) <= 0) {
                continue;
            }
            SweptQuantity quantity = SweptQuantity.of(sweep, ids, swing.measureVertex(), symbol);
            List<Flip> rootFlips = swing.flipsAt(rootDecision).castToList();
            List<Double> cuts = new ArrayList<>();
            cuts.add(swing.range().lower());
            rootFlips.forEach(flip -> cuts.add(flip.at()));
            cuts.add(swing.range().upper());
            List<Segment> segments = new ArrayList<>();
            for (int i = 1; i < cuts.size(); i++) {
                int chosenHere = i == 1
                        ? (rootFlips.isEmpty() ? baseChosen : rootFlips.get(0).chosenBelow())
                        : rootFlips.get(i - 2).chosenAbove();
                Choice choice = chosenHere < 0 ? Choice.OPEN : chosenHere == baseChosen ? Choice.BASE : Choice.OTHER;
                segments.add(new Segment(at(sweep, swing, cuts.get(i - 1), net), at(sweep, swing, cuts.get(i), net), choice));
            }
            List<Tick> ticks = new ArrayList<>();
            for (Flip flip : swing.flips()) {
                ticks.add(new Tick(at(sweep, swing, flip.at(), net), flip.decisionVertex() == rootDecision,
                        quantity.print(flip.at())));
            }
            bars.add(new Bar(SweptQuantity.name(tree, ids, swing.measureVertex()),
                    quantity.print(swing.range().lower()), quantity.print(swing.range().upper()), swing.stated(),
                    net ? swing.netAtLow() : swing.rootAtLow(), net ? swing.netAtHigh() : swing.rootAtHigh(),
                    net ? swing.leastNet() : swing.least(), net ? swing.greatestNet() : swing.greatest(), segments, ticks));
        }
        List<String> notes = new ArrayList<>();
        int still = (int) sweep.swings().stream().filter(s -> (net ? s.netWidth() : s.width()) <= 0).count();
        if (still > 0) {
            notes.add(still == 1 ? "One value does not move the answer." : still + " values do not move the answer.");
        }
        for (Unswept unswept : sweep.unswept()) {
            notes.add("Not swept: " + SweptQuantity.name(tree, ids, unswept.measureVertex()) + ", " + unswept.why() + ".");
        }
        String unit = symbol == null || symbol.isBlank() ? "" : ", in " + symbol;
        String title = net
                ? "Net value of \u201C" + chosen + "\u201D: what the next best alternative would cost, less what it costs"
                + unit + ". Zero is break-even."
                : "Expected cost at the root" + unit;
        return new Tornado(title, net, net ? chosen : null, net ? sweep.baseNet() : sweep.baseRoot(),
                net ? sweep.leastNet() : sweep.least(), net ? sweep.greatestNet() : sweep.greatest(), bars, notes);
    }

    /** Where the axis stands when a swing's measure is a value: the net value, or the root's cost. */
    private static double at(DecisionTreeSensitivity sweep, Swing swing, double value, boolean net) {
        return net ? swing.netAtValue(value).orElse(sweep.baseNet()) : swing.rootAtValue(value).orElse(sweep.baseRoot());
    }

    /** How a swept measure is named; see {@link SweptQuantity#name}. */
    static String nameOf(DecisionTree tree, Identities ids, int measureVertex) {
        return SweptQuantity.name(tree, ids, measureVertex);
    }

    /** A rolled-back value to the whole unit, as the live diagram prints it, after a symbol when one is given. */
    static String money(Bounds bounds, String symbol) {
        String prefix = symbol == null ? "" : symbol;
        if (bounds.isPoint()) {
            // a negative amount, which a net value can be, carries its sign before the symbol
            return (bounds.lower() < 0 ? "\u2212" : "") + prefix + String.format(Locale.ROOT, "%,d", Math.round(Math.abs(bounds.lower())));
        }
        return (bounds.lowerOpen() ? "(" : "[") + prefix + String.format(Locale.ROOT, "%,d", Math.round(bounds.lower()))
                + ", " + prefix + String.format(Locale.ROOT, "%,d", Math.round(bounds.upper())) + (bounds.upperOpen() ? ")" : "]");
    }

    /** An amount on the tornado's axis. */
    static String money(double amount, String symbol) {
        return Double.isNaN(amount) ? "?" : money(Bounds.point(amount), symbol);
    }
}
