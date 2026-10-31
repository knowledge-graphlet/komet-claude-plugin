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

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.calculator.CalculatorElement.Band;
import network.ike.komet.claude.calculator.CalculatorElement.Coefficient;
import network.ike.komet.claude.calculator.CalculatorElement.Condition;
import network.ike.komet.claude.calculator.CalculatorElement.Conversion;
import network.ike.komet.claude.calculator.CalculatorElement.Formula;
import network.ike.komet.claude.calculator.CalculatorElement.Input;
import network.ike.komet.claude.calculator.CalculatorElement.MissingValue;
import network.ike.komet.claude.calculator.CalculatorElement.Output;
import network.ike.komet.claude.calculator.CalculatorElement.Root;
import network.ike.komet.claude.calculator.CalculatorElement.Stratum;
import network.ike.komet.claude.calculator.CalculatorElement.Term;
import network.ike.komet.claude.decision.Bounds;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongDoubleMaps;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;
import org.eclipse.collections.api.map.primitive.MutableLongDoubleMap;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

/**
 * One evaluation of a calculator against the bindings of a document ({@code dev-calculator-blocks}):
 * a pure function of the calculator, the bindings and nothing else, computed on the view and never
 * stored. Each input is resolved to the bounds the equation reads, observed, supplied, assumed by
 * the method's policy or swept over its plausible range, or left not determinable with a finding
 * naming it; the stratum is selected by the concept-valued inputs; and the result is computed
 * <em>pointwise</em> at every corner of the inputs' bounds, since a published risk equation is
 * monotone in each input, so a bounded input gives honest bounds without the dependency problem
 * interval arithmetic would have over an equation that reads one input in several terms.
 */
public final class Evaluation {

    /** How an input's value was arrived at. */
    public enum Status { OBSERVED, SUPPLIED, ASSUMED, PLAUSIBLE, NOT_DETERMINED, NOT_ADMITTED, OUT_OF_DOMAIN }

    /**
     * One input as the evaluation read it: the bounds in the equation's semantic, the concept
     * value for a concept-valued input, the status, and the binding it came from.
     */
    public record InputState(Input input, Status status, Optional<Bounds> bounds, OptionalLong conceptValue,
                             Optional<Binding> binding, Optional<String> note) {
        /** Whether the value stood in for a statement rather than being read from one. */
        public boolean standsIn() {
            return status == Status.ASSUMED || status == Status.PLAUSIBLE;
        }

        /** Whether the input's bounds are an interval rather than a point. */
        public boolean interval() {
            return bounds.isPresent() && !bounds.get().isPoint();
        }
    }

    /** Beyond this many interval inputs the corner sweep samples the widest, with a finding. */
    static final int MAX_CORNER_INPUTS = 12;

    /** Tolerance for comparing bounds that arithmetic has rounded. */
    static final double EPSILON = 1e-9;

    private final Calculator calculator;
    private final Bindings bindings;
    private final CalculatorTerms terms;
    private final MutableList<ProfileFinding> findings = Lists.mutable.empty();
    private final MutableList<InputState> states = Lists.mutable.empty();
    private final MutableLongObjectMap<InputState> byQuantity = LongObjectMaps.mutable.empty();
    private Optional<Stratum> stratum = Optional.empty();
    private Optional<Bounds> share = Optional.empty();
    private Optional<Bounds> result = Optional.empty();
    private ImmutableList<Band> bands = Lists.immutable.empty();

    private Evaluation(Calculator calculator, Bindings bindings) {
        this.calculator = calculator;
        this.bindings = bindings;
        this.terms = calculator.terms();
        run();
    }

    /** Evaluates a calculator against bindings. */
    public static Evaluation of(Calculator calculator, Bindings bindings) {
        return new Evaluation(calculator, bindings);
    }

    public Calculator calculator() {
        return calculator;
    }

    public Bindings bindings() {
        return bindings;
    }

    /** The inputs as read, in the calculator's order. */
    public ImmutableList<InputState> inputs() {
        return states.toImmutable();
    }

    public Optional<InputState> input(long quantityNid) {
        return Optional.ofNullable(byQuantity.get(quantityNid));
    }

    /** The stratum the bound values selected. */
    public Optional<Stratum> stratum() {
        return stratum;
    }

    /** The result as the formula gives it, a share, before the output's scale. */
    public Optional<Bounds> share() {
        return share;
    }

