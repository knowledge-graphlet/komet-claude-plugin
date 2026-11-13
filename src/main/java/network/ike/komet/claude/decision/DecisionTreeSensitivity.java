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

import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntIntMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.ImmutableIntIntMap;
import org.eclipse.collections.api.map.primitive.MutableIntIntMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToDoubleFunction;

/**
 * The one-way sensitivity of a decision tree: for each stated measure, and for the price of a
 * healthy year, the tree rolled back again and again as that one value moves across its range
 * while every other measure holds its base, with the expected cost at the root at every step and
 * every value at which a decision's chosen alternative flips. This is what a tornado diagram
 * draws, bar by bar, and it is pure: a {@link Mover} says how a value is moved and the tree
 * rolled back again, so a block's text can be restated line by line or a tree rewritten in
 * memory, and a surface in JavaFX, SVG or Typst draws the same bars.
 *
 * <p><b>The range is the block's own uncertainty.</b> A measure stated as an interval swings
 * from its lower to its upper bound, and the swing is {@linkplain Swing#stated() stated}. A
 * measure stated as a point has no uncertainty of its own, so it swings over an assumed range
 * instead: half to double, a share no higher than one; a probability under a chance with a
 * residual event may rise only as far as the other events leave, and a share of exactly zero
 * swings zero to one. A derived measure is never swept; it moves when what it reads moves. A
 * measure that cannot be moved, or whose tree is not determinable anywhere in its range, is
 * {@linkplain #unswept() left out} with the reason.
 *
 * <p><b>Every step carries the root's net value as well as its cost.</b> The net value is the
 * advantage of the alternative the base chose: what the next best alternative would cost, less
 * what the chosen one costs, so it is positive while the base choice holds, zero at a flip and
 * negative beyond it. A tornado drawn on it reads at a glance: zero is break-even, and a bar is
 * long only where the measure can change the answer. It is {@code NaN} when the root did not
 * decide at the base, and the root's cost is then all there is.
 *
 * <p><b>The base is every measure at its midpoint.</b> A tree stated with intervals rolls back to
 * an interval, which is the joint uncertainty of everything moving at once; the sweep holds every
 * other measure at the midpoint of its bounds, so that each bar shows one measure's own share.
 *
 * <p><b>A flip is found by bisection</b> between two steps whose chosen alternatives differ, at
 * any decision in the tree, to a ten-thousandth of the range. The root's flips are the ones a
 * tornado colours; a nested decision's flips are carried too, for the glyph they belong to.
 */
public final class DecisionTreeSensitivity {

    /** The vertex that stands for the price of a healthy year, which is on the coordinate, not in the tree. */
    public static final int HEALTH_PRICE = -1;

    /** How a value is moved and the tree rolled back again, under the same coordinate otherwise. */
    public interface Mover {
        /**
         * The tree rolled back with one stated measure at other bounds and every other measure at
         * its base; empty when that measure cannot be moved.
         */
        Optional<Rollback> moved(int measureVertex, Bounds bounds);

        /** The base tree rolled back at another price of a healthy year. */
        Optional<Rollback> priced(double healthPrice);

        /**
         * The base tree rolled back with one stated measure of a tree it folds in, the tree whose
         * root is a concept, at other bounds; empty when that measure cannot be moved.
         */
        default Optional<Rollback> movedIn(long treeRoot, int measureVertex, Bounds bounds) {
            return Optional.empty();
        }
    }

    /**
     * A value of a tree the swept tree folds in at an outcome: the folded tree, its root's concept,
     * the measure's vertex there, and that tree's rollback at the base. Its swing is keyed by a code
     * of its own, below {@link #HEALTH_PRICE}.
     */
    public record FoldedValue(long treeRoot, DecisionTree tree, int vertex, Rollback base) {
    }

    /**
     * One step of a swing: the value the measure took, the expected cost at the root, the net
     * value of the base's choice there ({@code NaN} when the base did not decide), and what every
     * decision chose.
     */
    public record Sample(double value, double root, double net, ImmutableIntIntMap chosen) {
        /** The alternative a decision chose at this step, or empty when it was left open. */
        public OptionalInt chosen(int decisionVertex) {
            return chosen.containsKey(decisionVertex) ? OptionalInt.of(chosen.get(decisionVertex)) : OptionalInt.empty();
        }
    }

