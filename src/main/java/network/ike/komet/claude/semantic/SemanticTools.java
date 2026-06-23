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

import network.ike.komet.claude.anthropic.AnthropicTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The forced-decision tools that drive a semantic lift: {@code emit_semantic} (one validated
 * {@link SemanticInstance} per call, its schema built from the bound pattern's
 * {@link PatternFields}) and {@code finish_semantic} (a no-argument completion signal).
 *
 * <p>{@code emit_semantic} is the generalization of the narrative lift's {@code emit_anf}: rather
 * than a hand-written ANF schema, it builds one property per pattern field, branching by the
 * field's {@link FieldFamily} — component references get the three-way grounded/candidate/clarify
 * slot, collections an array of slots, literals a typed scalar, and structured datatypes a
 * clarify-only placeholder (deferred in v1). Every component reference is grounded through the
 * injected {@link Grounder}, which validates existence, active status, and the field's required
 * kind; an id that does not resolve (or resolves to the wrong kind) is rejected back to the model.
 * So "no hallucinated identifiers" is enforced in code, not merely requested in the prompt.
 */
public final class SemanticTools {

    private SemanticTools() {
    }

    /**
     * Receives a validated semantic from {@code emit_semantic}.
     */
    @FunctionalInterface
    public interface InstanceSink {
        /**
         * Records a validated semantic instance.
         *
         * @param instance the instance to record
         * @return {@code true} if newly recorded; {@code false} if an equal instance already was
         */
        boolean record(SemanticInstance instance);
    }

    /**
     * Creates the {@code emit_semantic} tool for a bound pattern.
     *
     * @param fields   the bound pattern's field metadata; must not be null
     * @param grounder grounds each component reference (existence + active + kind); must not be null
     * @param sink     receives each validated instance; must not be null
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitSemantic(PatternFields fields, Grounder grounder, InstanceSink sink) {
        return emitSemantic(fields, grounder, sink, slot -> {
        });
    }

    /**
     * Creates the {@code emit_semantic} tool that also reports each slot it grounds, proposes, or
     * surfaces, for a live inventory.
     *
     * @param fields       the bound pattern's field metadata; must not be null
     * @param grounder     grounds each component reference; must not be null
     * @param sink         receives each validated instance; must not be null
     * @param onDiscovered receives each slot as an instance validates; a null callback is a no-op
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool emitSemantic(PatternFields fields, Grounder grounder, InstanceSink sink,
                                             Consumer<ComponentSlot> onDiscovered) {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(grounder, "grounder");
        Objects.requireNonNull(sink, "sink");
        Consumer<ComponentSlot> discovered = (onDiscovered == null) ? slot -> {
        } : onDiscovered;
        return new EmitSemanticTool(fields, buildSchema(fields), grounder, sink, discovered);
    }

    /**
     * Creates the {@code finish_semantic} tool — a no-argument signal the model calls once when it
     * has emitted every semantic, so the lift can end without paying for a trailing model turn.
     *
     * @param onFinish run when the model signals completion; must not be null
     * @return the tool to add to the lift's tool list
     */
    public static AnthropicTool finishLift(Runnable onFinish) {
        Objects.requireNonNull(onFinish, "onFinish");
        return new FinishLiftTool(onFinish);
    }

    // ── finish_semantic ───────────────────────────────────────────────────

    private record FinishLiftTool(Runnable onFinish) implements AnthropicTool {

        @Override
        public String name() {
            return "finish_semantic";
        }

