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

/**
 * The handling family a pattern field's {@link FieldDataType} maps to — the projection that
 * decides how the field appears in the {@code emit_semantic} tool schema and how its value is
 * parsed. It collapses the 29-value {@code FieldDataType} enum into the handful of shapes the
 * semantic lift produces: component references (grounded), component collections, the literal
 * scalars, and the deferred structured values.
 *
 * <p>This is the load-bearing correction of the lift's design: the datatype, not an assumption
 * that every field names a concept, decides the value form. A component reference is grounded
 * against the store; a literal is a value and is never grounded; a structured value is deferred
 * to a later version and surfaced as a clarify.
 */
public enum FieldFamily {

    /** A field constrained to a concept reference ({@code CONCEPT_FIELD}). */
    CONCEPT_REF(ComponentSlot.Kind.CONCEPT),
    /** A field constrained to a semantic reference ({@code SEMANTIC_FIELD_TYPE}). */
    SEMANTIC_REF(ComponentSlot.Kind.SEMANTIC),
    /** A field constrained to a pattern reference (declared via a pattern datatype, if any). */
    PATTERN_REF(ComponentSlot.Kind.PATTERN),
    /** A generic component reference ({@code COMPONENT_FIELD}); any component kind is accepted. */
    COMPONENT_REF(null),
    /** An unordered set of component references ({@code COMPONENT_ID_SET_FIELD}). */
    COMPONENT_SET(null),
    /** An ordered list of component references ({@code COMPONENT_ID_LIST_FIELD}). */
    COMPONENT_LIST(null),
    /** A string literal. */
    STRING(null),
    /** An integral literal ({@code INTEGER} or {@code LONG}). */
    INTEGER(null),
    /** A real literal ({@code FLOAT} or {@code DECIMAL}). */
    FLOAT(null),
    /** A boolean literal. */
    BOOLEAN(null),
    /** A structured or otherwise unsupported value (tree, graph, instant, image …) — deferred. */
    DEFERRED(null);

    private final ComponentSlot.Kind requiredKind;

    FieldFamily(ComponentSlot.Kind requiredKind) {
        this.requiredKind = requiredKind;
    }

    /**
     * The component kind a grounded reference in this family must resolve to, or {@code null} when
     * the family accepts any component kind (the generic and collection families) or is not a
     * reference family at all.
     *
     * @return the required {@link ComponentSlot.Kind}, or {@code null} for "any / not applicable"
     */
    public ComponentSlot.Kind requiredKind() {
        return requiredKind;
    }

    /**
     * Whether this family is a single component reference (grounded against the store).
     *
     * @return true for {@link #CONCEPT_REF}, {@link #SEMANTIC_REF}, {@link #PATTERN_REF},
     *         {@link #COMPONENT_REF}
     */
    public boolean isComponentReference() {
        return this == CONCEPT_REF || this == SEMANTIC_REF || this == PATTERN_REF
                || this == COMPONENT_REF;
    }

    /**
     * Whether this family is a collection of component references.
     *
     * @return true for {@link #COMPONENT_SET}, {@link #COMPONENT_LIST}
     */
    public boolean isComponentCollection() {
        return this == COMPONENT_SET || this == COMPONENT_LIST;
    }

    /**
     * Whether this family is an ungrounded literal scalar.
     *
     * @return true for {@link #STRING}, {@link #INTEGER}, {@link #FLOAT}, {@link #BOOLEAN}
     */
    public boolean isLiteral() {
        return this == STRING || this == INTEGER || this == FLOAT || this == BOOLEAN;
    }

    /**
     * Maps a Tinkar {@link FieldDataType} to its handling family. Component-reference datatypes
     * (including the chronology/version variants) collapse to the matching reference family; the
     * generic {@code IDENTIFIED_THING} is the any-kind component reference; literal datatypes map
     * to their scalar; everything else (trees, graphs, points, instants, images, byte/object
     * arrays, stamps, field definitions) is {@link #DEFERRED}.
     *
     * @param dataType the field's data type; null yields {@link #DEFERRED}
     * @return the handling family (never null)
     */
    public static FieldFamily of(FieldDataType dataType) {
        if (dataType == null) {
            return DEFERRED;
        }
        return switch (dataType) {
            case CONCEPT, CONCEPT_VERSION, CONCEPT_CHRONOLOGY -> CONCEPT_REF;
            case SEMANTIC, SEMANTIC_VERSION, SEMANTIC_CHRONOLOGY -> SEMANTIC_REF;
            case PATTERN, PATTERN_VERSION, PATTERN_CHRONOLOGY -> PATTERN_REF;
            case IDENTIFIED_THING -> COMPONENT_REF;
            case COMPONENT_ID_SET -> COMPONENT_SET;
            case COMPONENT_ID_LIST -> COMPONENT_LIST;
            case STRING -> STRING;
            case INTEGER, LONG -> INTEGER;
            case FLOAT, DECIMAL -> FLOAT;
            case BOOLEAN -> BOOLEAN;
            default -> DEFERRED;
        };
    }
}
