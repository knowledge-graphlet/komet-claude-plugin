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

import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * The typed vertices of a decision tree, the profile's counterpart of the sealed
 * {@code LogicalAxiom} hierarchy: five node kinds and four measure kinds, each a view over one
 * vertex of the stored tree that reads its properties and its children through the
 * {@link DecisionTree}. Nothing here is stored; a changed probability is a new version of the
 * vertex, and the view reads it.
 */
public sealed interface DecisionElement
        permits DecisionElement.Node, DecisionElement.Cost, DecisionElement.Probability, DecisionElement.Parameter {

    /** The vertex this element reads. */
    int vertexIndex();

    /** The tree the vertex is in. */
    DecisionTree tree();

    /** The concept the element names, through the kernel's concept reference key. */
    default OptionalLong concept() {
        return tree().conceptProperty(vertexIndex(), tree().terms().conceptReference());
    }

    /** A node of the model: a decision, an alternative, a chance, an event or an outcome. */
    sealed interface Node extends DecisionElement permits Decision, Branch, Chance, Outcome {

        /** The ANF statements the node is or holds, by identity. */
        default ImmutableLongList statements() {
            return tree().componentsProperty(vertexIndex(), tree().terms().statement());
        }

        /** The kind of case component a record binds here, when the method leaves it open. */
        default OptionalLong inputKind() {
            return tree().conceptProperty(vertexIndex(), tree().terms().inputKind());
        }
    }

    /** A branch held as a vertex: an alternative under a decision, an event under a chance. */
    sealed interface Branch extends Node permits Alternative, Event {

        /** The one structural child: the chance, outcome or further decision the branch leads to. */
        default Optional<Node> next() {
            ImmutableList<Node> structural = tree().children(vertexIndex(), Node.class);
            return structural.isEmpty() ? Optional.empty() : Optional.of(structural.get(0));
        }

        /** The costs taking this branch incurs. */
        default ImmutableList<Cost> costs() {
            return tree().children(vertexIndex(), Cost.class);
        }
    }

    /** The square: where the decider chooses among the alternatives beneath it. */
    record Decision(DecisionTree tree, int vertexIndex) implements Node {
        public ImmutableList<Alternative> alternatives() {
            return tree.children(vertexIndex, Alternative.class);
        }

        /** The parameters the tree states under this decision, when it is the root. */
        public ImmutableList<Parameter> parameters() {
            return tree.children(vertexIndex, Parameter.class);
        }
    }

    /** One course the decider may choose. */
    record Alternative(DecisionTree tree, int vertexIndex) implements Branch {
    }

    /** The circle: where the world decides among the events beneath it. */
    record Chance(DecisionTree tree, int vertexIndex) implements Node {
        public ImmutableList<Event> events() {
            return tree.children(vertexIndex, Event.class);
        }
    }

    /** One of the things that can happen at a chance. */
    record Event(DecisionTree tree, int vertexIndex) implements Branch {
        /** The probability the event carries, when written; a residual event derives its own. */
        public Optional<Probability> probability() {
            ImmutableList<Probability> written = tree.children(vertexIndex, Probability.class);
            return written.isEmpty() ? Optional.empty() : Optional.of(written.get(0));
        }

        /** Whether this event is the remainder of its chance's partition. */
        public boolean residual() {
            return tree.flagProperty(vertexIndex, tree.terms().residual(), false);
        }
    }

    /** The leaf: where the consequences are counted. */
    record Outcome(DecisionTree tree, int vertexIndex) implements Node {
        public ImmutableList<Cost> costs() {
            return tree.children(vertexIndex, Cost.class);
        }

        /** The decision whose own tree folds in here, when the outcome is one. */
        public OptionalLong treeReference() {
            return tree.conceptProperty(vertexIndex, tree.terms().treeReference());
        }
    }

    /** A cost a branch or an outcome carries, as a measure. */
    sealed interface Cost extends DecisionElement permits MoneyCost, HealthCost {
        default Measure measure() {
            return tree().measure(vertexIndex());
        }
    }

    /** A cost in money, on a money semantic. */
    record MoneyCost(DecisionTree tree, int vertexIndex) implements Cost {
    }

    /** A cost in health, on a health semantic. */
    record HealthCost(DecisionTree tree, int vertexIndex) implements Cost {
    }

    /** The share of those at a chance who leave it by one event. */
    record Probability(DecisionTree tree, int vertexIndex) implements DecisionElement {
        public Measure measure() {
            return tree.measure(vertexIndex);
        }
    }

    /** A measure of the method under the root, named by the quantity it measures; the operand of a derivation. */
    record Parameter(DecisionTree tree, int vertexIndex) implements DecisionElement {
        public Measure measure() {
            return tree.measure(vertexIndex);
        }

        /** The quantity the parameter measures: its concept. */
        public OptionalLong quantity() {
            return concept();
        }
    }
}
