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

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfiledTree;
import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfiledTreeFactory;
import dev.ikm.tinkar.entity.graph.adaptor.profile.VertexKinds;
import dev.ikm.tinkar.terms.EntityFacade;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import network.ike.komet.claude.calculator.CalculatorElement.Band;
import network.ike.komet.claude.calculator.CalculatorElement.Coefficient;
import network.ike.komet.claude.calculator.CalculatorElement.Condition;
import network.ike.komet.claude.calculator.CalculatorElement.Conversion;
import network.ike.komet.claude.calculator.CalculatorElement.Formula;
import network.ike.komet.claude.calculator.CalculatorElement.Input;
import network.ike.komet.claude.calculator.CalculatorElement.MissingValue;
import network.ike.komet.claude.calculator.CalculatorElement.Output;
import network.ike.komet.claude.calculator.CalculatorElement.Recency;
import network.ike.komet.claude.calculator.CalculatorElement.Root;
import network.ike.komet.claude.calculator.CalculatorElement.Stratum;
import network.ike.komet.claude.calculator.CalculatorElement.Term;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.Measure;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

/**
 * The calculator profile over a stored tree ({@code dev-calculator-blocks}): the typed view of a
 * medical calculator held as one {@code DiTree}, mirroring {@code DecisionTree} over the decision
 * profile. The root names the calculator; its children are the output, the inputs with what may
 * bind to them, the equation terms derived from the inputs, the strata of cited coefficients with
 * the conditions that select them, the formula, and the guideline bands. Nothing computed is on
 * the tree: {@link Evaluation} binds statements to the inputs and computes the result on the view.
 */
public final class Calculator extends ProfiledTree<CalculatorElement> {

    private final CalculatorTerms terms;

    public Calculator(DiTreeEntity source, CalculatorTerms terms) {
        super(source, kinds(terms));
        this.terms = terms;
    }

    public static ProfiledTreeFactory<Calculator> factory(CalculatorTerms terms) {
        return new ProfiledTreeFactory<>(tree -> new Calculator(tree, terms));
    }

    private static VertexKinds<CalculatorElement> kinds(CalculatorTerms terms) {
        return VertexKinds.<CalculatorElement>builder()
                .kind(terms.calculatorNode(), (tree, index) -> new Root((Calculator) tree, index))
                .kind(terms.inputNode(), (tree, index) -> new Input((Calculator) tree, index))
                .kind(terms.outputNode(), (tree, index) -> new Output((Calculator) tree, index))
                .kind(terms.termNode(), (tree, index) -> new Term((Calculator) tree, index))
                .kind(terms.stratumNode(), (tree, index) -> new Stratum((Calculator) tree, index))
                .kind(terms.conditionNode(), (tree, index) -> new Condition((Calculator) tree, index))
                .kind(terms.coefficientNode(), (tree, index) -> new Coefficient((Calculator) tree, index))
                .kind(terms.formulaNode(), (tree, index) -> new Formula((Calculator) tree, index))
                .kind(terms.bandNode(), (tree, index) -> new Band((Calculator) tree, index))
                .kind(terms.conversionNode(), (tree, index) -> new Conversion((Calculator) tree, index))
                .kind(terms.recencyNode(), (tree, index) -> new Recency((Calculator) tree, index))
                .kind(terms.missingValueNode(), (tree, index) -> new MissingValue((Calculator) tree, index))
                .build();
    }

    public CalculatorTerms terms() {
        return terms;
    }

    /** The root, when the tree's root is a calculator. */
    public Optional<Root> calculator() {
        return root().filter(Root.class::isInstance).map(Root.class::cast);
    }

    /** The input for a quantity, when the calculator has one. */
    public Optional<Input> input(long quantityNid) {
        return calculator().flatMap(root -> root.inputs().detectOptional(input -> input.quantity() == quantityNid));
    }

    /** The equation term for a quantity, when the calculator has one. */
    public Optional<Term> term(long quantityNid) {
        return calculator().flatMap(root -> root.terms().detectOptional(term -> term.quantity() == quantityNid));
    }

