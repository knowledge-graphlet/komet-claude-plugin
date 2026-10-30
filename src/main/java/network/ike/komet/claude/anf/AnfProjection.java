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
package network.ike.komet.claude.anf;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfExpression;
import network.ike.komet.claude.anf.model.AnfExpressionBuilder;
import network.ike.komet.claude.anf.model.AnfProvisional;
import network.ike.komet.claude.anf.model.AnfRefusal;
import network.ike.komet.claude.anf.model.AnfSemantic;
import network.ike.komet.claude.anf.model.AnfStatement.Attribute;
import network.ike.komet.claude.anf.model.AnfStatement.Context;
import network.ike.komet.claude.anf.model.AnfStatement.Root;
import network.ike.komet.claude.tools.GraphTools;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@link AnfStatement} record as a projection of the statement tree, in both directions
 * (IKE-Network/ike-issues#1312): {@link #toTree} writes a record through the
 * {@link AnfExpressionBuilder}, so every rule the tree holds is checked, and {@link #fromTree}
 * reads a tree back into the record the narrative lift, its card and the {@code [anf]} codec
 * already know. The lift's {@code emit_anf} sink passes each statement through both, so what it
 * records is what the tree holds.
 *
 * <p><b>What the record cannot say is refused, never dropped.</b> The record carries the envelope
 * axes the lift grounds today: topic, subject of information, and the circumstance; the tree also
 * carries the subject of record, the authors, the statement time, the associated statements and
 * the attributes the structural definition names beyond the lift's (device, approach, specimen,
 * participants, location, a request's disposition, a measure's resolution). A tree holding any of
 * those is refused by {@link #fromTree} with the attribute named, so a lossy read never masquerades
 * as a round trip. A composed topic, one that is not a single concept reference, is refused the
 * same way: the record's topic is one concept, and the editor's promotion to a complex concept is
 * the path back to one.
 *
 * <p>A grounded attribute value becomes a concept reference by its public id; reading back needs
 * a {@link Grounding} that answers a concept's identifier and label from the live store, which
 * {@link #grounding(ViewCalculator)} supplies from a view. Candidates and clarifications travel as
 * the tree's provisional properties, verbatim.
 */
public final class AnfProjection {

    private AnfProjection() {
    }

    /**
     * Answers the grounded value of a concept the tree references: its identifier and label from
     * the live store, the record's view-derived parts.
     */
    @FunctionalInterface
    public interface Grounding {
        /**
         * @param concept a concept a tree vertex references
         * @return its grounded value, or null when the store does not know it
         */
        AnfValue.Grounded ground(ConceptFacade concept);
    }

    /**
     * The grounding a view supplies.
     *
     * @param view the view calculator
     * @return a grounding through {@link GraphTools#groundedOf}
     */
    public static Grounding grounding(ViewCalculator view) {
        Objects.requireNonNull(view, "view");
        return concept -> GraphTools.groundedOf(view, concept.nid());
    }

    /**
     * Passes a record through the tree and back: what the lift records is what the tree holds.
     *
     * @param statement the record
     * @param grounding the grounding for reading back
     * @return the record as the tree holds it
     * @throws AnfRefusal if the builder refuses the statement
     */
    public static AnfStatement through(AnfStatement statement, Grounding grounding) {
        return fromTree(toTree(statement), grounding);
    }

    // ── Record to tree ────────────────────────────────────────────────────────

    /**
     * Writes a record as a statement tree through the builder.
     *
     * @param statement the record
     * @return the tree, for the ANF statement pattern's field
     * @throws AnfRefusal if the builder refuses the statement
     */
    public static DiTreeEntity toTree(AnfStatement statement) {
        Objects.requireNonNull(statement, "statement");
        ConceptFacade kind = kindOf(statement.statementType());
        AnfExpressionBuilder builder = new AnfExpressionBuilder(kind);
        Context context = builder.context();
        builder.conceptReference(context, AnfSemantic.STATEMENT_TYPE, kind);
        expressionAttribute(builder, context, AnfSemantic.TOPIC, statement.topic());
        expressionAttribute(builder, context, AnfSemantic.SUBJECT_OF_INFORMATION, statement.subjectOfInformation());

        switch (statement.circumstance()) {
            case Circumstance.Performance p -> {
                var performance = (network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance)
                        builder.circumstance();
                measureAttribute(builder, performance, AnfSemantic.TIMING, p.timing());
                for (AnfValue purpose : p.purpose()) {
                    expressionAttribute(builder, performance, AnfSemantic.PURPOSE, purpose);
                }
                conceptAttribute(builder, performance, AnfSemantic.DISPOSITION, p.status());
                measureAttribute(builder, performance, AnfSemantic.RESULT, p.result());
                conceptAttribute(builder, performance, AnfSemantic.HEALTH_RISK, p.healthRisk());
                measureAttribute(builder, performance, AnfSemantic.NORMAL_RANGE, p.normalRange());
                conceptAttribute(builder, performance, AnfSemantic.BODY_SITE, p.bodySite());
                conceptAttribute(builder, performance, AnfSemantic.METHOD, p.method());
                conceptAttribute(builder, performance, AnfSemantic.LATERALITY, p.laterality());
            }
            case Circumstance.Request r -> {
                var request = (network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Request)
                        builder.circumstance();
                measureAttribute(builder, request, AnfSemantic.TIMING, r.timing());
                for (AnfValue purpose : r.purpose()) {
                    expressionAttribute(builder, request, AnfSemantic.PURPOSE, purpose);
                }
                conceptAttribute(builder, request, AnfSemantic.PRIORITY, r.priority());
                measureAttribute(builder, request, AnfSemantic.REQUESTED_RESULT, r.requestedResult());
                if (r.repetition() != null) {
                    Attribute.Repetition repetition = builder.repetition(request);
                    measureAttribute(builder, repetition, AnfSemantic.PERIOD_START, r.repetition().periodStart());
                    measureAttribute(builder, repetition, AnfSemantic.PERIOD_DURATION, r.repetition().periodDuration());
                    measureAttribute(builder, repetition, AnfSemantic.EVENT_SEPARATION, r.repetition().eventSeparation());
                    measureAttribute(builder, repetition, AnfSemantic.EVENT_DURATION, r.repetition().eventDuration());
                    measureAttribute(builder, repetition, AnfSemantic.EVENT_FREQUENCY, r.repetition().eventFrequency());
                }
                for (AnfValue trigger : r.conditionalTrigger()) {
                    conceptAttribute(builder, request, AnfSemantic.CONDITIONAL_TRIGGER, trigger);
                }
                conceptAttribute(builder, request, AnfSemantic.METHOD, r.method());
            }
            case Circumstance.Narrative n -> {
                var narrative = (network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Narrative)
                        builder.circumstance();
                measureAttribute(builder, narrative, AnfSemantic.TIMING, n.timing());
                for (AnfValue purpose : n.purpose()) {
                    expressionAttribute(builder, narrative, AnfSemantic.PURPOSE, purpose);
                }
                builder.narrativeText(narrative, n.text());
            }
        }
        return builder.asDiTree();
    }

    private static ConceptFacade kindOf(AnfStatement.Type type) {
        return switch (type) {
            case PERFORMANCE -> IkeConcept.PERFORMANCE_CIRCUMSTANCE;
            case REQUEST -> IkeConcept.REQUEST_CIRCUMSTANCE;
            case NARRATIVE -> IkeConcept.NARRATIVE_CIRCUMSTANCE;
        };
    }

    /** A grounded attribute value as the concept the tree references, by its public id. */
    static ConceptFacade concept(AnfValue.Grounded grounded) {
        return EntityProxy.Concept.make(grounded.label(), PublicIds.of(grounded.publicId().split(",")));
    }

    private static AnfProvisional provisional(AnfValue value) {
        return switch (value) {
            case AnfValue.Grounded grounded -> throw new IllegalArgumentException("A grounded value is not provisional");
            case AnfValue.Candidate c -> new AnfProvisional.Candidate(c.provisionalLabel(), c.text(),
                    c.disposition().name(), c.nearestMatches());
            case AnfValue.Clarify c -> new AnfProvisional.Clarification(c.field(), c.question());
        };
    }

    private static void conceptAttribute(AnfExpressionBuilder builder, network.ike.komet.claude.anf.model.AnfStatement parent,
                                    AnfSemantic attribute, AnfValue value) {
        if (value == null) {
            return;
        }
        if (value instanceof AnfValue.Grounded grounded) {
            builder.conceptReference(parent, attribute, concept(grounded));
        } else {
            builder.provisionalConceptReference(parent, attribute, provisional(value));
        }
    }

    private static void expressionAttribute(AnfExpressionBuilder builder, network.ike.komet.claude.anf.model.AnfStatement parent,
                                       AnfSemantic attribute, AnfValue value) {
        if (value == null) {
            return;
        }
        if (value instanceof AnfValue.Grounded grounded) {
            builder.expression(parent, attribute, concept(grounded));
        } else {
            builder.provisionalExpression(parent, attribute, provisional(value));
        }
    }

    private static void measureAttribute(AnfExpressionBuilder builder, network.ike.komet.claude.anf.model.AnfStatement parent,
                                    AnfSemantic attribute, AnfStatement.Result result) {
        if (result == null) {
            return;
        }
        BigDecimal lower = result.lowerBound() == null ? null : BigDecimal.valueOf(result.lowerBound());
        BigDecimal upper = result.upperBound() == null ? null : BigDecimal.valueOf(result.upperBound());
        if (result.measureSemantic() instanceof AnfValue.Grounded grounded) {
            builder.measure(parent, attribute, lower, upper, result.includeLowerBound(), result.includeUpperBound(),
                    null, concept(grounded));
        } else {
            builder.provisionalMeasure(parent, attribute, lower, upper, result.includeLowerBound(),
                    result.includeUpperBound(), null, provisional(result.measureSemantic()));
        }
    }

    // ── Tree to record ────────────────────────────────────────────────────────

    /**
     * Reads a statement tree back into the record.
     *
     * @param tree      the statement tree
     * @param grounding answers a referenced concept's identifier and label
     * @return the record
     * @throws AnfRefusal if the tree holds what the record cannot say, or references a concept
     *                    the grounding does not know
     */
    public static AnfStatement fromTree(DiTreeEntity tree, Grounding grounding) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(grounding, "grounding");
        Root root = AnfExpression.from(tree).root();
        AnfStatement.Type type = switch (root.kindSemantic()) {
            case PERFORMANCE -> AnfStatement.Type.PERFORMANCE;
            case REQUEST -> AnfStatement.Type.REQUEST;
            case NARRATIVE -> AnfStatement.Type.NARRATIVE;
            default -> throw new AnfRefusal("Not a statement kind: " + root.kindSemantic().describe());
        };
        List<String> beyond = new ArrayList<>();
        if (!root.associatedStatements().isEmpty()) {
            beyond.add("associated statements");
        }

        AnfValue topic = null;
        AnfValue subjectOfInformation = null;
        if (root.context().isPresent()) {
            Context context = root.context().get();
            context.subjectOfRecord().ifPresent(ignored -> beyond.add("the subject of record"));
            if (!context.authors().isEmpty()) {
                beyond.add("the authors");
            }
            context.statementTime().ifPresent(ignored -> beyond.add("the statement time"));
            topic = context.topic().map(expression -> valueOf(expression, grounding)).orElse(null);
            subjectOfInformation = context.subjectOfInformation()
                    .map(expression -> valueOf(expression, grounding)).orElse(null);
        }

        var circumstanceVertex = root.circumstance()
                .orElseThrow(() -> new AnfRefusal("The statement has no circumstance yet; the record needs one"));
        Circumstance circumstance = switch (circumstanceVertex) {
            case network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Performance p -> {
                p.device().ifPresent(ignored -> beyond.add("the device"));
                p.approach().ifPresent(ignored -> beyond.add("the approach"));
                p.specimenType().ifPresent(ignored -> beyond.add("the specimen type"));
                p.specimenSourceSite().ifPresent(ignored -> beyond.add("the specimen source site"));
                if (!p.participants().isEmpty()) {
                    beyond.add("the participants");
                }
                yield new Circumstance.Performance(
                        measure(p.timing(), grounding, beyond),
                        valuesOf(p.purposes(), grounding),
                        chip(p.disposition(), grounding),
                        measure(p.result(), grounding, beyond),
                        chip(p.healthRisk(), grounding),
                        measure(p.normalRange(), grounding, beyond),
                        chip(p.bodySite(), grounding),
                        chip(p.method(), grounding),
                        chip(p.laterality(), grounding));
            }
            case network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Request r -> {
                r.disposition().ifPresent(ignored -> beyond.add("a request's disposition"));
                r.device().ifPresent(ignored -> beyond.add("the device"));
                r.location().ifPresent(ignored -> beyond.add("the location"));
                r.specimenType().ifPresent(ignored -> beyond.add("the specimen type"));
                r.specimenSourceSite().ifPresent(ignored -> beyond.add("the specimen source site"));
                if (!r.participants().isEmpty()) {
                    beyond.add("the participants");
                }
                Circumstance.Repetition repetition = r.repetition().map(rep -> new Circumstance.Repetition(
                        measure(rep.periodStart(), grounding, beyond),
                        measure(rep.periodDuration(), grounding, beyond),
                        measure(rep.eventSeparation(), grounding, beyond),
                        measure(rep.eventDuration(), grounding, beyond),
                        measure(rep.eventFrequency(), grounding, beyond))).orElse(null);
                List<AnfValue> triggers = new ArrayList<>();
                for (Attribute.ConceptReference trigger : r.conditionalTriggers()) {
                    triggers.add(valueOf(trigger, grounding));
                }
                yield new Circumstance.Request(
                        measure(r.timing(), grounding, beyond),
                        valuesOf(r.purposes(), grounding),
                        chip(r.priority(), grounding),
                        measure(r.requestedResult(), grounding, beyond),
                        repetition,
                        triggers,
                        chip(r.method(), grounding));
            }
            case network.ike.komet.claude.anf.model.AnfStatement.Circumstance.Narrative n -> new Circumstance.Narrative(
                    measure(n.timing(), grounding, beyond),
                    valuesOf(n.purposes(), grounding),
                    n.text().map(Attribute.NarrativeText::text).orElse(""));
        };
        if (!beyond.isEmpty()) {
            throw new AnfRefusal("The statement record does not carry " + String.join(", ", beyond)
                    + " yet; the tree holds them, and the record is the lift's and the [anf] codec's form");
        }
        return new AnfStatement(type, topic, subjectOfInformation, circumstance);
    }

    private static AnfValue chip(Optional<Attribute.ConceptReference> chip, Grounding grounding) {
        return chip.map(reference -> valueOf(reference, grounding)).orElse(null);
    }

    private static AnfValue valueOf(Attribute.ConceptReference chip, Grounding grounding) {
        if (chip.concept().isPresent()) {
            return ground(chip.concept().get(), chip.semantic(), grounding);
        }
        return chip.provisional().map(AnfProjection::valueOf)
                .orElseThrow(() -> new AnfRefusal("The " + chip.semantic().describe() + " references nothing yet"));
    }

    private static AnfValue valueOf(Attribute.Expression expression, Grounding grounding) {
        Optional<ConceptFacade> single = expression.singleConcept();
        if (single.isPresent()) {
            return ground(single.get(), expression.semantic(), grounding);
        }
        if (expression.expressionTree().isPresent()) {
            throw new AnfRefusal("The " + expression.semantic().describe()
                    + " is a composed expression, which the record cannot carry: promote it to a complex concept first");
        }
        return expression.provisional().map(AnfProjection::valueOf)
                .orElseThrow(() -> new AnfRefusal("The " + expression.semantic().describe() + " holds nothing yet"));
    }

    private static List<AnfValue> valuesOf(Iterable<Attribute.Expression> expressions, Grounding grounding) {
        List<AnfValue> values = new ArrayList<>();
        for (Attribute.Expression expression : expressions) {
            values.add(valueOf(expression, grounding));
        }
        return values;
    }

    private static AnfStatement.Result measure(Optional<Attribute.Measure> measure, Grounding grounding,
                                               List<String> beyond) {
        if (measure.isEmpty()) {
            return null;
        }
        Attribute.Measure m = measure.get();
        m.resolution().ifPresent(ignored -> beyond.add("a measure's resolution"));
        AnfValue unit;
        if (m.unit().isPresent()) {
            unit = ground(m.unit().get(), m.semantic(), grounding);
        } else {
            unit = m.provisional().map(AnfProjection::valueOf)
                    .orElseThrow(() -> new AnfRefusal("The " + m.semantic().describe() + " has no measure semantic yet"));
        }
        return new AnfStatement.Result(
                m.lowerBound().map(BigDecimal::doubleValue).orElse(null),
                m.upperBound().map(BigDecimal::doubleValue).orElse(null),
                m.includeLowerBound(), m.includeUpperBound(), unit);
    }

    private static AnfValue ground(ConceptFacade concept, AnfSemantic attribute, Grounding grounding) {
        AnfValue.Grounded grounded = grounding.ground(concept);
        if (grounded == null) {
            throw new AnfRefusal("The " + attribute.describe() + " references a concept the knowledge base does not know");
        }
        return grounded;
    }

    private static AnfValue valueOf(AnfProvisional provisional) {
        return switch (provisional) {
            case AnfProvisional.Candidate c -> new AnfValue.Candidate(c.text(), c.label(), c.nearestMatches(),
                    disposition(c.disposition()));
            case AnfProvisional.Clarification c -> new AnfValue.Clarify(c.field(), c.question());
        };
    }

    private static AnfValue.Disposition disposition(String name) {
        try {
            return AnfValue.Disposition.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return AnfValue.Disposition.PENDING;
        }
    }
}
