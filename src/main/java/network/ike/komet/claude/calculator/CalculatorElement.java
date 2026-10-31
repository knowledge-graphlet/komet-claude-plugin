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
package network.ike.komet.claude.calculator;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.Measure;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;

/**
 * The typed view of one vertex of a calculator's tree, one record per vertex kind of the
 * calculator profile ({@code CalculatorSet}): the root, an input, the output, an equation term, a
 * stratum with its conditions and coefficients, the formula, a band, and the conversion, recency
 * and missing-value measures an input carries. Each reads its properties through the tree, so a
 * record is a cursor, never a copy.
 */
public sealed interface CalculatorElement
        permits CalculatorElement.Root, CalculatorElement.Input, CalculatorElement.Output, CalculatorElement.Term,
        CalculatorElement.Stratum, CalculatorElement.Condition, CalculatorElement.Coefficient,
        CalculatorElement.Formula, CalculatorElement.Band, CalculatorElement.Conversion,
        CalculatorElement.Recency, CalculatorElement.MissingValue {

    /** The vertex this element reads. */
    int vertexIndex();

    /** The tree the vertex is in. */
    Calculator tree();

    /** The concept the element names, through the kernel's concept reference key. */
    default OptionalLong concept() {
        return tree().conceptProperty(vertexIndex(), tree().terms().conceptReference());
    }

    /** The measure the vertex carries: bounds, semantic, basis, derivation. */
    default Measure measure() {
        return tree().measure(vertexIndex());
    }

    /** The source a cited value is taken from, through the Basis source key. */
    default OptionalLong source() {
        return tree().conceptProperty(vertexIndex(), tree().terms().basisSource());
    }

    /** The root: names the calculator's concept and the publication it comes from. */
    record Root(Calculator tree, int vertexIndex) implements CalculatorElement {
        public Optional<Output> output() {
            ImmutableList<Output> outputs = tree.children(vertexIndex, Output.class);
            return outputs.isEmpty() ? Optional.empty() : Optional.of(outputs.get(0));
        }

        public ImmutableList<Input> inputs() {
            return tree.children(vertexIndex, Input.class);
        }

        public ImmutableList<Term> terms() {
            return tree.children(vertexIndex, Term.class);
        }

        public ImmutableList<Stratum> strata() {
            return tree.children(vertexIndex, Stratum.class);
        }

        public Optional<Formula> formula() {
            ImmutableList<Formula> formulas = tree.children(vertexIndex, Formula.class);
            return formulas.isEmpty() ? Optional.empty() : Optional.of(formulas.get(0));
        }

        public ImmutableList<Band> bands() {
            return tree.children(vertexIndex, Band.class);
        }
    }

    /** One quantity the equation reads, and what may bind to it. */
    record Input(Calculator tree, int vertexIndex) implements CalculatorElement {
        /** The quantity the input is, which terms, coefficients and bindings name. */
        public long quantity() {
            return concept().orElse(0L);
        }

        /** The ANF topic a bound statement must have. */
        public OptionalLong topic() {
            return tree.conceptProperty(vertexIndex, tree.terms().inputTopic());
        }

        /** The measure semantic the equation expects, zero for a concept-valued input. */
        public long expectedSemantic() {
            return measure().semanticNid();
        }

        /** The domain the equation is valid over, when stated. */
        public Optional<Bounds> domain() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }

        public boolean required() {
            return tree.flagProperty(vertexIndex, tree.terms().required(), false);
        }

        /** The concepts a concept-valued input admits. */
        public ImmutableLongList admittedValues() {
            return tree.componentsProperty(vertexIndex, tree.terms().admittedValues());
        }

        /** Whether the input takes a concept rather than a number. */
        public boolean conceptValued() {
            return !admittedValues().isEmpty();
        }

        /** The when-missing policy, empty for the default, not determinable. */
        public OptionalLong whenMissing() {
            return tree.conceptProperty(vertexIndex, tree.terms().whenMissing());
        }

        public ImmutableList<Conversion> conversions() {
            return tree.children(vertexIndex, Conversion.class);
        }

        public Optional<Recency> recency() {
            ImmutableList<Recency> recencies = tree.children(vertexIndex, Recency.class);
            return recencies.isEmpty() ? Optional.empty() : Optional.of(recencies.get(0));
        }

        public Optional<MissingValue> missingValue() {
            ImmutableList<MissingValue> values = tree.children(vertexIndex, MissingValue.class);
            return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
        }
    }

    /** What the calculator determines, and the semantic and scale its result is written in. */
    record Output(Calculator tree, int vertexIndex) implements CalculatorElement {
        public long semantic() {
            return measure().semanticNid();
        }

        public Optional<Bounds> domain() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }

        /** The factor the result is shown at, one when absent. */
        public double scale() {
            OptionalDouble scale = tree.numberProperty(vertexIndex, tree.terms().resultScale());
            return scale.orElse(1.0);
        }
    }

    /** A quantity the formula multiplies a coefficient by, derived from the inputs and other terms. */
    record Term(Calculator tree, int vertexIndex) implements CalculatorElement {
        public long quantity() {
            return concept().orElse(0L);
        }

        /** The derivation kind, or the quantity read as it is when there are no operands. */
        public OptionalLong derivedFrom() {
            return measure().derivedFrom();
        }

        public ImmutableLongList operands() {
            return measure().operands();
        }
    }

    /** One published equation of the family, selected by its conditions. */
    record Stratum(Calculator tree, int vertexIndex) implements CalculatorElement {
        public ImmutableList<Condition> conditions() {
            return tree.children(vertexIndex, Condition.class);
        }

        public ImmutableList<Coefficient> coefficients() {
            return tree.children(vertexIndex, Coefficient.class);
        }
    }

    /** One condition selecting a stratum: an input and the concept value it must carry. */
    record Condition(Calculator tree, int vertexIndex) implements CalculatorElement {
        public long input() {
            return concept().orElse(0L);
        }

        public OptionalLong value() {
            return tree.conceptProperty(vertexIndex, tree.terms().conditionValue());
        }
    }

    /** A number a stratum states: the coefficient of a term, or a constant the formula reads. */
    record Coefficient(Calculator tree, int vertexIndex) implements CalculatorElement {
        /** The term or constant quantity the coefficient belongs to. */
        public long quantity() {
            return concept().orElse(0L);
        }

        public Optional<Bounds> value() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }
    }

    /** How the selected stratum folds into the result. */
    record Formula(Calculator tree, int vertexIndex) implements CalculatorElement {
        public OptionalLong kind() {
            return measure().derivedFrom();
        }

        /** The stratum constants the kind reads, in its order. */
        public ImmutableLongList operands() {
            return measure().operands();
        }
    }

    /** An interval of the result a guideline names. */
    record Band(Calculator tree, int vertexIndex) implements CalculatorElement {
        public Optional<Bounds> interval() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }
    }

    /** A semantic an input admits beside the expected one, and the factor to the expected one. */
    record Conversion(Calculator tree, int vertexIndex) implements CalculatorElement {
        public long fromSemantic() {
            return measure().semanticNid();
        }

        public Optional<Bounds> factor() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }
    }

    /** How old a bound statement may be. */
    record Recency(Calculator tree, int vertexIndex) implements CalculatorElement {
        public Optional<Bounds> window() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }

        public long unit() {
            return measure().semanticNid();
        }
    }

    /** The bounds, or the concept, the evaluator uses when nothing binds to the input. */
    record MissingValue(Calculator tree, int vertexIndex) implements CalculatorElement {
        public Optional<Bounds> bounds() {
            Measure measure = measure();
            return measure.stated() ? Optional.of(measure.bounds()) : Optional.empty();
        }

        /** The assumed concept, for a concept-valued input. */
        public OptionalLong conceptValue() {
            return concept();
        }
    }
}
