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

import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule.Successors;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.eclipse.collections.api.factory.Lists;

/**
 * The vertex identity rules of a complex concept specification's tree, one exhaustive switch over
 * {@link ComplexConceptSemantic} (IKE-Network/ike-issues#1310, #1312; ike-lab-documents topics
 * {@code arch-vertex-identity-*}). The specification and each expansion match their children as
 * operands; the definition, being singular, is identified by its meaning alone, so an edit of its
 * expression is an update of one vertex; a member is identified by its phenomenon and a
 * prerequisite by its statement, since each may repeat beneath its parent.
 */
public final class ComplexConceptIdentityTable implements VertexIdentityTable {

    @Override
    public boolean covers(long meaningNid) {
        return ComplexConceptSemantic.covers(meaningNid);
    }

    @Override
    public IdentityRule rule(long meaningNid) {
        return rule(ComplexConceptSemantic.get(meaningNid));
    }

    /**
     * @param semantic a vertex meaning
     * @return the identity rule for vertices of that meaning
     */
    public static IdentityRule rule(ComplexConceptSemantic semantic) {
        return switch (semantic) {
            case SPECIFICATION, UNIFORM_EXPANSION, FOCUSED_EXPANSION -> rule(Successors.OPERANDS);
            case DEFINITION -> rule(Successors.NONE);
            case MEMBER -> rule(Successors.NONE, KernelConcept.CONCEPT_REFERENCE);
            case PREREQUISITE -> rule(Successors.NONE, IkeConcept.PREREQUISITE_STATEMENT);
        };
    }

    private static IdentityRule rule(Successors successors, ConceptFacade... identifying) {
        return new IdentityRule(successors, Lists.immutable.of(identifying));
    }

    @Override
    public String toString() {
        return "ComplexConceptSemantic";
    }
}
