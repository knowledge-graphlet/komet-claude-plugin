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

import network.ike.komet.claude.stamp.print.DocumentModel.Admonition;
import network.ike.komet.claude.stamp.print.DocumentModel.Block;
import network.ike.komet.claude.stamp.print.DocumentModel.Container;
import network.ike.komet.claude.stamp.print.DocumentModel.Heading;
import network.ike.komet.claude.stamp.print.DocumentModel.Image;
import network.ike.komet.claude.stamp.print.DocumentModel.Item;
import network.ike.komet.claude.stamp.print.DocumentModel.ListBlock;
import network.ike.komet.claude.stamp.print.DocumentModel.Paragraph;
import network.ike.komet.claude.stamp.print.DocumentModel.Table;
import network.ike.komet.claude.stamp.print.DocumentModel.Verbatim;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Writes a {@link DocumentModel} as Typst source: page setup, running head, folios, headings,
 * prose paragraphs, lists, admonitions, verbatim blocks and tables with header rows. It
 * depends on nothing of Komet or JavaFX — the model and a {@link PageSetup} in, one string
 * out — so it is the shape the Typst writer library in {@code ike-docs} takes when it moves
 * there ({@code dev-document-surface-pages}); the escaping and fence rules follow that
 * library's {@code TypstText}.
 *
 * <p>The running head shows the document title on the left and the current section on the
 * right — the last heading of level 1 or 2 before the page, so a flowed child's title names
 * the section while the child runs. Folios are one sequence over the whole document.
 */
public final class TypstWriter {

    /** The deepest heading level the running head names: a top-level section, or a flowed child's title. */
    private static final int RUNNING_HEAD_DEPTH = 2;

    private TypstWriter() { }

    /** Where the folio goes. */
    public enum Folio { NONE, HEADER_RIGHT, FOOTER_CENTER, FOOTER_RIGHT }

    /**
     * The page: paper, margins, fonts and furniture.
     *
     * @param paper        a Typst paper name ({@code "us-letter"}, {@code "a4"}, {@code "us-legal"})
     * @param landscape    whether the paper is turned
     * @param marginTop    the top margin in points
     * @param marginRight  the right margin in points
     * @param marginBottom the bottom margin in points
     * @param marginLeft   the left margin in points
     * @param bodyFonts    the body font families in preference order
     * @param monoFonts    the verbatim font families in preference order
     * @param fontSize     the body size in points
     * @param runningHead  whether the running head is drawn
     * @param folio        where the page number goes
     */
    public record PageSetup(String paper, boolean landscape, double marginTop, double marginRight, double marginBottom,
                            double marginLeft, List<String> bodyFonts, List<String> monoFonts, double fontSize,
                            boolean runningHead, Folio folio) {

        public PageSetup {
            Objects.requireNonNull(paper, "paper is null");
            bodyFonts = List.copyOf(bodyFonts);
            monoFonts = List.copyOf(monoFonts);
            Objects.requireNonNull(folio, "folio is null");
        }

        /** US Letter, one-inch margins, a serif body at 12 pt, running head and a centered footer folio. */
        public static final PageSetup DEFAULT = new PageSetup("us-letter", false, 72, 72, 72, 72,
                List.of(DocumentFonts.SERIF, "Libertinus Serif", "Georgia"), List.of(DocumentFonts.MONO, "Menlo", "DejaVu Sans Mono"),
                12, true, Folio.FOOTER_CENTER);
    }

    /**
     * Writes the document.
     *
     * @param document the document
     * @param setup    the page
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup) {
        return write(document, setup, false);
    }

    /** The label every top-level block carries when written for measurement, queried with {@code typst query}. */
    public static final String BLOCK_LABEL = "ike-block";
    /** The label after the last block when written for measurement: its page is the page count. */
    public static final String END_LABEL = "ike-end";

    /**
     * Writes the document, optionally with a zero-size {@code metadata} element labelled
     * {@link #BLOCK_LABEL} before the title and before every top-level block, so
     * {@code typst query <file> "<ike-block>" --field location} answers where each block starts
     * — the measurement the JavaFX Page layout is checked against.
     *
     * @param document the document
     * @param setup    the page
     * @param labelled whether to mark the blocks
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup, boolean labelled) {
        return write(document, setup, labelled ? Labels.BLOCKS : Labels.NONE);
    }

    /** What a measured document is labelled with. */
    public enum Labels {
        /** Nothing: the document as printed. */
        NONE,
        /** Every top-level block, with its index, and the end. */
        BLOCKS,
        /** The blocks, and every word with its text ({@link TypstOracle#WORD_RULE}): the layout oracle's source. */
        WORDS
    }

