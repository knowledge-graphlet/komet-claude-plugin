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
package network.ike.komet.claude.stamp;

import java.util.ArrayList;
import java.util.List;

/**
 * The line a STAMPed Document card's footer shows for a document: what is in it and what is not
 * yet where it belongs, one line at a card's width. A count of nothing says nothing, and what a
 * person would do next is the toolbar's and the Repository panel's to say; the rest stands in
 * the line's tooltip.
 */
final class DocumentFooter {

    private DocumentFooter() {
    }

    /**
     * The footer's line.
     *
     * @param lines      the lines shown
     * @param blocks     the blocks, or a negative number for a document with no structure
     * @param references the references
     * @param affirmed   the lines affirmed
     * @param included   the included documents shown in place
     * @param edited     the lines edited in Komet and not yet committed
     * @param unresolved what is left of the includes that could not be resolved, empty for none
     * @return the parts that apply, joined
     */
    static String line(int lines, int blocks, int references, int affirmed, int included, long edited, String unresolved) {
        List<String> parts = new ArrayList<>();
        parts.add(count(lines, "line"));
        parts.add(blocks < 0 ? "no structure; re-materialize to add it" : count(blocks, "block"));
        if (references > 0) {
            parts.add(count(references, "reference"));
        }
        if (affirmed > 0) {
            parts.add(affirmed + " affirmed");
        }
        if (included > 0) {
            parts.add(included + " included");
        }
        if (edited > 0) {
            parts.add(edited + " edited, uncommitted");
        }
        if (!unresolved.isEmpty()) {
            parts.add(unresolved);
        }
        return String.join(" \u00b7 ", parts);
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
