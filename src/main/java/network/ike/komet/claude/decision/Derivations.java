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

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import network.ike.komet.claude.decision.DecisionElement.Parameter;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The derived measures of a tree, computed from its parameters by the derivation kinds with
 * native implementations ({@code Derivation kind} in the IKE starter set): complement, product,
 * cumulative hazard, Bayes posterior and Bayes marginal. A parameter is a measure under the root
 * named by the quantity it measures; a derived measure names its kind through {@code Derived
 * from} and the quantities it reads through {@code Derivation operands}. Every kind evaluates
 * over bounds, so a bounded assumption gives a bounded number, wider than the truth where one
 * operand appears twice (the dependency problem, accepted). A derivation whose kind is not one
 * of these, an ELM definition the evaluator cannot yet run, or whose operands are missing or
 * circular, has no value, and the finding says why.
 */
public final class Derivations {

    private final DecisionTree tree;
    private final DecisionTerms terms;
    /** The bounds of each parameter by its quantity, computed once; absent when not determinable. */
    private final MutableLongObjectMap<Optional<Bounds>> byQuantity = LongObjectMaps.mutable.empty();
    private final MutableLongObjectMap<Parameter> parameters = LongObjectMaps.mutable.empty();
    private final MutableList<ProfileFinding> findings = Lists.mutable.empty();
    private final List<Long> evaluating = new ArrayList<>();

    private Derivations(DecisionTree tree) {
        this.tree = tree;
        this.terms = tree.terms();
        for (Parameter parameter : tree.nodesOfType(Parameter.class)) {
            parameter.quantity().ifPresent(quantity -> {
                if (parameters.containsKey(quantity)) {
                    findings.add(new ProfileFinding(parameter.vertexIndex(), "a quantity has two parameters"));
                } else {
                    parameters.put(quantity, parameter);
                }
            });
        }
    }

    /** The derivations of a tree, evaluated as they are asked for. */
    public static Derivations of(DecisionTree tree) {
        return new Derivations(tree);
    }

    /** The bounds of a measure: the ones it states, or the ones its derivation computes. */
    public Optional<Bounds> bounds(Measure measure) {
        if (measure.stated()) {
            return Optional.of(measure.bounds());
        }
        if (!measure.derived()) {
            return Optional.empty();
        }
        return evaluate(measure.derivedFrom().getAsLong(), measure.operands(), -1);
    }

    /** The bounds of a measure vertex, naming the vertex in any finding. */
    public Optional<Bounds> bounds(int vertexIndex) {
        Measure measure = tree.measure(vertexIndex);
        if (measure.stated()) {
            return Optional.of(measure.bounds());
        }
        if (!measure.derived()) {
            return notDeterminable(vertexIndex, "a measure has no bounds and no derivation");
        }
        return evaluate(measure.derivedFrom().getAsLong(), measure.operands(), vertexIndex);
    }

    /** The bounds of the parameter for a quantity, derived if it is derived. */
    public Optional<Bounds> quantity(long quantityNid) {
        if (byQuantity.containsKey(quantityNid)) {
            return byQuantity.get(quantityNid);
        }
        Parameter parameter = parameters.get(quantityNid);
        if (parameter == null) {
            return Optional.empty();
        }
        if (evaluating.contains(quantityNid)) {
            return notDeterminable(parameter.vertexIndex(), "a derivation reads itself");
        }
        evaluating.add(quantityNid);
        Optional<Bounds> bounds = bounds(parameter.vertexIndex());
        evaluating.remove(quantityNid);
        byQuantity.put(quantityNid, bounds);
        return bounds;
    }

    /** The parameters by quantity, for a renderer. */
    public ImmutableList<Parameter> parameters() {
        return tree.nodesOfType(Parameter.class);
    }

    public ImmutableList<ProfileFinding> findings() {
        return findings.toImmutable();
    }

    // ── The kinds ────────────────────────────────────────────────────────────