    /**
     * Where a decision's choice changes as the measure rises: below {@code at} the decision chose
     * one alternative, above it another. An alternative of {@code -1} is a choice left open.
     */
    public record Flip(int decisionVertex, double at, int chosenBelow, int chosenAbove) {
    }

    /**
     * One measure's swing: its range, whether the block stated that range or the sweep assumed
     * it, the steps across it, and the flips found between them.
     */
    public record Swing(int measureVertex, Bounds range, boolean stated, double base,
                        ImmutableList<Sample> samples, ImmutableList<Flip> flips) {

        /** The expected cost at the root with the measure at its lower end. */
        public double rootAtLow() {
            return samples.get(0).root();
        }

        /** The expected cost at the root with the measure at its upper end. */
        public double rootAtHigh() {
            return samples.get(samples.size() - 1).root();
        }

        /** The least expected cost at the root anywhere in the range. */
        public double least() {
            return samples.stream().mapToDouble(Sample::root).min().orElse(Double.NaN);
        }

        /** The greatest expected cost at the root anywhere in the range. */
        public double greatest() {
            return samples.stream().mapToDouble(Sample::root).max().orElse(Double.NaN);
        }

        /** How far the root's expected cost moves across the range: the bar's length on a cost axis. */
        public double width() {
            return greatest() - least();
        }

        /** Whether every step carries a net value: the base decided, and every moved tree valued both arms. */
        public boolean hasNet() {
            return samples.allSatisfy(sample -> !Double.isNaN(sample.net()));
        }

        /** The net value of the base's choice with the measure at its lower end. */
        public double netAtLow() {
            return samples.get(0).net();
        }

        /** The net value of the base's choice with the measure at its upper end. */
        public double netAtHigh() {
            return samples.get(samples.size() - 1).net();
        }

        /** The least net value anywhere in the range. */
        public double leastNet() {
            return samples.stream().mapToDouble(Sample::net).min().orElse(Double.NaN);
        }

        /** The greatest net value anywhere in the range. */
        public double greatestNet() {
            return samples.stream().mapToDouble(Sample::net).max().orElse(Double.NaN);
        }

        /** How far the net value moves across the range: the bar's length on a net-value axis. */
        public double netWidth() {
            return greatestNet() - leastNet();
        }

        /** The flips at one decision, in rising order of the measure. */
        public ImmutableList<Flip> flipsAt(int decisionVertex) {
            return flips.select(flip -> flip.decisionVertex() == decisionVertex);
        }

        /**
         * The value of the measure at which the root's expected cost is a given amount, read off the
         * steps by straight lines between them; empty when the cost is never reached.
         */
        public Optional<Double> valueAtRoot(double root) {
            return valueAt(root, Sample::root);
        }

        /** The value of the measure at which the net value is a given amount; empty when it is never reached. */
        public Optional<Double> valueAtNet(double net) {
            return valueAt(net, Sample::net);
        }

        /** The expected cost at the root when the measure is a value, read off the steps by straight lines. */
        public Optional<Double> rootAtValue(double value) {
            return at(value, Sample::root);
        }

        /** The net value of the base's choice when the measure is a value, read off the steps by straight lines. */
        public Optional<Double> netAtValue(double value) {
            return at(value, Sample::net);
        }

        private Optional<Double> valueAt(double target, ToDoubleFunction<Sample> axis) {
            for (int i = 1; i < samples.size(); i++) {
                Sample a = samples.get(i - 1);
                Sample b = samples.get(i);
                double ya = axis.applyAsDouble(a);
                double yb = axis.applyAsDouble(b);
                double lo = Math.min(ya, yb);
                double hi = Math.max(ya, yb);
                if (target >= lo - Bounds.EPSILON && target <= hi + Bounds.EPSILON) {
                    if (yb == ya) {
                        return Optional.of(a.value());
                    }
                    double t = (target - ya) / (yb - ya);
                    return Optional.of(a.value() + t * (b.value() - a.value()));
                }
            }
            return Optional.empty();
        }

        private Optional<Double> at(double value, ToDoubleFunction<Sample> axis) {
            for (int i = 1; i < samples.size(); i++) {
                Sample a = samples.get(i - 1);
                Sample b = samples.get(i);
                if (value >= a.value() - Bounds.EPSILON && value <= b.value() + Bounds.EPSILON) {
                    double ya = axis.applyAsDouble(a);
                    double yb = axis.applyAsDouble(b);
                    if (b.value() == a.value()) {
                        return Optional.of(ya);
                    }
                    double t = (value - a.value()) / (b.value() - a.value());
                    return Optional.of(ya + t * (yb - ya));
                }
            }
            return Optional.empty();
        }
    }

