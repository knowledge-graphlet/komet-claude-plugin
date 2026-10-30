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
package network.ike.komet.claude.complexconcept.model;

import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.LogicalExpression;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.komet.claude.anf.model.AnfExpression;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.Optional;
import java.util.UUID;

/**
 * One vertex of a complex concept specification's tree, typed by its meaning: the sealed hierarchy
 * of the complex concept adaptor layer, the analogue of {@code AnfStatement} for ANF and of
 * {@code LogicalAxiom} for EL++ (IKE-Network/ike-issues#1312). The only implementor is
 * {@link ComplexConceptAdaptor}, one adaptor per vertex. No other adaptor layer extends this one:
 * the definition is an EL++ expression and each prerequisite an ANF statement, both held by value
 * as tree properties and exposed through their own adaptor layers.
 */
public sealed interface ComplexConcept permits ComplexConcept.Root, ComplexConcept.Definition,
        ComplexConcept.Expansion, ComplexConcept.Member, ComplexConcept.Prerequisite, ComplexConceptAdaptor {

    /** @return the vertex's index in its tree */
    int vertexIndex();

    /** @return the vertex's identity, which persists across versions */
    UUID vertexUUID();

    /** @return the vertex's meaning */
    ComplexConceptSemantic semantic();

    /** @return the vertex's children, in order */
    ImmutableList<ComplexConcept> children();

    /** The root: the specification. */
    sealed interface Root extends ComplexConcept permits ComplexConceptAdaptor.RootAdaptor {
        /** @return the description logic definition, when present */
        Optional<Definition> definition();

        /** @return the expansion, uniform or focused, when present */
        Optional<Expansion> expansion();
    }

    /** The description logic definition, an EL++ expression held by value. */
    sealed interface Definition extends ComplexConcept permits ComplexConceptAdaptor.DefinitionAdaptor {
        /** @return the nested tree, when present */
        Optional<DiTreeEntity> expressionTree();

        /** @return the nested tree adapted by the logical expression adaptor layer */
        Optional<LogicalExpression> expression();
    }

    /** An expansion specification, uniform or focused. */
    sealed interface Expansion extends ComplexConcept {

        /** A uniform expansion: one provided value distributes to every member. */
        sealed interface Uniform extends Expansion permits ComplexConceptAdaptor.UniformExpansionAdaptor {
            /** @return AND conjunction or OR conjunction, when set */
            Optional<ConceptFacade> conjunction();

            /** @return the scale every member is measured on, when set */
            Optional<ConceptFacade> measurementDomain();

            /** @return for an OR conjunction, how a non-absent value is handled, when set */
            Optional<ConceptFacade> unsafeDistributionPolicy();

            /** @return the members, in order */
            ImmutableList<Member> members();
        }

        /** A focused expansion: the provided value applies to the focus; prerequisites are fixed. */
        sealed interface Focused extends Expansion permits ComplexConceptAdaptor.FocusedExpansionAdaptor {
            /** @return the phenomenon the provided value applies to, when set */
            Optional<ConceptFacade> focus();

            /** @return the prerequisites, in order */
            ImmutableList<Prerequisite> prerequisites();
        }
    }

    /** A member of a uniform expansion. */
    sealed interface Member extends ComplexConcept permits ComplexConceptAdaptor.MemberAdaptor {
        /** @return the phenomenon one expanded statement is about, when set */
        Optional<ConceptFacade> phenomenon();
    }

    /** A prerequisite of a focused expansion. */
    sealed interface Prerequisite extends ComplexConcept permits ComplexConceptAdaptor.PrerequisiteAdaptor {
        /** @return the nested ANF statement tree, when present */
        Optional<DiTreeEntity> statementTree();

        /** @return the nested tree adapted by the ANF adaptor layer */
        Optional<AnfExpression> statement();

        /** @return how the prerequisite relates to the focus statement when expanded, when set */
        Optional<ConceptFacade> association();
    }
}
