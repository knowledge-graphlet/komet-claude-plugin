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

import network.ike.komet.claude.block.Identities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/**
 * A graphlet model under review ({@code dev-grow-model-review}): the model's text as its
 * document has it, and a copy in which a person moves its values to see what the tree would
 * choose. Nothing here writes: a change is not saved until the person saves it, and {@link #body()}
 * is then the text the model's block takes, as one edit.
 *
 * <p>The copy is the document's text with each moved value restated on its line, so a review in
 * which every value is back at the document's is the document's text, character for character,
 * and saving it would write nothing.
 *
 * <p>The values are the ones a tornado sweeps, in the tornado's order at the document's values,
 * longest swing first. Both the order and each value's range are fixed when the review opens: a
 * row does not move, and a slider's ends do not shift, while a value is under the hand. Every
 * later sweep draws the net value of one alternative, the one the document's values chose, so a
 * bar keeps its meaning when the choice changes.
 */
public final class ModelReview {

    /**
     * One value of the model.
     *
     * @param vertex   the measure's vertex in the model's tree
     * @param range    the range its slider crosses: its statement's, or the one a tornado assumes for a point
     * @param stated   whether a statement or the model stated that range, or the sweep assumed it
     * @param document the value the document has
     * @param now      the value the copy has
     */
    public record Value(int vertex, Bounds range, boolean stated, double document, double now) {

        /** Whether the copy's value differs from the document's. */
        public boolean changed() {
            return differs(now, document);
        }
    }

    private String documentBody;
    private final DecisionTerms terms;
    private final Identities ids;
    private final double price;
    private final ExternalQuantities externals;
    private final Map<String, String> linkedBodies;
    private final StatementMeasures statements;
    private DecisionTreeText.Parsed read;
    /** The alternative the document's values chose, whose net value every sweep draws; -1 when none was chosen. */
    private final int reference;
    /** By vertex, in the tornado's order at the document's values: the range, and whether it was stated. */
    private final Map<Integer, Bounds> ranges = new LinkedHashMap<>();
    private final Map<Integer, Boolean> stated = new LinkedHashMap<>();
    private final Map<Integer, Double> document = new LinkedHashMap<>();
    /** By vertex: each value the copy has that the document does not. */
    private final Map<Integer, Double> moved = new TreeMap<>();
    private String body;
    /** The sweep at the document's values, which gave the review its values, their order and their ranges. */
    private DecisionTreeSensitivity opened;

    private ModelReview(String documentBody, DecisionTerms terms, Identities ids, double price, ExternalQuantities externals,
                        Map<String, String> linkedBodies, StatementMeasures statements, DecisionTreeText.Parsed read,
                        int reference) {
        this.documentBody = documentBody;
        this.body = documentBody;
        this.terms = terms;
        this.ids = ids;
        this.price = price;
        this.externals = externals;
        this.linkedBodies = linkedBodies;
        this.statements = statements;
        this.read = read;
        this.reference = reference;
    }

    /**
     * Opens a review of a model.
     *
     * @param body         the model's text as the document has it
     * @param terms        the profile's concepts
     * @param ids          what resolves the model's symbols
     * @param price        the price of a healthy year the model is read at
     * @param externals    the quantities other blocks of the document compute
     * @param linkedBodies the trees an outcome may fold in, by their roots' identifiers
     * @param statements   the document's statements, which the model's parameters read by name
     * @return the review, or empty when the text does not read as a tree
     */
    public static Optional<ModelReview> open(String body, DecisionTerms terms, Identities ids, double price,
                                             ExternalQuantities externals, Map<String, String> linkedBodies,
                                             StatementMeasures statements) {
        ExternalQuantities quantities = externals == null ? ExternalQuantities.none() : externals;
        Map<String, String> linked = linkedBodies == null ? Map.of() : Map.copyOf(linkedBodies);
        StatementMeasures measures = statements == null ? StatementMeasures.none() : statements;
        DecisionTreeText.Parsed read = DecisionTreeText.parse(body, terms, ids, quantities, measures);
        if (read.tree().isEmpty()) {
            return Optional.empty();
        }
        Optional<DecisionTreeSensitivity> first = BlockSweep.sweep(body, terms, ids, price, BlockSweep.DEFAULT_STEPS,
                quantities, linked, measures);
        int reference = first.flatMap(sweep -> sweep.baseChosen().isPresent()
                ? Optional.of(sweep.baseChosen().getAsInt()) : Optional.<Integer>empty()).orElse(-1);
        ModelReview review = new ModelReview(body, terms, ids, price, quantities, linked, measures, read, reference);
        review.opened = first.orElse(null);
        first.ifPresent(sweep -> {
            for (DecisionTreeSensitivity.Swing swing : sweep.swings()) {
                int vertex = swing.measureVertex();
                // the price of a healthy year and a folded tree's values are not the model's own lines
                if (vertex < 0 || read.lineOf(vertex).isEmpty()) {
                    continue;
                }
                review.ranges.put(vertex, swing.range());
                review.stated.put(vertex, swing.stated());
                review.document.put(vertex, swing.base());
            }
        });
        return Optional.of(review);
    }

