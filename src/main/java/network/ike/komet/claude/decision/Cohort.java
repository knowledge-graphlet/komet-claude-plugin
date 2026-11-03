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
import network.ike.komet.claude.decision.DecisionElement.Decision;
import network.ike.komet.claude.decision.DecisionElement.Event;
import network.ike.komet.claude.decision.DecisionElement.Node;
import network.ike.komet.claude.decision.DecisionElement.Outcome;
import network.ike.komet.claude.decision.DecisionElement.Probability;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntDoubleMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.map.primitive.MutableIntDoubleMap;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * A decision tree counted out for a cohort: how many of a thousand, say, reach each node, which
 * is how the regulatory topics explain a tree before any price is put on it. The whole cohort
 * enters the root and takes every alternative in turn, since each alternative is its own
 * hypothesis; at a chance the count splits by the events' shares, the residual taking what the
 * others leave. Beneath a nested decision the count follows the alternative the rollback chose,
 * so a tally of where the cohort ends up is a tally of the course the tree recommends. A
 * {@link Tally} is that: for each alternative at the root, the outcomes its cohort reaches,
 * largest first, like outcomes merged by the concept that names them. Pure over the tree, the
 * rollback's derivations and its choices.
 */
public final class Cohort {

    /** One outcome a cohort reaches: the concept naming it, when it has one, and how many arrive. */
    public record Leaf(OptionalLong concept, int vertexIndex, double count) {
    }

    /** Where the cohort that takes one alternative at the root ends up. */
    public record Tally(int alternativeVertex, double count, ImmutableList<Leaf> leaves) {
    }

    private final double size;
    private final MutableIntDoubleMap counts = IntDoubleMaps.mutable.empty();
    /** By outcome: the count of the tree it folds in, for those who reach the outcome. */
    private final org.eclipse.collections.api.map.primitive.MutableIntObjectMap<Cohort> folded =
            org.eclipse.collections.api.factory.primitive.IntObjectMaps.mutable.empty();
    private final ImmutableList<Tally> tallies;

    private Cohort(DecisionTree tree, Rollback rollback, double size) {
        this.size = size;
        MutableList<Tally> tallies = Lists.mutable.empty();
        Optional<DecisionElement> root = tree.root();
        if (root.isPresent() && root.get() instanceof Decision decision) {
            walk(decision, rollback, size);
            for (Alternative alternative : decision.alternatives()) {
                Map<Object, Leaf> leaves = new LinkedHashMap<>();
                gather(alternative, rollback, leaves);
                tallies.add(new Tally(alternative.vertexIndex(), size, sorted(leaves)));
            }
        } else if (root.isPresent() && root.get() instanceof Chance chance) {
            // a tree of consequences: one tally, of everyone who enters it
            walk(chance, rollback, size);
            Map<Object, Leaf> leaves = new LinkedHashMap<>();
            for (Event event : chance.events()) {
                gather(event, rollback, leaves);
            }
            tallies.add(new Tally(chance.vertexIndex(), size, sorted(leaves)));
        }
        this.tallies = tallies.toImmutable();
    }

    private static ImmutableList<Leaf> sorted(Map<Object, Leaf> leaves) {
        MutableList<Leaf> sorted = Lists.mutable.withAll(leaves.values());
        sorted.sortThis(Comparator.comparingDouble(Leaf::count).reversed());
        return sorted.toImmutable();
    }

    /** The count of the tree an outcome folds in, for those who reach the outcome, when it folds one in. */
    public Optional<Cohort> folded(int outcomeVertex) {
        return Optional.ofNullable(folded.get(outcomeVertex));
    }

    /** Counts a tree out for a cohort of a size, following the rollback's choices beneath the root. */
    public static Cohort of(DecisionTree tree, Rollback rollback, double size) {
        return new Cohort(tree, rollback, size);
    }

    /** The cohort's size: how many enter the root. */
    public double size() {
        return size;
    }

    /** How many of the cohort reach a node or take a branch; empty where a share on the way is not determinable. */
    public OptionalDouble count(int vertexIndex) {
        return counts.containsKey(vertexIndex) ? OptionalDouble.of(counts.get(vertexIndex)) : OptionalDouble.empty();
    }