    /** The result at the output's scale, the number the surface shows. */
    public Optional<Bounds> result() {
        return result;
    }

    /** The bands the result touches, in the calculator's order: one for a point, several for a wide interval. */
    public ImmutableList<Band> bands() {
        return bands;
    }

    public ImmutableList<ProfileFinding> findings() {
        return findings.toImmutable();
    }

    public boolean determinable() {
        return result.isPresent();
    }

    /**
     * Whether any input stood in for a statement, assumed or swept: the disposition a recording would
     * carry is interim. A value stated inline in the block is the author's own statement of fact,
     * and does not make it so.
     */
    public boolean interim() {
        return states.anySatisfy(InputState::standsIn);
    }

    // ── The run ──────────────────────────────────────────────────────────────

    private void run() {
        Optional<Root> root = calculator.calculator();
        if (root.isEmpty()) {
            findings.add(ProfileFinding.ofTree("the root is not a calculator"));
            return;
        }
        boolean determinable = true;
        for (Input input : root.get().inputs()) {
            InputState state = resolve(input);
            states.add(state);
            byQuantity.put(input.quantity(), state);
            if (state.bounds().isEmpty() && state.conceptValue().isEmpty()) {
                determinable = false;
            }
        }
        stratum = selectStratum(root.get());
        Optional<Formula> formula = root.get().formula();
        if (formula.isEmpty()) {
            findings.add(new ProfileFinding(root.get().vertexIndex(), "a calculator has no formula"));
            return;
        }
        if (!determinable || stratum.isEmpty()) {
            return;
        }
        Optional<Bounds> computed = sweep(root.get(), stratum.get(), formula.get());
        if (computed.isEmpty()) {
            return;
        }
        share = computed;
        double scale = root.get().output().map(Output::scale).orElse(1.0);
        Bounds scaled = computed.get().scale(scale);
        root.get().output().flatMap(Output::domain).ifPresent(domain -> {
            if (scaled.lower() < domain.lower() - EPSILON || scaled.upper() > domain.upper() + EPSILON) {
                findings.add(new ProfileFinding(root.get().output().get().vertexIndex(),
                        "the result lies outside the output's domain: " + scaled));
            }
        });
        result = Optional.of(scaled);
        bands = root.get().bands().select(band -> band.interval()
                .map(interval -> interval.upper() >= scaled.lower() - EPSILON
                        && interval.lower() <= scaled.upper() + EPSILON
                        && !(interval.upperOpen() && interval.upper() <= scaled.lower() + EPSILON && !scaled.isPoint())
                        && !(interval.upperOpen() && interval.upper() <= scaled.lower() && scaled.isPoint()))
                .orElse(false));
    }

    // ── Inputs ───────────────────────────────────────────────────────────────

    private InputState resolve(Input input) {
        long quantity = input.quantity();
        Optional<Binding.Supplied> assumption = bindings.assumption(quantity);
        Optional<Binding> stated = bindings.stated(quantity);
        if (assumption.isPresent()) {
            return supplied(input, assumption.get(), Status.ASSUMED, assumption.get());
        }
        if (stated.isPresent()) {
            switch (stated.get()) {
                case Binding.Observed observed -> {
                    return observed(input, observed);
                }
                case Binding.Supplied supplied -> {
                    return supplied(input, supplied, supplied.assumption() ? Status.ASSUMED : Status.SUPPLIED, supplied);
                }
                case Binding.Missing missing -> {
                    return missing(input, Optional.of(missing));
                }
                case Binding.Clarify clarify -> {
                    return missing(input, Optional.of(clarify));
                }
            }
        }
        return missing(input, Optional.empty());
    }

    private InputState observed(Input input, Binding.Observed observed) {
        if (input.conceptValued()) {
            return conceptState(input, observed.conceptValue(), Status.OBSERVED, observed);
        }
        return measureState(input, observed.bounds(), observed.semanticNid(), Status.OBSERVED, observed);
    }

    private InputState supplied(Input input, Binding.Supplied supplied, Status status, Binding binding) {
        if (input.conceptValued()) {
            return conceptState(input, supplied.conceptValue(), status, binding);
        }
        return measureState(input, supplied.bounds(), supplied.semanticNid(), status, binding);
    }