    /** A measure the sweep could not swing, and why. */
    public record Unswept(int measureVertex, String why) {
    }

    private final DecisionTree tree;
    private final Rollback base;
    private final double baseRoot;
    private final double baseNet;
    /** The alternative whose net value every swing is drawn on; -1 when the root did not decide. */
    private final int reference;
    private final ImmutableList<Swing> swings;
    private final ImmutableList<Unswept> unswept;

    private DecisionTreeSensitivity(DecisionTree tree, Rollback base, int reference, ImmutableList<Swing> swings,
                                    ImmutableList<Unswept> unswept) {
        this.tree = tree;
        this.base = base;
        this.reference = reference;
        this.baseRoot = base.rootValue().map(Bounds::midpoint).orElse(Double.NaN);
        this.baseNet = net(base, reference);
        this.swings = swings;
        this.unswept = unswept;
    }

    /**
     * Sweeps every stated measure of a tree and the price of a healthy year.
     *
     * @param tree  the tree as the block states it, with its intervals, which say what each swing is
     * @param base  the tree rolled back with every measure at its base, which shares the vertex indices
     * @param mover how one value is moved and the tree rolled back again
     * @param steps how many steps the range is divided into; the samples are one more, plus one at each flip
     */
    public static DecisionTreeSensitivity of(DecisionTree tree, Rollback base, Mover mover, int steps) {
        return of(tree, base, mover, steps, vertex -> Optional.empty());
    }

    /**
     * The range a parameter's statement determined, by the parameter's vertex, when the document
     * has the statement: the parameter then swings over it, as stated, while the tree runs at the
     * parameter's own value ({@code dev-decision-point-knowledge}, settled 2026-11-06).
     */
    @FunctionalInterface
    public interface Ranges {
        Optional<Bounds> range(int measureVertex);
    }

    /**
     * Sweeps every stated measure of a tree and the price of a healthy year, a parameter that
     * reads a statement over the statement's range.
     *
     * @param ranges the ranges the parameters' statements determined, by vertex
     */
    public static DecisionTreeSensitivity of(DecisionTree tree, Rollback base, Mover mover, int steps, Ranges ranges) {
        return of(tree, base, mover, steps, ranges, -1);
    }

