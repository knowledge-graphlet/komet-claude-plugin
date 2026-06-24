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
import java.util.stream.Collectors;

/**
 * One semantic lifted for a pattern: the bound pattern's identity together with the value filled
 * into each field the request supported. It is the generalization of the narrative lift's
 * validated ANF statement — an immutable, typed kernel a renderer (or a later store write) can
 * project from — but its shape is the bound pattern's field definitions rather than a fixed ANF
 * envelope.
 *
 * <p>Only fields the request spoke to carry a value; a field left untouched simply has no entry.
 * Every component reference in the instance was validated against the live store when the
 * {@code emit_semantic} tool accepted it, so a grounded slot is always a real, active component.
 *
 * @param patternNid   the bound pattern's native identifier
 * @param patternLabel the bound pattern's display label
 * @param fields       the filled field values, in pattern field order; never null (copied)
 */
public record SemanticInstance(int patternNid, String patternLabel, List<FieldValue> fields) {

    /**
     * Validates and defensively copies the instance.
     *
     * @throws NullPointerException if {@code patternLabel} or {@code fields} is null
     */
    public SemanticInstance {
        Objects.requireNonNull(patternLabel, "patternLabel");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    /**
     * One field's filled value, tagged with the field's pattern index and meaning label so the
     * instance is self-describing without the pattern in hand.
     *
     * @param index        the field's zero-based index in the pattern
     * @param meaningLabel the field's meaning label
     * @param value        the value filled into the field; never null
     */
    public record FieldValue(int index, String meaningLabel, SemanticFieldValue value) {
        /**
         * Validates the field value.
         *
         * @throws NullPointerException if {@code meaningLabel} or {@code value} is null
         */
        public FieldValue {
            Objects.requireNonNull(meaningLabel, "meaningLabel");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Whether every filled field is fully grounded — no candidate, clarify, or deferred gap
     * anywhere in the instance. A {@code true} result is the boundary a later phase can promote
     * from "render only" to "write a semantic".
     *
     * @return true if no field carries a gap
     */
    public boolean fullyGrounded() {
        return fields.stream().allMatch(f -> f.value().grounded());
    }

    /**
     * A compact, human-readable projection of the instance for logging and tests: the pattern
     * label followed by one line per filled field. This is the headless renderer; the JavaFX card
     * is a later phase.
     *
     * @return a multi-line description (never null)
     */
    public String describe() {
        return patternLabel + " (semantic)\n"
                + fields.stream().map(SemanticInstance::describeField).collect(Collectors.joining("\n"));
    }

    private static String describeField(FieldValue field) {
        return "  [" + field.index() + "] " + field.meaningLabel() + " = " + describeValue(field.value());
    }

    private static String describeValue(SemanticFieldValue value) {
        return switch (value) {
            case SemanticFieldValue.Component component -> describeSlot(component.slot());
            case SemanticFieldValue.Components components -> components.slots().stream()
                    .map(SemanticInstance::describeSlot).collect(Collectors.joining("; ", "[", "]"));
            case SemanticFieldValue.StringValue string -> "\"" + string.value() + "\"";
            case SemanticFieldValue.IntegerValue integer -> Long.toString(integer.value());
            case SemanticFieldValue.FloatValue real -> Double.toString(real.value());
            case SemanticFieldValue.BooleanValue bool -> Boolean.toString(bool.value());
            case SemanticFieldValue.Deferred deferred -> "(deferred: " + deferred.clarify().question() + ")";
        };
    }

    private static String describeSlot(ComponentSlot slot) {
        return switch (slot) {
            case ComponentSlot.Grounded grounded ->
                    grounded.label() + " [" + grounded.kind() + " " + grounded.identifier() + "]";
            case ComponentSlot.Candidate candidate -> "candidate: " + candidate.provisionalLabel();
            case ComponentSlot.Clarify clarify -> "clarify: " + clarify.question();
        };
    }
}
