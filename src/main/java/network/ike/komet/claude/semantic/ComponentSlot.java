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
package network.ike.komet.claude.semantic;

import java.util.List;
import java.util.Objects;

/**
 * The grounded value of one <em>component-reference</em> field of a lifted semantic — the
 * sealed, three-way outcome of resolving a single reference against the open knowledge base.
 * It is the generalization of the narrative lift's concept slot from a concept to <em>any</em>
 * component: a {@link Grounded} reference carries the {@link Kind} of the component it resolved
 * to (concept, semantic, or pattern), so a field's allowed kind can be enforced and a
 * semantic reference is never silently canonicalized up to its enclosing concept.
 *
 * <p>The three states keep the lift honest: a reference is either resolved against an existing,
 * active component ({@link Grounded}), an explicit terminology gap ({@link Candidate}), or an
 * explicit narrative ambiguity ({@link Clarify}) — never a guessed identifier. Literal-valued
 * fields (string, number, boolean) do not use a slot at all; see {@link SemanticFieldValue}.
 */
public sealed interface ComponentSlot permits ComponentSlot.Grounded, ComponentSlot.Candidate,
        ComponentSlot.Clarify {

    /**
     * The kind of component a {@link Grounded} reference resolved to — the discriminator a
     * component field's declared datatype constrains ({@code CONCEPT_FIELD} → {@link #CONCEPT},
     * {@code SEMANTIC_FIELD_TYPE} → {@link #SEMANTIC}); the generic component datatype accepts
     * any kind.
     */
    enum Kind { CONCEPT, SEMANTIC, PATTERN }

    /**
     * A reference resolved to an existing, active component in the knowledge base.
     *
     * @param nid        the component's native identifier (machine-local int id; not durable)
     * @param kind       the kind of component resolved (concept, semantic, or pattern); never null
     * @param publicId   the component's <em>durable</em> identity — its {@code PublicId} UUID
     *                   array, written as the comma-joined UUIDs; the coordinate-independent
     *                   round-trip key, never a single-terminology code
     * @param identifier the component's friendly display identifier as the store returned it
     *                   (an SCTID, or a UUID when none) — for display, not the durable key
     * @param label      the component's fully-specified or preferred name
     */
    record Grounded(int nid, Kind kind, String publicId, String identifier, String label)
            implements ComponentSlot {
        /**
         * Validates the grounded slot.
         *
         * @throws NullPointerException if {@code kind}, {@code publicId}, {@code identifier}, or
         *                              {@code label} is null
         */
        public Grounded {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(publicId, "publicId");
            Objects.requireNonNull(identifier, "identifier");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * A clear meaning the knowledge base does not yet carry — a terminology gap to be proposed
     * and pre-coordinated through curation, never composed from parts or guessed as a code.
     *
     * @param text             the surfaced phrase the meaning came from
     * @param provisionalLabel a human label for the proposed component (a name, never a code)
     * @param nearestMatches   identifiers of the nearest existing components (evidence), possibly empty
     */
    record Candidate(String text, String provisionalLabel, List<String> nearestMatches)
            implements ComponentSlot {
        /**
         * Validates and defensively copies the candidate slot.
         *
         * @throws NullPointerException if {@code text} or {@code provisionalLabel} is null
         */
        public Candidate {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(provisionalLabel, "provisionalLabel");
            nearestMatches = (nearestMatches == null) ? List.of() : List.copyOf(nearestMatches);
        }
    }

    /**
     * A narrative ambiguity or missing datum — the indeterminate field surfaced as a question
     * for the author. Distinct from a {@link Candidate}: the meaning is not clear, so this is a
     * content question, not a terminology gap. Also the placeholder for a field whose datatype is
     * not yet supported (a structured value in the first version).
     *
     * @param field    the field that could not be settled (the field's meaning label)
     * @param question the question to put to the author
     */
    record Clarify(String field, String question) implements ComponentSlot {
        /**
         * Validates the clarify slot.
         *
         * @throws NullPointerException if {@code field} or {@code question} is null
         */
        public Clarify {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(question, "question");
        }
    }
}