    /** The measure a vertex carries, in the decision profile's form. */
    public Measure measure(int vertexIndex) {
        OptionalDouble lower = numberProperty(vertexIndex, terms.intervalLowerBound());
        OptionalDouble upper = numberProperty(vertexIndex, terms.intervalUpperBound());
        double lo = lower.orElse(upper.orElse(Double.NaN));
        double hi = upper.orElse(lower.orElse(Double.NaN));
        Bounds bounds = new Bounds(lo, hi,
                flagProperty(vertexIndex, terms.lowerBoundOpen(), false),
                flagProperty(vertexIndex, terms.upperBoundOpen(), false));
        long semantic = conceptProperty(vertexIndex, terms.unitOfMeasure()).orElse(0L);
        return new Measure(bounds, semantic,
                conceptProperty(vertexIndex, terms.measureBasis()),
                conceptProperty(vertexIndex, terms.derivedFrom()),
                componentsProperty(vertexIndex, terms.operands()));
    }

    /** A component-list property: one nid, or several, however stored. */
    public ImmutableLongList componentsProperty(int vertexIndex, long propertyKeyNid) {
        Object value = vertex(vertexIndex).propertyFast(propertyKeyNid);
        MutableLongList result = LongLists.mutable.empty();
        switch (value) {
            case null -> { }
            case Long nid -> result.add(nid);
            case Integer nid -> result.add(nid);
            case EntityFacade facade -> result.add(facade.nid());
            case long[] nids -> result.addAll(nids);
            case ImmutableLongList nids -> result.addAll(nids);
            case Iterable<?> items -> items.forEach(item -> {
                switch (item) {
                    case Long nid -> result.add(nid);
                    case Integer nid -> result.add(nid);
                    case EntityFacade facade -> result.add(facade.nid());
                    default -> throw new IllegalStateException("vertex " + vertexIndex + " names a non-component: " + item);
                }
            });
            default -> throw new IllegalStateException("vertex " + vertexIndex + " property " + propertyKeyNid
                    + " names no component: " + value);
        }
        return result.toImmutable();
    }

    // ── Well-formedness ──────────────────────────────────────────────────────

