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
package network.ike.komet.claude.help;

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import design.knowledge.stamp.adoc.dialect.InlineMarkup;
import design.knowledge.stamp.adoc.dialect.InlineMarkup.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Projects a help topic, AsciiDoc in the IKE dialect, to the Markdown the rich text renderer
 * draws, so help reads in the same control as every other read-only text in Komet: selectable,
 * copyable, in the same fonts. The structure comes from {@link IkeDialectParser} and the inline
 * markup from {@link InlineMarkup}, the parsers the stamped documents are read with, so a topic
 * means here what it means in the documentation build.
 *
 * <p>Sections become headings, paragraphs prose, lists lists (a description list a list of bold
 * terms), listings and literal blocks fenced code, admonitions quoted with their label, tables
 * GFM tables. A cross reference becomes a link whose destination is {@code xref:} and the
 * target, which the help viewer follows to another topic of the book; a URL link keeps its
 * address. Footnotes, anchors, concealed index terms and images are left out: help is read in a
 * narrow drawer.
 */
final class AsciiDocMarkdown {

    /** The scheme of a cross reference's destination in the projected Markdown. */
    static final String XREF = "xref:";

    private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.[^\\s.].*$");
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[[^\\[].*]$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\[\\[[^\\]]+]]$");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Pattern ADMONITION_PREFIX = Pattern.compile("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s+");
    private static final Pattern MARKDOWN_BLOCK_START = Pattern.compile("^(#|>|[-+*]\\s|\\d+[.)]\\s)");
    private static final String INDENT = "   ";

    private AsciiDocMarkdown() {
    }

    /**
     * Projects a topic.
     *
     * @param adoc the topic's AsciiDoc source
     * @return its Markdown
     */
    static String markdown(String adoc) {
        Document document = IkeDialectParser.parse(adoc);
        StringBuilder md = new StringBuilder();
        if (!document.title().isBlank()) {
            md.append("# ").append(inline(document, document.title())).append("\n\n");
        }
        for (Block block : document.root().children()) {
            block(document, block, md);
        }
        return md.toString().strip() + "\n";
    }

    /**
     * The target topic of a cross reference: its target without the {@code .adoc} suffix or a
     * fragment.
     *
     * @param target the cross reference's target, {@code kinds-of-data.adoc#instance} or {@code kinds-of-data}
     * @return the topic identifier, {@code kinds-of-data}
     */
    static String topicOf(String target) {
        String topic = target;
        int hash = topic.indexOf('#');
        if (hash >= 0) {
            topic = topic.substring(0, hash);
        }
        if (topic.endsWith(".adoc")) {
            topic = topic.substring(0, topic.length() - ".adoc".length());
        }
        return topic;
    }

    private static void block(Document document, Block block, StringBuilder md) {
        switch (block.kind()) {
            case SECTION -> {
                md.append("#".repeat(Math.min(block.level() + 1, 6))).append(' ')
                        .append(inline(document, block.title())).append("\n\n");
                children(document, block, md);
            }
            case PARAGRAPH -> paragraph(document, block, md);
            case LIST -> list(document, block, md, "");
            case LISTING, LITERAL -> listing(document, block, md);
            case ADMONITION -> admonition(document, block, md);
            case TABLE -> table(document, block, md);
            case QUOTE -> {
                StringBuilder quoted = new StringBuilder();
                title(document, block, quoted);
                body(document, block, quoted);
                md.append(quote(quoted.toString())).append("\n\n");
            }
            case EXAMPLE, SIDEBAR, OPEN -> {
                title(document, block, md);
                body(document, block, md);
            }
            case IMAGE, DIAGRAM, BIBLIOGRAPHY_ENTRY -> {
                // Not drawn in the help drawer.
            }
            default -> {
                title(document, block, md);
                body(document, block, md);
            }
        }
    }

    private static void children(Document document, Block block, StringBuilder md) {
        for (Block child : block.children()) {
            block(document, child, md);
        }
    }

    /** A container's children, or its own lines as a paragraph when it has none. */
    private static void body(Document document, Block block, StringBuilder md) {
        if (block.isContainer()) {
            children(document, block, md);
        } else {
            prose(document, contentLines(document, block), md);
        }
    }

