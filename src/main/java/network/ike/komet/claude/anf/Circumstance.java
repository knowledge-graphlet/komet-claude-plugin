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
 * The clinical detail of an {@link AnfStatement}, as exactly one of three direction-of-fit
 * kinds — a {@link Performance} (what was observed or done), a {@link Request} (what is
 * asked for), or a {@link Narrative} (content that resists formalization). This sealed XOR
 * is ANF's central structural invariant: a statement carries <strong>precisely one</strong>
 * circumstance, and the impossible states (a request with a measured result, a narrative
 * with a priority) are made unrepresentable rather than merely discouraged. See
 * {@code anf-structural-definition}.
 *
 * <p>Every concept reference is an {@link AnfSlot} (grounded, candidate, or clarify) and
 * every measured value is an {@link AnfStatement.Result}; nothing here is free text except
 * the narrative escape valve. Per IKE's no-post-coordination rule, each slot names exactly
 * one pre-coordinated concept — the typed modifier fields (body site, method, laterality,
 * …) are first-class <em>circumstance</em> attributes, never refinements composed into the
 * topic. Compositional meaning belongs inside a concept's definition, not in a statement.
 *
 * <p>This is the typed home for what the lift's earlier (Demo&nbsp;0) model flattened to a
 * top-level {@code result}/{@code status}/{@code narrative}; the sealed kind lets the
 * renderer and the {@code emit_anf} tool switch over it exhaustively, so a new circumstance
 * is a compile error until handled everywhere.
 */
public sealed interface Circumstance
        permits Circumstance.Performance, Circumstance.Request, Circumstance.Narrative {

    /**
     * When the circumstance occurred or is requested — a time-valued measure; null when the
     * narrative leaves timing indeterminate.
     *
     * @return the timing measure, or null
     */
    AnfStatement.Result timing();

    /**
     * Why the performance was done or the request made — zero or more grounded purpose
     * concepts; never null (possibly empty).
     *
     * @return the purpose slots
     */
    List<AnfSlot> purpose();

    /**
     * The performance circumstance: the clinical detail of an action or observation that has
     * occurred. The {@code result} is what was determined — a measured interval, or a
     * presence/absence result on the Presence scale — and the typed modifiers locate and
     * qualify the determination. Only the attributes relevant to the clinical element need be
     * populated; absent attributes are null.
     *
     * @param timing      when the determination occurred; null when not stated
     * @param purpose     why it was done; never null (possibly empty)
     * @param status      the status of the action or determination (e.g. performed, final,
     *                    amended); a slot, or null when not stated
     * @param result      what was determined; null when the circumstance carries no result
     * @param healthRisk  the clinical-risk classification of the result (e.g. low, high,
     *                    critically high); a slot, or null when not stated
     * @param normalRange the reference range of normal values for the determination; null
     *                    when not stated
     * @param bodySite    the anatomical location; a slot, or null when not stated
     * @param method      how the determination was made; a slot, or null when not stated
     * @param laterality  left / right / bilateral; a slot, or null when not stated
     */
    record Performance(
            AnfStatement.Result timing,
            List<AnfSlot> purpose,
            AnfSlot status,
            AnfStatement.Result result,
            AnfSlot healthRisk,
            AnfStatement.Result normalRange,
            AnfSlot bodySite,
            AnfSlot method,
            AnfSlot laterality) implements Circumstance {

        /** Defensively copies {@code purpose}. */
        public Performance {
            purpose = (purpose == null) ? List.of() : List.copyOf(purpose);
        }
    }

    /**
     * The request circumstance: the clinical detail of something asked for or ordered. The
     * {@code requestedResult} is the result sought; {@code repetition} carries a
     * frequency/duration schedule; {@code conditionalTrigger} carries the conditions for
     * execution as concept slots (the nested associated-statement form is deferred). Only
     * relevant attributes are populated; absent attributes are null/empty.
     *
     * @param timing             when execution is requested; null when not stated
     * @param purpose            the clinical indication; never null (possibly empty)
     * @param priority           routine / urgent / stat; a slot, or null when not stated
     * @param requestedResult    the result sought; null when not stated
     * @param repetition         the frequency/duration schedule; null when not stated
     * @param conditionalTrigger conditions for execution, each a concept slot; never null
     *                           (possibly empty)
     * @param method             how the request should be carried out; a slot, or null
     */
    record Request(
            AnfStatement.Result timing,
            List<AnfSlot> purpose,
            AnfSlot priority,
            AnfStatement.Result requestedResult,
            Repetition repetition,
            List<AnfSlot> conditionalTrigger,
            AnfSlot method) implements Circumstance {

        /** Defensively copies {@code purpose} and {@code conditionalTrigger}. */
        public Request {
            purpose = (purpose == null) ? List.of() : List.copyOf(purpose);
            conditionalTrigger = (conditionalTrigger == null) ? List.of() : List.copyOf(conditionalTrigger);
        }
    }

    /**
     * The narrative circumstance: the escape valve for clinical content that resists
     * structured representation. Its {@code text} is the only required content; timing and
     * purpose remain available because even unstructured content may be situated in time and
     * intent. Intended for infrequent use as a fallback, never as a general-purpose text
     * field.
     *
     * @param timing  when the narrative content applies; null when not stated
     * @param purpose why it is recorded; never null (possibly empty)
     * @param text    the unstructured content; never null
     */
    record Narrative(
            AnfStatement.Result timing,
            List<AnfSlot> purpose,
            String text) implements Circumstance {

        /**
         * Validates and defensively copies the narrative circumstance.
         *
         * @throws NullPointerException if {@code text} is null
         */
        public Narrative {
            Objects.requireNonNull(text, "text");
            purpose = (purpose == null) ? List.of() : List.copyOf(purpose);
        }
    }

    /**
     * A repetition schedule for a {@link Request} — the temporal pattern of recurring
     * execution, each component a time-valued {@link AnfStatement.Result} measure (the ANF
     * request-control model). All components are optional and null when not stated.
     *
     * @param periodStart     when the schedule begins
     * @param periodDuration  how long the schedule runs
     * @param eventSeparation the gap between successive events
     * @param eventDuration   how long each event lasts
     * @param eventFrequency  how often events occur
     */
    record Repetition(
            AnfStatement.Result periodStart,
            AnfStatement.Result periodDuration,
            AnfStatement.Result eventSeparation,
            AnfStatement.Result eventDuration,
            AnfStatement.Result eventFrequency) {
    }
}
