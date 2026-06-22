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

import java.util.List;

/**
 * One slot of a lifted ANF statement — the sealed, three-way outcome of grounding
 * a single clinical term against the open knowledge base. The slot is the unit
 * that keeps the lift honest: a term is either resolved, an explicit terminology
 * gap, or an explicit narrative ambiguity, never a guess.
 *
 * <p>The distinction between {@link Candidate} and {@link Clarify} is load-bearing:
 * a candidate is a <em>terminology</em> gap whose meaning is clear; a clarify is a
 * <em>clinical-content</em> question the narrative leaves open. See the design
 * topics {@code dev-anf-candidate-concepts} and {@code dev-anf-narrative-lift}.
 */
public sealed interface AnfSlot permits AnfSlot.Grounded, AnfSlot.Candidate, AnfSlot.Clarify {

    /**
     * A term resolved to an existing, active concept in the knowledge base.
     *
     * @param nid        the concept's native identifier (local int id)
     * @param identifier the concept's stable identifier as the store returned it
     *                   (an SCTID, or a UUID when no SCTID is present)
     * @param label      the concept's fully-specified or preferred name
     */
    record Grounded(int nid, String identifier, String label) implements AnfSlot {
        /**
         * Validates the grounded slot.
         *
         * @throws NullPointerException if {@code identifier} or {@code label} is null
         */
        public Grounded {
            java.util.Objects.requireNonNull(identifier, "identifier");
            java.util.Objects.requireNonNull(label, "label");
        }
    }

    /**
     * A clear clinical meaning the knowledge base does not yet carry — a real
     * provisional concept (held on the author's thread path), with its disposition
     * pending. The model proposes; the graph and the curator dispose.
     *
     * @param text            the surfaced phrase the meaning came from
     * @param provisionalLabel a human label for the provisional concept
     * @param nearestMatches  identifiers of the nearest existing concepts (evidence
     *                        for disposition), possibly empty
     * @param disposition     the candidate's current disposition
     */
    record Candidate(String text, String provisionalLabel, List<String> nearestMatches,
                     Disposition disposition) implements AnfSlot {
        /**
         * Validates and defensively copies the candidate slot.
         *
         * @throws NullPointerException if {@code text}, {@code provisionalLabel}, or
         *                              {@code disposition} is null
         */
        public Candidate {
            java.util.Objects.requireNonNull(text, "text");
            java.util.Objects.requireNonNull(provisionalLabel, "provisionalLabel");
            java.util.Objects.requireNonNull(disposition, "disposition");
            nearestMatches = nearestMatches == null ? List.of() : List.copyOf(nearestMatches);
        }
    }

    /**
     * A narrative ambiguity or missing datum — the indeterminate field surfaced as
     * a question for the author. Distinct from a {@link Candidate}: the meaning is
     * not clear, so this is a clinical-content question, not a terminology gap.
     *
     * @param field    the ANF field that could not be settled (e.g. {@code laterality})
     * @param question the question to put to the author
     */
    record Clarify(String field, String question) implements AnfSlot {
        /**
         * Validates the clarify slot.
         *
         * @throws NullPointerException if {@code field} or {@code question} is null
         */
        public Clarify {
            java.util.Objects.requireNonNull(field, "field");
            java.util.Objects.requireNonNull(question, "question");
        }
    }

    /**
     * Closed vocabulary for what to do with a {@link Candidate}. The disposition is
     * the curation act of promotion (see {@code dev-kompendium-promotion}); v1
     * surfaces it for human review rather than choosing automatically.
     */
    enum Disposition {
        /** Not yet decided; awaiting a refined search or a curator. */
        PENDING,
        /** A refined search found the right existing concept; this is really grounded. */
        MATCH_EXISTING,
        /** The meaning is expressible by composing existing concepts; no new primitive. */
        POST_COORDINATE,
        /** Genuinely missing; warrants a new concept (a contribution to the Kompendium). */
        PROPOSE_NEW,
        /** A lexical variant of an existing concept; add a description, do not create one. */
        ALIGN_SYNONYM,
        /** Not formalizable yet; stays as narrative circumstance. */
        LEAVE_NARRATIVE
    }
}