    @Override
    public ImmutableList<ProfileFinding> check() {
        MutableList<ProfileFinding> findings = Lists.mutable.empty();
        checkProfiled(findings);
        Optional<Root> root = calculator();
        if (root.isEmpty()) {
            findings.add(ProfileFinding.ofTree("the root is not a calculator"));
            return findings.toImmutable();
        }
        Root calculator = root.get();
        if (calculator.concept().isEmpty()) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator names no concept"));
        }
        if (calculator.output().isEmpty()) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator has no output"));
        }
        if (children(calculator.vertexIndex(), Output.class).size() > 1) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator has one output"));
        }
        if (calculator.inputs().isEmpty()) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator has no input"));
        }
        if (calculator.strata().isEmpty()) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator has no stratum"));
        }
        if (calculator.formula().isEmpty()) {
            findings.add(new ProfileFinding(calculator.vertexIndex(), "a calculator has no formula"));
        }
        for (CalculatorElement element : nodes()) {
            switch (element) {
                case Root r -> childrenOnly(findings, r.vertexIndex(), "a calculator",
                        Output.class, Input.class, Term.class, Stratum.class, Formula.class, Band.class);
                case Input input -> checkInput(findings, input);
                case Output output -> {
                    childrenOnly(findings, output.vertexIndex(), "an output");
                    if (output.concept().isEmpty()) {
                        findings.add(new ProfileFinding(output.vertexIndex(), "an output names no topic"));
                    }
                    if (output.semantic() == 0) {
                        findings.add(new ProfileFinding(output.vertexIndex(), "an output names no measure semantic"));
                    }
                }
                case Term term -> {
                    childrenOnly(findings, term.vertexIndex(), "an equation term");
                    if (term.quantity() == 0) {
                        findings.add(new ProfileFinding(term.vertexIndex(), "an equation term names no quantity"));
                    }
                    checkDerivation(findings, term.vertexIndex(), term.derivedFrom(), term.operands(), true);
                }
                case Stratum stratum -> {
                    childrenOnly(findings, stratum.vertexIndex(), "a stratum", Condition.class, Coefficient.class);
                    if (stratum.coefficients().isEmpty()) {
                        findings.add(new ProfileFinding(stratum.vertexIndex(), "a stratum states no coefficient"));
                    }
                    for (Condition condition : stratum.conditions()) {
                        if (input(condition.input()).isEmpty()) {
                            findings.add(new ProfileFinding(condition.vertexIndex(), "a condition tests no input of the calculator"));
                        }
                        if (condition.value().isEmpty()) {
                            findings.add(new ProfileFinding(condition.vertexIndex(), "a condition names no value"));
                        }
                    }
                }
                case Condition condition -> { }   // checked under its stratum
                case Coefficient coefficient -> {
                    if (coefficient.quantity() == 0) {
                        findings.add(new ProfileFinding(coefficient.vertexIndex(), "a coefficient names no term or constant"));
                    }
                    if (coefficient.value().isEmpty()) {
                        findings.add(new ProfileFinding(coefficient.vertexIndex(), "a coefficient states no value"));
                    }
                }
                case Formula formula -> {
                    if (formula.kind().isEmpty()) {
                        findings.add(new ProfileFinding(formula.vertexIndex(), "a formula names no kind"));
                    } else if (!terms.isFormulaKind(formula.kind().getAsLong())) {
                        findings.add(new ProfileFinding(formula.vertexIndex(), "a formula names no kind the evaluator can run"));
                    } else if (formula.kind().getAsLong() == terms.coxSurvival() && formula.operands().size() != 2) {
                        findings.add(new ProfileFinding(formula.vertexIndex(),
                                "a Cox survival formula reads the baseline survival then the mean, not " + formula.operands().size() + " operands"));
                    }
                }
                case Band band -> {
                    if (band.concept().isEmpty()) {
                        findings.add(new ProfileFinding(band.vertexIndex(), "a band names no concept"));
                    }
                    if (band.interval().isEmpty()) {
                        findings.add(new ProfileFinding(band.vertexIndex(), "a band states no interval"));
                    }
                }
                case Conversion conversion -> {
                    if (conversion.fromSemantic() == 0) {
                        findings.add(new ProfileFinding(conversion.vertexIndex(), "a conversion names no semantic it converts from"));
                    }
                    if (conversion.factor().isEmpty()) {
                        findings.add(new ProfileFinding(conversion.vertexIndex(), "a conversion states no factor"));
                    }
                }
                case Recency recency -> {
                    if (recency.window().isEmpty()) {
                        findings.add(new ProfileFinding(recency.vertexIndex(), "a recency states no window"));
                    }
                }
                case MissingValue missing -> {
                    if (missing.bounds().isEmpty() && missing.conceptValue().isEmpty()) {
                        findings.add(new ProfileFinding(missing.vertexIndex(), "a missing value states neither bounds nor a concept"));
                    }
                }
            }
        }
        return findings.toImmutable();
    }

    private void checkInput(MutableList<ProfileFinding> findings, Input input) {
        childrenOnly(findings, input.vertexIndex(), "an input", Conversion.class, Recency.class, MissingValue.class);
        if (input.quantity() == 0) {
            findings.add(new ProfileFinding(input.vertexIndex(), "an input names no quantity"));
        }
        if (input.expectedSemantic() == 0 && !input.conceptValued()) {
            findings.add(new ProfileFinding(input.vertexIndex(),
                    "an input names the measure semantic it expects, or the concept values it admits"));
        }
        OptionalLong policy = input.whenMissing();
        boolean needsValue = policy.isPresent()
                && (policy.getAsLong() == terms.plausible() || policy.getAsLong() == terms.assumed());
        if (needsValue && input.missingValue().isEmpty()) {
            findings.add(new ProfileFinding(input.vertexIndex(),
                    "an input swept or assumed when missing states its missing value"));
        }
        if (policy.isPresent() && terms.policyWordless(policy.getAsLong()).isEmpty()) {
            findings.add(new ProfileFinding(input.vertexIndex(), "an input names a when-missing policy the evaluator does not know"));
        }
        if (children(input.vertexIndex(), MissingValue.class).size() > 1) {
            findings.add(new ProfileFinding(input.vertexIndex(), "an input has one missing value"));
        }
    }

    private void checkDerivation(MutableList<ProfileFinding> findings, int vertexIndex, OptionalLong from,
                                 ImmutableLongList operands, boolean term) {
        if (from.isEmpty()) {
            findings.add(new ProfileFinding(vertexIndex, "an equation term names what it is derived from"));
            return;
        }
        long kind = from.getAsLong();
        if (terms.isTermKind(kind)) {
            if (operands.isEmpty()) {
                findings.add(new ProfileFinding(vertexIndex, "a derivation reads its operands after of"));
            }
            for (int i = 0; i < operands.size(); i++) {
                long operand = operands.get(i);
                if (input(operand).isEmpty() && term(operand).isEmpty()) {
                    findings.add(new ProfileFinding(vertexIndex, "a derivation reads a quantity the calculator has no input or term for"));
                }
            }
        } else if (operands.isEmpty()) {
            if (input(kind).isEmpty() && term(kind).isEmpty()) {
                findings.add(new ProfileFinding(vertexIndex, "an equation term reads a quantity the calculator has no input or term for"));
            }
        } else {
            findings.add(new ProfileFinding(vertexIndex, "a derivation names no kind the evaluator can run"));
        }
    }

    @SafeVarargs
    private void childrenOnly(MutableList<ProfileFinding> findings, int vertexIndex, String what,
                              Class<? extends CalculatorElement>... allowed) {
        for (CalculatorElement child : children(vertexIndex)) {
            boolean ok = false;
            for (Class<? extends CalculatorElement> kind : allowed) {
                if (kind.isInstance(child)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                findings.add(new ProfileFinding(child.vertexIndex(), what + " does not carry a "
                        + child.getClass().getSimpleName().toLowerCase()));
            }
        }
    }

    // ── Writing a calculator ─────────────────────────────────────────────────

    public static Builder builder(CalculatorTerms terms) {
        return new Builder(terms);
    }

    /** Builds a calculator's tree vertex by vertex; every number is written as a measure. */
    public static final class Builder {
        private final CalculatorTerms terms;
        private final DiTreeEntity.Builder tree = DiTreeEntity.builder();
        private final MutableList<Handle> handles = Lists.mutable.empty();

        private Builder(CalculatorTerms terms) {
            this.terms = terms;
        }

        public Handle calculator(long conceptNid) {
            return calculator(conceptNid, UUID.randomUUID());
        }

        public Handle calculator(long conceptNid, UUID vertexId) {
            Handle root = new Handle(terms.calculatorNode(), conceptNid, vertexId);
            tree.setRoot(root.vertex);
            return root;
        }

        public DiTreeEntity build() {
            handles.forEach(Handle::commit);
            return tree.build();
        }

        public Calculator tree() {
            return new Calculator(build(), terms);
        }

        /** One vertex under construction; the child methods return the child's handle. */
        public final class Handle {
            private final EntityVertex vertex;
            private final MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();

            private Handle(long meaningNid, long conceptNid, UUID vertexId) {
                this.vertex = EntityVertex.make(vertexId, meaningNid);
                if (conceptNid != 0) {
                    properties.put(terms.conceptReference(), conceptNid);
                }
                handles.add(this);
            }

            private Handle child(long meaningNid, long conceptNid, UUID vertexId) {
                Handle child = new Handle(meaningNid, conceptNid, vertexId);
                tree.addEdge(child.vertex, vertex);
                return child;
            }

            private void commit() {
                vertex.setProperties(properties);
            }

            public int vertexIndex() {
                return vertex.vertexIndex();
            }

            public UUID vertexId() {
                return new UUID(vertex.mostSignificantBits(), vertex.leastSignificantBits());
            }

            // Children of the root.

            public Handle output(long topicNid, long semanticNid, UUID vertexId) {
                Handle output = child(terms.outputNode(), topicNid, vertexId);
                output.properties.put(terms.unitOfMeasure(), semanticNid);
                return output;
            }

            public Handle input(long quantityNid, long semanticNid, UUID vertexId) {
                Handle input = child(terms.inputNode(), quantityNid, vertexId);
                if (semanticNid != 0) {
                    input.properties.put(terms.unitOfMeasure(), semanticNid);
                }
                return input;
            }

            /** An equation term derived by a kind from quantities, or reading one quantity when the kind is that quantity. */
            public Handle term(long quantityNid, long kindOrQuantityNid, long[] operandQuantities, UUID vertexId) {
                Handle term = child(terms.termNode(), quantityNid, vertexId);
                term.properties.put(terms.derivedFrom(), kindOrQuantityNid);
                if (operandQuantities.length > 0) {
                    term.properties.put(terms.operands(), operandQuantities.clone());
                }
                term.properties.put(terms.measureBasis(), terms.shared().derivedBasis());
                return term;
            }

            public Handle stratum(long conceptNid, UUID vertexId) {
                return child(terms.stratumNode(), conceptNid, vertexId);
            }

            public Handle formula(long kindNid, long[] operandQuantities, UUID vertexId) {
                Handle formula = child(terms.formulaNode(), 0, vertexId);
                formula.properties.put(terms.derivedFrom(), kindNid);
                if (operandQuantities.length > 0) {
                    formula.properties.put(terms.operands(), operandQuantities.clone());
                }
                return formula;
            }

            public Handle band(long conceptNid, Bounds interval, UUID vertexId) {
                Handle band = child(terms.bandNode(), conceptNid, vertexId);
                band.bounds(interval);
                return band;
            }

            // Children of a stratum.

            public Handle condition(long inputQuantityNid, long valueNid, UUID vertexId) {
                Handle condition = child(terms.conditionNode(), inputQuantityNid, vertexId);
                condition.properties.put(terms.conditionValue(), valueNid);
                return condition;
            }

            public Handle coefficient(long quantityNid, Bounds value, UUID vertexId) {
                Handle coefficient = child(terms.coefficientNode(), quantityNid, vertexId);
                coefficient.bounds(value);
                return coefficient;
            }

            // Children of an input.

            public Handle conversion(long fromSemanticNid, Bounds factor, UUID vertexId) {
                Handle conversion = child(terms.conversionNode(), 0, vertexId);
                conversion.properties.put(terms.unitOfMeasure(), fromSemanticNid);
                conversion.bounds(factor);
                return conversion;
            }

            public Handle recency(Bounds window, long unitNid, UUID vertexId) {
                Handle recency = child(terms.recencyNode(), 0, vertexId);
                recency.bounds(window);
                recency.properties.put(terms.unitOfMeasure(), unitNid);
                return recency;
            }

            public Handle missingValue(Bounds bounds, UUID vertexId) {
                Handle missing = child(terms.missingValueNode(), 0, vertexId);
                missing.bounds(bounds);
                return missing;
            }

            public Handle missingConcept(long conceptNid, UUID vertexId) {
                return child(terms.missingValueNode(), conceptNid, vertexId);
            }

            // Properties of this vertex.

            /** The vertex's own bounds: an input's or output's domain, a coefficient's value, a band's interval. */
            public Handle bounds(Bounds bounds) {
                properties.put(terms.intervalLowerBound(), bounds.lower());
                properties.put(terms.intervalUpperBound(), bounds.upper());
                if (bounds.lowerOpen()) {
                    properties.put(terms.lowerBoundOpen(), Boolean.TRUE);
                } else {
                    properties.remove(terms.lowerBoundOpen());
                }
                if (bounds.upperOpen()) {
                    properties.put(terms.upperBoundOpen(), Boolean.TRUE);
                } else {
                    properties.remove(terms.upperBoundOpen());
                }
                return this;
            }

            public Handle topic(long topicNid) {
                properties.put(terms.inputTopic(), topicNid);
                return this;
            }

            public Handle required() {
                properties.put(terms.required(), Boolean.TRUE);
                return this;
            }

            public Handle admittedValues(long[] valueNids) {
                properties.put(terms.admittedValues(), valueNids.clone());
                return this;
            }

            public Handle whenMissing(long policyNid) {
                properties.put(terms.whenMissing(), policyNid);
                return this;
            }

            public Handle scale(double factor) {
                properties.put(terms.resultScale(), factor);
                return this;
            }

            public Handle basis(long basisKindNid) {
                properties.put(terms.measureBasis(), basisKindNid);
                return this;
            }

            public Handle source(long sourceNid) {
                properties.put(terms.basisSource(), sourceNid);
                return this;
            }
        }
    }
}
