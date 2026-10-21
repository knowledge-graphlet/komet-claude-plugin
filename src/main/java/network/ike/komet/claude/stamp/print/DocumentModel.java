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
package network.ike.komet.claude.stamp.print;

import java.util.List;
import java.util.Objects;

/**
 * A document as a print writer reads it: a title and a flat run of blocks, each a plain-text
 * construct with no source lines, no provenance and no control wiring. It is the one model
 * both outputs of a stamped document consume — the paged JavaFX preview and the Typst
 * writer — and it is what the Typst writer library in {@code ike-docs} would take when the
 * writer moves there ({@code dev-document-surface-pages}, IKE-Network/ike-issues#1307).
 *
 * <p>Headings carry the section a running head names from that point on; everything else is
 * content. Paragraph text is the lines of the source paragraph joined by spaces; verbatim text
 * keeps its line breaks; a table is rows of cells with the header rows counted.
 *
 * @param title  the document title, possibly empty
 * @param blocks the blocks in reading order
 */
public record DocumentModel(String title, List<Block> blocks) {

    public DocumentModel {
        Objects.requireNonNull(title, "title is null");
        blocks = List.copyOf(blocks);
    }

    /** One block of the document. */
    public sealed interface Block permits Heading, Paragraph, Admonition, Verbatim, ListBlock, Table, Image, Container { }

    /**
     * A heading. Level 0 is a document title; an assembly's child titles arrive at the level
     * the include offsets them to.
     *
     * @param level the level, 0 for a title, 1 for a top-level section
     * @param text  the heading text
     */
    public record Heading(int level, String text) implements Block { }

    /**
     * A paragraph of prose.
     *
     * @param text the lines joined by spaces
     */
    public record Paragraph(String text) implements Block { }

    /**
     * An admonition.
     *
     * @param label  {@code NOTE}, {@code TIP}, {@code WARNING}, {@code IMPORTANT} or {@code CAUTION}
     * @param blocks the admonition's content: one paragraph for the paragraph form, the body's blocks for the block form
     */
    public record Admonition(String label, List<Block> blocks) implements Block { }

    /**
     * A verbatim block: a listing, a literal block or a diagram source.
     *
     * @param title the block title, possibly empty
     * @param text  the content with its line breaks, delimiters excluded
     */
    public record Verbatim(String title, String text) implements Block { }

    /**
     * A list.
     *
     * @param kind  the list kind
     * @param title the list title, possibly empty
     * @param items the items in order
     */
    public record ListBlock(Kind kind, String title, List<Item> items) implements Block {

        /** The list kinds a writer distinguishes. */
        public enum Kind { UNORDERED, ORDERED, DESCRIPTION, BIBLIOGRAPHY }
    }

    /**
     * One list item.
     *
     * @param term   a description list's term, empty for the other kinds
     * @param text   the item's principal text, its wrapped lines joined by spaces
     * @param blocks the blocks nested in the item: nested lists, attached blocks
     */
    public record Item(String term, String text, List<Block> blocks) { }

    /**
     * A table.
     *
     * @param title      the table title, possibly empty
     * @param widths     the relative column widths, one per column, from {@code cols} or all 1
     * @param headerRows how many leading rows are header rows
     * @param rows       the rows, each a list of cell texts, short rows padded by the writer
     */
    public record Table(String title, List<Integer> widths, int headerRows, List<List<String>> rows) implements Block {

        /** @return the column count */
        public int columns() {
            return widths.size();
        }
    }

    /**
     * A block image, named rather than embedded: the writers draw its caption and target.
     *
     * @param target the image target as written
     * @param title  the block title, possibly empty
     */
    public record Image(String target, String title) implements Block { }

    /**
     * A delimited container: an example, a sidebar, a quote or an open block.
     *
     * @param kind   the container kind
     * @param title  the block title, possibly empty
     * @param blocks the contained blocks
     */
    public record Container(Kind kind, String title, List<Block> blocks) implements Block {

        /** The container kinds a writer distinguishes. */
        public enum Kind { EXAMPLE, SIDEBAR, QUOTE, OPEN }
    }
}