    private static void title(Document document, Block block, StringBuilder md) {
        if (!block.title().isBlank()) {
            md.append("**").append(inline(document, block.title())).append("**\n\n");
        }
    }

    private static void paragraph(Document document, Block block, StringBuilder md) {
        title(document, block, md);
        List<String> lines = contentLines(document, block);
        if (!lines.isEmpty() && Character.isWhitespace(lines.getFirst().charAt(0))) {
            fence(lines.stream().map(String::strip).toList(), md);
        } else {
            prose(document, lines, md);
        }
    }

    private static void prose(Document document, List<String> lines, StringBuilder md) {
        if (lines.isEmpty()) {
            return;
        }
        String text = inline(document, String.join(" ", lines.stream().map(String::strip).toList()));
        if (MARKDOWN_BLOCK_START.matcher(text).find()) {
            text = "\\" + text;
        }
        md.append(text).append("\n\n");
    }

    private static void list(Document document, Block list, StringBuilder md, String indent) {
        String style = list.parameters().toLowerCase(Locale.ROOT);
        boolean ordered = style.contains("ordered") && !style.contains("unordered");
        boolean description = style.contains("description");
        int number = 1;
        for (Block item : list.children()) {
            List<String> lines = contentLines(document, item);
            String text;
            if (description) {
                String first = lines.isEmpty() ? "" : lines.getFirst();
                int separator = first.indexOf("::");
                String definition = separator < 0 ? "" : first.substring(separator).replaceFirst("^:+\\s*", "");
                List<String> rest = new ArrayList<>();
                if (!definition.isBlank()) {
                    rest.add(definition);
                }
                rest.addAll(lines.subList(Math.min(1, lines.size()), lines.size()));
                text = "**" + inline(document, item.title()) + "**"
                        + (rest.isEmpty() ? "" : " — " + inline(document, join(rest)));
            } else {
                List<String> stripped = new ArrayList<>(lines);
                if (!stripped.isEmpty()) {
                    stripped.set(0, LIST_MARKER.matcher(stripped.getFirst()).replaceFirst(""));
                }
                text = inline(document, join(stripped));
            }
            md.append(indent).append(ordered ? number++ + ". " : "- ").append(text).append('\n');
            for (Block child : item.children()) {
                StringBuilder nested = new StringBuilder();
                if (child.kind() == Block.Kind.LIST) {
                    list(document, child, nested, "");
                } else {
                    block(document, child, nested);
                }
                for (String line : nested.toString().strip().split("\n", -1)) {
                    md.append(line.isEmpty() ? "" : indent + INDENT + line).append('\n');
                }
            }
        }
        if (indent.isEmpty()) {
            md.append('\n');
        }
    }

    private static void listing(Document document, Block block, StringBuilder md) {
        title(document, block, md);
        fence(contentLines(document, block), md);
    }

    private static void fence(List<String> lines, StringBuilder md) {
        md.append("```\n");
        for (String line : lines) {
            md.append(line).append('\n');
        }
        md.append("```\n\n");
    }

    private static void admonition(Document document, Block block, StringBuilder md) {
        String kind = block.parameters().replaceAll("[\\[\\]]", "").split(",")[0].strip();
        String label = kind.isEmpty() ? "Note" : kind.charAt(0) + kind.substring(1).toLowerCase(Locale.ROOT);
        StringBuilder inner = new StringBuilder();
        if (block.isContainer()) {
            children(document, block, inner);
        } else {
            List<String> lines = new ArrayList<>(contentLines(document, block));
            if (!lines.isEmpty()) {
                lines.set(0, ADMONITION_PREFIX.matcher(lines.getFirst()).replaceFirst(""));
            }
            prose(document, lines, inner);
        }
        md.append(quote("**" + label + ":** " + inner.toString().strip())).append("\n\n");
    }