    /**
     * Writes the document with the given labels: a zero-size {@code metadata} element carrying
     * the block's index, labelled {@link #BLOCK_LABEL}, before the title and before every
     * top-level block, one labelled {@link #END_LABEL} after the last, and, for
     * {@link Labels#WORDS}, the show rule that labels every word with its text — what the
     * {@link TypstOracle} reads the lines from.
     *
     * @param document the document
     * @param setup    the page
     * @param labels   what to label
     * @return Typst source
     */
    public static String write(DocumentModel document, PageSetup setup, Labels labels) {
        StringBuilder out = new StringBuilder(4096);
        out.append(preamble(document.title(), setup));
        if (labels == Labels.WORDS) {
            out.append(TypstOracle.WORD_RULE);
        }
        CHUNK_WORDS.set(labels == Labels.WORDS);
        try {
            body(document, labels, out);
        } finally {
            CHUNK_WORDS.set(false);
        }
        return out.toString();
    }

    private static void body(DocumentModel document, Labels labels, StringBuilder out) {
        int index = 0;
        if (!document.title().isEmpty()) {
            if (labels != Labels.NONE) {
                out.append(marker(index++));
            }
            out.append("#ike-title[").append(escape(document.title())).append("]\n\n");
        }
        for (Block block : document.blocks()) {
            if (labels != Labels.NONE) {
                out.append(marker(index++));
            }
            block(block, out, "");
        }
        if (labels != Labels.NONE) {
            out.append("#metadata(none) <").append(END_LABEL).append(">\n");
        }
    }

