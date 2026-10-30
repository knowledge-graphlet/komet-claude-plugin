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

/**
 * The value filled into one field of a lifted semantic, sealed by the field's datatype family.
 * A component-reference field carries a {@link Component} (or {@link Components} for a
 * collection) whose value is grounded, a candidate, or a clarify; a literal field carries the
 * corresponding scalar; a field whose datatype is not yet supported carries a {@link Deferred}
 * holding the clarify that explains the gap. The variant a field uses is fixed by its
 * {@link FieldFamily} — the value form the datatype dictates.
 */
public sealed interface SemanticFieldValue permits SemanticFieldValue.Component,
        SemanticFieldValue.Components, SemanticFieldValue.StringValue,
        SemanticFieldValue.IntegerValue, SemanticFieldValue.FloatValue,
        SemanticFieldValue.BooleanValue, SemanticFieldValue.Deferred {

    /**
     * Whether this value is fully grounded — for a component reference, its value is a
     * {@link ComponentValue.Grounded}; for a collection, every value is; for a literal, always true;
     * for a deferred value, never. Used to report whether a lifted instance is complete or still
     * carries gaps.
     *
     * @return true if the value contributes no candidate, clarify, or deferred gap
     */
    boolean grounded();

    /**
     * A single component reference.
     *
     * @param componentValue the grounded / candidate / clarify value; never null
     */
    record Component(ComponentValue componentValue) implements SemanticFieldValue {
        /**
         * Validates the component value.
         *
         * @throws NullPointerException if {@code componentValue} is null
         */
        public Component {
            Objects.requireNonNull(componentValue, "componentValue");
        }

        @Override
        public boolean grounded() {
            return componentValue instanceof ComponentValue.Grounded;
        }
    }

    /**
     * An ordered or unordered collection of component references (the set/list datatypes).
     *
     * @param componentValues the member values, in emit order; never null (defensively copied)
     */
    record Components(List<ComponentValue> componentValues) implements SemanticFieldValue {
        /**
         * Validates and defensively copies the collection value.
         *
         * @throws NullPointerException if {@code componentValues} is null
         */
        public Components {
            componentValues = List.copyOf(Objects.requireNonNull(componentValues, "componentValues"));
        }

        @Override
        public boolean grounded() {
            return componentValues.stream().allMatch(s -> s instanceof ComponentValue.Grounded);
        }
    }

    /**
     * A string literal.
     *
     * @param value the string; never null
     */
    record StringValue(String value) implements SemanticFieldValue {
        /**
         * Validates the string value.
         *
         * @throws NullPointerException if {@code value} is null
         */
        public StringValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean grounded() {
            return true;
        }
    }

    /**
     * An integral literal (covers the {@code INTEGER} and {@code LONG} datatypes).
     *
     * @param value the integral value
     */
    record IntegerValue(long value) implements SemanticFieldValue {
        @Override
        public boolean grounded() {
            return true;
        }
    }

    /**
     * A real literal (covers the {@code FLOAT} and {@code DECIMAL} datatypes).
     *
     * @param value the real value
     */
    record FloatValue(double value) implements SemanticFieldValue {
        @Override
        public boolean grounded() {
            return true;
        }
    }

    /**
     * A boolean literal.
     *
     * @param value the boolean value
     */
    record BooleanValue(boolean value) implements SemanticFieldValue {
        @Override
        public boolean grounded() {
            return true;
        }
    }

    /**
     * A placeholder for a field whose datatype is not supported in the first version (a structured
     * tree/graph, an instant, an image): the value is deferred and the clarify names the gap.
     *
     * @param clarify the clarify explaining why the field was not filled; never null
     */
    record Deferred(ComponentValue.Clarify clarify) implements SemanticFieldValue {
        /**
         * Validates the deferred value.
         *
         * @throws NullPointerException if {@code clarify} is null
         */
        public Deferred {
            Objects.requireNonNull(clarify, "clarify");
        }

        @Override
        public boolean grounded() {
            return false;
        }
    }
}