    /** The model's text as the document has it. */
    public String documentBody() {
        return documentBody;
    }

    /** The document's text as the review read it: its tree, the line of each value, the statements its parameters read. */
    public DecisionTreeText.Parsed read() {
        return read;
    }

    /** The sweep at the document's values, when the model could be swept: what the review's bars show until a value moves. */
    public Optional<DecisionTreeSensitivity> opened() {
        return Optional.ofNullable(opened);
    }

    /** The copy: the document's text with every moved value restated on its line. */
    public String body() {
        return body;
    }

    /** The price of a healthy year the review reads the model at. */
    public double price() {
        return price;
    }

    /** The alternative the document's values chose, whose net value every sweep of the review draws. */
    public OptionalInt reference() {
        return reference >= 0 ? OptionalInt.of(reference) : OptionalInt.empty();
    }

    /** The model's values, in the tornado's order at the document's values. */
    public List<Value> values() {
        List<Value> values = new ArrayList<>();
        for (Map.Entry<Integer, Bounds> entry : ranges.entrySet()) {
            int vertex = entry.getKey();
            double was = document.get(vertex);
            values.add(new Value(vertex, entry.getValue(), stated.get(vertex), was, moved.getOrDefault(vertex, was)));
        }
        return values;
    }

    /** One value, when the review has it. */
    public Optional<Value> value(int vertex) {
        if (!ranges.containsKey(vertex)) {
            return Optional.empty();
        }
        double was = document.get(vertex);
        return Optional.of(new Value(vertex, ranges.get(vertex), stated.get(vertex), was, moved.getOrDefault(vertex, was)));
    }

    /**
     * Moves a value in the copy. A value moved to the document's own is no longer a change.
     *
     * @param vertex the value's vertex
     * @param value  the value to take, inside its range or beyond it
     * @return whether the copy's text changed
     */
    public boolean move(int vertex, double value) {
        if (!ranges.containsKey(vertex) || Double.isNaN(value) || Double.isInfinite(value)) {
            return false;
        }
        if (differs(value, document.get(vertex))) {
            moved.put(vertex, value);
        } else {
            moved.remove(vertex);
        }
        return restate();
    }