    private InputState conceptState(Input input, OptionalLong value, Status status, Binding binding) {
        if (value.isEmpty()) {
            findings.add(new ProfileFinding(input.vertexIndex(), "a concept-valued input is bound to a measure, not a concept"));
            return new InputState(input, Status.NOT_ADMITTED, Optional.empty(), OptionalLong.empty(), Optional.of(binding),
                    Optional.of("bound to a measure where a concept is expected"));
        }
        if (!input.admittedValues().contains(value.getAsLong())) {
            findings.add(new ProfileFinding(input.vertexIndex(), "an input is bound to a concept it does not admit"));
            return new InputState(input, Status.NOT_ADMITTED, Optional.empty(), OptionalLong.empty(), Optional.of(binding),
                    Optional.of("the bound concept is not one the input admits"));
        }
        return new InputState(input, status, Optional.empty(), value, Optional.of(binding), Optional.empty());
    }

        private InputState measureState(Input input, Bounds bounds, long semantic, Status status, Binding binding) {
        if (Double.isInfinite(bounds.lower()) || Double.isInfinite(bounds.upper())) {
            // the statement says the value is not determined: the input's when-missing policy decides,
            // and the statement stays the binding so the rail shows what the record says
            return missing(input, Optional.of(binding), "the statement's result is indeterminate");
        }
        Bounds converted = bounds;
        Optional<String> note = Optional.empty();
        long expected = input.expectedSemantic();
        if (semantic != 0 && expected != 0 && semantic != expected) {
            Optional<Conversion> conversion = input.conversions()
                    .detectOptional(c -> c.fromSemantic() == semantic && c.factor().isPresent());
            if (conversion.isEmpty()) {
                findings.add(new ProfileFinding(input.vertexIndex(),
                        "an input is bound to a value on a measure semantic it does not admit"));
                return new InputState(input, Status.NOT_ADMITTED, Optional.empty(), OptionalLong.empty(), Optional.of(binding),
                        Optional.of("the bound value's measure semantic is not one the input admits"));
            }
            converted = bounds.times(conversion.get().factor().get());
            note = Optional.of("converted by " + conversion.get().factor().get());
        }
        Status finalStatus = status;
        Optional<Bounds> domain = input.domain();
        if (domain.isPresent() && (converted.lower() < domain.get().lower() - EPSILON
                || converted.upper() > domain.get().upper() + EPSILON)) {
            findings.add(new ProfileFinding(input.vertexIndex(),
                    "an input lies outside the domain the equation is valid over: " + converted + " against " + domain.get()));
            finalStatus = Status.OUT_OF_DOMAIN;
            note = Optional.of("outside the equation's domain " + domain.get());
        }
        return new InputState(input, finalStatus, Optional.of(converted), OptionalLong.empty(), Optional.of(binding), note);
    }

        private InputState missing(Input input, Optional<Binding> binding) {
        return missing(input, binding, "no statement is bound");
    }

    private InputState missing(Input input, Optional<Binding> binding, String because) {
        OptionalLong policy = input.whenMissing();
        Optional<MissingValue> value = input.missingValue();
        if (policy.isPresent() && policy.getAsLong() == terms.plausible() && value.isPresent()) {
            if (input.conceptValued()) {
                findings.add(new ProfileFinding(input.vertexIndex(), "a concept-valued input cannot be swept; assume a value instead"));
            } else if (value.get().bounds().isPresent()) {
                                return new InputState(input, Status.PLAUSIBLE, value.get().bounds(), OptionalLong.empty(), binding,
                        Optional.of(because + "; swept over the plausible range"));
            }
        }
        if (policy.isPresent() && policy.getAsLong() == terms.assumed() && value.isPresent()) {
            if (input.conceptValued() && value.get().conceptValue().isPresent()) {
                                return new InputState(input, Status.ASSUMED, Optional.empty(), value.get().conceptValue(), binding,
                        Optional.of(because + "; assumed by the calculator's policy"));
            }
            if (!input.conceptValued() && value.get().bounds().isPresent()) {
                                return new InputState(input, Status.ASSUMED, value.get().bounds(), OptionalLong.empty(), binding,
                        Optional.of(because + "; assumed by the calculator's policy"));
            }
        }
                String why = binding.isPresent() && binding.get() instanceof Binding.Clarify clarify
                ? "left to clarify: " + clarify.question()
                : because;
        findings.add(new ProfileFinding(input.vertexIndex(), "an input is not determinable: " + why));
        return new InputState(input, Status.NOT_DETERMINED, Optional.empty(), OptionalLong.empty(), binding, Optional.of(why));
    }

