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
 * instead: zero to one for a share, half to double for anything else; a probability under a chance
 * with a residual event may rise only as far as the other events leave. A derived measure is
 * never swept; it moves when what it reads moves. A measure that cannot be moved, or whose tree
 * is not determinable anywhere in its range, is {@linkplain #unswept() left out} with the reason.
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
    }

    /** One step of a swing: the value the measure took, the expected cost at the root, and what every decision chose. */
    public record Sample(double value, double root, ImmutableIntIntMap chosen) {
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

        /** How far the root's expected cost moves across the range: the bar's length. */
        public double width() {
            return greatest() - least();
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
            for (int i = 1; i < samples.size(); i++) {
                Sample a = samples.get(i - 1);
                Sample b = samples.get(i);
                double lo = Math.min(a.root(), b.root());
                double hi = Math.max(a.root(), b.root());
                if (root >= lo - Bounds.EPSILON && root <= hi + Bounds.EPSILON) {
                    if (b.root() == a.root()) {
                        return Optional.of(a.value());
                    }
                    double t = (root - a.root()) / (b.root() - a.root());
                    return Optional.of(a.value() + t * (b.value() - a.value()));
                }
            }
            return Optional.empty();
        }

        /** The expected cost at the root when the measure is a value, read off the steps by straight lines. */
        public Optional<Double> rootAtValue(double value) {
            for (int i = 1; i < samples.size(); i++) {
                Sample a = samples.get(i - 1);
                Sample b = samples.get(i);
                if (value >= a.value() - Bounds.EPSILON && value <= b.value() + Bounds.EPSILON) {
                    if (b.value() == a.value()) {
                        return Optional.of(a.root());
                    }
                    double t = (value - a.value()) / (b.value() - a.value());
                    return Optional.of(a.root() + t * (b.root() - a.root()));
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
    private final ImmutableList<Swing> swings;
    private final ImmutableList<Unswept> unswept;

    private DecisionTreeSensitivity(DecisionTree tree, Rollback base, ImmutableList<Swing> swings,
                                    ImmutableList<Unswept> unswept) {
        this.tree = tree;
        this.base = base;
        this.baseRoot = base.rootValue().map(Bounds::midpoint).orElse(Double.NaN);
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
        if (steps < 2) {
            throw new IllegalArgumentException("a sweep takes two or more steps, not " + steps);
        }
        MutableList<Swing> swings = Lists.mutable.empty();
        MutableList<Unswept> unswept = Lists.mutable.empty();
        if (base.determinable()) {
            for (DecisionElement element : tree.nodes()) {
                Measure measure = switch (element) {
                    case Cost cost -> cost.measure();
                    case Probability probability -> probability.measure();
                    case Parameter parameter -> parameter.measure();
                    default -> null;
                };
                if (measure == null || !measure.stated()) {
                    continue;
                }
                int vertex = element.vertexIndex();
                Optional<Bounds> range = range(tree, base, element, measure);
                if (range.isEmpty()) {
                    unswept.add(new Unswept(vertex, element instanceof Probability
                            ? "its chance has no residual event to take up what it gives or takes"
                            : "a value of zero has no assumed range"));
                    continue;
                }
                boolean stated = !measure.bounds().isPoint();
                sweep(tree, vertex, range.get(), stated, measure.bounds().midpoint(),
                        value -> mover.moved(vertex, Bounds.point(value)), steps)
                        .ifPresentOrElse(swings::add,
                                () -> unswept.add(new Unswept(vertex, "the tree is not determinable when it moves")));
            }
            double price = base.coordinate().healthPrice().midpoint();
            if (price > 0) {
                sweep(tree, HEALTH_PRICE, Bounds.of(price / 2, price * 2), false, price, mover::priced, steps)
                        .ifPresent(swings::add);
            }
        }
        swings.sortThis(Comparator.comparingDouble(Swing::width).reversed());
        return new DecisionTreeSensitivity(tree, base, swings.toImmutable(), unswept.toImmutable());
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
            return Optional.of(Bounds.of(0, ceiling));
        }
        if (value == 0) {
            return Optional.empty();
        }
        return Optional.of(value > 0 ? Bounds.of(value / 2, value * 2) : Bounds.of(value * 2, value / 2));
    }

    private interface Step {
        Optional<Rollback> at(double value);
    }

    private static Optional<Swing> sweep(DecisionTree tree, int vertex, Bounds range, boolean stated, double base,
                                         Step step, int steps) {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i <= steps; i++) {
            double value = range.lower() + (range.upper() - range.lower()) * i / steps;
            sample(tree, step, value).ifPresent(samples::add);
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
                    flips.add(bisect(tree, step, d, below, above, tolerance));
                }
            }
        }
        flips.sortThis(Comparator.comparingDouble(Flip::at));
        // A flip is a kink in the root's cost: a sample there keeps a straight line between samples honest.
        for (Flip flip : flips) {
            sample(tree, step, flip.at()).ifPresent(samples::add);
        }
        samples.sort(Comparator.comparingDouble(Sample::value));
        return Optional.of(new Swing(vertex, range, stated, base, Lists.immutable.withAll(samples), flips.toImmutable()));
    }

    private static Flip bisect(DecisionTree tree, Step step, int decision, Sample below, Sample above, double tolerance) {
        double lo = below.value();
        double hi = above.value();
        int chosenBelow = below.chosen(decision).orElse(-1);
        int chosenAbove = above.chosen(decision).orElse(-1);
        for (int i = 0; i < 40 && hi - lo > tolerance; i++) {
            double mid = (lo + hi) / 2;
            Optional<Sample> at = sample(tree, step, mid);
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

    private static Optional<Sample> sample(DecisionTree tree, Step step, double value) {
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
        return Optional.of(new Sample(value, rollback.rootValue().orElseThrow().midpoint(), chosen.toImmutable()));
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
