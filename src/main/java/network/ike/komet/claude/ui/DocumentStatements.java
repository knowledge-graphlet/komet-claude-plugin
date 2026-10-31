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
package network.ike.komet.claude.ui;

import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.anf.AnfValue;
import network.ike.komet.claude.anf.Circumstance;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.calculator.Binding;
import network.ike.komet.claude.calculator.Calculation;
import network.ike.komet.claude.calculator.CalculatorElement;
import network.ike.komet.claude.decision.Bounds;

/**
 * Reads a calculator's bound statement from the document before the store
 * ({@code dev-calculator-blocks}): a {@code calculate} block names a statement by the identity an
 * {@code anf} block in the same document gave itself, and this source hands the calculator that
 * statement's result, or the concept it is about, as the lift read it. A statement the document
 * does not hold falls through to the store.
 */
final class DocumentStatements implements Calculation.StatementSource {

    private final BlockContext context;
    private final Identities ids;
    private final Calculation.StatementSource store;

    DocumentStatements(BlockContext context, Identities ids, Calculation.StatementSource store) {
        this.context = context;
        this.ids = ids;
        this.store = store;
    }

    @Override
    public Optional<Binding.Observed> read(long statementNid, CalculatorElement.Input input) {
        Optional<AnfStatement> statement = ids.identifier(statementNid).flatMap(context::statement);
        if (statement.isEmpty()) {
            return store.read(statementNid, input);
        }
        AnfStatement s = statement.get();
        if (input.conceptValued()) {
            return s.topic() instanceof AnfValue.Grounded grounded
                    ? Optional.of(Binding.Observed.concept(grounded.nid(), statementNid))
                    : Optional.empty();
        }
        if (!(s.circumstance() instanceof Circumstance.Performance performance) || performance.result() == null) {
            return Optional.empty();
        }
        AnfStatement.Result result = performance.result();
        double lower = result.lowerBound() == null ? Double.NEGATIVE_INFINITY : result.lowerBound();
        double upper = result.upperBound() == null ? Double.POSITIVE_INFINITY : result.upperBound();
        long semantic = result.measureSemantic() instanceof AnfValue.Grounded grounded ? grounded.nid() : 0;
        Bounds bounds = new Bounds(lower, upper, !result.includeLowerBound(), !result.includeUpperBound());
        return Optional.of(new Binding.Observed(bounds, semantic, OptionalLong.empty(), statementNid, Optional.empty()));
    }
}