    private static void table(Document document, Block block, StringBuilder md) {
        title(document, block, md);
        List<List<String>> rows = new ArrayList<>();
        for (Block row : block.children()) {
            List<String> cells = new ArrayList<>();
            for (int line : row.lines()) {
                String text = document.line(line).strip();
                if (text.startsWith("|")) {
                    for (String cell : text.substring(1).split("\\|")) {
                        cells.add(inline(document, cell.strip()).replace("|", "\\|"));
                    }
                } else if (!cells.isEmpty()) {
                    cells.set(cells.size() - 1, cells.getLast() + " " + inline(document, text));
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        for (int r = 0; r < rows.size(); r++) {
            List<String> cells = new ArrayList<>(rows.get(r));
            while (cells.size() < columns) {
                cells.add("");
            }
            md.append("| ").append(String.join(" | ", cells)).append(" |\n");
            if (r == 0) {
                md.append("|").append(" --- |".repeat(columns)).append('\n');
            }
        }
        md.append('\n');
    }

    /**
     * A block's own text lines: its lines without a block title, attribute or anchor line before
     * the content, and without the delimiters of a delimited block.
     */
    private static List<String> contentLines(Document document, Block block) {
        List<String> lines = new ArrayList<>();
        for (int number : block.lines()) {
            lines.add(document.line(number));
        }
        while (!lines.isEmpty() && (BLOCK_TITLE.matcher(lines.getFirst()).matches()
                || ATTRIBUTE_LINE.matcher(lines.getFirst()).matches()
                || ANCHOR_LINE.matcher(lines.getFirst()).matches())) {
            lines.removeFirst();
        }
        if (!lines.isEmpty() && DELIMITER.matcher(lines.getFirst()).matches()) {
            lines.removeFirst();
        }
        if (!lines.isEmpty() && DELIMITER.matcher(lines.getLast()).matches()) {
            lines.removeLast();
        }
        lines.removeIf(String::isBlank);
        return lines;
    }

    private static String join(List<String> lines) {
        return String.join(" ", lines.stream().map(String::strip).toList());
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder();
        for (String line : text.strip().split("\n", -1)) {
            quoted.append(line.isEmpty() ? ">" : "> " + line).append('\n');
        }
        return quoted.toString().stripTrailing();
    }

    /** Inline AsciiDoc as Markdown. */
    static String inline(Document document, String text) {
        StringBuilder md = new StringBuilder();
        for (Span span : InlineMarkup.parse(text)) {
            span(document, span, md);
        }
        return md.toString();
    }

    private static void span(Document document, Span span, StringBuilder md) {
        switch (span.element()) {
            case TEXT -> md.append(escape(span.text()));
            case STRONG -> md.append("**").append(spans(document, span)).append("**");
            case EMPHASIS -> md.append('*').append(spans(document, span)).append('*');
            case MONOSPACE -> md.append('`').append(span.text().replace("`", "")).append('`');
            case CROSS_REFERENCE -> {
                String target = span.attributes().getOrDefault("linkend", "");
                String label = span.children().isEmpty() ? escape(topicOf(target).replace('-', ' ')) : spans(document, span);
                md.append('[').append(label).append("](").append(XREF).append(target.replace(" ", "%20")).append(')');
            }
            case LINK -> {
                String href = span.attributes().getOrDefault("href", "");
                String label = span.children().isEmpty() ? escape(href) : spans(document, span);
                md.append('[').append(label).append("](").append(href.replace(" ", "%20")).append(')');
            }
            case ATTRIBUTE_REFERENCE -> md.append(escape(document.attribute(span.text()).orElse("{" + span.text() + "}")));
            case FOOTNOTE, ANCHOR -> {
                // Not drawn in the help drawer.
            }
            case LINE_BREAK -> md.append(' ');
            // A flow index term shows its text; a concealed one, which has no content, shows nothing.
            case INDEX_TERM -> md.append(spans(document, span));
            default -> md.append(span.children().isEmpty() ? escape(span.text()) : spans(document, span));
        }
    }

    private static String spans(Document document, Span span) {
        StringBuilder md = new StringBuilder();
        for (Span child : span.children()) {
            span(document, child, md);
        }
        return md.toString();
    }

    /** Escapes the characters CommonMark would read as inline markup. */
    private static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if ("\\`*_[]<".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