    /** The page setup and the styles every construct relies on. */
    static String preamble(String title, PageSetup setup) {
        String t = string(title);
        StringBuilder sb = new StringBuilder(2048);
        sb.append("// Written by Komet's stamped document surface — do not edit; the source is the AsciiDoc in git.\n");
        sb.append("#set document(title: ").append(t).append(")\n");
        sb.append("#set page(\n");
        sb.append("  paper: ").append(string(setup.paper())).append(",\n");
        if (setup.landscape()) {
            sb.append("  flipped: true,\n");
        }
        sb.append("  margin: (top: ").append(points(setup.marginTop())).append(", bottom: ").append(points(setup.marginBottom()))
                .append(", left: ").append(points(setup.marginLeft())).append(", right: ").append(points(setup.marginRight())).append("),\n");
        boolean headerFolio = setup.folio() == Folio.HEADER_RIGHT;
        if (setup.runningHead() || headerFolio) {
            sb.append("  header: context {\n");
            sb.append("    set text(size: 0.8em, fill: luma(70))\n");
            sb.append("    let section = query(selector(heading).before(here())).filter(h => h.level <= ").append(RUNNING_HEAD_DEPTH).append(")\n");
            sb.append("    let current = if section.len() > 0 { section.last().body } else { [] }\n");
            if (setup.runningHead()) {
                if (headerFolio) {
                    sb.append("    grid(columns: (1fr, auto), align: (left, right), [").append("#text(").append(t).append(")").append(" #h(1em) #current], counter(page).display(\"1\"))\n");
                } else {
                    sb.append("    grid(columns: (1fr, auto), align: (left, right), text(").append(t).append("), current)\n");
                }
            } else {
                sb.append("    align(right, counter(page).display(\"1\"))\n");
            }
            if (setup.runningHead()) {
                sb.append("    v(-0.4em)\n    line(length: 100%, stroke: 0.4pt + luma(170))\n");
            }
            sb.append("  },\n");
        }
        if (setup.folio() == Folio.FOOTER_CENTER || setup.folio() == Folio.FOOTER_RIGHT) {
            sb.append("  footer: context align(").append(setup.folio() == Folio.FOOTER_CENTER ? "center" : "right")
                    .append(", text(size: 0.8em, fill: luma(70), counter(page).display(\"1\"))),\n");
        }
        sb.append(")\n");
        sb.append("#set text(font: ").append(fonts(setup.bodyFonts())).append(", size: ").append(points(setup.fontSize()))
                .append(", lang: \"en\", hyphenate: true)\n");
        sb.append("""
                #set par(justify: true, leading: 0.65em, spacing: 1.1em)
                #set heading(numbering: none)
                #show heading: set text(weight: "bold")
                """);
        // Absolute sizes: Typst's own heading rule already scales a level-1 heading by 1.4em and a level-2 by
        // 1.2em, and an em here would compound with it (a level-1 heading came out at 2.1 times the body).
        sb.append("#show heading.where(level: 1): set text(size: ").append(points(setup.fontSize() * 1.5)).append(")\n");
        sb.append("#show heading.where(level: 2): set text(size: ").append(points(setup.fontSize() * 1.25)).append(")\n");
        sb.append("#show heading.where(level: 3): set text(size: ").append(points(setup.fontSize() * 1.1)).append(")\n");
        sb.append("#show heading.where(level: 4): set text(size: ").append(points(setup.fontSize())).append(", style: \"italic\")\n");
        sb.append("""
                #show heading: it => { v(0.9em, weak: true); it; v(0.5em, weak: true) }
                """);
        sb.append("#show raw: set text(font: ").append(fonts(setup.monoFonts())).append(", size: 0.85em)\n");
        sb.append("""
                #show raw.where(block: true): it => block(width: 100%, fill: luma(247), inset: 8pt, radius: 3pt, it)
                #set table(stroke: 0.5pt + luma(150), inset: 5pt, align: left + horizon)
                #show table.cell: set par(justify: false)
                #show table.cell: set text(hyphenate: false)
                #show table.cell.where(y: 0): set text(weight: "bold")
                #show figure.where(kind: table): set figure.caption(position: top)
                #show figure.caption: set text(size: 0.85em, weight: "bold")
                #show figure.caption: set align(left)
                #set figure(numbering: none)
                #set list(indent: 1em)
                #set enum(indent: 1em)
                #set terms(hanging-indent: 1.5em, separator: [: ])

                #let ike-title(body) = block(below: 1.2em, text(size: 1.8em, weight: "bold", body))
                #let ike-block-title(body) = block(above: 1em, below: 0.5em, text(weight: "bold", size: 0.95em, body))
                #let ike-admonition-colors = (
                  NOTE: rgb("#2a5a8a"), TIP: rgb("#3b8c2f"), IMPORTANT: rgb("#b8860b"),
                  WARNING: rgb("#c25e00"), CAUTION: rgb("#b00020"),
                )
                #let ike-admonition(kind, body) = {
                  let c = ike-admonition-colors.at(kind, default: luma(90))
                  block(width: 100%, inset: (left: 10pt, right: 8pt, top: 6pt, bottom: 6pt),
                    stroke: (left: 2pt + c), fill: luma(248),
                    [#text(weight: "bold", size: 0.8em, fill: c, kind) \\ #body])
                }
                #let ike-example(title, body) = block(width: 100%, inset: 8pt, stroke: 0.5pt + luma(190), radius: 2pt,
                  [#if title != none [#text(weight: "bold", size: 0.9em, title) \\ ]#body])
                #let ike-sidebar(title, body) = block(width: 100%, inset: 8pt, fill: luma(244), radius: 2pt,
                  [#if title != none [#text(weight: "bold", size: 0.9em, title) \\ ]#body])
                #let ike-image(target, title) = block(width: 100%, inset: 6pt, stroke: 0.5pt + luma(190),
                  [#text(size: 0.85em, fill: luma(90))[Image: #raw(target)]#if title != none [ — #title]])

                """);
        return sb.toString();
    }

    private static String marker(int index) {
        return "#metadata(" + index + ") <" + BLOCK_LABEL + ">\n";
    }

    private static void blocks(List<Block> blocks, StringBuilder out, String indent) {
        for (Block block : blocks) {
            block(block, out, indent);
        }
    }

