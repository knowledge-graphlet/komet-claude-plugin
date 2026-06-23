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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.tools.GraphTools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The forced-decision tools that drive a narrative lift: {@code emit_anf} (one validated
 * {@link AnfStatement} per call) and {@code finish_lift} (a no-argument completion signal).
 * Modelled on the Claude check area's {@code report_result}, {@code emit_anf} makes the model
 * deliver <em>structured</em> statements into a {@link StatementSink} rather than prose, and
 * it re-validates every grounded concept identifier against the live store before accepting
 * a statement. An identifier claimed as grounded that does not resolve is rejected back to
 * the model — so "no hallucinated codes" is enforced in code, not merely requested in the
 * prompt.
 *
 * <p>A compound narrative yields several statements: the model calls {@code emit_anf} once
 * per independent clinical assertion, and each call validates and records exactly one
 * statement. Each concept slot is the sealed three-way {@link AnfSlot} — grounded, an
 * explicit terminology-gap {@link AnfSlot.Candidate}, or a narrative {@link AnfSlot.Clarify}
 * — so an ungroundable meaning becomes an honest gap, never a guess.
 */
public final class AnfTools {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(AnfTools.class);

    private AnfTools() {
    }

    /**
     * Receives a validated statement from {@code emit_anf}.
     *
     * <p>Returns whether the statement was newly recorded: a {@code false} return lets the
     * tool tell the model the statement was a duplicate (idempotent emit) rather than
     * accumulating it twice.
     */
    @FunctionalInterface
    public interface StatementSink {
        /**
         * Records a validated statement.
         *
         * @param statement the statement to record
         * @return {@code true} if newly recorded; {@code false} if a structurally-equal
         *         statement was already recorded
         */
        boolean record(AnfStatement statement);
    }

    /**
     * Creates the {@code emit_anf} tool.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator} (the lift window's
     *                     view); must not be null
     * @param sink         receives each validated statement; must not be null
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitAnf(Supplier<ViewCalculator> viewSupplier, StatementSink sink) {
        return emitAnf(viewSupplier, sink, slot -> {
        });
    }

    /**
     * Creates the {@code emit_anf} tool that also reports each slot it grounds, proposes, or
     * surfaces, for a live inventory.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator}; must not be null
     * @param sink         receives each validated statement; must not be null
     * @param onDiscovered receives each slot (grounded, candidate, or clarify) as a statement
     *                     validates; a null callback is treated as a no-op
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitAnf(Supplier<ViewCalculator> viewSupplier, StatementSink sink,
                                        Consumer<AnfSlot> onDiscovered) {
        Objects.requireNonNull(viewSupplier, "viewSupplier");
        Objects.requireNonNull(sink, "sink");
        return new EmitAnfTool(viewSupplier, sink, (onDiscovered == null) ? slot -> {
        } : onDiscovered);
    }

    /**
     * Creates the {@code finish_lift} tool — a no-argument signal the model calls once when it
     * has emitted every statement, giving the lift a positive completion signal (distinct from
     * the turn cap) so it can end without paying for a trailing model turn.
     *
     * @param onFinish run when the model signals completion; must not be null
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool finishLift(Runnable onFinish) {
        Objects.requireNonNull(onFinish, "onFinish");
        return new FinishLiftTool(onFinish);
    }

    // ── finish_lift ──────────────────────────────────────────────────────────

    /** The {@code finish_lift} tool implementation. */
    private record FinishLiftTool(Runnable onFinish) implements AnthropicTool {

        @Override
        public String name() {
            return "finish_lift";
        }

        @Override
        public String description() {
            return "Call this ONCE, with no arguments, after you have emitted every clinical statement "
                    + "in the narrative with emit_anf. It signals that the lift is complete so the result "
                    + "can be shown immediately. Do not call it before emitting your statements, and send "
                    + "no further message after calling it.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return obj("type", "object", "properties", obj());
        }

        @Override
        public String execute(Map<String, Object> input) {
            onFinish.run();
            return "done";
        }
    }

    // ── emit_anf ─────────────────────────────────────────────────────────────

