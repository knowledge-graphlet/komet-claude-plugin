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
import network.ike.komet.claude.decision.DecisionElement.Branch;
import network.ike.komet.claude.decision.DecisionElement.Chance;
import network.ike.komet.claude.decision.DecisionElement.Cost;
import network.ike.komet.claude.decision.DecisionElement.CountCost;
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.HealthCost;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The consequence ledger of a decision: for each alternative at the root, what a case who takes
 * it can expect, with every consequence kept in its own unit. Money, healthy life lost, and
 * whatever the tree counts, ruptures or retreatments or implants removed, are folded from the
 * leaves along the course the rollback chose at every decision beneath, each on its own, never
 * priced into one another. This is how the regulatory topics weigh a device: a count of
 * removals against a count of dollars, and against patients who go without, with the pricing
 * left to the reader. A ledger is the trade the tornado's net value hides, and it is readable
 * when nothing is priced at all. Pure over the tree, the rollback's derivations and its choices.
 */
public final class Ledger {

    /** What a case who takes an alternative can expect: money, healthy life lost, and each counted unit. */
    public record Entry(int alternativeVertex, double money, double health, Map<Long, Double> counts) {
        /** The expected count of a unit; zero when the course never counts it. */
        public double count(long unit) {
            return counts.getOrDefault(unit, 0.0);
        }
    }

    private final ImmutableList<Entry> entries;
    private final List<Long> units;
    private final OptionalInt chosen;

    private Ledger(ImmutableList<Entry> entries, List<Long> units, OptionalInt chosen) {
        this.entries = entries;
        this.units = Collections.unmodifiableList(units);
        this.chosen = chosen;
    }

    /** The ledger of a tree rolled back under a coordinate; empty when the root is not a decision. */
    public static Optional<Ledger> of(DecisionTree tree, Rollback rollback) {
        Optional<Decision> root = tree.decision();
        if (root.isEmpty()) {
            return Optional.empty();
        }
        List<Long> units = new ArrayList<>();
        for (CountCost count : tree.nodesOfType(CountCost.class)) {
            long unit = count.measure().semanticNid();
            if (unit != 0 && !units.contains(unit)) {
                units.add(unit);
            }
        }
        List<Entry> entries = new ArrayList<>();
        for (Alternative alternative : root.get().alternatives()) {
            expected(tree, rollback, alternative.vertexIndex()).ifPresent(entries::add);
        }
        return Optional.of(new Ledger(org.eclipse.collections.api.factory.Lists.immutable.withAll(entries), units,
                rollback.chosen(tree.rootIndex())));
    }

    /** One entry per alternative at the root, in the tree's order, those with a determinable course. */
    public ImmutableList<Entry> entries() {
        return entries;
    }

    /** The entry of an alternative, when it has one. */
    public Optional<Entry> entry(int alternativeVertex) {
        return entries.detectOptional(entry -> entry.alternativeVertex() == alternativeVertex);
    }

    /** Every unit the tree counts, by the measure semantic that names it, in order of first appearance. */
    public List<Long> units() {
        return units;
    }

    /** The alternative the rollback chose at the root, when it decided. */
    public OptionalInt chosen() {
        return chosen;
    }

    /**
     * What a case can expect from a node on, along the course the rollback chose at every
     * decision beneath it, each consequence kept apart: the costs a leaf or a branch carries, a
     * chance's events weighted by their shares, a decision's chosen alternative. Empty where a
     * share or a cost is not determinable, or a decision beneath is left open.
     */
    static Optional<Entry> expected(DecisionTree tree, Rollback base, int vertexIndex) {
        Optional<DecisionElement> element = tree.node(vertexIndex);
        if (element.isEmpty() || !(element.get() instanceof Node node)) {
            return Optional.empty();
        }
        return switch (node) {
            case Decision decision -> {
                OptionalInt chosen = base.chosen(decision.vertexIndex());
                yield chosen.isPresent() ? expected(tree, base, chosen.getAsInt()).map(e -> at(vertexIndex, e)) : Optional.empty();
            }
            case Chance chance -> {
                Map<Event, Double> shares = Cohort.shares(chance, base);
                Entry sum = new Entry(vertexIndex, 0, 0, new LinkedHashMap<>());
                for (Event event : chance.events()) {
                    Double share = shares.get(event);
                    Optional<Entry> ahead = expected(tree, base, event.vertexIndex());
                    if (share == null || ahead.isEmpty()) {
                        yield Optional.empty();
                    }
                    sum = plus(sum, ahead.get(), share);
                }
                yield Optional.of(sum);
            }
            case Branch branch -> {
                Optional<Entry> own = costs(base, vertexIndex, branch.costs());
                Optional<Node> next = branch.next();
                if (own.isEmpty()) {
                    yield Optional.empty();
                }
                if (next.isEmpty()) {
                    yield own;
                }
                Optional<Entry> ahead = expected(tree, base, next.get().vertexIndex());
                yield ahead.map(a -> plus(own.get(), a, 1));
            }
            case Outcome outcome -> costs(base, vertexIndex, outcome.costs());
        };
    }

    private static Entry at(int vertexIndex, Entry entry) {
        return new Entry(vertexIndex, entry.money(), entry.health(), entry.counts());
    }

    private static Entry plus(Entry a, Entry b, double weight) {
        Map<Long, Double> counts = new LinkedHashMap<>(a.counts());
        b.counts().forEach((unit, count) -> counts.merge(unit, count * weight, Double::sum));
        return new Entry(a.alternativeVertex(), a.money() + weight * b.money(), a.health() + weight * b.health(), counts);
    }

    private static Optional<Entry> costs(Rollback base, int vertexIndex, ImmutableList<Cost> costs) {
        double money = 0;
        double health = 0;
        Map<Long, Double> counts = new LinkedHashMap<>();
        for (Cost cost : costs) {
            Optional<Bounds> bounds = base.derivations().bounds(cost.vertexIndex());
            if (bounds.isEmpty()) {
                return Optional.empty();
            }
            switch (cost) {
                case MoneyCost m -> money += bounds.get().midpoint();
                case HealthCost h -> health += bounds.get().midpoint();
                case CountCost c -> counts.merge(c.measure().semanticNid(), bounds.get().midpoint(), Double::sum);
            }
        }
        return Optional.of(new Entry(vertexIndex, money, health, counts));
    }
}