    // ── The stratum ──────────────────────────────────────────────────────────

    private Optional<Stratum> selectStratum(Root root) {
        ImmutableList<Stratum> strata = root.strata();
        if (strata.isEmpty()) {
            findings.add(new ProfileFinding(root.vertexIndex(), "a calculator has no stratum"));
            return Optional.empty();
        }
        List<Stratum> matching = new ArrayList<>();
        boolean undecidable = false;
        for (Stratum candidate : strata) {
            boolean holds = true;
            for (Condition condition : candidate.conditions()) {
                InputState state = byQuantity.get(condition.input());
                if (state == null || state.conceptValue().isEmpty()) {
                    undecidable = true;
                    holds = false;
                    break;
                }
                if (condition.value().isEmpty() || condition.value().getAsLong() != state.conceptValue().getAsLong()) {
                    holds = false;
                    break;
                }
            }
            if (holds) {
                matching.add(candidate);
            }
        }
        if (matching.size() == 1) {
            return Optional.of(matching.get(0));
        }
        if (matching.isEmpty()) {
            findings.add(new ProfileFinding(root.vertexIndex(), undecidable
                    ? "no stratum can be selected while an input its conditions test is not determined"
                    : "no stratum's conditions hold for the bound values"));
            return Optional.empty();
        }
        findings.add(new ProfileFinding(root.vertexIndex(), matching.size() + " strata's conditions hold; a calculator selects one"));
        return Optional.empty();
    }

    // ── The sweep ────────────────────────────────────────────────────────────

