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

import design.knowledge.stamp.adoc.dialect.Block;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.print.DocumentModel.Admonition;
import network.ike.komet.claude.stamp.print.DocumentModel.Container;
import network.ike.komet.claude.stamp.print.DocumentModel.Heading;
import network.ike.komet.claude.stamp.print.DocumentModel.Image;
import network.ike.komet.claude.stamp.print.DocumentModel.Item;
import network.ike.komet.claude.stamp.print.DocumentModel.ListBlock;
import network.ike.komet.claude.stamp.print.DocumentModel.Paragraph;
import network.ike.komet.claude.stamp.print.DocumentModel.Table;
import network.ike.komet.claude.stamp.print.DocumentModel.Verbatim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@link StoredDocument}'s blocks into a {@link DocumentModel}: the same walk the
 * Reading view makes over the stored structure, reduced to what a page shows. Headings are
 * read from the heading line, prose lines are joined by spaces with a trailing hard-break
 * backslash dropped, list markers and continuation marks are stripped, delimiters, block
 * titles and attribute lines are dropped from verbatim and container blocks, and a table's
 * rows are split into cells on their bars with the {@code cols} and {@code header} options
 * read from the block's parameters.
 *
 * <p>An assembly is read as one document: where a host's include line names a document the
 * store holds, that child flows in place — its title a heading at the include's
 * {@code leveloffset}, its sections offset with it — so the children paginate continuously
 * under one running head and one folio sequence.
 */
