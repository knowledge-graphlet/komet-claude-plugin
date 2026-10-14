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
package network.ike.komet.claude.doc;

import dev.ikm.tinkar.terms.EntityProxy.Concept;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one conversation turn's Markdown into the shared block model
 * ({@code IKE-Network/ike-issues#844}): its lines, and the elements that group them — sections
 * opened by headings, paragraphs, listings, quotations, lists and their items, tables and their
 * rows, and opaque blocks for anything else. The turn is the root element, so every line belongs to
 * exactly one element: the innermost one whose range covers it and none of whose children does.
 * Joining the lines with {@code \n} gives the turn's Markdown back exactly.
 *
 * <p>Pure: no store, no JavaFX. {@link JournalStore} writes the result.
 */
public final class MarkdownStructure {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    /**
     * One element: its kind, level, title and verbatim parameters, the inclusive line range it
     * covers, and its members in document order.
     *
     * @param kind       the element kind concept
     * @param level      a section's heading level or a list's nesting depth, else 0
     * @param title      a section's heading text, else empty
     * @param parameters a fence's info string or a list's kind, else empty
     * @param firstLine  the first line it covers, zero-based
     * @param lastLine   the last line it covers, zero-based, inclusive
     * @param members    its lines and child elements in order
     */
    public record Element(Concept kind, int level, String title, String parameters,
                          int firstLine, int lastLine, List<Member> members) {
    }

    /** A member of an element: one of its own lines, or a child element. */
    public sealed interface Member {
    }

    /**
     * A line the element owns directly.
     *
     * @param index the line's zero-based index in the turn
     */
    public record Line(int index) implements Member {
    }

    /**
     * A child element.
     *
     * @param element the child
     */
    public record Child(Element element) implements Member {
    }

    /**
     * A parsed turn: its lines and its root element, of kind {@link DocumentModelTerms#CONVERSATION_TURN}.
     *
     * @param lines the turn's lines, split on {@code \n} with trailing empty lines kept
     * @param turn  the root element covering every line
     */
    public record Turn(List<String> lines, Element turn) {
    }

    private MarkdownStructure() {
    }

    /**
     * Parses a turn.
     *
     * @param markdown the turn's Markdown source; {@code null} reads as empty
     * @return its lines and element tree
     */
    public static Turn parse(String markdown) {
        String text = markdown == null ? "" : markdown;
        List<String> lines = List.of(text.split("\n", -1));
        int last = lines.size() - 1;
        List<Draft> top = new ArrayList<>();
        for (Node node = PARSER.parse(text).getFirstChild(); node != null; node = node.getNext()) {
            Draft draft = draft(node, 1, last);
            if (draft != null) {
                top.add(draft);
            }
        }
        Draft turn = new Draft(DocumentModelTerms.CONVERSATION_TURN, 0, "", "", 0, last);
        nestSections(turn, top, lines);
        return new Turn(lines, turn.build());
    }

    /** Places top-level blocks under the sections their headings open, nesting by level. */
    private static void nestSections(Draft turn, List<Draft> top, List<String> lines) {
        List<Draft> open = new ArrayList<>();
        open.add(turn);
        for (int i = 0; i < top.size(); i++) {
            Draft d = top.get(i);
            if (isSection(d)) {
                while (open.size() > 1 && open.getLast().level >= d.level) {
                    open.removeLast();
                }
                open.getLast().children.add(d);
                open.add(d);
            } else {
                open.getLast().children.add(d);
            }
        }
        // A section runs until the next heading of its level or shallower, or the turn's end.
        closeSections(turn, turn.lastLine);
    }

    private static void closeSections(Draft parent, int end) {
        List<Draft> children = parent.children;
        for (int i = 0; i < children.size(); i++) {
            Draft child = children.get(i);
            if (isSection(child)) {
                int next = i + 1 < children.size() ? children.get(i + 1).firstLine - 1 : end;
                child.lastLine = Math.max(child.lastLine, next);
                closeSections(child, child.lastLine);
            }
        }
    }

    private static boolean isSection(Draft d) {
        return d.kind.publicId().equals(DocumentModelTerms.SECTION.publicId());
    }

    private static Draft draft(Node node, int listDepth, int lastLine) {
        int[] range = range(node, lastLine);
        if (range == null) {
            return null;
        }
        int first = range[0];
        int last = range[1];
        return switch (node) {
            case Heading h -> new Draft(DocumentModelTerms.SECTION, h.getLevel(), headingText(node), "", first, last);
            case Paragraph p -> new Draft(DocumentModelTerms.PARAGRAPH, 0, "", "", first, last);
            case FencedCodeBlock f -> new Draft(DocumentModelTerms.LISTING, 0, "",
                    f.getInfo() == null ? "" : f.getInfo(), first, last);
            case IndentedCodeBlock c -> new Draft(DocumentModelTerms.LISTING, 0, "", "", first, last);
            case BlockQuote q -> new Draft(DocumentModelTerms.QUOTATION, 0, "", "", first, last);
            case ListBlock l -> {
                Draft list = new Draft(DocumentModelTerms.LIST, listDepth, "",
                        l instanceof BulletList ? "bullet" : "ordered", first, last);
                for (Node item = l.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem) {
                        int[] r = range(item, lastLine);
                        if (r != null) {
                            list.children.add(new Draft(DocumentModelTerms.LIST_ITEM, listDepth, "", "", r[0], r[1]));
                        }
                    }
                }
                yield list;
            }
            case TableBlock t -> {
                Draft table = new Draft(DocumentModelTerms.TABLE, 0, "", "", first, last);
                collectRows(t, table, lastLine);
                yield table;
            }
            default -> new Draft(DocumentModelTerms.OPAQUE, 0, "", "", first, last);
        };
    }

    private static void collectRows(Node parent, Draft table, int lastLine) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableRow) {
                int[] r = range(child, lastLine);
                if (r != null) {
                    table.children.add(new Draft(DocumentModelTerms.TABLE_ROW, 0, "", "", r[0], r[1]));
                }
            } else {
                collectRows(child, table, lastLine);
            }
        }
    }

    /** The node's inclusive line range from its source spans, clamped to the turn; null when it has none. */
    private static int[] range(Node node, int lastLine) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return null;
        }
        int first = Math.min(spans.getFirst().getLineIndex(), lastLine);
        int last = Math.min(spans.getLast().getLineIndex(), lastLine);
        return new int[] {first, Math.max(first, last)};
    }

    private static String headingText(Node heading) {
        StringBuilder text = new StringBuilder();
        appendText(heading, text);
        return text.toString().strip();
    }

    private static void appendText(Node node, StringBuilder text) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text t) {
                text.append(t.getLiteral());
            } else if (child instanceof org.commonmark.node.Code c) {
                text.append(c.getLiteral());
            } else if (child instanceof org.commonmark.node.SoftLineBreak) {
                text.append(' ');
            } else {
                appendText(child, text);
            }
        }
    }

    /** A mutable element while its section bounds are still being settled. */
    private static final class Draft {
        private final Concept kind;
        private final int level;
        private final String title;
        private final String parameters;
        private final int firstLine;
        private int lastLine;
        private final List<Draft> children = new ArrayList<>();

        private Draft(Concept kind, int level, String title, String parameters, int firstLine, int lastLine) {
            this.kind = kind;
            this.level = level;
            this.title = title;
            this.parameters = parameters;
            this.firstLine = firstLine;
            this.lastLine = lastLine;
        }

        /** Interleaves the children with the lines no child covers, in line order. */
        private Element build() {
            List<Member> members = new ArrayList<>();
            int line = firstLine;
            for (Draft child : children) {
                for (; line < child.firstLine; line++) {
                    members.add(new Line(line));
                }
                members.add(new Child(child.build()));
                line = Math.max(line, child.lastLine + 1);
            }
            for (; line <= lastLine; line++) {
                members.add(new Line(line));
            }
            return new Element(kind, level, title, parameters, firstLine, lastLine, List.copyOf(members));
        }
    }
}
