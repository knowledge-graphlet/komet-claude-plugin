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
import java.util.Objects;

/**
 * The validated kernel of a clinical statement lifted into Analysis Normal Form —
 * a typed, sealed value that both downstream forms (the rich card and the
 * ANF-in-AsciiDoc canonical form) project from, so a statement and its document
 * cannot disagree.
 *
 * <p>Every concept reference is an {@link AnfSlot} (grounded, candidate, or
 * clarify); the statement type and the result's measure carry closed structure
 * rather than free text. This is the shape the {@code emit_anf} tool fills and
 * validates against the live store; see {@code dev-anf-narrative-lift}.
 *
 * @param statementType        whether the statement describes a performance, a
 *                             request, or unstructured narrative
 * @param topic                the clinical topic — a grounded focus plus optional
 *                             grounded role-fillers (post-coordination); null only
 *                             for a {@link Type#NARRATIVE} statement
 * @param result               the measured or requested result; null when absent
 *                             (e.g. a narrative statement)
 * @param status               the status concept slot (e.g. final / preliminary /
 *                             amended); may be null when not stated
 * @param modifiers            typed modifier slots (body site, method, laterality,
 *                             priority, …), each a grounded / candidate / clarify
 *                             slot; never null (possibly empty)
 * @param associatedStatements prerequisite or related statements lifted out of the
 *                             topic; never null (possibly empty)
 * @param narrative            the unstructured text for a {@link Type#NARRATIVE}
 *                             statement; null otherwise
 */
public record AnfStatement(
        Type statementType,
        Topic topic,
        Result result,
        AnfSlot status,
        List<AnfSlot> modifiers,
        List<AnfStatement> associatedStatements,
        String narrative) {

    /**
     * Validates and defensively copies the statement.
     *
     * @throws NullPointerException if {@code statementType} is null
     */
    public AnfStatement {
        Objects.requireNonNull(statementType, "statementType");
        modifiers = modifiers == null ? List.of() : List.copyOf(modifiers);
        associatedStatements = associatedStatements == null ? List.of() : List.copyOf(associatedStatements);
    }

    /**
     * The direction-of-fit classification of a statement (a closed vocabulary).
     * <em>Performance</em> describes what was done or observed (the result is
     * measured); <em>Request</em> describes what is asked for (the result is
     * sought); <em>Narrative</em> is the escape valve for content not yet lifted.
     */
    public enum Type { PERFORMANCE, REQUEST, NARRATIVE }

    /**
     * A statement topic: a grounded focus concept and, optionally, grounded
     * role-fillers that post-coordinate it (for example, a focus of
     * {@code Observation procedure} with a {@code Has focus} role pointing at
     * {@code Diabetes mellitus}). The topic carries concept identity only — no
     * polarity, context, or temporality, which belong in the circumstance.
     *
     * @param focus       the focus slot (the clinical thing); never null
     * @param roleFillers the post-coordination role-filler pairs; never null
     *                    (possibly empty)
     */
    public record Topic(AnfSlot focus, List<RoleFiller> roleFillers) {
        /**
         * Validates and defensively copies the topic.
         *
         * @throws NullPointerException if {@code focus} is null
         */
        public Topic {
            Objects.requireNonNull(focus, "focus");
            roleFillers = roleFillers == null ? List.of() : List.copyOf(roleFillers);
        }
    }

    /**
     * One post-coordination role-filler pair within a {@link Topic} — a relationship
     * role (e.g. {@code Method}, {@code Using device}) pointing at a filler concept.
     *
     * @param role   the role slot; never null
     * @param filler the filler slot; never null
     */
    public record RoleFiller(AnfSlot role, AnfSlot filler) {
        /**
         * Validates the role-filler pair.
         *
         * @throws NullPointerException if {@code role} or {@code filler} is null
         */
        public RoleFiller {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(filler, "filler");
        }
    }

    /**
     * A result as a bounded interval together with a grounded measure semantic (the
     * unit or coordinate system that makes the number meaningful). Presence is the
     * interval {@code [1, ∞)} on the Presence scale and absence is {@code [0, 0]} —
     * a result, never a negated concept (the SWEC resolution).
     *
     * @param lowerBound        the lower bound; null denotes an unbounded lower side
     * @param upperBound        the upper bound; null denotes an unbounded upper side
     * @param includeLowerBound whether the lower bound is inclusive
     * @param includeUpperBound whether the upper bound is inclusive
     * @param measureSemantic   the grounded unit / scale concept; never null
     */
    public record Result(Double lowerBound, Double upperBound,
                         boolean includeLowerBound, boolean includeUpperBound,
                         AnfSlot measureSemantic) {
        /**
         * Validates the result.
         *
         * @throws NullPointerException if {@code measureSemantic} is null
         */
        public Result {
            Objects.requireNonNull(measureSemantic, "measureSemantic");
        }
    }
}