    /** The {@code emit_anf} tool implementation. */
    private record EmitAnfTool(Supplier<ViewCalculator> viewSupplier, StatementSink sink,
                               Consumer<AnfSlot> onDiscovered)
            implements AnthropicTool {

        @Override
        public String name() {
            return "emit_anf";
        }

        @Override
        public String description() {
            return "Emit ONE clinical statement lifted into Analysis Normal Form. Call emit_anf once per "
                    + "independent clinical assertion in the narrative — a compound narrative becomes "
                    + "several emit_anf calls, one each. Fill the flat envelope: a single pre-coordinated "
                    + "topic; the fields for that statement_type (performance, request, or narrative) at the "
                    + "TOP LEVEL — not nested under a 'circumstance' object; subject_of_information ONLY when "
                    + "the statement is about someone "
                    + "other than the subject of record (a relative for family history, a fetus, a donor "
                    + "organ). Reference EXACTLY ONE pre-coordinated concept per slot — never compose a focus "
                    + "plus role-fillers in a statement; IKE does not post-coordinate, and compositional "
                    + "meaning lives inside a concept's definition, not the statement. Each concept slot "
                    + "accepts ONE of grounded_concept_id, candidate, or clarify: use a candidate when a "
                    + "clear meaning has no concept yet (a terminology gap), a clarify when the narrative "
                    + "itself is ambiguous — never invent a code, and never put a code in a candidate. Every "
                    + "grounded_concept_id must be an identifier the search/concept tools returned; one that "
                    + "does not resolve is rejected, so search again or emit a candidate rather than guessing. "
                    + "Presence is the result interval [1, no upper bound] on the 'Presence (property) "
                    + "(qualifier value)' concept (a qualifier value, NOT a unit) and absence is [0,0]; a "
                    + "finding asserted with no measured value is a presence result. When you have emitted "
                    + "every statement, call finish_lift once and send no further message.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return SCHEMA;
        }

        @Override
        public String execute(Map<String, Object> input) {
            Map<String, Object> in = (input == null) ? Map.of() : input;
            LOG.info("ANF emit_anf keys={} statement_type={}", in.keySet(), str(in, "statement_type"));
            ViewCalculator v = view();
            if (v == null) {
                return "No active knowledge-base view is available to ground the statement.";
            }
            AnfStatement.Type type = parseType(str(in, "statement_type"));
            if (type == null) {
                return "statement_type must be one of: performance, request, narrative.";
            }
            String mismatch = circumstanceFieldMismatch(type, in);
            if (mismatch != null) {
                return mismatch;
            }

            List<String> unresolved = new ArrayList<>();
            AnfSlot topic = parseSlot(map(in, "topic"), v, unresolved);
            AnfSlot subjectOfInformation = parseSlot(map(in, "subject_of_information"), v, unresolved);

            // The circumstance fields are read from the TOP LEVEL: the model reliably emits a flat
            // object and was dropping a nested `circumstance` wrapper (sending it as an empty string).
            // The typed Circumstance record is still built here — only the wire shape is flat.
            Circumstance circumstance = switch (type) {
                case PERFORMANCE -> new Circumstance.Performance(
                        parseMeasure(map(in, "timing"), v, unresolved),
                        parseSlotList(list(in, "purpose"), v, unresolved),
                        parseSlot(map(in, "status"), v, unresolved),
                        parseMeasure(map(in, "result"), v, unresolved),
                        parseSlot(map(in, "health_risk"), v, unresolved),
                        parseMeasure(map(in, "normal_range"), v, unresolved),
                        parseSlot(map(in, "body_site"), v, unresolved),
                        parseSlot(map(in, "method"), v, unresolved),
                        parseSlot(map(in, "laterality"), v, unresolved));
                case REQUEST -> new Circumstance.Request(
                        parseMeasure(map(in, "timing"), v, unresolved),
                        parseSlotList(list(in, "purpose"), v, unresolved),
                        parseSlot(map(in, "priority"), v, unresolved),
                        parseMeasure(map(in, "requested_result"), v, unresolved),
                        parseRepetition(map(in, "repetition"), v, unresolved),
                        parseSlotList(list(in, "conditional_trigger"), v, unresolved),
                        parseSlot(map(in, "method"), v, unresolved));
                case NARRATIVE -> new Circumstance.Narrative(
                        parseMeasure(map(in, "timing"), v, unresolved),
                        parseSlotList(list(in, "purpose"), v, unresolved),
                        textOf(str(in, "text")));
            };

            if (!unresolved.isEmpty()) {
                return "These identifiers did not resolve to a concept in the knowledge base: "
                        + String.join(", ", new LinkedHashSet<>(unresolved))
                        + ". Search for the correct concepts and call emit_anf again — do not invent codes. "
                        + "If a meaning is genuinely missing, emit that slot as a candidate instead.";
            }
            if (type != AnfStatement.Type.NARRATIVE && topic == null) {
                return "topic is required for a performance or request statement; supply a grounded, "
                        + "candidate, or clarify topic (never omit the statement).";
            }
            if (type == AnfStatement.Type.NARRATIVE && ((Circumstance.Narrative) circumstance).text().isBlank()) {
                return "a narrative statement requires non-empty text.";
            }

            AnfStatement statement;
            try {
                statement = new AnfStatement(type, topic, subjectOfInformation, circumstance);
            } catch (RuntimeException e) {
                return "Invalid statement: " + e.getMessage();
            }
            boolean recorded = sink.record(statement);
            return recorded ? "recorded" : "This statement was already recorded; do not emit it again.";
        }

        private ViewCalculator view() {
            try {
                return viewSupplier.get();
            } catch (RuntimeException e) {
                return null;
            }
        }

        // ── Slot / measure parsing (instance: they fire the discovery callback) ──

        /**
         * Parses a three-way concept slot: a {@code grounded_concept_id} (validated), a
         * {@code candidate} (built without grounding), or a {@code clarify}. Returns null when
         * the slot is absent or malformed; a grounded id that fails to resolve is recorded in
         * {@code unresolved} (so the caller rejects the emit) and returns null.
         */
        private AnfSlot parseSlot(Map<String, Object> slotMap, ViewCalculator v, List<String> unresolved) {
            if (slotMap == null) {
                return null;
            }
            String groundedId = str(slotMap, "grounded_concept_id");
            if (groundedId != null && !groundedId.isBlank()) {
                return ground(groundedId, v, unresolved);
            }
            Map<String, Object> candidateMap = map(slotMap, "candidate");
            if (candidateMap != null) {
                String label = str(candidateMap, "provisional_label");
                if (label == null || label.isBlank()) {
                    return null;
                }
                String text = str(candidateMap, "text");
                List<String> nearest = groundedEvidence(list(candidateMap, "nearest_match_ids"), v);
                AnfSlot.Candidate candidate = new AnfSlot.Candidate(
                        (text == null || text.isBlank()) ? label : text, label, nearest,
                        AnfSlot.Disposition.PENDING);
                fireDiscovered(candidate);
                return candidate;
            }
            Map<String, Object> clarifyMap = map(slotMap, "clarify");
            if (clarifyMap != null) {
                String field = str(clarifyMap, "field");
                String question = str(clarifyMap, "question");
                if (field == null || field.isBlank() || question == null || question.isBlank()) {
                    return null;
                }
                AnfSlot.Clarify clarify = new AnfSlot.Clarify(field, question);
                fireDiscovered(clarify);
                return clarify;
            }
            return null;
        }

        /** Grounds an id to a {@link AnfSlot.Grounded}; on failure records it and returns null. */
        private AnfSlot ground(String id, ViewCalculator v, List<String> unresolved) {
            Optional<AnfSlot.Grounded> grounded = GraphTools.resolveConcept(id, v);
            if (grounded.isEmpty()) {
                unresolved.add(id);
                return null;
            }
            AnfSlot.Grounded slot = grounded.get();
            fireDiscovered(slot);
            return slot;
        }

        /** Resolves a candidate's nearest-match ids to identifiers, dropping any that do not resolve. */
        private List<String> groundedEvidence(List<Object> ids, ViewCalculator v) {
            if (ids == null) {
                return List.of();
            }
            List<String> kept = new ArrayList<>();
            for (Object id : ids) {
                if (id != null) {
                    GraphTools.resolveConcept(id.toString(), v).ifPresent(g -> kept.add(g.identifier()));
                }
            }
            return kept;
        }

        /** Parses a list of concept slots, skipping malformed entries. */
        private List<AnfSlot> parseSlotList(List<Object> items, ViewCalculator v, List<String> unresolved) {
            if (items == null) {
                return List.of();
            }
            List<AnfSlot> slots = new ArrayList<>();
            for (Object item : items) {
                Map<String, Object> slotMap = asMap(item);
                if (slotMap != null) {
                    AnfSlot slot = parseSlot(slotMap, v, unresolved);
                    if (slot != null) {
                        slots.add(slot);
                    }
                }
            }
            return slots;
        }

        /** Parses a measure (bounds + a grounded measure-semantic slot); null when absent. */
        private AnfStatement.Result parseMeasure(Map<String, Object> measureMap, ViewCalculator v,
                                                 List<String> unresolved) {
            if (measureMap == null) {
                return null;
            }
            AnfSlot measure = parseSlot(map(measureMap, "measure_semantic"), v, unresolved);
            if (measure == null) {
                return null;
            }
            return new AnfStatement.Result(
                    num(measureMap, "lower_bound"),
                    num(measureMap, "upper_bound"),
                    bool(measureMap, "include_lower_bound", true),
                    bool(measureMap, "include_upper_bound", true),
                    measure);
        }

        /** Parses a request repetition schedule (five optional time measures); null when absent. */
        private Circumstance.Repetition parseRepetition(Map<String, Object> repetitionMap, ViewCalculator v,
                                                        List<String> unresolved) {
            if (repetitionMap == null) {
                return null;
            }
            return new Circumstance.Repetition(
                    parseMeasure(map(repetitionMap, "period_start"), v, unresolved),
                    parseMeasure(map(repetitionMap, "period_duration"), v, unresolved),
                    parseMeasure(map(repetitionMap, "event_separation"), v, unresolved),
                    parseMeasure(map(repetitionMap, "event_duration"), v, unresolved),
                    parseMeasure(map(repetitionMap, "event_frequency"), v, unresolved));
        }

        /** Reports a discovered slot to the callback, never letting a callback error break grounding. */
        private void fireDiscovered(AnfSlot slot) {
            try {
                onDiscovered.accept(slot);
            } catch (RuntimeException ignored) {
                // a UI callback must never break the tool loop
            }
        }
    }