    /**
     * Sweeps a tree with the net value drawn for one alternative throughout. A tornado draws the
     * net value of the alternative the base chose. A review of a model moves the base, and the
     * choice with it, so it names the alternative its bars are about, and a bar keeps its meaning
     * while the choice changes.
     *
     * @param ranges    the ranges the parameters' statements determined, by vertex
     * @param reference the alternative whose net value is drawn, by its vertex; negative for the
     *                  alternative the base chose
     */
    public static DecisionTreeSensitivity of(DecisionTree tree, Rollback base, Mover mover, int steps, Ranges ranges,
                                             int reference) {
        if (steps < 2) {
            throw new IllegalArgumentException("a sweep takes two or more steps, not " + steps);
        }
        MutableList<Swing> swings = Lists.mutable.empty();
        MutableList<Unswept> unswept = Lists.mutable.empty();
        org.eclipse.collections.api.map.primitive.MutableIntObjectMap<FoldedValue> folded =
                org.eclipse.collections.api.factory.primitive.IntObjectMaps.mutable.empty();
        int baseChosen = reference >= 0 ? reference : base.chosen(tree.rootIndex()).orElse(-1);
        if (base.determinable()) {
            for (DecisionElement element : tree.nodes()) {
                Measure measure = switch (element) {
                    case DecisionElement.CountCost count -> null; // counted, never priced: it cannot move the answer
                    case Cost cost -> cost.measure();
                    case Probability probability -> probability.measure();
                    case Parameter parameter -> parameter.measure();
                    default -> null;
                };
                if (measure == null || !measure.stated()) {
                    continue;
                }
                int vertex = element.vertexIndex();
                // a parameter that reads a statement swings over the range the statement determined, when it is one
                Optional<Bounds> determined = ranges.range(vertex).filter(r -> !r.isPoint() && !Double.isInfinite(r.lower()) && !Double.isInfinite(r.upper()));
                Optional<Bounds> range = determined.isPresent() ? determined : range(tree, base, element, measure);
                if (range.isEmpty()) {
                    unswept.add(new Unswept(vertex, element instanceof Probability
                            ? "its chance has no residual event to take up what it gives or takes"
                            : "a value of zero has no assumed range"));
                    continue;
                }
                boolean stated = determined.isPresent() || !measure.bounds().isPoint();
                sweep(tree, baseChosen, vertex, range.get(), stated, measure.bounds().midpoint(),
                        value -> mover.moved(vertex, Bounds.point(value)), steps)
                        .ifPresentOrElse(swings::add,
                                () -> unswept.add(new Unswept(vertex, "the tree is not determinable when it moves")));
            }
            // the values of every tree folded in at an outcome, each moved in its own block and the whole tree rolled back
            java.util.Set<Long> seen = new java.util.HashSet<>();
            for (Rollback inner : base.foldedAll().values()) {
                java.util.OptionalLong root = inner.tree().root().map(DecisionElement::concept).orElse(java.util.OptionalLong.empty());
                if (root.isEmpty() || !seen.add(root.getAsLong())) {
                    continue;
                }
                for (DecisionElement element : inner.tree().nodes()) {
                    Measure measure = switch (element) {
                        case DecisionElement.CountCost count -> null;
                        case Cost cost -> cost.measure();
                        case Probability probability -> probability.measure();
                        case Parameter parameter -> parameter.measure();
                        default -> null;
                    };
                    if (measure == null || !measure.stated()) {
                        continue;
                    }
                    int code = FOLDED - folded.size();
                    int vertex = element.vertexIndex();
                    long treeRoot = root.getAsLong();
                    folded.put(code, new FoldedValue(treeRoot, inner.tree(), vertex, inner));
                    Optional<Bounds> range = range(inner.tree(), inner, element, measure);
                    if (range.isEmpty()) {
                        unswept.add(new Unswept(code, element instanceof Probability
                                ? "its chance has no residual event to take up what it gives or takes"
                                : "a value of zero has no assumed range"));
                        continue;
                    }
                    sweep(tree, baseChosen, code, range.get(), !measure.bounds().isPoint(), measure.bounds().midpoint(),
                            value -> mover.movedIn(treeRoot, vertex, Bounds.point(value)), steps)
                            .ifPresentOrElse(swings::add,
                                    () -> unswept.add(new Unswept(code, "the tree is not determinable when it moves")));
                }
            }
            double price = base.coordinate().healthPrice().midpoint();
            if (price > 0) {
                sweep(tree, baseChosen, HEALTH_PRICE, Bounds.of(price / 2, price * 2), false, price, mover::priced, steps)
                        .ifPresent(swings::add);
            }
        }
        // longest first on the axis a tornado draws: the net value where every swing carries one, else the cost
        boolean net = swings.allSatisfy(Swing::hasNet);
        swings.sortThis(Comparator.comparingDouble(net ? Swing::netWidth : Swing::width).reversed());
        DecisionTreeSensitivity sensitivity = new DecisionTreeSensitivity(tree, base, baseChosen, swings.toImmutable(), unswept.toImmutable());
        sensitivity.folded = folded.toImmutable();
        return sensitivity;
    }

    /** The first code a value of a folded tree is keyed by; each further one counts down. */
    public static final int FOLDED = -2;

    private org.eclipse.collections.api.map.primitive.ImmutableIntObjectMap<FoldedValue> folded =
            org.eclipse.collections.api.factory.primitive.IntObjectMaps.immutable.empty();

    /** The value of a folded tree a swing's code stands for, when it stands for one. */
    public Optional<FoldedValue> folded(int measureVertex) {
        return Optional.ofNullable(folded.get(measureVertex));
    }

