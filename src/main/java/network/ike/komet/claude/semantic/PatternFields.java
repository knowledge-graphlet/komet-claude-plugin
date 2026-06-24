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

import dev.ikm.tinkar.component.FieldDataType;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.FieldDefinitionForEntity;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The field metadata of a bound pattern, snapshotted as the shape the semantic lift produces from.
 * Each field's {@code (meaning, purpose, datatype)} triple — read from the pattern's
 * {@link PatternEntityVersion#fieldDefinitions()} — drives both the {@code emit_semantic} tool
 * schema and the {@linkplain #digest() field digest} the model reasons over, so the two cannot
 * disagree. This is the heart of "pattern-focused": no per-pattern code, just the projection of
 * the pattern's own field definitions.
 *
 * @param patternNid   the bound pattern's native identifier
 * @param patternLabel the bound pattern's display label
 * @param fields       the field metadata, in pattern field order; never null (copied)
 */
public record PatternFields(int patternNid, String patternLabel, List<FieldMeta> fields) {

    /**
     * Validates and defensively copies the field metadata.
     *
     * @throws NullPointerException if {@code patternLabel} or {@code fields} is null
     */
    public PatternFields {
        Objects.requireNonNull(patternLabel, "patternLabel");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
    }

    /**
     * One field's metadata: its position, the JSON property key it is filled under, its meaning
     * and purpose labels, and its declared datatype. The {@linkplain #family() handling family} is
     * derived from the datatype.
     *
     * @param index        the field's zero-based index in the pattern
     * @param propertyName the unique JSON property key the model fills this field under
     * @param meaningLabel the field's meaning label (what the field is)
     * @param purposeLabel the field's purpose label (the role it plays)
     * @param dataType     the field's declared datatype; may be null (treated as deferred)
     */
    public record FieldMeta(int index, String propertyName, String meaningLabel, String purposeLabel,
                            FieldDataType dataType) {
        /**
         * Validates the field metadata.
         *
         * @throws NullPointerException if {@code propertyName}, {@code meaningLabel}, or
         *                              {@code purposeLabel} is null
         */
        public FieldMeta {
            Objects.requireNonNull(propertyName, "propertyName");
            Objects.requireNonNull(meaningLabel, "meaningLabel");
            Objects.requireNonNull(purposeLabel, "purposeLabel");
        }

        /**
         * The handling family this field's datatype maps to.
         *
         * @return the {@link FieldFamily} (never null; a null datatype yields
         *         {@link FieldFamily#DEFERRED})
         */
        public FieldFamily family() {
            return FieldFamily.of(dataType);
        }
    }

    /**
     * Builds the field metadata from a pattern version, resolving meaning/purpose/pattern labels
     * through the given view and assigning a unique JSON property key per field. A field whose
     * datatype cannot be read is captured with a null datatype (a deferred field) rather than
     * failing the whole lift.
     *
     * @param patternNid the bound pattern's nid
     * @param version    the latest pattern version; must not be null
     * @param view       the view that resolves labels; must not be null
     * @return the field metadata snapshot (never null)
     */
    public static PatternFields from(int patternNid, PatternEntityVersion version, ViewCalculator view) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(view, "view");
        String patternLabel = label(view, patternNid);
        ImmutableList<? extends FieldDefinitionForEntity> definitions = version.fieldDefinitions();
        List<FieldMeta> metas = new ArrayList<>(definitions.size());
        Set<String> usedKeys = new HashSet<>();
        for (int i = 0; i < definitions.size(); i++) {
            FieldDefinitionForEntity definition = definitions.get(i);
            String meaningLabel = label(view, definition.meaningNid());
            String purposeLabel = label(view, definition.purposeNid());
            FieldDataType dataType = safeDataType(definition);
            String propertyName = uniqueKey(meaningLabel, i, usedKeys);
            metas.add(new FieldMeta(i, propertyName, meaningLabel, purposeLabel, dataType));
        }
        return new PatternFields(patternNid, patternLabel, metas);
    }

    /**
     * Renders the field digest injected into the lift's system prompt: one line per field, stating
     * the property key the field is filled under and the field's meaning, purpose, datatype, and
     * allowed value form. The digest mirrors the {@code emit_semantic} schema exactly.
     *
     * @return the field digest (never null)
     */
    public String digest() {
        StringBuilder sb = new StringBuilder("## Active pattern: ").append(patternLabel).append('\n');
        sb.append("Fill these fields with emit_semantic (omit a field the request does not speak to):\n");
        for (FieldMeta field : fields) {
            sb.append("- \"").append(field.propertyName()).append("\" — meaning: ")
                    .append(field.meaningLabel()).append("; purpose: ").append(field.purposeLabel())
                    .append("; datatype: ").append(field.dataType() == null ? "(unknown)" : field.dataType())
                    .append(" → ").append(valueForm(field.family())).append('\n');
        }
        return sb.toString();
    }

    /** A short human description of the value form a family expects, for the digest. */
    private static String valueForm(FieldFamily family) {
        return switch (family) {
            case CONCEPT_REF -> "one grounded concept";
            case SEMANTIC_REF -> "one grounded semantic";
            case PATTERN_REF -> "one grounded pattern";
            case COMPONENT_REF -> "one grounded component (concept, semantic, or pattern)";
            case COMPONENT_SET -> "a set of grounded components";
            case COMPONENT_LIST -> "an ordered list of grounded components";
            case STRING -> "a string literal";
            case INTEGER -> "an integer literal";
            case FLOAT -> "a numeric literal";
            case BOOLEAN -> "a boolean literal";
            case DEFERRED -> "a structured value — not supported in v1; leave empty";
        };
    }

    private static String label(ViewCalculator view, int nid) {
        return view.getFullyQualifiedNameText(nid)
                .orElseGet(() -> view.getPreferredDescriptionTextWithFallbackOrNid(nid));
    }

    /** Reads a field's datatype, treating an unreadable datatype as a deferred (null) field. */
    private static FieldDataType safeDataType(FieldDefinitionForEntity definition) {
        try {
            return definition.fieldDataType();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Derives a unique, JSON-safe property key from a field's meaning label, falling back to a
     * positional key on collision so two fields with the same meaning label stay distinct.
     */
    private static String uniqueKey(String meaningLabel, int index, Set<String> used) {
        String base = slug(meaningLabel);
        if (used.add(base)) {
            return base;
        }
        String positional = base + "_" + index;
        used.add(positional);
        return positional;
    }

    /** Lowercases and reduces a label to {@code [a-z0-9_]}, collapsing runs and trimming. */
    private static String slug(String label) {
        String lowered = label.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String trimmed = sb.toString().replaceAll("^_+|_+$", "");
        return trimmed.isEmpty() ? "field" : trimmed;
    }
}
