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

import network.ike.komet.claude.decision.DecisionElement.Parameter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/**
 * The values of a model as a reader meets them (KEC, 2026-11-13): each value under what the tree
 * reads it for, and each connected to the values and the branches it goes with. A value a chance
 * node's event reads is under that chance node; a cost is among the costs, a count among the
 * counts; a value another value is worked out from is beneath that value; a value nothing reads
 * is said to be read by no branch.
 *
 * <p>It reads the tree and computes nothing: the values' bounds are the derivations' to give.
 */
public final class ModelValues {

    /** What a group of values is read for. */
    public enum Kind {
        /** The chances of one chance node's events. */
        CHANCE,
        /** Costs in money. */
        COSTS,
        /** Costs in health. */
        HEALTH,
        /** Counted things. */
        COUNTS,
        /** Values no branch reads, and no other value is worked out from. */
        UNREAD
    }

    /**
     * One row of a group.
     *
     * @param vertex the value's parameter vertex
     * @param depth  0 for a value the tree reads, 1 for a value it is worked out from, and so on
     */
    public record Row(int vertex, int depth) {
    }

    /**
     * Values read for one thing, in the model's order.
     *
     * @param kind    what they are read for
     * @param concept the chance node's concept, for {@link Kind#CHANCE}, the first of its name; empty for any other kind
     * @param rows    the values, a value worked out from others directly above them
     */
    public record Group(Kind kind, OptionalLong concept, List<Row> rows) {
        public Group {
            rows = List.copyOf(rows);
        }
    }

    private final DecisionTree tree;
    private final List<Integer> parameters = new ArrayList<>();
    /** By parameter vertex: the parameters it is worked out from, and the ones worked out from it. */
    private final Map<Integer, List<Integer>> inputs = new LinkedHashMap<>();
    private final Map<Integer, List<Integer>> consumers = new LinkedHashMap<>();
    /** By parameter vertex: the probabilities and costs of the tree that read it themselves. */
    private final Map<Integer, List<Integer>> direct = new LinkedHashMap<>();
    /** By parameter vertex: the values it is worked out from that stand directly beneath its row. */
    private final Map<Integer, List<Integer>> beneath = new LinkedHashMap<>();
    private final List<Group> groups = new ArrayList<>();

    private ModelValues(DecisionTree tree, java.util.function.LongFunction<String> names) {
        this.tree = tree;
        Map<Long, Integer> byQuantity = new LinkedHashMap<>();
        for (Parameter parameter : tree.nodesOfType(Parameter.class)) {
            parameters.add(parameter.vertexIndex());
            inputs.put(parameter.vertexIndex(), new ArrayList<>());
            consumers.put(parameter.vertexIndex(), new ArrayList<>());
            direct.put(parameter.vertexIndex(), new ArrayList<>());
            parameter.quantity().ifPresent(quantity -> byQuantity.putIfAbsent(quantity, parameter.vertexIndex()));
        }
        for (DecisionElement element : tree.nodes()) {
            Measure measure = switch (element) {
                case Parameter parameter -> parameter.measure();
                case DecisionElement.Cost cost -> cost.measure();
                case DecisionElement.Probability probability -> probability.measure();
                default -> null;
            };
            if (measure == null || !measure.derived()) {
                continue;
            }
            // a plain read names the quantity as what it is derived from; a derivation names it among its operands
            List<Long> read = new ArrayList<>();
            if (measure.operands().isEmpty()) {
                read.add(measure.derivedFrom().getAsLong());
            } else {
                measure.operands().forEach(read::add);
            }
            for (long quantity : read) {
                Integer source = byQuantity.get(quantity);
                if (source == null || source == element.vertexIndex()) {
                    continue;
                }
                if (element instanceof Parameter) {
                    if (!inputs.get(element.vertexIndex()).contains(source)) {
                        inputs.get(element.vertexIndex()).add(source);
                        consumers.get(source).add(element.vertexIndex());
                    }
                } else if (!direct.get(source).contains(element.vertexIndex())) {
                    direct.get(source).add(element.vertexIndex());
                }
            }
        }
        Map<String, Kind> kinds = new LinkedHashMap<>();
        Map<String, OptionalLong> concepts = new LinkedHashMap<>();
        Map<String, List<Row>> rows = new LinkedHashMap<>();
        for (int vertex : parameters) {
            boolean read = !direct.get(vertex).isEmpty();
            if (!read && !consumers.get(vertex).isEmpty()) {
                continue;                                                // beneath the value worked out from it
            }
            Kind kind = read ? kindOf(direct.get(vertex).getFirst()) : Kind.UNREAD;
            OptionalLong concept = kind == Kind.CHANCE ? chanceOf(direct.get(vertex).getFirst()) : OptionalLong.empty();
            // by name: a tree read from text keeps two chance nodes of one name apart, and a reader does not
            String key = kind + (concept.isPresent() ? ":" + names.apply(concept.getAsLong()) : "");
            kinds.putIfAbsent(key, kind);
            concepts.putIfAbsent(key, concept);
            place(vertex, 0, rows.computeIfAbsent(key, k -> new ArrayList<>()), new HashSet<>());
        }
        rows.forEach((key, list) -> groups.add(new Group(kinds.get(key), concepts.get(key), list)));
    }

