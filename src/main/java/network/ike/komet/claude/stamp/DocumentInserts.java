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

import design.knowledge.stamp.adoc.dialect.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What the document surface's Insert menu adds (ike-lab-documents topic
 * {@code dev-document-edit-menu}): the standard AsciiDoc blocks a document has, and one entry per
 * registered {@link StructuredBlockKind}, each a skeleton the person then edits in place. The new
 * block goes after the selected block, through the starter set's {@code Add document block} action,
 * so it is the person's pending edit, uncommitted until the document is committed in git.
 *
 * <p>A block is inserted whole with its lines as its members, so the standard entries are the
 * kinds {@link DocumentEditor#isLeafKind} allows. A heading, a list, a table and the delimited
 * containers hold other blocks, and wait on an insertion that writes a subtree.
 */
public final class DocumentInserts {

    private DocumentInserts() {
    }

    /**
     * One entry of the Insert menu.
     *
     * @param id              the menu item's id, for automation: {@code stampedDocumentInsert…}
     * @param label           the entry's text
     * @param kind            the parsed kind the text forms, which the action checks
     * @param text            the new block's text, one line per line
     * @param opensForEditing whether the surface opens the new block for editing at once
     */
    public record Insertion(String id, String label, Block.Kind kind, String text, boolean opensForEditing) { }

    /** The text a new paragraph or cell starts with, to be replaced. */
    public static final String PLACEHOLDER = "Text.";

    /** The admonitions AsciiDoc knows, in its order. */
    public static final List<String> ADMONITIONS = List.of("NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION");

    /**
     * The standard AsciiDoc blocks the menu inserts: a paragraph, a listing, a literal, and the
     * five admonitions in their paragraph form. The paragraph and listing keep the ids of the
     * verbs they replace, so scripts that drove Add paragraph after… still find them.
     *
     * @return the entries in menu order
     */
    public static List<Insertion> standard() {
        List<Insertion> entries = new ArrayList<>();
        entries.add(new Insertion("stampedDocumentAddParagraph", "Paragraph", Block.Kind.PARAGRAPH, PLACEHOLDER + "\n", true));
        entries.add(new Insertion("stampedDocumentAddListing", "Listing", Block.Kind.LISTING, "----\n" + PLACEHOLDER + "\n----\n", true));
        entries.add(new Insertion("stampedDocumentInsertLiteral", "Literal", Block.Kind.LITERAL, "....\n" + PLACEHOLDER + "\n....\n", true));
        return List.copyOf(entries);
    }

    /**
     * The admonitions, each a paragraph led by its label, as {@code NOTE: Text.}.
     *
     * @return the five entries
     */
    public static List<Insertion> admonitions() {
        List<Insertion> entries = new ArrayList<>();
        for (String label : ADMONITIONS) {
            String word = label.charAt(0) + label.substring(1).toLowerCase(Locale.ROOT);
            entries.add(new Insertion("stampedDocumentInsert" + word, word, Block.Kind.ADMONITION, label + ": " + PLACEHOLDER + "\n", true));
        }
        return List.copyOf(entries);
    }

    /**
     * The entry for a structured kind, when the kind offers a skeleton.
     *
     * @param kind    the kind
     * @param context what the document's blocks have made known
     * @return the entry
     */
    public static Optional<Insertion> of(StructuredBlockKind kind, StructuredBlockKind.Context context) {
        return kind.skeleton(context).map(skeleton -> {
            String text = skeleton.text().endsWith("\n") ? skeleton.text() : skeleton.text() + "\n";
            // an open block, --, is the form the ANF blocks take; everything else is a listing
            Block.Kind parsed = text.lines().skip(1).findFirst().map(String::strip).filter("--"::equals).isPresent() ? Block.Kind.OPEN : Block.Kind.LISTING;
            return new Insertion("stampedDocumentInsert" + camel(kind.style()), kind.menuName(), parsed, text, skeleton.opensForEditing());
        });
    }

    /** {@code decision-tree} as {@code DecisionTree}. */
    static String camel(String style) {
        StringBuilder sb = new StringBuilder();
        for (String part : style.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }
}