    /** The range a measure swings over: its stated bounds, or the assumed range for a point. */
    static Optional<Bounds> range(DecisionTree tree, Rollback base, DecisionElement element, Measure measure) {
        Bounds bounds = measure.bounds();
        if (!bounds.isPoint()) {
            return Optional.of(bounds);
        }
        double value = bounds.lower();
        boolean share = measure.semanticNid() == tree.terms().shareAtNode();
        if (share && value >= 0 && value <= 1) {
            double ceiling = 1;
            if (element instanceof Probability probability) {
                OptionalInt chanceIndex = tree.parentIndex(probability.vertexIndex()).isPresent()
                        ? tree.parentIndex(tree.parentIndex(probability.vertexIndex()).getAsInt()) : OptionalInt.empty();
                Optional<Chance> chance = chanceIndex.isPresent()
                        ? tree.node(chanceIndex.getAsInt()).filter(Chance.class::isInstance).map(Chance.class::cast)
                        : Optional.empty();
                if (chance.isEmpty() || chance.get().events().noneSatisfy(Event::residual)) {
                    return Optional.empty();
                }
                double others = 0;
                for (Event event : chance.get().events()) {
                    Optional<Probability> p = event.probability();
                    if (p.isPresent() && p.get().vertexIndex() != probability.vertexIndex()) {
                        others += base.derivations().bounds(p.get().vertexIndex()).map(Bounds::midpoint).orElse(0.0);
                    }
                }
                ceiling = Math.max(value, Math.min(1, 1 - others));
            }
            if (value == 0) {
                return Optional.of(Bounds.of(0, ceiling));
            }
            return Optional.of(Bounds.of(value / 2, Math.min(ceiling, value * 2)));
        }
        if (value == 0) {
            return Optional.empty();
        }
        return Optional.of(value > 0 ? Bounds.of(value / 2, value * 2) : Bounds.of(value * 2, value / 2));
    }

    private interface Step {
        Optional<Rollback> at(double value);
    }