        @Override
        public String description() {
            return "Call this ONCE, with no arguments, after you have emitted every semantic for the "
                    + "request with emit_semantic. It signals that the lift is complete so the result can be "
                    + "shown immediately. Do not call it before emitting, and send no further message after.";
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

    // ── emit_semantic ───────────────────────────────────────────────────────

    private record EmitSemanticTool(PatternFields fields, Map<String, Object> schema, Grounder grounder,
                                    InstanceSink sink, Consumer<ComponentSlot> onDiscovered)
            implements AnthropicTool {

        @Override
        public String name() {
            return "emit_semantic";
        }

        @Override
        public String description() {
            return "Emit ONE semantic for the active pattern. Fill each field under its property key (see "
                    + "the field digest): a component-reference field takes EXACTLY ONE of grounded_component_id "
                    + "(an identifier the search/concept tools returned), candidate (a clear meaning the store "
                    + "does not yet carry), or clarify (an ambiguity to surface as a question); a collection "
                    + "field takes an array of those; a literal field takes a value of its declared type. The "
                    + "referent of a component field must be an EXISTING component of the field's allowed kind — "
                    + "never invent an identifier; one that does not resolve, or resolves to the wrong kind, is "
                    + "rejected back to you. Omit a field the request does not speak to. When you have emitted "
                    + "every semantic, call finish_semantic once and send no further message.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema;
        }

        @Override
        public String execute(Map<String, Object> input) {
            Map<String, Object> in = (input == null) ? Map.of() : input;
            List<String> unresolved = new ArrayList<>();
            List<ComponentSlot> discovered = new ArrayList<>();
            List<SemanticInstance.FieldValue> values = new ArrayList<>();

            for (PatternFields.FieldMeta field : fields.fields()) {
                Object raw = in.get(field.propertyName());
                if (raw == null) {
                    continue;
                }
                SemanticFieldValue value = parseField(field, raw, unresolved, discovered);
                if (value != null) {
                    values.add(new SemanticInstance.FieldValue(field.index(), field.meaningLabel(), value));
                }
            }

            if (!unresolved.isEmpty()) {
                return "These identifiers did not resolve to an existing, active component of the field's "
                        + "required kind: " + String.join(", ", new LinkedHashSet<>(unresolved))
                        + ". Search for the correct component and call emit_semantic again — do not invent "
                        + "identifiers. If a meaning is genuinely missing, emit that field as a candidate.";
            }

            SemanticInstance instance =
                    new SemanticInstance(fields.patternNid(), fields.patternLabel(), values);
            boolean recorded = sink.record(instance);
            if (recorded) {
                for (ComponentSlot slot : discovered) {
                    fireDiscovered(slot);
                }
                return "recorded";
            }
            return "This semantic was already recorded; do not emit it again.";
        }

        /** Parses one field's value by its datatype family; null when the field carries nothing usable. */
        private SemanticFieldValue parseField(PatternFields.FieldMeta field, Object raw,
                                              List<String> unresolved, List<ComponentSlot> discovered) {
            FieldFamily family = field.family();
            if (family.isComponentReference()) {
                ComponentSlot slot = parseSlot(asMap(raw), family.requiredKind(), field.meaningLabel(),
                        unresolved, discovered);
                return (slot == null) ? null : new SemanticFieldValue.Component(slot);
            }
            if (family.isComponentCollection()) {
                List<Object> items = asList(raw);
                if (items == null) {
                    return null;
                }
                List<ComponentSlot> slots = new ArrayList<>();
                for (Object item : items) {
                    Map<String, Object> itemMap = asMap(item);
                    if (itemMap != null) {
                        ComponentSlot slot = parseSlot(itemMap, null, field.meaningLabel(),
                                unresolved, discovered);
                        if (slot != null) {
                            slots.add(slot);
                        }
                    }
                }
                return slots.isEmpty() ? null : new SemanticFieldValue.Components(slots);
            }
            return switch (family) {
                case STRING -> new SemanticFieldValue.StringValue(raw.toString());
                case INTEGER -> {
                    Long value = asLong(raw);
                    yield (value == null) ? null : new SemanticFieldValue.IntegerValue(value);
                }
                case FLOAT -> {
                    Double value = asDouble(raw);
                    yield (value == null) ? null : new SemanticFieldValue.FloatValue(value);
                }
                case BOOLEAN -> {
                    Boolean value = asBoolean(raw);
                    yield (value == null) ? null : new SemanticFieldValue.BooleanValue(value);
                }
                default -> parseDeferred(asMap(raw), field.meaningLabel(), field.dataType());
            };
        }

        /** Parses a component slot: exactly one of grounded_component_id, candidate, or clarify. */
        private ComponentSlot parseSlot(Map<String, Object> slotMap, ComponentSlot.Kind requiredKind,
                                        String fieldLabel, List<String> unresolved,
                                        List<ComponentSlot> discovered) {
            if (slotMap == null) {
                return null;
            }
            String groundedId = str(slotMap, "grounded_component_id");
            if (groundedId != null && !groundedId.isBlank()) {
                Optional<ComponentSlot.Grounded> grounded = grounder.ground(groundedId, requiredKind);
                if (grounded.isPresent()) {
                    discovered.add(grounded.get());
                    return grounded.get();
                }
                unresolved.add(groundedId);
                return null;
            }
            Map<String, Object> candidateMap = asMap(slotMap.get("candidate"));
            if (candidateMap != null) {
                String label = str(candidateMap, "provisional_label");
                if (label == null || label.isBlank()) {
                    return null;
                }
                String text = str(candidateMap, "text");
                ComponentSlot.Candidate candidate = new ComponentSlot.Candidate(
                        (text == null || text.isBlank()) ? label : text, label,
                        strList(candidateMap.get("nearest_match_ids")));
                discovered.add(candidate);
                return candidate;
            }
            ComponentSlot.Clarify clarify = parseClarify(asMap(slotMap.get("clarify")), fieldLabel);
            if (clarify != null) {
                discovered.add(clarify);
            }
            return clarify;
        }

        /** A clarify-only value for a structured (deferred) field, or null when not supplied. */
        private SemanticFieldValue parseDeferred(Map<String, Object> slotMap, String fieldLabel,
                                                 Object dataType) {
            if (slotMap == null) {
                return null;
            }
            ComponentSlot.Clarify clarify = parseClarify(asMap(slotMap.get("clarify")), fieldLabel);
            if (clarify == null) {
                // Default the gap explanation when the model filled the field without a clarify.
                clarify = new ComponentSlot.Clarify(fieldLabel,
                        "datatype " + dataType + " is not supported in v1; left for a later phase");
            }
            return new SemanticFieldValue.Deferred(clarify);
        }

        private static ComponentSlot.Clarify parseClarify(Map<String, Object> clarifyMap, String fieldLabel) {
            if (clarifyMap == null) {
                return null;
            }
            String question = str(clarifyMap, "question");
            if (question == null || question.isBlank()) {
                return null;
            }
            return new ComponentSlot.Clarify(fieldLabel, question);
        }

        private void fireDiscovered(ComponentSlot slot) {
            try {
                onDiscovered.accept(slot);
            } catch (RuntimeException ignored) {
                // a UI callback must never break the tool loop
            }
        }
    }

    // ── Schema construction (insertion-ordered for a byte-stable, cacheable tool prefix) ──

    /** Builds the {@code emit_semantic} input schema: one property per pattern field, by family. */
    private static Map<String, Object> buildSchema(PatternFields fields) {
        LinkedHashMap<String, Object> properties = new LinkedHashMap<>();
        for (PatternFields.FieldMeta field : fields.fields()) {
            properties.put(field.propertyName(), fieldSchema(field));
        }
        return obj("type", "object", "properties", properties, "required", List.of());
    }

    /** The schema fragment for one field, chosen by its datatype family. */
    private static Map<String, Object> fieldSchema(PatternFields.FieldMeta field) {
        String prefix = field.meaningLabel() + " — " + field.purposeLabel() + ". ";
        FieldFamily family = field.family();
        if (family.isComponentReference()) {
            return slotSchema(prefix + kindHint(family));
        }
        if (family.isComponentCollection()) {
            return obj("type", "array",
                    "description", prefix + "An array of component references; each member must be an "
                            + "existing component.",
                    "items", slotSchema("A member component reference."));
        }
        return switch (family) {
            case STRING -> obj("type", "string", "description", prefix + "A string value.");
            case INTEGER -> obj("type", "integer", "description", prefix + "An integer value.");
            case FLOAT -> obj("type", "number", "description", prefix + "A numeric value.");
            case BOOLEAN -> obj("type", "boolean", "description", prefix + "A boolean value.");
            default -> deferredSchema(prefix);
        };
    }

    /** A three-way concept-slot schema: exactly one of grounded_component_id, candidate, or clarify. */
    private static Map<String, Object> slotSchema(String description) {
        return obj(
                "type", "object",
                "description", description + " Supply EXACTLY ONE of: grounded_component_id (an SCTID or "
                        + "UUID the tools returned for an existing component); candidate (a clear meaning the "
                        + "knowledge base does not yet carry); or clarify (an ambiguity to surface as a "
                        + "question). Never put a code in a candidate.",
                "properties", obj(
                        "grounded_component_id", strProp("SCTID or UUID of an existing component."),
                        "candidate", obj(
                                "type", "object",
                                "description", "A clear meaning with no existing component — a terminology gap.",
                                "properties", obj(
                                        "text", strProp("the phrase the meaning came from"),
                                        "provisional_label", strProp("a human label for the proposed component "
                                                + "(a name, never a code)"),
                                        "nearest_match_ids", obj("type", "array",
                                                "description", "SCTIDs/UUIDs of the nearest existing components",
                                                "items", strProp("an SCTID or UUID"))),
                                "required", List.of("provisional_label")),
                        "clarify", obj(
                                "type", "object",
                                "description", "An ambiguity the author must resolve.",
                                "properties", obj("question", strProp("the question to put to the author")),
                                "required", List.of("question"))));
    }

    /** A clarify-only schema for a structured (deferred) field, not editable in v1. */
    private static Map<String, Object> deferredSchema(String description) {
        return obj(
                "type", "object",
                "description", description + "A structured datatype not supported in v1 — leave empty, or "
                        + "provide a clarify if the request implies it.",
                "properties", obj(
                        "clarify", obj(
                                "type", "object",
                                "properties", obj("question", strProp("why this field is left unfilled")),
                                "required", List.of("question"))));
    }

    private static String kindHint(FieldFamily family) {
        return switch (family) {
            case CONCEPT_REF -> "The referent must be an existing concept.";
            case SEMANTIC_REF -> "The referent must be an existing semantic.";
            case PATTERN_REF -> "The referent must be an existing pattern.";
            default -> "The referent may be any existing component (concept, semantic, or pattern).";
        };
    }

    // ── Map / schema helpers ──────────────────────────────────────────────

    /** Builds an insertion-ordered map so the serialized schema is byte-stable across lifts. */
    private static Map<String, Object> obj(Object... keyValues) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = (map == null) ? null : map.get(key);
        if (value instanceof String s) {
            return s;
        }
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static List<String> strList(Object value) {
        List<Object> raw = asList(value);
        if (raw == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null) {
                out.add(item.toString());
            }
        }
        return out;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("true")) {
                return Boolean.TRUE;
            }
            if (t.equals("false")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }
}