    /** Where the cohort ends up, one tally per alternative at the root. */
    public ImmutableList<Tally> tallies() {
        return tallies;
    }

    private void walk(Node node, Rollback rollback, double count) {
        counts.put(node.vertexIndex(), count);
        switch (node) {
            case Decision decision -> {
                for (Alternative alternative : decision.alternatives()) {
                    walk(alternative, rollback, count);
                }
            }
            case Chance chance -> {
                Map<Event, Double> shares = shares(chance, rollback);
                for (Event event : chance.events()) {
                    Double share = shares.get(event);
                    if (share != null) {
                        walk(event, rollback, count * share);
                    }
                }
            }
            case Branch branch -> branch.next().ifPresent(next -> walk(next, rollback, count));
            case Outcome outcome -> rollback.folded(outcome.vertexIndex()).ifPresent(inner ->
                    folded.put(outcome.vertexIndex(), new Cohort(inner.tree(), inner, count)));
        }
    }

    /** Each event's share of those at a chance, the residual's what the others leave; absent where not determinable. */
    static Map<Event, Double> shares(Chance chance, Rollback rollback) {
        Map<Event, Double> shares = new LinkedHashMap<>();
        Bounds others = Bounds.point(0);
        Event residual = null;
        boolean determinable = true;
        for (Event event : chance.events()) {
            if (event.residual()) {
                residual = event;
                continue;
            }
            Optional<Probability> p = event.probability();
            Optional<Bounds> share = p.flatMap(probability -> rollback.derivations().bounds(probability.vertexIndex()));
            if (share.isEmpty()) {
                determinable = false;
                continue;
            }
            shares.put(event, share.get().midpoint());
            others = others.plus(share.get());
        }
        if (residual != null && determinable) {
            shares.put(residual, others.complement().clip(0, 1).midpoint());
        }
        return shares;
    }

    /** The leaves beneath a branch, by the course the rollback chose, merged by the concept naming them. */
    private void gather(Branch branch, Rollback rollback, Map<Object, Leaf> leaves) {
        OptionalDouble here = count(branch.vertexIndex());
        if (here.isEmpty()) {
            return;
        }
        Optional<Node> next = branch.next();
        if (next.isEmpty()) {
            leaf(branch, here.getAsDouble(), leaves);
            return;
        }
        switch (next.get()) {
            case Outcome outcome -> {
                // an outcome that folds in a tree ends where that tree's leaves do
                Cohort inner = folded.get(outcome.vertexIndex());
                if (inner == null) {
                    leaf(outcome, here.getAsDouble(), leaves);
                } else {
                    for (Tally tally : inner.tallies()) {
                        for (Leaf leaf : tally.leaves()) {
                            Object key = leaf.concept().isPresent() ? (Object) leaf.concept().getAsLong() : (Object) ("folded " + leaf.vertexIndex());
                            Leaf was = leaves.get(key);
                            leaves.put(key, new Leaf(leaf.concept(), leaf.vertexIndex(), (was == null ? 0 : was.count()) + leaf.count()));
                        }
                    }
                }
            }
            case Chance chance -> {
                for (Event event : chance.events()) {
                    gather(event, rollback, leaves);
                }
            }
            case Decision decision -> rollback.chosen(decision.vertexIndex()).ifPresent(chosen ->
                    decision.alternatives().detectOptional(a -> a.vertexIndex() == chosen).ifPresent(a -> gather(a, rollback, leaves)));
            case Branch other -> gather(other, rollback, leaves);
        }
    }

    private static void leaf(DecisionElement node, double count, Map<Object, Leaf> leaves) {
        OptionalLong concept = node.concept();
        Object key = concept.isPresent() ? (Object) concept.getAsLong() : (Object) ("vertex " + node.vertexIndex());
        Leaf was = leaves.get(key);
        leaves.put(key, new Leaf(concept, node.vertexIndex(), (was == null ? 0 : was.count()) + count));
    }
}