public final class StoredDocumentModel {

    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\[\\[[^\\]]*\\]\\]$");
    private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.([^\\s.].*)$");
    private static final Pattern ADMONITION_PREFIX = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s*");
    private static final Pattern ADMONITION_STYLE = Pattern.compile("\\[(NOTE|TIP|WARNING|IMPORTANT|CAUTION)(?:[,\\]].*)?");
    private static final Pattern COLS = Pattern.compile("cols\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern COL_SPEC = Pattern.compile("^(?:(\\d+)\\*)?[<^>.]*(\\d+)?[<^>.]*[a-z]?$");
    private static final Pattern IMAGE_MACRO = Pattern.compile("^image::([^\\[]*)\\[(.*)\\]$");
    private static final Pattern DESCRIPTION_TERM = Pattern.compile("^(?!\\s)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern LEVEL_OFFSET = Pattern.compile("leveloffset\\s*=\\s*([+-]?\\d+)");
    private static final Pattern INDEX_TERM_MACRO = Pattern.compile("indexterm:\\[[^\\]]*\\]\\s*");
    private static final Pattern CONCEALED_TERM = Pattern.compile("\\(\\(\\([^()]+\\)\\)\\)\\s*");
    private static final Pattern FLOW_TERM = Pattern.compile("\\(\\(([^()]+)\\)\\)");

    /**
     * A flowed child of an assembly: an included document and the level its title lands at.
     *
     * @param document    the child as the store holds it
     * @param levelOffset the include's {@code leveloffset}, added to the including document's own; the child's
     *                    title becomes a heading at that level, at least 1
     */
    public record Child(StoredDocument document, int levelOffset) {

        /** The level offset an include's parameters name, or 0. */
        public static int levelOffset(String includeParameters) {
            if (includeParameters == null) {
                return 0;
            }
            Matcher m = LEVEL_OFFSET.matcher(includeParameters);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        }
    }

    private final StoredDocument document;
    private final Map<Long, StoredLine> linesByNid = new HashMap<>();
    private final int levelOffset;
    /** The child flowed in place of an include line, by the line's nid, across every member. */
    private final Map<Long, Child> includes;

    private StoredDocumentModel(StoredDocument document, int levelOffset, Map<Long, Child> includes) {
        this.document = document;
        this.levelOffset = levelOffset;
        this.includes = includes;
        for (StoredLine line : document.lines()) {
            linesByNid.put(line.nid(), line);
        }
    }

    /**
     * Reads one document; its include lines print nothing.
     *
     * @param document the document as the store holds it
     * @return the model
     */
    public static DocumentModel of(StoredDocument document) {
        return of(document, Map.of());
    }

    /**
     * Reads an assembly: the host document with each resolved child flowed in place of its
     * include line, the children's own includes resolved the same way.
     *
     * @param document the host as the store holds it
     * @param includes the child shown in place of an include line, by the nid of that line, for the host and every child
     * @return the model, the children's headings offset by their includes
     */
    public static DocumentModel of(StoredDocument document, Map<Long, Child> includes) {
        List<DocumentModel.Block> blocks = new ArrayList<>();
        String title = new StoredDocumentModel(document, 0, Map.copyOf(includes)).read(blocks, true);
        return new DocumentModel(title, blocks);
    }

    /** Reads this document's blocks onto the list; returns its title. A child's title is a heading; the host's is the model's title. */
    private String read(List<DocumentModel.Block> out, boolean isRoot) {
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        if (document.root() == null) {
            // No structure in the store: every line is a paragraph of its own, as the Reading view shows it.
            for (StoredLine line : document.lines()) {
                out.add(new Paragraph(line.text()));
            }
            return title;
        }
        if (title.isEmpty()) {
            title = document.root().title();
        }
        if (!isRoot && !title.isEmpty()) {
            out.add(new Heading(levelOffset, title));
        }
        for (Member member : members(document.root())) {
            if (member.block() != null) {
                block(member.block(), out);
            }
        }
        return title;
    }

    /** A block's member: a line or a child block, in document order. */
    private record Member(StoredLine line, StoredBlock block) { }

    private List<Member> members(StoredBlock block) {
        List<Member> members = new ArrayList<>();
        for (long nid : block.members()) {
            StoredLine line = linesByNid.get(nid);
            if (line != null) {
                members.add(new Member(line, null));
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    members.add(new Member(null, child));
                }
            }
        }
        return members;
    }

    private List<StoredLine> ownLines(StoredBlock block) {
        return members(block).stream().map(Member::line).filter(l -> l != null).toList();
    }

    private List<StoredBlock> childBlocks(StoredBlock block) {
        return members(block).stream().map(Member::block).filter(b -> b != null).toList();
    }

    private void block(StoredBlock block, List<DocumentModel.Block> out) {
        List<StoredLine> own = ownLines(block);
        List<StoredBlock> children = childBlocks(block);
        switch (block.kind()) {
            case DOCUMENT -> children(children, out);
            case SECTION -> {
                String heading = own.stream().map(StoredLine::text).filter(t -> t.matches("^=+\\s.*")).findFirst()
                        .map(t -> t.replaceFirst("^=+\\s*", "").strip()).orElse(block.title());
                out.add(new Heading(block.level() + levelOffset, heading));
                children(children, out);
            }
            case PARAGRAPH -> {
                String text = prose(contentLines(own));
                if (!text.isEmpty()) {
                    out.add(new Paragraph(text));
                }
            }
            case ADMONITION -> {
                String label = admonitionLabel(block, own);
                List<DocumentModel.Block> body = new ArrayList<>();
                String text = prose(contentLines(own));
                if (!text.isEmpty()) {
                    body.add(new Paragraph(ADMONITION_PREFIX.matcher(text).replaceFirst("")));
                }
                children(children, body);
                out.add(new Admonition(label, body));
            }
            case LISTING, LITERAL, DIAGRAM -> out.add(new Verbatim(block.title(), String.join("\n", verbatimLines(own))));
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                List<DocumentModel.Block> body = new ArrayList<>();
                children(children, body);
                out.add(new Container(switch (block.kind()) {
                    case EXAMPLE -> Container.Kind.EXAMPLE;
                    case QUOTE -> Container.Kind.QUOTE;
                    case SIDEBAR -> Container.Kind.SIDEBAR;
                    default -> Container.Kind.OPEN;
                }, block.title(), body));
            }
            case LIST -> out.add(list(block, children));
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> { } // only inside a LIST
            case TABLE -> out.add(table(block, children));
            case TABLE_ROW, TABLE_CELL -> { } // only inside a TABLE
            case IMAGE -> {
                String target = own.stream().map(StoredLine::text).map(IMAGE_MACRO::matcher).filter(Matcher::matches)
                        .map(m -> m.group(1)).findFirst().orElse("");
                out.add(new Image(target, block.title()));
            }
            case OPAQUE -> {
                // Comments, attribute entries and passthrough print nothing; an include line whose child
                // the store holds flows that child in here, at the include's level offset on top of ours.
                for (StoredLine line : own) {
                    Child child = includes.get(line.nid());
                    if (child != null) {
                        new StoredDocumentModel(child.document(), Math.max(1, levelOffset + child.levelOffset()), includes).read(out, false);
                    }
                }
            }
        }
    }

    private void children(List<StoredBlock> children, List<DocumentModel.Block> out) {
        for (StoredBlock child : children) {
            block(child, out);
        }
    }

    private static String admonitionLabel(StoredBlock block, List<StoredLine> own) {
        Matcher style = ADMONITION_STYLE.matcher(block.parameters());
        if (style.find()) {
            return style.group(1);
        }
        for (StoredLine line : own) {
            Matcher m = ADMONITION_PREFIX.matcher(line.text());
            if (m.find()) {
                return m.group(1);
            }
        }
        return "NOTE";
    }

    private ListBlock list(StoredBlock block, List<StoredBlock> children) {
        String parameters = block.parameters();
        ListBlock.Kind kind = parameters.contains("[bibliography]") ? ListBlock.Kind.BIBLIOGRAPHY
                : parameters.contains("description") ? ListBlock.Kind.DESCRIPTION
                : parameters.contains("ordered") && !parameters.contains("unordered") ? ListBlock.Kind.ORDERED
                : ListBlock.Kind.UNORDERED;
        List<Item> items = new ArrayList<>();
        for (StoredBlock item : children) {
            if (item.kind() != Block.Kind.LIST_ITEM && item.kind() != Block.Kind.BIBLIOGRAPHY_ENTRY) {
                continue;
            }
            List<StoredLine> own = ownLines(item);
            if (kind == ListBlock.Kind.UNORDERED && !own.isEmpty()) {
                String first = own.getFirst().text().stripLeading();
                if (!first.isEmpty() && (first.startsWith(".") || Character.isDigit(first.charAt(0)))) {
                    kind = ListBlock.Kind.ORDERED; // the parser names the kind by the first marker; a bare list says nothing
                }
            }
            String term = "";
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < own.size(); i++) {
                String text = own.get(i).text();
                if (text.strip().equals("+")) {
                    continue;
                }
                if (i == 0) {
                    if (kind == ListBlock.Kind.DESCRIPTION) {
                        Matcher d = DESCRIPTION_TERM.matcher(text);
                        if (d.matches()) {
                            term = d.group(1).strip();
                            text = d.group(3) == null ? "" : d.group(3);
                        }
                    } else if (kind == ListBlock.Kind.BIBLIOGRAPHY) {
                        text = text.replaceFirst("^\\s*(\\*+|-)\\s+", "").replaceFirst("^\\[\\[\\[[^\\]]+\\]\\]\\]\\s*", "");
                    } else {
                        text = MARKER.matcher(text).replaceFirst("");
                    }
                }
                texts.add(text);
            }
            List<DocumentModel.Block> nested = new ArrayList<>();
            children(childBlocks(item), nested);
            items.add(new Item(term, prose(texts), nested));
        }
        return new ListBlock(kind, block.title(), items);
    }

    private Table table(StoredBlock block, List<StoredBlock> children) {
        List<List<String>> rows = new ArrayList<>();
        for (StoredBlock row : children) {
            if (row.kind() != Block.Kind.TABLE_ROW) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (StoredLine line : ownLines(row)) {
                cells.addAll(cells(line.text()));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        List<Integer> widths = widths(block.parameters(), columns);
        boolean header = block.parameters().contains("header") && !rows.isEmpty();
        return new Table(block.title(), widths, header ? 1 : 0, rows);
    }

    /** The cells of one table source line: {@code | a | b} gives two, a line with no bar is one cell. */
    static List<String> cells(String text) {
        String t = text.strip();
        if (!t.startsWith("|")) {
            return List.of(t);
        }
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean open = false;
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                current.append('|');
                i++;
            } else if (c == '|') {
                cells.add(current.toString().strip());
                current.setLength(0);
                open = true;
            } else {
                current.append(c);
                open = true;
            }
        }
        if (open || cells.isEmpty()) {
            cells.add(current.toString().strip());
        }
        return cells;
    }

    /** The relative widths {@code cols} names, repeated specs expanded, padded or cut to the column count. */
    static List<Integer> widths(String parameters, int columns) {
        List<Integer> widths = new ArrayList<>();
        Matcher m = COLS.matcher(parameters == null ? "" : parameters);
        if (m.find()) {
            for (String spec : m.group(1).split(",")) {
                Matcher s = COL_SPEC.matcher(spec.strip());
                int repeat = 1;
                int width = 1;
                if (s.matches()) {
                    repeat = s.group(1) == null ? 1 : Integer.parseInt(s.group(1));
                    width = s.group(2) == null ? 1 : Integer.parseInt(s.group(2));
                }
                for (int i = 0; i < repeat; i++) {
                    widths.add(Math.max(1, width));
                }
            }
        }
        // A cols that names more columns than any row holds stands; rows are padded to it. Fewer, or
        // none, and the widest row decides, the missing columns weighted 1.
        while (widths.size() < Math.max(1, columns)) {
            widths.add(1);
        }
        return widths;
    }

    /** The lines of a paragraph-like block that are content: not an anchor, title, attribute or delimiter line. */
    private static List<String> contentLines(List<StoredLine> own) {
        List<String> lines = new ArrayList<>();
        for (StoredLine line : own) {
            String text = line.text();
            if (ANCHOR_LINE.matcher(text).matches() || ATTRIBUTE_LINE.matcher(text).matches() || DELIMITER.matcher(text).matches()
                    || (BLOCK_TITLE.matcher(text).matches() && lines.isEmpty())) {
                continue;
            }
            lines.add(text);
        }
        return lines;
    }

    /** The content of a verbatim block: what lies between its first and last delimiter line. */
    private static List<String> verbatimLines(List<StoredLine> own) {
        List<String> texts = own.stream().map(StoredLine::text).toList();
        int first = -1;
        int last = -1;
        for (int i = 0; i < texts.size(); i++) {
            if (DELIMITER.matcher(texts.get(i)).matches()) {
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (first < 0) {
            return texts;
        }
        if (last == first) {
            return texts.subList(first + 1, texts.size());
        }
        return texts.subList(first + 1, last);
    }

    /**
     * Prose lines joined by spaces, each stripped: index-term macros and concealed terms are
     * dropped (they print nothing), a flow term keeps its words, a trailing hard-break
     * {@code +} ends a line of the paragraph (a newline in the text), a trailing backslash is dropped.
     */
    static String prose(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        boolean breakBefore = false;
        for (String line : lines) {
            String t = inline(line).strip();
            if (t.endsWith("\\")) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            boolean hardBreak = t.equals("+") || t.endsWith(" +");
            if (hardBreak) {
                t = t.substring(0, t.length() - 1).stripTrailing();
            }
            if (!t.isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append(breakBefore ? '\n' : ' ');
                }
                sb.append(t);
                breakBefore = false;
            }
            breakBefore |= hardBreak;
        }
        return sb.toString();
    }

    /** One line with the inline constructs that print nothing removed. */
    static String inline(String text) {
        String t = INDEX_TERM_MACRO.matcher(text).replaceAll("");
        t = CONCEALED_TERM.matcher(t).replaceAll("");
        return FLOW_TERM.matcher(t).replaceAll("$1");
    }
}
