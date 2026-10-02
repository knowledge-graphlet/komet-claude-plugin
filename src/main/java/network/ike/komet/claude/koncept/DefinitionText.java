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
package network.ike.komet.claude.koncept;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.DiTreeText;
import dev.ikm.tinkar.entity.graph.EntityVertex;

/**
 * The text form of a definition tree — the stated or inferred axioms of a concept — in which
 * every component is named by a description and none by a nid
 * ({@code IKE-Network/ike-issues#1170}).
 *
 * <p>The layout is the one {@link DiTreeText} writes, which follows
 * {@link DiTreeEntity#toString()}: one line per vertex, indented by depth, with the vertex's
 * index, the indexes of its successors and its meaning, followed by one bulleted line per
 * property, ordered by name. tinkar-service writes the same layout for a knowledge base served
 * over gRPC ({@code IKE-Network/ike-issues#1177}), so a definition reads the same in both modes.
 *
 * <p>The names are what this class decides. {@code toString()} takes a name from the store's
 * default description service and writes {@code <nid>} when that has none, and it writes an id
 * list with the nid of every element. Here a name is the description the view selects, a
 * component with no description is named by its UUID ({@link ComponentText#name}), and an id
 * list is a list of names.
 */
public final class DefinitionText {

    private DefinitionText() {
    }

    /**
     * The text of a whole definition tree, from its root.
     *
     * @param tree the definition tree
     * @param view the view that selects each description
     * @return the tree, one vertex per line with its properties beneath it; never a nid
     */
    public static String tree(DiTreeEntity tree, ViewCalculator view) {
        return DiTreeText.tree(tree, nid -> ComponentText.name(view, nid));
    }

    /**
     * The text of one vertex on its own, on one line: its meaning, the value it holds for that
     * meaning when it has one, and then its other properties.
     *
     * @param vertex the vertex
     * @param view   the view that selects each description
     * @return the vertex as {@code Meaning}, {@code Meaning: value}, or either followed by
     *         {@code {Property=value, …}}; never a nid
     */
    public static String vertex(EntityVertex vertex, ViewCalculator view) {
        return DiTreeText.vertex(vertex, nid -> ComponentText.name(view, nid));
    }
}