    private static void block(Block block, StringBuilder out, String indent) {
        switch (block) {
            case Heading h -> out.append(indent).append("=".repeat(Math.clamp(h.level(), 1, 6))).append(' ')
                    .append(escape(h.text())).append("\n\n");
            case Paragraph p -> out.append(indent).append(markup(p.text()).replace("\n", "\n" + indent)).append("\n\n");
            case Admonition a -> {
                out.append(indent).append("#ike-admonition(").append(string(a.label())).append(")[\n");
                blocks(a.blocks(), out, indent + "  ");
                trimBlank(out);
                out.append('\n').append(indent).append("]\n\n");
            }
            case Verbatim v -> {
                if (!v.title().isEmpty()) {
                    out.append(indent).append("#ike-block-title[").append(escape(v.title())).append("]\n");
                }
                String fence = fence(v.text());
                out.append(indent).append(fence).append('\n');
                for (String line : v.text().split("\n", -1)) {
                    out.append(indent).append(line).append('\n');
                }
                out.append(indent).append(fence).append("\n\n");
            }
            case ListBlock l -> {
                if (!l.title().isEmpty()) {
                    out.append(indent).append("#ike-block-title[").append(escape(l.title())).append("]\n");
                }
                for (Item item : l.items()) {
                    String marker = switch (l.kind()) {
                        case ORDERED -> "+ ";
                        case DESCRIPTION -> "/ " + escape(item.term()) + ": ";
                        case UNORDERED, BIBLIOGRAPHY -> "- ";
                    };
                    out.append(indent).append(marker).append(markup(item.text()).replace("\n", "\n" + indent + "  ")).append('\n');
                    if (!item.blocks().isEmpty()) {
                        StringBuilder nested = new StringBuilder();
                        blocks(item.blocks(), nested, indent + "  ");
                        trimBlank(nested);
                        out.append(nested).append('\n');
                    }
                }
                out.append('\n');
            }
            case Table t -> table(t, out, indent);
            case Image i -> out.append(indent).append("#ike-image(").append(string(i.target())).append(", ")
                    .append(i.title().isEmpty() ? "none" : "[" + escape(i.title()) + "]").append(")\n\n");
            case Container c -> {
                switch (c.kind()) {
                    case EXAMPLE, SIDEBAR -> {
                        out.append(indent).append(c.kind() == Container.Kind.EXAMPLE ? "#ike-example(" : "#ike-sidebar(")
                                .append(c.title().isEmpty() ? "none" : "[" + escape(c.title()) + "]").append(")[\n");
                        blocks(c.blocks(), out, indent + "  ");
                        trimBlank(out);
                        out.append('\n').append(indent).append("]\n\n");
                    }
                    case QUOTE -> {
                        if (!c.title().isEmpty()) {
                            out.append(indent).append("#ike-block-title[").append(escape(c.title())).append("]\n");
                        }
                        out.append(indent).append("#quote(block: true)[\n");
                        blocks(c.blocks(), out, indent + "  ");
                        trimBlank(out);
                        out.append('\n').append(indent).append("]\n\n");
                    }
                    case OPEN -> {
                        if (!c.title().isEmpty()) {
                            out.append(indent).append("#ike-block-title[").append(escape(c.title())).append("]\n");
                        }
                        blocks(c.blocks(), out, indent);
                    }
                }
            }
        }
    }

    private static void table(Table t, StringBuilder out, String indent) {
        int columns = Math.max(1, t.columns());
        out.append(indent).append("#figure(\n");
        out.append(indent).append("  table(\n");
        out.append(indent).append("    columns: (");
        for (int i = 0; i < columns; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(i < t.widths().size() ? t.widths().get(i) : 1).append("fr");
        }
        out.append("),\n");
        int header = Math.min(t.headerRows(), t.rows().size());
        if (header > 0) {
            out.append(indent).append("    table.header(repeat: true,\n");
            for (int r = 0; r < header; r++) {
                row(t.rows().get(r), columns, out, indent + "      ");
            }
            out.append(indent).append("    ),\n");
        }
        for (int r = header; r < t.rows().size(); r++) {
            row(t.rows().get(r), columns, out, indent + "    ");
        }
        out.append(indent).append("  ),\n");
        if (!t.title().isEmpty()) {
            out.append(indent).append("  caption: [").append(escape(t.title())).append("],\n");
        }
        out.append(indent).append("  kind: table,\n");
        out.append(indent).append(")\n\n");
    }

    private static void row(List<String> cells, int columns, StringBuilder out, String indent) {
        out.append(indent);
        for (int c = 0; c < columns; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            out.append('[').append(escape(cell)).append("], ");
        }
        out.append('\n');
    }