    private static Optional<Swing> sweep(DecisionTree tree, int baseChosen, int vertex, Bounds range, boolean stated,
                                         double base, Step step, int steps) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double value = range.lower() + (range.upper() - range.lower()) * i / steps;
            sample(tree, baseChosen, step, value).ifPresent(samples::add);
        }
        if (samples.size() < 2) {
            return Optional.empty();
        }
        MutableList<Flip> flips = Lists.mutable.empty();
        double tolerance = (range.upper() - range.lower()) * 1e-4;
        for (int i = 1; i < samples.size(); i++) {
            Sample below = samples.get(i - 1);
            Sample above = samples.get(i);
            for (Decision decision : tree.nodesOfType(Decision.class)) {
                int d = decision.vertexIndex();
                int chosenBelow = below.chosen(d).orElse(-1);
                int chosenAbove = above.chosen(d).orElse(-1);
                if (chosenBelow != chosenAbove) {
                    flips.add(bisect(tree, baseChosen, step, d, below, above, tolerance));
                }
            }
        }
        flips.sortThis(Comparator.comparingDouble(Flip::at));
        // A flip is a kink in the root's cost: a sample there keeps a straight line between samples honest.
        for (Flip flip : flips) {
            sample(tree, baseChosen, step, flip.at()).ifPresent(samples::add);
        }
        samples.sort(Comparator.comparingDouble(Sample::value));
        return Optional.of(new Swing(vertex, range, stated, base, Lists.immutable.withAll(samples), flips.toImmutable()));
    }

    private static Flip bisect(DecisionTree tree, int baseChosen, Step step, int decision, Sample below, Sample above,
                               double tolerance) {
        double lo = below.value();
        double hi = above.value();
        int chosenBelow = below.chosen(decision).orElse(-1);
        int chosenAbove = above.chosen(decision).orElse(-1);
        for (int i = 0; i < 40 && hi - lo > tolerance; i++) {
            double mid = (lo + hi) / 2;
            Optional<Sample> at = sample(tree, baseChosen, step, mid);
            if (at.isEmpty()) {
                break;
            }
            int chosen = at.get().chosen(decision).orElse(-1);
            if (chosen == chosenBelow) {
                lo = mid;
            } else {
                hi = mid;
                chosenAbove = chosen;
            }
        }
        return new Flip(decision, (lo + hi) / 2, chosenBelow, chosenAbove);
    }

    private static Optional<Sample> sample(DecisionTree tree, int baseChosen, Step step, double value) {
        Optional<Rollback> moved = step.at(value);
        if (moved.isEmpty() || !moved.get().determinable()) {
            return Optional.empty();
        }
        Rollback rollback = moved.get();
        MutableIntIntMap chosen = IntIntMaps.mutable.empty();
        // Chosen alternatives are read by vertex index, which a tree moved on one value shares with the base.
        for (Decision decision : rollback.tree().nodesOfType(Decision.class)) {
            rollback.chosen(decision.vertexIndex()).ifPresent(alternative -> chosen.put(decision.vertexIndex(), alternative));
        }
        return Optional.of(new Sample(value, rollback.rootValue().orElseThrow().midpoint(), net(rollback, baseChosen),
                chosen.toImmutable()));
    }

    /**
     * The net value of an alternative at the root of a rolled-back tree: what the next best
     * alternative would cost, less what this one costs. {@code NaN} when there is no such
     * alternative, or when either is not determinable.
     */
    static double net(Rollback rollback, int alternativeVertex) {
        Optional<Decision> root = rollback.tree().decision();
        if (alternativeVertex < 0 || root.isEmpty()) {
            return Double.NaN;
        }
        Optional<Bounds> own = rollback.value(alternativeVertex);
        double nextBest = Double.POSITIVE_INFINITY;
        for (Alternative other : root.get().alternatives()) {
            if (other.vertexIndex() == alternativeVertex) {
                continue;
            }
            Optional<Bounds> cost = rollback.value(other.vertexIndex());
            if (cost.isEmpty()) {
                return Double.NaN;
            }
            nextBest = Math.min(nextBest, cost.get().midpoint());
        }
        if (own.isEmpty() || Double.isInfinite(nextBest)) {
            return Double.NaN;
        }
        return nextBest - own.get().midpoint();
    }

    public DecisionTree tree() {
        return tree;
    }

    /** The tree rolled back with every measure at its base. */
    public Rollback base() {
        return base;
    }

    /** The expected cost at the root with every measure at its base. */
    public double baseRoot() {
        return baseRoot;
    }

    /** The alternative the root chose at the base, when it decided. */
    public OptionalInt baseChosen() {
        return base.chosen(tree.rootIndex());
    }

    /**
     * The alternative whose net value every swing is drawn on: the one the base chose, unless the
     * sweep was asked to keep another throughout.
     */
    public OptionalInt reference() {
        return reference >= 0 ? OptionalInt.of(reference) : OptionalInt.empty();
    }

    /**
     * Whether the sweep is drawn on net value: the root decided at the base, and every swing
     * valued the chosen alternative against the next best at every step.
     */
    public boolean netValued() {
        return !Double.isNaN(baseNet) && swings.allSatisfy(Swing::hasNet);
    }

    /** The net value of the base's choice at the base: its advantage over the next best alternative. */
    public double baseNet() {
        return baseNet;
    }

    /** The least net value over every swing, the base included. */
    public double leastNet() {
        return Math.min(baseNet, swings.stream().mapToDouble(Swing::leastNet).min().orElse(baseNet));
    }

    /** The greatest net value over every swing, the base included. */
    public double greatestNet() {
        return Math.max(baseNet, swings.stream().mapToDouble(Swing::greatestNet).max().orElse(baseNet));
    }

    /** Every swing, longest first. */
    public ImmutableList<Swing> swings() {
        return swings;
    }

    /** The swing of one measure, or of {@link #HEALTH_PRICE}. */
    public Optional<Swing> swing(int measureVertex) {
        return swings.detectOptional(swing -> swing.measureVertex() == measureVertex);
    }

    /** The measures that could not be swept, with why. */
    public ImmutableList<Unswept> unswept() {
        return unswept;
    }

    /** The least expected cost at the root over every swing, the base included. */
    public double least() {
        return Math.min(baseRoot, swings.stream().mapToDouble(Swing::least).min().orElse(baseRoot));
    }

    /** The greatest expected cost at the root over every swing, the base included. */
    public double greatest() {
        return Math.max(baseRoot, swings.stream().mapToDouble(Swing::greatest).max().orElse(baseRoot));
    }
}
