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
 * <p>The envelope carries every axis the structural definition names except the associated
 * statements: the subject of record, the authors and the statement time sit beside the topic
 * and the subject of information. A note supplies the first three when a statement leaves them
 * out, so a lift from a note need not repeat them; a statement states them only where it differs.
 * Associated statements stay with the statement tree, which can reference another statement; the
 * record has no identity for one.
 *
 * @param statementType        whether the statement describes a performance, a request, or
 *                             unstructured narrative; must match the {@code circumstance}
 *                             kind
 * @param topic                the clinical topic — exactly one pre-coordinated concept (a
 *                             {@link AnfValue.Grounded}, or an {@link AnfValue.Candidate} when
 *                             none exists yet); carries no polarity, subject, context, or
 *                             temporality; may be null only for a pure narrative statement
 * @param subjectOfInformation who or what the statement is about when that is not the subject
 *                             of record — a family-member relationship (family history), a
 *                             fetus, a donor organ; null means the statement is about the
 *                             subject of record (defaulted)
 * @param circumstance         the clinical detail as exactly one performance, request, or
 *                             narrative circumstance; never null
 * @param subjectOfRecord      who the statement is about, with the participant's role; null
 *                             when the statement leaves it to its note
 * @param authors              who made the statement, each with a role; never null (empty when
 *                             the statement leaves it to its note)
 * @param statementTime        when the statement was made, a time-valued measure; null when the
 *                             statement leaves it to its note
 */
public record AnfStatement(
        Type statementType,
        AnfValue topic,
        AnfValue subjectOfInformation,
        Circumstance circumstance,
        Participant subjectOfRecord,
        List<Participant> authors,
        Result statementTime) {

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
        authors = (authors == null) ? List.of() : List.copyOf(authors);
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
     * A statement whose envelope leaves the subject of record, the authors and the statement
     * time to its note.
     *
     * @param statementType        whether the statement describes a performance, a request, or
     *                             unstructured narrative
     * @param topic                the clinical topic
     * @param subjectOfInformation who or what the statement is about, when not the subject of
     *                             record
     * @param circumstance         the clinical detail
     */
    public AnfStatement(Type statementType, AnfValue topic, AnfValue subjectOfInformation, Circumstance circumstance) {
        this(statementType, topic, subjectOfInformation, circumstance, null, List.of(), null);
    }

    /**
     * This statement with another circumstance of the same kind, its envelope kept.
     *
     * @param replacement the circumstance
     * @return the statement
     */
    public AnfStatement withCircumstance(Circumstance replacement) {
        return new AnfStatement(statementType, topic, subjectOfInformation, replacement, subjectOfRecord, authors,
                statementTime);
    }

    /**
     * This statement with another topic, the rest kept.
     *
     * @param replacement the topic
     * @return the statement
     */
    public AnfStatement withTopic(AnfValue replacement) {
        return new AnfStatement(statementType, replacement, subjectOfInformation, circumstance, subjectOfRecord,
                authors, statementTime);
    }

    /**
     * This statement with another subject of information, the rest kept.
     *
     * @param replacement the subject of information
     * @return the statement
     */
    public AnfStatement withSubjectOfInformation(AnfValue replacement) {
        return new AnfStatement(statementType, topic, replacement, circumstance, subjectOfRecord, authors,
                statementTime);
    }

    /**
     * A participant in a statement — its subject of record, an author, or one who performed or is
     * to perform it — as a concept with the role the participant takes.
     *
     * @param participant the participant concept; never null
     * @param role        the participant's role; null when not stated
     */
    public record Participant(AnfValue participant, AnfValue role) {
        /**
         * Validates the participant.
         *
         * @throws NullPointerException if {@code participant} is null
         */
        public Participant {
            Objects.requireNonNull(participant, "participant");
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
     * @param resolution        the significant digits or precision of the bounds; null when not
     *                          stated
     */
    public record Result(Double lowerBound, Double upperBound,
                         boolean includeLowerBound, boolean includeUpperBound,
                         AnfValue measureSemantic, Double resolution) {
        /**
         * Validates the result.
         *
         * @throws NullPointerException if {@code measureSemantic} is null
         */
        public Result {
            Objects.requireNonNull(measureSemantic, "measureSemantic");
        }

        /**
         * A result whose resolution is not stated.
         *
         * @param lowerBound        the lower bound; null denotes an unbounded lower side
         * @param upperBound        the upper bound; null denotes an unbounded upper side
         * @param includeLowerBound whether the lower bound is inclusive
         * @param includeUpperBound whether the upper bound is inclusive
         * @param measureSemantic   the grounded unit / scale concept; never null
         */
        public Result(Double lowerBound, Double upperBound, boolean includeLowerBound, boolean includeUpperBound,
                      AnfValue measureSemantic) {
            this(lowerBound, upperBound, includeLowerBound, includeUpperBound, measureSemantic, null);
        }
    }
}
