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
package network.ike.komet.claude.anf.model;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import org.eclipse.collections.api.list.ImmutableList;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * One vertex of an ANF statement tree, typed by its meaning: the sealed hierarchy of the ANF
 * adaptor layer, the analogue of {@code LogicalAxiom} for EL++ and of the complex clause
 * plugin's {@code Clause} (IKE-Network/ike-issues#1312). The only implementor is
 * {@link AnfAdaptor}, one adaptor per vertex, whose concrete classes expose the accessors of each
 * shape. No other adaptor layer extends this one: a nested EL++ expression is held by value as a tree
 * property and exposed as a {@link LogicalExpression} of its own adaptor layer.
 *
 * <p>Every accessor that may answer nothing answers an {@link Optional}, because a statement is
 * built incrementally in the editor and a tree under construction is still a well-formed tree.
 * What is required for a complete statement is the {@link AnfExpressionBuilder}'s
 * {@code completeness} check, not a hole in the hierarchy.
 */
public sealed interface AnfStatement permits AnfStatement.Root, AnfStatement.Context, AnfStatement.Circumstance,
        AnfStatement.Attribute, AnfAdaptor {

    /** @return this vertex's index within the source graph. */
    int vertexIndex();

    /** @return this vertex's UUID, its identity across versions. */
    UUID vertexUUID();

    /** @return this vertex's meaning. */
    AnfSemantic semantic();

    /** @return the children of this vertex, in edge order. */
    ImmutableList<AnfStatement> children();

    /** The statement: the root, carrying its kind, with the context and the one circumstance beneath. */
    sealed interface Root extends AnfStatement permits AnfAdaptor.RootAdaptor {

        /** @return the statement kind: Performance, Request or Narrative circumstance. */
        ConceptFacade kind();

        /** @return the circumstance kind the statement kind names. */
        AnfSemantic kindSemantic();

        /** @return the context, when one has been added. */
        Optional<Context> context();

        /** @return the one circumstance, when one has been added. */
        Optional<Circumstance> circumstance();

        /** @return the associated statements, in edge order. */
        ImmutableList<Attribute.AssociatedStatement> associatedStatements();
    }

    /** The envelope: who and what the statement is about, its type, its authors, its time. */
    sealed interface Context extends AnfStatement permits AnfAdaptor.ContextAdaptor {

        Optional<Attribute.Participant> subjectOfRecord();

        Optional<Attribute.Expression> subjectOfInformation();

        Optional<Attribute.Expression> topic();

        Optional<Attribute.ConceptReference> statementType();

        ImmutableList<Attribute.Author> authors();

        Optional<Attribute.Measure> statementTime();
    }

    /** The clinical detail, exactly one of three kinds. */
    sealed interface Circumstance extends AnfStatement {

        Optional<Attribute.Measure> timing();

        ImmutableList<Attribute.Expression> purposes();

        /** What was determined or done. */
        sealed interface Performance extends Circumstance permits AnfAdaptor.PerformanceAdaptor {
            Optional<Attribute.Measure> result();

            Optional<Attribute.ConceptReference> disposition();

            Optional<Attribute.ConceptReference> healthRisk();

            Optional<Attribute.Measure> normalRange();

            Optional<Attribute.ConceptReference> method();

            Optional<Attribute.ConceptReference> device();

            Optional<Attribute.ConceptReference> bodySite();

            Optional<Attribute.ConceptReference> laterality();

            Optional<Attribute.ConceptReference> approach();

            Optional<Attribute.ConceptReference> specimenType();

            Optional<Attribute.ConceptReference> specimenSourceSite();

            ImmutableList<Attribute.Participant> participants();
        }

        /** What is asked for. */
        sealed interface Request extends Circumstance permits AnfAdaptor.RequestAdaptor {
            Optional<Attribute.ConceptReference> priority();

            Optional<Attribute.Measure> requestedResult();

            Optional<Attribute.ConceptReference> disposition();

            Optional<Attribute.Repetition> repetition();

            ImmutableList<Attribute.ConceptReference> conditionalTriggers();

            ImmutableList<Attribute.Participant> participants();

            Optional<Attribute.ConceptReference> device();

            Optional<Attribute.ConceptReference> method();

            Optional<Attribute.ConceptReference> location();

            Optional<Attribute.ConceptReference> specimenType();

            Optional<Attribute.ConceptReference> specimenSourceSite();
        }

        /** Content that resists structure. */
        sealed interface Narrative extends Circumstance permits AnfAdaptor.NarrativeAdaptor {
            Optional<Attribute.NarrativeText> text();
        }
    }

    /** An attribute of the context or of a circumstance: a leaf, or a clause of measures. */
    sealed interface Attribute extends AnfStatement {

        /**
         * @return what the vertex carries in place of a concept when it has none yet: a candidate
         *         or a clarification
         */
        Optional<AnfProvisional> provisional();

        /** A concept reference chip. */
        sealed interface ConceptReference extends Attribute permits AnfAdaptor.ConceptReferenceAdaptor {
            Optional<ConceptFacade> concept();
        }

        /**
         * A measure: bounds, whether each is included, the resolution and the unit, under the
         * kernel's interval keys. An absent bound is unbounded on that side.
         */
        sealed interface Measure extends Attribute permits AnfAdaptor.MeasureAdaptor {
            Optional<BigDecimal> lowerBound();

            Optional<BigDecimal> upperBound();

            boolean includeLowerBound();

            boolean includeUpperBound();

            Optional<BigDecimal> resolution();

            /** @return the measure semantic: the unit or scale that makes the number meaningful. */
            Optional<ConceptFacade> unit();
        }

        /** A nested logical expression held by value. */
        sealed interface Expression extends Attribute permits AnfAdaptor.ExpressionAdaptor {
            /** @return the nested tree as stored. */
            Optional<DiTreeEntity> expressionTree();

            /** @return the nested tree adapted by the logical expression adaptor layer. */
            Optional<LogicalExpression> expression();

            /**
             * @return the one concept the expression reduces to, when it is the degenerate case:
             *         a definition root over one necessary set over one conjunction over one
             *         concept reference
             */
            Optional<ConceptFacade> singleConcept();
        }

        /** A reference to a participant with its role as a concept. */
        sealed interface Participant extends Attribute permits AnfAdaptor.ParticipantAdaptor {
            Optional<ConceptFacade> participant();

            Optional<ConceptFacade> role();
        }

        /** A reference to a participant with its role as a nested expression. */
        sealed interface Author extends Attribute permits AnfAdaptor.AuthorAdaptor {
            Optional<ConceptFacade> participant();

            Optional<DiTreeEntity> roleTree();

            Optional<LogicalExpression> role();
        }

        /** A text literal. */
        sealed interface NarrativeText extends Attribute permits AnfAdaptor.NarrativeTextAdaptor {
            String text();
        }

        /** A request's schedule: a clause of five measures. */
        sealed interface Repetition extends Attribute permits AnfAdaptor.RepetitionAdaptor {
            Optional<Measure> periodStart();

            Optional<Measure> periodDuration();

            Optional<Measure> eventSeparation();

            Optional<Measure> eventDuration();

            Optional<Measure> eventFrequency();
        }

        /** A reference to another statement with the association type. */
        sealed interface AssociatedStatement extends Attribute permits AnfAdaptor.AssociatedStatementAdaptor {
            Optional<EntityFacade> statement();

            Optional<ConceptFacade> associationType();
        }
    }
}
