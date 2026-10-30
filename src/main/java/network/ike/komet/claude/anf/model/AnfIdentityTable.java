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

import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule.Successors;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import org.eclipse.collections.api.factory.Lists;

/**
 * The identity table of ANF statement trees (arch-vertex-identity-table;
 * IKE-Network/ike-issues#1310, #1312), provided to tinkar-core's write path as a
 * {@link VertexIdentityTable} service, so a statement written twice keeps the UUIDs of the
 * vertices it continues and the diff between two versions is a {@code DiTreeEditScript}.
 *
 * <p>By the identity rule (arch-vertex-identity-rule):
 * <ul>
 *   <li>the root, the context, each circumstance and a repetition hold operands told apart by
 *       key, and for an attribute a statement has at most one of, its meaning is the whole key:
 *       a changed result or a re-grounded topic is an update of that vertex, never a replacement;
 *   <li>an attribute a statement may have several of is told apart by what it refers to: a
 *       purpose by its expression, a conditional trigger by its concept, a participant and an
 *       author by the participant, an associated statement by the statement and the type;
 *   <li>every attribute is a leaf, except the repetition clause.
 * </ul>
 *
 * <p>One exhaustive switch over {@link AnfSemantic} with no {@code default} branch, so a meaning
 * added without its rule cannot build. Do not add one.
 */
public final class AnfIdentityTable implements VertexIdentityTable {

    @Override
    public boolean covers(long meaningNid) {
        return AnfSemantic.covers(meaningNid);
    }

    @Override
    public IdentityRule rule(long meaningNid) {
        return rule(AnfSemantic.get(meaningNid));
    }

    /**
     * The table's entry for a statement meaning.
     *
     * @param semantic the meaning
     * @return its rule
     */
    public static IdentityRule rule(AnfSemantic semantic) {
        return switch (semantic) {
            case STATEMENT, CONTEXT, PERFORMANCE, REQUEST, NARRATIVE, REPETITION -> rule(Successors.OPERANDS);
            case SUBJECT_OF_RECORD, SUBJECT_OF_INFORMATION, TOPIC, STATEMENT_TYPE, STATEMENT_TIME,
                 TIMING, RESULT, DISPOSITION, HEALTH_RISK, NORMAL_RANGE, METHOD, DEVICE, BODY_SITE, LATERALITY,
                 APPROACH, SPECIMEN_TYPE, SPECIMEN_SOURCE_SITE, PRIORITY, REQUESTED_RESULT, PERIOD_START,
                 PERIOD_DURATION, EVENT_SEPARATION, EVENT_DURATION, EVENT_FREQUENCY, LOCATION,
                 NARRATIVE_TEXT -> rule(Successors.NONE);
            case PURPOSE -> rule(Successors.NONE, IkeConcept.ANF_LOGICAL_EXPRESSION);
            case CONDITIONAL_TRIGGER -> rule(Successors.NONE, KernelConcept.CONCEPT_REFERENCE);
            case AUTHOR, PARTICIPANT -> rule(Successors.NONE, KernelConcept.CONCEPT_REFERENCE);
            case ASSOCIATED_STATEMENT -> rule(Successors.NONE, IkeConcept.ANF_ASSOCIATED_STATEMENT_REFERENCE,
                    IkeConcept.ANF_ASSOCIATION_TYPE);
        };
    }

    private static IdentityRule rule(Successors successors, ConceptFacade... identifying) {
        return new IdentityRule(successors, Lists.immutable.of(identifying));
    }

    @Override
    public String toString() {
        return "AnfSemantic";
    }
}
