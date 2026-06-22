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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The forced-decision tool that ends a narrative lift: {@code emit_anf}. Modelled on
 * the Claude check area's {@code report_result}, it makes the model deliver a
 * <em>structured</em> {@link AnfStatement} into an {@link AtomicReference} sink
 * rather than prose, and it re-validates every concept identifier against the live
 * store before accepting the statement. An identifier that does not resolve is
 * rejected back to the model — so "no hallucinated codes" is enforced in code, not
 * merely requested in the prompt.
 *
 * <p>This v1 (Demo&nbsp;0) accepts grounded concepts only: every {@code *_concept_id}
 * must resolve. The {@link AnfSlot} model is already three-way, so candidate and
 * clarify slots are an additive change to this tool, not a redesign.
 */
public final class AnfTools {

    private AnfTools() {
    }

    /**
     * Creates the {@code emit_anf} tool.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator} (the lift
     *                     window's view); must not be null
     * @param sink         receives the validated statement when the model emits one;
     *                     must not be null
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitAnf(Supplier<ViewCalculator> viewSupplier,
                                        AtomicReference<AnfStatement> sink) {
        return emitAnf(viewSupplier, sink, slot -> {
        });
    }

    /**
     * Creates the {@code emit_anf} tool that also reports each slot it grounds.
     *
     * @param viewSupplier supplies the current {@link ViewCalculator}; must not be null
     * @param sink         receives the validated statement when the model emits one;
     *                     must not be null
     * @param onDiscovered receives each grounded slot as the statement validates, for a
     *                     live inventory; a null callback is treated as a no-op
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitAnf(Supplier<ViewCalculator> viewSupplier,
                                        AtomicReference<AnfStatement> sink,
                                        Consumer<AnfSlot> onDiscovered) {
        Objects.requireNonNull(viewSupplier, "viewSupplier");
        Objects.requireNonNull(sink, "sink");
        return new EmitAnfTool(viewSupplier, sink, (onDiscovered == null) ? slot -> {
        } : onDiscovered);
    }

    /** The {@code emit_anf} tool implementation. */
    private record EmitAnfTool(Supplier<ViewCalculator> viewSupplier, AtomicReference<AnfStatement> sink,
                               Consumer<AnfSlot> onDiscovered)
            implements AnthropicTool {

        @Override
        public String name() {
            return "emit_anf";
        }

        @Override
        public String description() {
            return "Emit the clinical statement lifted into Analysis Normal Form. Call exactly once, "
                    + "after grounding every clinical term with the search and concept tools. Every "
                    + "*_concept_id must be an identifier those tools returned for a real concept; an "
                    + "identifier that does not resolve is rejected, and you must search for the correct "
                    + "one and call emit_anf again rather than inventing a code. Reference EXACTLY ONE "
                    + "pre-coordinated concept per slot — never compose a focus plus role-fillers (IKE "
                    + "does not post-coordinate). Put who the statement is about, when not the patient, in "
                    + "subject_of_information_concept_id; polarity in the result; status/timing as "
                    + "modifiers. Presence is the result interval [1, no upper bound] on the "
                    + "'Presence (property) (qualifier value)' concept (a qualifier value, NOT a unit) "
                    + "and absence is [0,0] — never a negated concept and never a unit of measure. A "
                    + "finding asserted with no measured value is a presence result.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return SCHEMA;
        }

        @Override
        public String execute(Map<String, Object> input) {
            ViewCalculator v = view();
            if (v == null) {
                return "No active knowledge-base view is available to ground the statement.";
            }
            AnfStatement.Type type = parseType(str(input, "statement_type"));
            if (type == null) {
                return "statement_type must be one of: performance, request, narrative.";
            }
            Map<String, Object> topicMap = map(input, "topic");
            if (topicMap == null) {
                return "topic is required.";
            }

            List<String> unresolved = new ArrayList<>();
            AnfSlot topic = ground(str(topicMap, "topic_concept_id"), v, unresolved, onDiscovered());

            AnfSlot subjectOfInformation = null;
            String soiId = str(input, "subject_of_information_concept_id");
            if (soiId != null && !soiId.isBlank()) {
                subjectOfInformation = ground(soiId, v, unresolved, onDiscovered());
            }

            AnfStatement.Result result = null;
            Map<String, Object> resultMap = map(input, "result");
            if (resultMap != null) {
                AnfSlot measure = ground(str(resultMap, "measure_semantic_concept_id"), v, unresolved, onDiscovered());
                if (measure != null) {
                    result = new AnfStatement.Result(
                            num(resultMap, "lower_bound"),
                            num(resultMap, "upper_bound"),
                            bool(resultMap, "include_lower_bound", true),
                            bool(resultMap, "include_upper_bound", true),
                            measure);
                }
            }

            AnfSlot status = null;
            String statusId = str(input, "status_concept_id");
            if (statusId != null && !statusId.isBlank()) {
                status = ground(statusId, v, unresolved, onDiscovered());
            }

            if (!unresolved.isEmpty()) {
                return "These identifiers did not resolve to a concept in the knowledge base: "
                        + String.join(", ", new LinkedHashSet<>(unresolved))
                        + ". Search for the correct concepts and call emit_anf again — do not invent codes.";
            }
            if (topic == null) {
                return "topic.topic_concept_id is required and must resolve to a single concept.";
            }

            AnfStatement statement = new AnfStatement(
                    type,
                    topic,
                    subjectOfInformation,
                    result,
                    status,
                    List.of(),
                    List.of(),
                    str(input, "narrative"));
            sink.set(statement);
            return "recorded";
        }

        private ViewCalculator view() {
            try {
                return viewSupplier.get();
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    // ── Grounding ───────────────────────────────────────────────────────────

    /**
     * Grounds an identifier to a {@link AnfSlot.Grounded}; on failure records it in
     * {@code unresolved} and returns null (so the caller rejects the emit).
     */
    private static AnfSlot ground(String id, ViewCalculator v, List<String> unresolved,
                                  Consumer<AnfSlot> onDiscovered) {
        if (id == null || id.isBlank()) {
            return null;
        }
        Optional<AnfSlot.Grounded> grounded = GraphTools.resolveConcept(id, v);
        if (grounded.isEmpty()) {
            unresolved.add(id);
            return null;
        }
        AnfSlot.Grounded slot = grounded.get();
        if (onDiscovered != null) {
            try {
                onDiscovered.accept(slot);
            } catch (RuntimeException ignored) {
                // a UI callback must never break grounding
            }
        }
        return slot;
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

    // ── Map accessors (the tool input arrives as a parsed JSON map) ──────────

    private static String str(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        if (o instanceof String s) {
            return s;
        }
        return o == null ? null : o.toString();
    }

    private static Map<String, Object> map(Map<String, Object> m, String k) {
        return asMap(m == null ? null : m.get(k));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Double num(Map<String, Object> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Boolean b ? b : def;
    }

    // ── Input schema ─────────────────────────────────────────────────────────

    private static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "statement_type", Map.of(
                            "type", "string",
                            "enum", List.of("performance", "request", "narrative"),
                            "description", "Direction of fit: performance (observed/done), request (ordered), or narrative."),
                    "topic", Map.of(
                            "type", "object",
                            "description", "The clinical topic — EXACTLY ONE pre-coordinated concept (its SCTID or UUID). One concept per slot: no polarity (that is the result), no subject (that is subject_of_information), no timing or status (those are modifiers). Never compose a focus plus role-fillers — IKE does not post-coordinate; if no single concept fits, search more thoroughly rather than assembling one.",
                            "properties", Map.of(
                                    "topic_concept_id", Map.of("type", "string",
                                            "description", "SCTID or UUID of the single pre-coordinated topic concept.")),
                            "required", List.of("topic_concept_id")),
                    "subject_of_information_concept_id", Map.of("type", "string",
                            "description", "Optional. SCTID or UUID of the ONE concept naming who or what the statement is about when that is not the patient — e.g. a family-member relationship concept (family history), a fetus, or a donor organ. Omit for the patient (self)."),
                    "result", Map.of(
                            "type", "object",
                            "description", "The measured or requested result as an interval with a grounded measure semantic. Presence is [1, no upper]; absence is [0,0].",
                            "properties", Map.of(
                                    "lower_bound", Map.of("type", "number"),
                                    "upper_bound", Map.of("type", "number"),
                                    "include_lower_bound", Map.of("type", "boolean"),
                                    "include_upper_bound", Map.of("type", "boolean"),
                                    "measure_semantic_concept_id", Map.of("type", "string",
                                            "description", "SCTID or UUID of the grounded unit or scale concept. For a presence/absence result this is the 'Presence (property) (qualifier value)' concept (a qualifier value) — NEVER a unit of measure such as percent, mg/dL, or a count.")),
                            "required", List.of("measure_semantic_concept_id")),
                    "status_concept_id", Map.of("type", "string",
                            "description", "Optional SCTID or UUID of the status concept (e.g. final, preliminary, amended)."),
                    "narrative", Map.of("type", "string",
                            "description", "Unstructured text; use only for a narrative statement.")),
            "required", List.of("statement_type", "topic"));
}