    /**
     * Takes a text of the model that a drawing has restated, as the tree does when a value is
     * edited on one of its nodes: each of the review's values is read from the text, at the
     * midpoint of what the text states for it.
     *
     * @param restated the model's text with a value restated
     * @return whether the copy's text changed
     */
    public boolean take(String restated) {
        Optional<DecisionTree> tree = restated == null ? Optional.empty()
                : DecisionTreeText.parse(restated, terms, ids, externals, statements).tree();
        if (tree.isEmpty()) {
            return false;
        }
        for (int vertex : ranges.keySet()) {
            double value = tree.get().measure(vertex).bounds().midpoint();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                continue;
            }
            if (differs(value, document.get(vertex))) {
                moved.put(vertex, value);
            } else {
                moved.remove(vertex);
            }
        }
        return restate();
    }

    /** Resets one value to the document's. */
    public boolean reset(int vertex) {
        return moved.remove(vertex) != null && restate();
    }

    /** Resets every value to the document's. */
    public boolean resetAll() {
        if (moved.isEmpty()) {
            return false;
        }
        moved.clear();
        return restate();
    }

    /**
     * The copy has been saved in the document: its text is now the document's, each moved value
     * is the document's value, and nothing differs. The values keep their order and their ranges.
     */
    public void saved() {
        if (moved.isEmpty()) {
            return;
        }
        documentBody = body;
        document.putAll(moved);
        moved.clear();
        read = DecisionTreeText.parse(documentBody, terms, ids, externals, statements);
    }

    /**
     * The measures of the tree that read a value: each probability or cost that is read from the
     * quantity the value's parameter states, or the value's own measure when it is stated on a
     * node of the tree and not as a parameter.
     *
     * @param vertex the value's vertex
     * @return the measures' vertices, in the tree's order; empty for a value the review does not have
     */
    public List<Integer> readers(int vertex) {
        List<Integer> readers = new ArrayList<>();
        DecisionTree tree = read.tree().orElse(null);
        if (tree == null || !ranges.containsKey(vertex)) {
            return readers;
        }
        Optional<DecisionElement> value = tree.node(vertex);
        if (value.isEmpty()) {
            return readers;
        }
        if (!(value.get() instanceof DecisionElement.Parameter parameter)) {
            readers.add(vertex);
            return readers;
        }
        java.util.OptionalLong quantity = parameter.quantity();
        if (quantity.isEmpty()) {
            return readers;
        }
        for (DecisionElement element : tree.nodes()) {
            Measure measure = switch (element) {
                case DecisionElement.Cost cost -> cost.measure();
                case DecisionElement.Probability probability -> probability.measure();
                default -> null;
            };
            if (measure == null || !measure.derived()) {
                continue;
            }
            // a plain read names the quantity as what it is derived from; a derivation names it among its operands
            boolean reads = measure.operands().isEmpty()
                    ? measure.derivedFrom().getAsLong() == quantity.getAsLong()
                    : measure.operands().contains(quantity.getAsLong());
            if (reads) {
                readers.add(element.vertexIndex());
            }
        }
        return readers;
    }

    /**
     * The values a node of the tree reads: those whose measures are stated on the node, a
     * probability on an event, a cost on an alternative or an event.
     *
     * @param nodeVertex the node's vertex: a decision, an alternative, a chance, an event or an outcome
     * @return the values' vertices, in the review's order
     */
    public List<Integer> valuesAt(int nodeVertex) {
        List<Integer> at = new ArrayList<>();
        DecisionTree tree = read.tree().orElse(null);
        if (tree == null) {
            return at;
        }
        for (int vertex : ranges.keySet()) {
            for (int reader : readers(vertex)) {
                if (tree.parentIndex(reader).orElse(-1) == nodeVertex) {
                    at.add(vertex);
                    break;
                }
            }
        }
        return at;
    }

    /** How many values the copy has that the document does not. */
    public int changed() {
        return moved.size();
    }

    /** The copy from the document's text: each moved value restated on its own line, which moves no other line. */
    private boolean restate() {
        String restated = documentBody;
        for (Map.Entry<Integer, Double> entry : moved.entrySet()) {
            OptionalInt line = read.lineOf(entry.getKey());
            if (line.isPresent()) {
                restated = DecisionTreeEdits.restate(restated, line.getAsInt(), Bounds.point(entry.getValue())).orElse(restated);
            }
        }
        boolean changed = !restated.equals(body);
        body = restated;
        return changed;
    }

    /** The copy rolled back, when it reads as a tree. */
    public Optional<Rollback> rollback() {
        return rollback(body);
    }

    /** A text of the model rolled back under the review's reading: the copy's, or the document's. */
    public Optional<Rollback> rollback(String text) {
        return DecisionTreeText.parse(text, terms, ids, externals, statements).tree()
                .map(tree -> Rollback.of(tree, BlockSweep.coordinate(tree, price),
                        LinkedTrees.of(linkedBodies, terms, ids, externals, statements)));
    }

    /**
     * Sweeps a text of the model as the review draws its bars: each value over the range the
     * review opened with, the other values as the text has them, and the net value that of the
     * alternative the document's values chose. Reads nothing that changes, so a sweep may run
     * off the surface's thread on the text as it stood when the sweep was asked for.
     *
     * @param text  the copy's text at the moment of asking
     * @param steps how many steps each swing takes
     */
    public Optional<DecisionTreeSensitivity> sweep(String text, int steps) {
        Map<Integer, Bounds> fixed = Map.copyOf(ranges);
        return BlockSweep.sweep(text, terms, ids, price, steps, externals, linkedBodies, statements,
                vertex -> Optional.ofNullable(fixed.get(vertex)), reference);
    }

    /**
     * The values at which the choice at the root changes as one value crosses its range, the
     * other values as a sweep found them.
     */
    public static List<Double> thresholds(DecisionTreeSensitivity sweep, int vertex) {
        List<Double> at = new ArrayList<>();
        Optional<DecisionElement.Decision> root = sweep.tree().decision();
        if (root.isEmpty()) {
            return at;
        }
        sweep.swing(vertex).ifPresent(swing ->
                swing.flipsAt(root.get().vertexIndex()).forEach(flip -> at.add(flip.at())));
        return at;
    }

    /** Whether two values of a measure are apart: by more than rounding leaves between a number and its restatement. */
    private static boolean differs(double one, double other) {
        return Math.abs(one - other) > 1e-9 * Math.max(1, Math.max(Math.abs(one), Math.abs(other)));
    }
}
