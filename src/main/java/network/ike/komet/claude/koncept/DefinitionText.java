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

import dev.ikm.tinkar.common.id.IntIdCollection;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.EntityFacade;
import org.eclipse.collections.api.list.primitive.ImmutableIntList;
import org.eclipse.collections.api.map.primitive.ImmutableIntObjectMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The text form of a definition tree — the stated or inferred axioms of a concept — in which
 * every component is named by a description and none by a nid
 * ({@code IKE-Network/ike-issues#1170}).
 *
 * <p>The layout follows the one {@link DiTreeEntity#toString()} writes: one line per vertex,
 * indented by depth, with the vertex's index, the indexes of its successors and its meaning,
 * followed by one bulleted line per property. The indexes number the vertices of this one tree;
 * they are not identifiers of components.
 *
 * <p>The names are what differs. {@code toString()} takes a name from the store's default
 * description service and writes {@code <nid>} when that has none, and it writes an id list
 * with the nid of every element. Here a name is the description the view selects, a component
 * with no description is named by its UUID ({@link ComponentText#name}), and an id list is a
 * list of names. Properties are ordered by name, so the same tree reads the same in every
 * store.
 */
public final class DefinitionText {

    /** Indentation added for each level of depth. */
    private static final String INDENT = "  ";

    /** One property of a vertex, as text: the name of its key and the text of its value. */
    private record Property(String name, String value) {
    }

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
        StringBuilder text = new StringBuilder();
        appendVertex(text, tree, tree.root().vertexIndex(), 1, view);
        return text.toString();
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
        StringBuilder text = new StringBuilder();
        appendMeaning(text, vertex, view);
        List<Property> properties = properties(vertex, view);
        if (properties.isEmpty()) {
            return text.toString();
        }
        text.append(" {");
        for (int i = 0; i < properties.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(properties.get(i).name()).append('=').append(properties.get(i).value());
        }
        return text.append('}').toString();
    }

    /** Appends a vertex's line, its property lines, and then its successors, depth first. */
    private static void appendVertex(StringBuilder text, DiTreeEntity tree, int index, int depth,
                                     ViewCalculator view) {
        EntityVertex vertex = tree.vertex(index);
        String indent = INDENT.repeat(depth);
        ImmutableIntList successors = tree.successors(index);

        text.append(indent).append(" [").append(index).append(']');
        if (!successors.isEmpty()) {
            text.append("➞[");
            for (int i = 0; i < successors.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                text.append(successors.get(i));
            }
            text.append(']');
        }
        text.append(' ');
        appendMeaning(text, vertex, view);
        text.append('\n');

        for (Property property : properties(vertex, view)) {
            text.append(indent).append("    •").append(property.name())
                    .append(": ").append(property.value()).append('\n');
        }

        for (int i = 0; i < successors.size(); i++) {
            appendVertex(text, tree, successors.get(i), depth + 1, view);
        }
    }

    /**
     * Appends a vertex's meaning, and after it the value the vertex holds under that same
     * meaning when it has one — a concept reference vertex holds the referenced concept this
     * way.
     */
    private static void appendMeaning(StringBuilder text, EntityVertex vertex, ViewCalculator view) {
        int meaningNid = vertex.getMeaningNid();
        text.append(ComponentText.name(view, meaningNid));
        ImmutableIntObjectMap<Object> properties = vertex.properties();
        if (properties.containsKey(meaningNid)) {
            text.append(": ").append(value(properties.get(meaningNid), view));
        }
    }

    /**
     * A vertex's properties other than the one keyed by its own meaning, ordered by name and
     * then by value.
     */
    private static List<Property> properties(EntityVertex vertex, ViewCalculator view) {
        int meaningNid = vertex.getMeaningNid();
        ImmutableIntObjectMap<Object> properties = vertex.properties();
        List<Property> named = new ArrayList<>(properties.size());
        properties.forEachKeyValue((keyNid, value) -> {
            if (keyNid != meaningNid) {
                named.add(new Property(ComponentText.name(view, keyNid), value(value, view)));
            }
        });
        named.sort(Comparator.comparing(Property::name).thenComparing(Property::value));
        return named;
    }

    /**
     * The text of a property value. A component is its name; a list or set of components is the
     * list of their names; anything else — a string, a number, a boolean — is its own text.
     */
    private static String value(Object value, ViewCalculator view) {
        return switch (value) {
            case null -> "";
            case EntityFacade facade -> ComponentText.name(view, facade.nid());
            case IntIdCollection ids -> names(ids, view);
            default -> value.toString();
        };
    }

    /** The names of the components of an id list or set, in its order, as {@code [a, b]}. */
    private static String names(IntIdCollection ids, ViewCalculator view) {
        StringBuilder text = new StringBuilder("[");
        int[] nids = ids.toArray();
        for (int i = 0; i < nids.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(ComponentText.name(view, nids[i]));
        }
        return text.append(']').toString();
    }
}
