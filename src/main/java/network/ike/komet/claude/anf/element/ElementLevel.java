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
package network.ike.komet.claude.anf.element;

/**
 * How much of the two-step lift a note shows ({@code dev-anf-element-isolation}, "What the reader
 * sees"): one switch over the note. Each level adds to the one before: the narrative with its
 * spans underlined, then each element's sentence and state, then each element's ANF statement.
 */
public enum ElementLevel {
    /** The note alone, its element spans underlined and its uncovered text shaded. */
    NARRATIVE("Narrative", "The note, its spans underlined in each element's colour"),
    /** The note and its elements: each row the quoted span, the element sentence and its state. */
    ELEMENTS("Elements", "Each element's sentence and state beneath the note"),
    /** The note, its elements and their ANF: each row adds the statement's topic and result. */
    ANF("ANF", "Each element's ANF statement; a click unfolds the editable tree");

    private final String label;
    private final String description;

    ElementLevel(String label, String description) {
        this.label = label;
        this.description = description;
    }

    /** The switch segment's text. */
    public String label() {
        return label;
    }

    /** The switch segment's tooltip. */
    public String description() {
        return description;
    }

    /** Whether this level shows what {@code other} shows: the levels are cumulative. */
    public boolean shows(ElementLevel other) {
        return compareTo(other) >= 0;
    }
}
