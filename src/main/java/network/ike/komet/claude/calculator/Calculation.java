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

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.decision.Bounds;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * One use of a calculator in a document, as the {@code [calculate]} block states it
 * ({@code dev-calculator-blocks}): the calculator by identity, and per input what binds there, an
 * ANF statement by identity, a value stated inline, nothing, a clarification, or an assumption.
 * The record side of the method-and-record split: the method is referenced, never copied, and the
 * statements are referenced, never restated. {@link #bindings(StatementSource)} turns the
 * references into {@link Bindings} by reading the statements through a source the surface supplies.
 */
public record Calculation(long calculatorNid, ImmutableList<Line> lines) {

    /** What one line binds, or assumes, for one input. */
    public record Line(long inputQuantity, Spec spec) {
    }

    /** The binding as written. */
    public sealed interface Spec permits Statement, Inline, Missing, Clarify, Assume {
    }

    /** A statement by identity, with the policy that found it when it was found by one. */
    public record Statement(long statementNid, Optional<String> policy) implements Spec {
    }

    /** A value stated in the block with no statement behind it. */
    public record Inline(Binding.Supplied value) implements Spec {
    }

    /** Nothing bound: the method's when-missing policy decides. */
    public record Missing() implements Spec {
    }

    /** A question left open in place of a binding. */
    public record Clarify(String question) implements Spec {
    }

    /** An assumption beside whatever is bound: a what-if the reader asked for, visibly hypothetical. */
    public record Assume(Binding.Supplied value) implements Spec {
    }

    /** Reads a bound statement's value for an input: the surface's store, or a fixture. */
    @FunctionalInterface
    public interface StatementSource {
        /** The statement's value as a binding, empty when the statement cannot be read. */
        Optional<Binding.Observed> read(long statementNid, CalculatorElement.Input input);

        /** A source that reads nothing, for a build with no store. */
        static StatementSource none() {
            return (statementNid, input) -> Optional.empty();
        }
    }

    /** The lines for one input, in order: at most one binding and one assumption are meaningful. */
    public List<Line> linesFor(long inputQuantity) {
        return lines.select(line -> line.inputQuantity() == inputQuantity).castToList();
    }

    /** The bindings these lines state, with statements read through the source. */
    public Bindings bindings(Calculator calculator, StatementSource source) {
        Bindings.Builder b = Bindings.builder();
        for (Line line : lines) {
            Optional<CalculatorElement.Input> input = calculator.input(line.inputQuantity());
            switch (line.spec()) {
                case Statement statement -> {
                    Optional<Binding.Observed> read = input.flatMap(i -> source.read(statement.statementNid(), i));
                    b.bind(line.inputQuantity(), read.isPresent() ? read.get()
                            : new Binding.Clarify("the statement " + statement.statementNid() + " could not be read"));
                }
                case Inline inline -> b.bind(line.inputQuantity(), inline.value());
                case Missing missing -> b.bind(line.inputQuantity(), new Binding.Missing());
                case Clarify clarify -> b.bind(line.inputQuantity(), new Binding.Clarify(clarify.question()));
                case Assume assume -> b.assume(line.inputQuantity(), assume.value());
            }
        }
        return b.build();
    }

    /** These lines with an assumption for an input replaced or added, the what-if's write-back. */
    public Calculation withAssumption(long inputQuantity, Bounds bounds, long semanticNid) {
        ImmutableList<Line> kept = lines.reject(line -> line.inputQuantity() == inputQuantity && line.spec() instanceof Assume);
        return new Calculation(calculatorNid,
                kept.newWith(new Line(inputQuantity, new Assume(Binding.Supplied.assumed(bounds, semanticNid)))));
    }

    /** These lines with an input's assumption removed. */
    public Calculation withoutAssumption(long inputQuantity) {
        return new Calculation(calculatorNid,
                lines.reject(line -> line.inputQuantity() == inputQuantity && line.spec() instanceof Assume));
    }

    /** The assumption for an input, when one is written. */
    public Optional<Binding.Supplied> assumption(long inputQuantity) {
        return lines.detectOptional(line -> line.inputQuantity() == inputQuantity && line.spec() instanceof Assume)
                .map(line -> ((Assume) line.spec()).value());
    }

    /** Whether a line binds the input by identity to a statement. */
    public OptionalLong statementFor(long inputQuantity) {
        return lines.detectOptional(line -> line.inputQuantity() == inputQuantity && line.spec() instanceof Statement)
                .map(line -> OptionalLong.of(((Statement) line.spec()).statementNid()))
                .orElse(OptionalLong.empty());
    }
}