    // ── Cross-field circumstance guard ───────────────────────────────────────

    private static final Set<String> PERFORMANCE_ONLY =
            Set.of("status", "result", "health_risk", "normal_range", "body_site", "laterality");
    private static final Set<String> REQUEST_ONLY =
            Set.of("priority", "requested_result", "repetition", "conditional_trigger");
    private static final Set<String> NARRATIVE_ONLY = Set.of("text");

    /**
     * Rejects a circumstance that carries a field belonging to a different kind (e.g. a
     * {@code priority} under a performance circumstance), mirroring the {@link AnfStatement}
     * constructor invariant as a model-facing message rather than an exception.
     */
    private static String circumstanceFieldMismatch(AnfStatement.Type type, Map<String, Object> circ) {
        Set<String> forbidden = switch (type) {
            case PERFORMANCE -> union(REQUEST_ONLY, NARRATIVE_ONLY);
            case REQUEST -> union(PERFORMANCE_ONLY, NARRATIVE_ONLY);
            case NARRATIVE -> union(PERFORMANCE_ONLY, REQUEST_ONLY);
        };
        for (String key : forbidden) {
            if (circ.get(key) != null) {
                return "Field '" + key + "' does not belong to a " + type.name().toLowerCase(Locale.ROOT)
                        + " circumstance. Use only that kind's fields, or change statement_type.";
            }
        }
        return null;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new LinkedHashSet<>(a);
        all.addAll(b);
        return all;
    }

