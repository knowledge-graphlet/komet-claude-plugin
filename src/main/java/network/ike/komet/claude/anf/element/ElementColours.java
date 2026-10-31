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
 * The colours elements are drawn in, the same in the lift's region and on the document surface,
 * so an element's underline in the note and its row match. Assigned by the element's place in its
 * note, first span first.
 */
public final class ElementColours {

    private static final String[] COLOURS = {"#7F77DD", "#1D9E75", "#D85A30", "#D4537E", "#378ADD", "#BA7517",
            "#639922", "#993556"};

    private ElementColours() {
    }

    /** The colour of the element at {@code index} in its note, as a web colour. */
    public static String of(int index) {
        return COLOURS[Math.floorMod(index, COLOURS.length)];
    }
}