    /** Drops the trailing blank lines a block run leaves, so a closing bracket follows the content. */
    private static void trimBlank(StringBuilder sb) {
        while (!sb.isEmpty() && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
    }

    // ── text ──────────────────────────────────────────────────────────────────

    /** Prose as markup: escaped, with a hard line break where the text breaks a line. */
    private static String markup(String text) {
        String out = escape(text).replace("\n", " \\\n");
        return CHUNK_WORDS.get() ? chunk(out) : out;
    }

    /** Whether paragraphs are being written for the word-labelled measurement: see {@link #chunk}. */
    private static final ThreadLocal<Boolean> CHUNK_WORDS = ThreadLocal.withInitial(() -> false);
    /** Words per text element in the word-labelled form. */
    static final int CHUNK = 120;

    /**
     * The word-labelling show rule wraps every match of one text element in content of its own,
     * and typst realizes those as nested groups: a paragraph of some hundred words exceeds its
     * grouping depth. A zero-width space ({@code #h(0pt)}) after every {@value #CHUNK}th word ends
     * the text element there without adding a glyph, a space or a break opportunity the following
     * space does not already give, so each element stays shallow; the layout is pixel-identical.
     */
    static String chunk(String markup) {
        StringBuilder out = new StringBuilder(markup.length() + 64);
        int words = 0;
        for (int i = 0; i < markup.length(); i++) {
            char c = markup.charAt(i);
            if (c == ' ' && i > 0 && markup.charAt(i - 1) != ' ' && markup.charAt(i - 1) != '\\') {
                if (++words % CHUNK == 0) {
                    out.append("#h(0pt)");
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String points(double value) {
        return (value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value)) + "pt";
    }

    private static String fonts(List<String> families) {
        if (families.size() == 1) {
            return string(families.getFirst());
        }
        List<String> quoted = new ArrayList<>();
        for (String f : families) {
            quoted.add(string(f));
        }
        return "(" + String.join(", ", quoted) + ")";
    }

    /**
     * A Typst string literal.
     *
     * @param plain the plain text
     * @return the quoted, escaped literal
     */
    public static String string(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 2);
        sb.append('"');
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A raw block fence the content cannot close: one more backtick than the longest run
     * inside, never fewer than three.
     *
     * @param content the raw content
     * @return the fence
     */
    public static String fence(String content) {
        int longest = 0;
        int run = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '`') {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 0;
            }
        }
        return "`".repeat(Math.max(3, longest + 1));
    }

    /**
     * Escapes plain text for Typst markup: the characters that open expressions, styling,
     * math, labels, references and comments, the shorthands ({@code --}, {@code -?},
     * {@code ...}), a {@code ;}, and the line-leading characters that start lists, headings
     * and terms.
     *
     * @param plain plain text
     * @return escaped markup text
     */
    public static String escape(String plain) {
        StringBuilder sb = new StringBuilder(plain.length() + 8);
        boolean lineStart = true;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (lineStart && c != ' ' && c != '\t') {
                if (startsBlockMarker(plain, i)) {
                    sb.append('\\');
                }
                lineStart = false;
            }
            char next = i + 1 < plain.length() ? plain.charAt(i + 1) : 0;
            char prev = i > 0 ? plain.charAt(i - 1) : 0;
            switch (c) {
                case '\\', '#', '*', '_', '`', '$', '<', '>', '@', '[', ']', '~', ';' -> sb.append('\\').append(c);
                case '-' -> {
                    if (next == '-' || next == '?' || prev == '-') {
                        sb.append("\\-");
                    } else {
                        sb.append(c);
                    }
                }
                case '.' -> {
                    if (next == '.' && i + 2 < plain.length() && plain.charAt(i + 2) == '.') {
                        sb.append("\\.");
                    } else {
                        sb.append(c);
                    }
                }
                case '/' -> {
                    if (next == '/' || next == '*') {
                        sb.append("\\/");
                    } else {
                        sb.append(c);
                    }
                }
                case '\n' -> {
                    sb.append(c);
                    lineStart = true;
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Whether the text at {@code i} (a line's first non-blank) would start a list item, heading or term. */
    private static boolean startsBlockMarker(String s, int i) {
        char c = s.charAt(i);
        boolean spaceAfter = i + 1 < s.length() && (s.charAt(i + 1) == ' ' || s.charAt(i + 1) == '\t');
        if ((c == '-' || c == '+' || c == '/') && (spaceAfter || i + 1 == s.length())) {
            return true;
        }
        if (c == '=') {
            return true;
        }
        if (Character.isDigit(c)) {
            int j = i;
            while (j < s.length() && Character.isDigit(s.charAt(j))) {
                j++;
            }
            return j < s.length() && s.charAt(j) == '.' && (j + 1 == s.length() || s.charAt(j + 1) == ' ');
        }
        return false;
    }
}