    private static AnfStatement.Type parseType(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "performance" -> AnfStatement.Type.PERFORMANCE;
            case "request" -> AnfStatement.Type.REQUEST;
            case "narrative" -> AnfStatement.Type.NARRATIVE;
            default -> null;
        };
    }

    // ── Map accessors (the tool input arrives as a parsed JSON map) ───────────

    private static String str(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof String s) {
            return s;
        }
        return o == null ? null : o.toString();
    }

    private static String textOf(String s) {
        return s == null ? "" : s;
    }

    private static Map<String, Object> map(Map<String, Object> m, String k) {
        return asMap(m == null ? null : m.get(k));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof List ? (List<Object>) o : null;
    }

    private static Double num(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Boolean b ? b : def;
    }

    // ── Input schema (LinkedHashMap-ordered for a byte-stable, cacheable tool prefix) ──

    /** Builds an insertion-ordered map so the serialized tool schema is byte-stable across lifts. */
    private static Map<String, Object> obj(Object... keyValues) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }

    /** A three-way concept-slot schema: exactly one of grounded_concept_id, candidate, or clarify. */
    private static Map<String, Object> slotSchema(String description) {
        return obj(
                "type", "object",
                "description", description + " Supply EXACTLY ONE of: grounded_concept_id (an SCTID or UUID "
                        + "the tools returned for an existing concept); candidate (a clear clinical meaning the "
                        + "knowledge base does not yet carry); or clarify (a narrative ambiguity to surface as a "
                        + "question). Never put a code in a candidate, and never a free phrase in "
                        + "grounded_concept_id.",
                "properties", obj(
                        "grounded_concept_id", strProp("SCTID or UUID of an existing pre-coordinated concept."),
                        "candidate", obj(
                                "type", "object",
                                "description", "A clear meaning with no existing concept — a terminology gap.",
                                "properties", obj(
                                        "text", strProp("the narrative phrase the meaning came from"),
                                        "provisional_label", strProp("a human label for the proposed concept "
                                                + "(a name, never a code)"),
                                        "nearest_match_ids", obj("type", "array",
                                                "description", "SCTIDs/UUIDs of the nearest existing concepts, "
                                                        + "as evidence",
                                                "items", strProp("an SCTID or UUID"))),
                                "required", List.of("text", "provisional_label")),
                        "clarify", obj(
                                "type", "object",
                                "description", "A narrative ambiguity the author must resolve.",
                                "properties", obj(
                                        "field", strProp("the ANF field that could not be settled "
                                                + "(e.g. laterality, body_site, result)"),
                                        "question", strProp("the question to put to the author")),
                                "required", List.of("field", "question"))));
    }

    /** A measure schema: an interval with a grounded measure-semantic slot. */
    private static Map<String, Object> measureSchema(String description) {
        return obj(
                "type", "object",
                "description", description,
                "properties", obj(
                        "lower_bound", obj("type", "number"),
                        "upper_bound", obj("type", "number"),
                        "include_lower_bound", obj("type", "boolean"),
                        "include_upper_bound", obj("type", "boolean"),
                        "measure_semantic", slotSchema("The grounded unit or scale concept that makes the "
                                + "value meaningful. For a presence/absence result this is the 'Presence "
                                + "(property) (qualifier value)' concept (a qualifier value, NOT a unit such as "
                                + "percent, mg/dL, or a count).")),
                "required", List.of("measure_semantic"));
    }

    private static final Map<String, Object> SCHEMA = obj(
            "type", "object",
            "properties", obj(
                    "statement_type", obj(
                            "type", "string",
                            "enum", List.of("performance", "request", "narrative"),
                            "description", "Direction of fit. Fill the fields for this kind: performance uses "
                                    + "status/result/health_risk/normal_range/body_site/method/laterality; request "
                                    + "uses priority/requested_result/repetition/conditional_trigger/method; "
                                    + "narrative uses text. timing and purpose apply to any kind. Do not mix "
                                    + "fields across kinds."),
                    "topic", slotSchema("The clinical topic — exactly one pre-coordinated concept, with no "
                            + "polarity (that is the result), no subject (that is subject_of_information), and no "
                            + "timing or status (those are separate fields). Never compose a focus plus "
                            + "role-fillers."),
                    "subject_of_information", slotSchema("Optional. Who or what the statement is about when "
                            + "that is not the subject of record — a family-member relationship (family history), "
                            + "a fetus, a donor organ. Omit for the subject of record."),
                    "timing", measureSchema("Optional (any kind). When the circumstance occurred or is "
                            + "requested."),
                    "purpose", obj("type", "array",
                            "description", "Optional (any kind). Why it was done or requested.",
                            "items", slotSchema("A purpose concept.")),
                    "status", slotSchema("Performance. The status of the action or determination (e.g. "
                            + "performed, final, amended)."),
                    "result", measureSchema("Performance. What was determined. Presence is [1, no upper bound] "
                            + "on the Presence scale; absence is [0,0]."),
                    "health_risk", slotSchema("Performance. The clinical-risk classification of the result "
                            + "(e.g. low, high, critically high)."),
                    "normal_range", measureSchema("Performance. The reference range of normal values for the "
                            + "determination."),
                    "body_site", slotSchema("Performance. The anatomical location."),
                    "method", slotSchema("Performance or request. How the determination is made or the "
                            + "request carried out."),
                    "laterality", slotSchema("Performance. Left, right, or bilateral."),
                    "priority", slotSchema("Request. routine / urgent / stat."),
                    "requested_result", measureSchema("Request. The result sought."),
                    "repetition", obj("type", "object",
                            "description", "Request. A frequency/duration schedule.",
                            "properties", obj(
                                    "period_start", measureSchema("When the schedule begins."),
                                    "period_duration", measureSchema("How long the schedule runs."),
                                    "event_separation", measureSchema("Gap between successive events."),
                                    "event_duration", measureSchema("How long each event lasts."),
                                    "event_frequency", measureSchema("How often events occur."))),
                    "conditional_trigger", obj("type", "array",
                            "description", "Request. Conditions for execution, each a concept.",
                            "items", slotSchema("A condition concept.")),
                    "text", strProp("Narrative. The unstructured content.")),
            "required", List.of("statement_type"));
}
