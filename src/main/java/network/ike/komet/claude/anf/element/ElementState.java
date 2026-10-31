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

/** Where a clinical element stands in the two-step lift ({@code dev-anf-element-isolation}). */
public enum ElementState {
    /** Isolated, with no statement yet. */
    NOT_LIFTED("not lifted"),
    /** The statement is grounded and well formed. */
    LIFTED("lifted"),
    /** The statement holds a candidate concept or a clarification. */
    NEEDS_ATTENTION("needs attention"),
    /** The element's sentence changed after it was lifted. */
    STALE("stale");

    private final String word;

    ElementState(String word) {
        this.word = word;
    }

    /** The state as the surface prints it. */
    public String word() {
        return word;
    }
}