    private Optional<Bounds> sweep(Root root, Stratum stratum, Formula formula) {
        List<InputState> numeric = states.select(s -> !s.input().conceptValued() && s.bounds().isPresent());
        List<InputState> intervals = new ArrayList<>(numeric.stream().filter(InputState::interval).toList());
        intervals.sort((a, b) -> Double.compare(b.bounds().get().width(), a.bounds().get().width()));
        if (intervals.size() > MAX_CORNER_INPUTS) {
            findings.add(new ProfileFinding(root.vertexIndex(), intervals.size()
                    + " inputs carry intervals; the sweep takes the corners of the " + MAX_CORNER_INPUTS
                    + " widest and the midpoints of the rest, so the bounds may understate"));
            intervals = intervals.subList(0, MAX_CORNER_INPUTS);
        }
        MutableLongDoubleMap point = LongDoubleMaps.mutable.empty();
        for (InputState state : numeric) {
            point.put(state.input().quantity(), state.bounds().get().midpoint());
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        boolean lowerOpen = false;
        boolean upperOpen = false;
        int corners = 1 << intervals.size();
        Pointwise pointwise = new Pointwise(stratum, formula);
        // every corner, plus the midpoint, which is the one evaluation a point reading needs
        for (int corner = -1; corner < corners; corner++) {
            boolean anyOpen = false;
            for (int i = 0; i < intervals.size(); i++) {
                Bounds b = intervals.get(i).bounds().get();
                boolean high = corner >= 0 && (corner & (1 << i)) != 0;
                double value = corner < 0 ? b.midpoint() : high ? b.upper() : b.lower();
                anyOpen |= corner >= 0 && (high ? b.upperOpen() : b.lowerOpen());
                point.put(intervals.get(i).input().quantity(), value);
            }
            Optional<Double> value = pointwise.at(point);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            double v = value.get();
            if (v < lo) {
                lo = v;
                lowerOpen = anyOpen;
            }
            if (v > hi) {
                hi = v;
                upperOpen = anyOpen;
            }
        }
        if (Double.isNaN(lo) || Double.isNaN(hi) || Double.isInfinite(lo) || Double.isInfinite(hi)) {
            findings.add(new ProfileFinding(formula.vertexIndex(), "the formula gave no number"));
            return Optional.empty();
        }
        return Optional.of(new Bounds(lo, hi, lowerOpen, upperOpen));
    }

    /** The equation at one point of the inputs: the terms, the linear predictor, the formula. */
    private final class Pointwise {
        private final Stratum stratum;
        private final Formula formula;
        private final MutableLongDoubleMap constants = LongDoubleMaps.mutable.empty();
        private final List<Coefficient> termCoefficients = new ArrayList<>();
        private boolean reported;

        Pointwise(Stratum stratum, Formula formula) {
            this.stratum = stratum;
            this.formula = formula;
            ImmutableLongList operands = formula.operands();
            for (Coefficient coefficient : stratum.coefficients()) {
                if (coefficient.value().isEmpty()) {
                    continue;
                }
                if (operands.contains(coefficient.quantity())) {
                    constants.put(coefficient.quantity(), coefficient.value().get().midpoint());
                } else {
                    termCoefficients.add(coefficient);
                }
            }
        }

        Optional<Double> at(MutableLongDoubleMap point) {
            OptionalLong kind = formula.kind();
            if (kind.isEmpty() || !terms.isFormulaKind(kind.getAsLong())) {
                report(formula.vertexIndex(), "a formula names no kind the evaluator can run");
                return Optional.empty();
            }
            double predictor = 0;
            for (Coefficient coefficient : termCoefficients) {
                Optional<Double> value = quantity(coefficient.quantity(), point, new ArrayList<>());
                if (value.isEmpty()) {
                    report(coefficient.vertexIndex(), "a coefficient's term is not determinable");
                    return Optional.empty();
                }
                predictor += coefficient.value().get().midpoint() * value.get();
            }
            ImmutableLongList operands = formula.operands();
            if (kind.getAsLong() == terms.coxSurvival()) {
                if (operands.size() != 2 || !constants.containsKey(operands.get(0)) || !constants.containsKey(operands.get(1))) {
                    report(formula.vertexIndex(), "a Cox survival formula reads the stratum's baseline survival and mean, which the stratum does not state");
                    return Optional.empty();
                }
                double baseline = constants.get(operands.get(0));
                double mean = constants.get(operands.get(1));
                return Optional.of(1 - Math.pow(baseline, Math.exp(predictor - mean)));
            }
            report(formula.vertexIndex(), "a formula names no kind the evaluator can run");
            return Optional.empty();
        }

        /** An input's value at the point, or a term's value derived from the point. */
        private Optional<Double> quantity(long nid, MutableLongDoubleMap point, List<Long> evaluating) {
            if (point.containsKey(nid)) {
                return Optional.of(point.get(nid));
            }
            Optional<Term> term = calculator.term(nid);
            if (term.isEmpty()) {
                InputState state = byQuantity.get(nid);
                if (state != null && state.conceptValue().isPresent()) {
                    report(state.input().vertexIndex(), "a concept-valued input is read as a number");
                }
                return Optional.empty();
            }
            if (evaluating.contains(nid)) {
                report(term.get().vertexIndex(), "a term reads itself");
                return Optional.empty();
            }
            evaluating.add(nid);
            try {
                return derive(term.get(), point, evaluating);
            } finally {
                evaluating.remove(nid);
            }
        }

        private Optional<Double> derive(Term term, MutableLongDoubleMap point, List<Long> evaluating) {
            OptionalLong from = term.derivedFrom();
            if (from.isEmpty()) {
                return Optional.empty();
            }
            long kind = from.getAsLong();
            ImmutableLongList operands = term.operands();
            if (operands.isEmpty() && !terms.isTermKind(kind)) {
                return quantity(kind, point, evaluating);
            }
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < operands.size(); i++) {
                Optional<Double> value = quantity(operands.get(i), point, evaluating);
                if (value.isEmpty()) {
                    return Optional.empty();
                }
                values.add(value.get());
            }
            if (kind == terms.naturalLogarithm()) {
                if (values.size() != 1 || values.get(0) <= 0) {
                    report(term.vertexIndex(), "a natural logarithm reads one positive operand");
                    return Optional.empty();
                }
                return Optional.of(Math.log(values.get(0)));
            }
            if (kind == terms.shared().complement()) {
                if (values.size() != 1) {
                    report(term.vertexIndex(), "a complement reads one operand");
                    return Optional.empty();
                }
                return Optional.of(1 - values.get(0));
            }
            if (kind == terms.shared().product()) {
                if (values.size() < 2) {
                    report(term.vertexIndex(), "a product reads two or more operands");
                    return Optional.empty();
                }
                double product = 1;
                for (double v : values) {
                    product *= v;
                }
                return Optional.of(product);
            }
            report(term.vertexIndex(), "a derivation names no kind the evaluator can run");
            return Optional.empty();
        }

        private void report(int vertexIndex, String message) {
            if (!reported) {
                findings.add(new ProfileFinding(vertexIndex, message));
                reported = true;
            }
        }
    }
}
