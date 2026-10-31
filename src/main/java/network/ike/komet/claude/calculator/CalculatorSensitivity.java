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
import network.ike.komet.claude.calculator.CalculatorElement.Input;
import network.ike.komet.claude.calculator.CalculatorElement.MissingValue;
import network.ike.komet.claude.decision.Bounds;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

/**
 * The one-way sweep of a calculator's inputs ({@code dev-calculator-blocks}): each numeric input is
 * swung across its swing with the others held at their midpoints, and the rows are ordered by how
 * far they move the result, the tornado beneath the calculator. The swing is the input's own
 * bounds when they are an interval, an observed resolution or a missing input's plausible range,
 * drawn solid; for a point reading it is the plausible range the method declares, else a tenth
 * either way within the domain, a presence swinging zero to one, drawn hollow, since it is the
 * viewer's question rather than the record's uncertainty.
 */
public final class CalculatorSensitivity {

    /** One input's row: its swing, the result at each end, and whether the swing is the input's own interval. */
    public record Row(Input input, Bounds swing, Bounds low, Bounds high, boolean solid) {
        /** How far the result's midpoint moves across the swing. */
        public double width() {
            return Math.abs(high.midpoint() - low.midpoint());
        }

        /** The result's bounds across the whole swing. */
        public Bounds span() {
            double lo = Math.min(low.lower(), high.lower());
            double hi = Math.max(low.upper(), high.upper());
            return Bounds.of(lo, hi);
        }
    }

    private final Evaluation reference;
    private final ImmutableList<Row> rows;

    private CalculatorSensitivity(Evaluation reference, ImmutableList<Row> rows) {
        this.reference = reference;
        this.rows = rows;
    }

    /** The sweep of an evaluation's inputs; empty rows when the evaluation is not determinable. */
    public static CalculatorSensitivity of(Evaluation reference) {
        MutableList<Row> rows = Lists.mutable.empty();
        if (!reference.determinable()) {
            return new CalculatorSensitivity(reference, rows.toImmutable());
        }
        Bindings midpoints = atMidpoints(reference);
        for (Evaluation.InputState state : reference.inputs()) {
            if (state.input().conceptValued() || state.bounds().isEmpty()) {
                continue;
            }
            Bounds own = state.bounds().get();
            boolean solid = !own.isPoint();
            Bounds swing = solid ? own : assumedSwing(state.input(), own);
            if (swing.isPoint()) {
                continue;
            }
            long quantity = state.input().quantity();
            long semantic = state.input().expectedSemantic();
            Optional<Bounds> low = Evaluation.of(reference.calculator(),
                    midpoints.withAssumption(quantity, Binding.Supplied.assumed(Bounds.point(swing.lower()), semantic))).result();
            Optional<Bounds> high = Evaluation.of(reference.calculator(),
                    midpoints.withAssumption(quantity, Binding.Supplied.assumed(Bounds.point(swing.upper()), semantic))).result();
            if (low.isEmpty() || high.isEmpty()) {
                continue;
            }
            rows.add(new Row(state.input(), swing, low.get(), high.get(), solid));
        }
        rows.sortThis((a, b) -> Double.compare(b.width(), a.width()));
        return new CalculatorSensitivity(reference, rows.toImmutable());
    }

    public Evaluation reference() {
        return reference;
    }

    /** The rows, widest swing first. */
    public ImmutableList<Row> rows() {
        return rows;
    }

    /** The row for an input, when it has one. */
    public Optional<Row> row(long inputQuantity) {
        return rows.detectOptional(row -> row.input().quantity() == inputQuantity);
    }

    /** The result at the midpoint of every input, the hairline the bars hang from. */
    public Optional<Bounds> midpoint() {
        return Evaluation.of(reference.calculator(), atMidpoints(reference)).result();
    }

    /** Bindings that pin every numeric input to the midpoint of the bounds the reference read. */
    private static Bindings atMidpoints(Evaluation reference) {
        Bindings bindings = reference.bindings();
        for (Evaluation.InputState state : reference.inputs()) {
            if (state.input().conceptValued() || state.bounds().isEmpty()) {
                continue;
            }
            bindings = bindings.withAssumption(state.input().quantity(),
                    Binding.Supplied.assumed(Bounds.point(state.bounds().get().midpoint()), state.input().expectedSemantic()));
        }
        return bindings;
    }

    /**
     * The swing for a point reading: the plausible range the method declares; else, for a presence
     * or any input whose domain is zero to one, that domain; else a tenth either way, clipped to
     * the domain when there is one. Never the whole domain of a continuous input, which would
     * ask what a forty-year-old's risk is of a fifty-five-year-old.
     */
    static Bounds assumedSwing(Input input, Bounds point) {
        Optional<Bounds> plausible = input.missingValue().flatMap(MissingValue::bounds);
        if (plausible.isPresent() && !plausible.get().isPoint()) {
            return plausible.get();
        }
        Optional<Bounds> domain = input.domain();
        double value = point.midpoint();
        if (domain.isPresent() && domain.get().lower() == 0 && domain.get().upper() == 1) {
            return domain.get();
        }
        if (value == 0) {
            return Bounds.of(0, 1);
        }
        double low = value - Math.abs(value) / 10;
        double high = value + Math.abs(value) / 10;
        if (domain.isPresent() && Double.isFinite(domain.get().lower()) && Double.isFinite(domain.get().upper())) {
            low = Math.max(low, domain.get().lower());
            high = Math.min(high, domain.get().upper());
        }
        return low < high ? Bounds.of(low, high) : point;
    }
}