    /**
     * The values of a tree.
     *
     * @param tree  a model's tree
     * @param names what a concept is called: the chances of chance nodes of one name are one group,
     *              under the concept of the first of them
     * @return its values, grouped and connected
     */
    public static ModelValues of(DecisionTree tree, java.util.function.LongFunction<String> names) {
        return new ModelValues(tree, names);
    }

    /** A value, then beneath it the values it is worked out from that the tree does not read themselves. */
    private void place(int vertex, int depth, List<Row> rows, Set<Integer> above) {
        rows.add(new Row(vertex, depth));
        if (!above.add(vertex) || depth >= 6) {
            return;
        }
        for (int input : inputs.get(vertex)) {
            if (direct.get(input).isEmpty() && consumers.get(input).getFirst() == vertex) {
                beneath.computeIfAbsent(vertex, v -> new ArrayList<>()).add(input);
                place(input, depth + 1, rows, above);
            }
        }
        above.remove(vertex);
    }

    private Kind kindOf(int reader) {
        return switch (tree.node(reader).orElse(null)) {
            case DecisionElement.MoneyCost money -> Kind.COSTS;
            case DecisionElement.HealthCost health -> Kind.HEALTH;
            case DecisionElement.CountCost count -> Kind.COUNTS;
            case null, default -> Kind.CHANCE;
        };
    }

    /** The concept of the chance node whose event states a probability. */
    private OptionalLong chanceOf(int probability) {
        int event = tree.parentIndex(probability).orElse(-1);
        int chance = event < 0 ? -1 : tree.parentIndex(event).orElse(-1);
        return chance < 0 ? OptionalLong.empty()
                : tree.node(chance).filter(DecisionElement.Chance.class::isInstance)
                .map(node -> ((DecisionElement.Chance) node).concept()).orElse(OptionalLong.empty());
    }

    /** How many values the model has. */
    public int count() {
        return parameters.size();
    }

    /** The groups, in the order the model first names a value of each. */
    public List<Group> groups() {
        return List.copyOf(groups);
    }

    /**
     * The values a value is worked out from.
     *
     * @param vertex a value's parameter vertex
     * @return the parameter vertices, in the derivation's order; empty for a stated value
     */
    public List<Integer> inputs(int vertex) {
        return List.copyOf(inputs.getOrDefault(vertex, List.of()));
    }

    /**
     * The values a value is worked out from whose rows stand directly beneath its own: those the
     * tree does not read themselves.
     *
     * @param vertex a value's parameter vertex
     * @return the parameter vertices, in the derivation's order
     */
    public List<Integer> beneath(int vertex) {
        return List.copyOf(beneath.getOrDefault(vertex, List.of()));
    }

    /**
     * The measures of the tree that read a value: each probability or cost read from it, or from
     * a value worked out from it.
     *
     * @param vertex a value's parameter vertex
     * @return the measures' vertices
     */
    public List<Integer> readers(int vertex) {
        Set<Integer> readers = new LinkedHashSet<>();
        for (int value : downstream(vertex)) {
            readers.addAll(direct.getOrDefault(value, List.of()));
        }
        return List.copyOf(readers);
    }

    /**
     * The values that go with a value: itself, the values it is worked out from, and the values
     * worked out from it.
     *
     * @param vertex a value's parameter vertex
     * @return the parameter vertices
     */
    public List<Integer> related(int vertex) {
        Set<Integer> related = new LinkedHashSet<>(downstream(vertex));
        collect(vertex, inputs, related);
        return List.copyOf(related);
    }

    /**
     * The values a node of the tree reads: those a probability or a cost stated on the node is
     * read from, and the values those are worked out from. A residual event, whose chance is the
     * rest, reads the chances of the other events of its chance node.
     *
     * @param node the node's vertex: an alternative, an event or an outcome
     * @return the parameter vertices
     */
    public List<Integer> valuesAt(int node) {
        Set<Integer> at = new LinkedHashSet<>();
        // a residual event's chance is what its fellow events leave, so it goes with the chances stated on them
        int chance = tree.node(node).filter(DecisionElement.Event.class::isInstance)
                .filter(event -> ((DecisionElement.Event) event).residual()).isPresent() ? tree.parentIndex(node).orElse(-1) : -1;
        for (int vertex : parameters) {
            for (int reader : direct.get(vertex)) {
                int on = tree.parentIndex(reader).orElse(-1);
                boolean fellow = chance >= 0 && on != node && tree.node(reader).orElse(null) instanceof DecisionElement.Probability
                        && tree.parentIndex(on).orElse(-1) == chance;
                if (on == node || fellow) {
                    at.add(vertex);
                    collect(vertex, inputs, at);
                    break;
                }
            }
        }
        return List.copyOf(at);
    }

    /** A value and every value worked out from it, directly or through another. */
    private Set<Integer> downstream(int vertex) {
        Set<Integer> down = new LinkedHashSet<>();
        if (inputs.containsKey(vertex)) {
            down.add(vertex);
            collect(vertex, consumers, down);
        }
        return down;
    }

    private static void collect(int vertex, Map<Integer, List<Integer>> next, Set<Integer> into) {
        for (int other : next.getOrDefault(vertex, List.of())) {
            if (into.add(other)) {
                collect(other, next, into);
            }
        }
    }
}
