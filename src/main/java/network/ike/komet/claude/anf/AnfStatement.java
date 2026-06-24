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

import java.util.Objects;

/**
 * The validated kernel of a clinical statement lifted into Analysis Normal Form — a typed,
 * sealed value that both downstream forms (the rich card and the ANF-in-AsciiDoc canonical
 * form) project from, so a statement and its document cannot disagree.
 *
 * <p>The envelope separates concept identity (the {@link #topic}) from assertion structure
 * (the {@link #circumstance}): the topic is exactly one pre-coordinated concept with no
 * polarity, subject, context, or temporality, and the circumstance is exactly one of
 * {@link Circumstance.Performance}, {@link Circumstance.Request}, or
 * {@link Circumstance.Narrative} — the {@code {XOR}} the canon repeats as its central
 * invariant. Polarity is a result on the Presence scale inside the circumstance, never a
 * negated topic (the SWEC resolution). See {@code dev-anf-narrative-lift} and
 * {@code anf-structural-definition}.
 *
 * <p><strong>Deferred envelope axes</strong> (intentional, not forgotten — they need a
 * patient/authoring context this standalone lift surface does not yet have): the subject of
 * record, the informant, the statement author, and the statement time. They are added when
 * the lift is embedded in a patient-context journal. Likewise {@code associatedStatements}
 * cross-linking is deferred; prerequisites lift as independent sibling statements for now.
 *
 * @param statementType        whether the statement describes a performance, a request, or
 *                             unstructured narrative; must match the {@code circumstance}
 *                             kind
 * @param topic                the clinical topic — exactly one pre-coordinated concept (a
 *                             {@link AnfSlot.Grounded}, or an {@link AnfSlot.Candidate} when
 *                             none exists yet); carries no polarity, subject, context, or
 *                             temporality; may be null only for a pure narrative statement
 * @param subjectOfInformation who or what the statement is about when that is not the subject
 *                             of record — a family-member relationship (family history), a
 *                             fetus, a donor organ; null means the statement is about the
 *                             subject of record (defaulted)
 * @param circumstance         the clinical detail as exactly one performance, request, or
 *                             narrative circumstance; never null
 */
public record AnfStatement(
        Type statementType,
        AnfSlot topic,
        AnfSlot subjectOfInformation,
        Circumstance circumstance) {

    /**
     * Validates the statement.
     *
     * @throws NullPointerException     if {@code statementType} or {@code circumstance} is null
     * @throws IllegalArgumentException if {@code statementType} does not match the kind of
     *                                  {@code circumstance} (a cross-field invariant the
     *                                  record components cannot enforce individually)
     */
    public AnfStatement {
        Objects.requireNonNull(statementType, "statementType");
        Objects.requireNonNull(circumstance, "circumstance");
        boolean matches = switch (circumstance) {
            case Circumstance.Performance performance -> statementType == Type.PERFORMANCE;
            case Circumstance.Request request -> statementType == Type.REQUEST;
            case Circumstance.Narrative narrative -> statementType == Type.NARRATIVE;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "statementType " + statementType + " does not match circumstance kind "
                            + circumstance.getClass().getSimpleName());
        }
    }

    /**
     * The direction-of-fit classification of a statement (a closed vocabulary), kept in
     * lockstep with the {@link Circumstance} kind by the constructor invariant.
     * <em>Performance</em> describes what was done or observed (the result is measured);
     * <em>Request</em> describes what is asked for (the result is sought); <em>Narrative</em>
     * is the escape valve for content not yet lifted.
     */
    public enum Type { PERFORMANCE, REQUEST, NARRATIVE }

    /**
     * A result as a bounded interval together with a grounded measure semantic (the unit or
     * coordinate system that makes the number meaningful). Presence is the interval
     * {@code [1, ∞)} on the Presence scale and absence is {@code [0, 0]} — a result, never a
     * negated concept (the SWEC resolution).
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
