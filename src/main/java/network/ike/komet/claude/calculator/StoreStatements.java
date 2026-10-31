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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.decision.Bounds;

/**
 * Reads a bound ANF statement's value from the store for a calculator's input
 * ({@code dev-calculator-blocks}): the latest version the view shows, its tree as an ANF
 * statement, and from it the performance result as the input's bounds and semantic, or for a
 * concept-valued input the topic the statement is about. A statement the view cannot show, or one
 * with no result, reads as nothing, and the evaluation says so.
 */
public final class StoreStatements implements Calculation.StatementSource {

    private final ViewCalculator view;

    public StoreStatements(ViewCalculator view) {
        this.view = view;
    }

    @Override
    public Optional<Binding.Observed> read(long statementNid, CalculatorElement.Input input) {
        if (view == null) {
            return Optional.empty();
        }
        Optional<AnfStatement.Root> root = statement(statementNid);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        if (input.conceptValued()) {
            return root.get().context().flatMap(AnfStatement.Context::topic)
                    .flatMap(AnfStatement.Attribute.Expression::singleConcept)
                    .map(concept -> Binding.Observed.concept(concept.nid(), statementNid));
        }
        Optional<AnfStatement.Attribute.Measure> result = root.get().circumstance()
                .filter(AnfStatement.Circumstance.Performance.class::isInstance)
                .map(AnfStatement.Circumstance.Performance.class::cast)
                .flatMap(AnfStatement.Circumstance.Performance::result);
        if (result.isEmpty()) {
            return Optional.empty();
        }
        AnfStatement.Attribute.Measure measure = result.get();
        double lower = measure.lowerBound().map(BigDecimal::doubleValue).orElse(Double.NEGATIVE_INFINITY);
        double upper = measure.upperBound().map(BigDecimal::doubleValue).orElse(Double.POSITIVE_INFINITY);
        if (Double.isInfinite(lower) && Double.isInfinite(upper)) {
            return Optional.empty();
        }
        Bounds bounds = new Bounds(lower, upper, !measure.includeLowerBound(), !measure.includeUpperBound());
        long semantic = measure.unit().map(ConceptFacade::nid).orElse(0L);
        return Optional.of(new Binding.Observed(bounds, semantic, OptionalLong.empty(), statementNid, Optional.empty()));
    }

    /** The statement the view shows for a semantic, read through the ANF adaptor. */
    Optional<AnfStatement.Root> statement(long statementNid) {
        try {
            var latest = view.latest(statementNid);
            if (latest.isAbsent()) {
                return Optional.empty();
            }
            Object head = latest.get();
            if (!(head instanceof SemanticEntityVersion version) || version.fieldValues().isEmpty()
                    || !(version.fieldValues().get(0) instanceof DiTreeEntity tree)) {
                return Optional.empty();
            }
            return Optional.of(AnfExpression.from(tree).root());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