    private Optional<Bounds> evaluate(long kind, ImmutableLongList operands, int vertexIndex) {
        if (operands.isEmpty() && !terms.isDerivationKind(kind)) {
            // derived from a quantity alone: the measure takes that parameter's value as it is
            if (!parameters.containsKey(kind)) {
                return notDeterminable(vertexIndex, "a measure reads a quantity the tree has no parameter for");
            }
            Optional<Bounds> value = quantity(kind);
            if (value.isEmpty()) {
                return notDeterminable(vertexIndex, "the parameter a measure reads is not determinable");
            }
            return value;
        }
        if (!terms.isDerivationKind(kind)) {
            return notDeterminable(vertexIndex, "a derivation names no kind the evaluator can run");
        }
        List<Bounds> values = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            long quantity = operands.get(i);
            if (!parameters.containsKey(quantity)) {
                return notDeterminable(vertexIndex, "a derivation reads a quantity the tree has no parameter for");
            }
            Optional<Bounds> value = quantity(quantity);
            if (value.isEmpty()) {
                return notDeterminable(vertexIndex, "an operand of a derivation is not determinable");
            }
            values.add(value.get());
        }
        if (kind == terms.complement()) {
            return arity(values, 1, vertexIndex, "complement").map(v -> v.get(0).complement());
        }
        if (kind == terms.product()) {
            if (values.size() < 2) {
                return notDeterminable(vertexIndex, "a product reads two or more operands");
            }
            Bounds product = values.get(0);
            for (int i = 1; i < values.size(); i++) {
                product = product.times(values.get(i));
            }
            return Optional.of(product);
        }
        if (kind == terms.cumulativeHazard()) {
            return arity(values, 2, vertexIndex, "cumulative hazard").map(v -> cumulativeHazard(v.get(0), v.get(1)));
        }
        if (kind == terms.bayesPosterior()) {
            return arity(values, 3, vertexIndex, "Bayes posterior").map(v -> posterior(v.get(0), v.get(1), v.get(2)));
        }
        return arity(values, 3, vertexIndex, "Bayes marginal").map(v -> marginal(v.get(0), v.get(1), v.get(2)));
    }

    private Optional<List<Bounds>> arity(List<Bounds> values, int arity, int vertexIndex, String kind) {
        if (values.size() != arity) {
            notDeterminable(vertexIndex, "a " + kind + " reads " + arity + (arity == 1 ? " operand" : " operands")
                    + ", not " + values.size());
            return Optional.empty();
        }
        return Optional.of(values);
    }

    /** One minus the complement of the hazard to the power of the years: rising in both. */
    static Bounds cumulativeHazard(Bounds hazard, Bounds years) {
        double lo = 1 - Math.pow(1 - hazard.lower(), years.lower());
        double hi = 1 - Math.pow(1 - hazard.upper(), years.upper());
        return new Bounds(Math.min(lo, hi), Math.max(lo, hi),
                hazard.lowerOpen() || years.lowerOpen(), hazard.upperOpen() || years.upperOpen());
    }

    /** Prior times the likelihood given present, over that plus the complement of the prior times the likelihood given absent. */
    static Bounds posterior(Bounds prior, Bounds givenPresent, Bounds givenAbsent) {
        Bounds numerator = prior.times(givenPresent);
        Bounds denominator = marginal(prior, givenPresent, givenAbsent);
        return numerator.dividedBy(denominator).clip(0, 1);
    }

    /** Prior times the likelihood given present plus the complement of the prior times the likelihood given absent. */
    static Bounds marginal(Bounds prior, Bounds givenPresent, Bounds givenAbsent) {
        return prior.times(givenPresent).plus(prior.complement().times(givenAbsent)).clip(0, 1);
    }

    private Optional<Bounds> notDeterminable(int vertexIndex, String why) {
        findings.add(new ProfileFinding(vertexIndex, why));
        return Optional.empty();
    }
}
