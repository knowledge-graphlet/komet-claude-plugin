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

import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.StyleResolver;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttribute;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModelViewOnlyBase;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.print.DocumentFonts;
import network.ike.komet.claude.stamp.print.InlineText;
import network.ike.komet.claude.stamp.print.TypstWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The view-only model of one prose block of a stamped document (ike-lab-documents topic
 * {@code dev-stamped-document-surface}; IKE-Network/ike-issues#1307): one styled segment per
 * source line, each carrying its {@link StoredLine} in the {@link #LINE} attribute, so the
 * line under any text position — the pointer's, the selection's — is a lookup away. A
 * paragraph of the model joins its lines with the separator AsciiDoc puts between them (a
 * space for prose, nothing for a ledger that shows one line per paragraph), so the block reads
 * as prose while every word keeps its line.
 *
 * <p>The model is immutable; what the Provenance layer draws over it — commit tints, the
 * review-queue underline, the highlight of one commit's runs — is a {@link Decoration} given
 * at build time, and a surface that changes its layer builds the model again from the same
 * {@link Spec}.
 */
public final class LineRunModel extends StyledTextModelViewOnlyBase {

    /** The character attribute that carries a segment's source line. */
    public static final StyleAttribute<LineRun> LINE = StyleAttribute.character("network.ike.komet.claude.stamp.line", LineRun.class);

    /**
     * A paragraph style attribute of a page line: the paragraph is one line of a justified
     * paragraph that is not its last, so the model ends it with a filler wider than the page —
     * JavaFX justifies a line only when it wrapped, and the filler makes it wrap. The page clips
     * the filler's line away.
     */
    public static final StyleAttribute<Boolean> JUSTIFY_FILL = StyleAttribute.character("network.ike.komet.claude.stamp.justifyFill", Boolean.class);

    /**
     * One run of the model: the span of one source line in one paragraph.
     *
     * @param paragraph the paragraph index
     * @param start     the run's first character offset
     * @param end       the offset after its last character
     * @param line      the source line
     */
    public record LineRun(int paragraph, int start, int end, StoredLine line) {

        /** @return the line's nid */
        public long nid() {
            return line.nid();
        }

        /** @return the position of the run's first character */
        public TextPos startPos() {
            return TextPos.ofLeading(paragraph, start);
        }

        /** @return the position after the run's last character */
        public TextPos endPos() {
            return TextPos.ofLeading(paragraph, end);
        }

        /** @return whether the run holds the given offset of its paragraph */
        boolean covers(int offset) {
            return offset >= start && (offset < end || (start == end && offset == end));
        }
    }

    /**
     * A source line as a paragraph should read it: the stored line and the text drawn for it,
     * which may differ from the stored text (a list marker stripped, a trailing backslash gone).
     *
     * @param line the stored line
     * @param text the text the run draws
     */
    public record Line(StoredLine line, String text) { }

    /**
     * One paragraph to build: its lines, the separator between them and the base style.
     *
     * @param lines     the lines in order
     * @param separator what joins consecutive lines ({@code " "} for prose, {@code ""} for one line per paragraph)
     * @param style     the base style of every run
     * @param runs      the inline spans drawn over the base style, by offset in the paragraph's drawn text, ascending
     */
    public record Paragraph(List<Line> lines, String separator, StyleAttributeMap style, List<InlineText.Run> runs) {

        public Paragraph {
            runs = List.copyOf(runs);
        }

        /** A paragraph of one style. */
        public Paragraph(List<Line> lines, String separator, StyleAttributeMap style) {
            this(lines, separator, style, List.of());
        }

        /**
         * Cuts a range of the drawn text at the changes of inline style.
         *
         * @param from the first character
         * @param to   the character after the last
         * @return the pieces in order, covering the range, each in one style
         */
        public List<StyledPiece> pieces(int from, int to) {
            List<StyledPiece> out = new ArrayList<>();
            int at = from;
            for (InlineText.Run run : runs) {
                if (run.to() <= at || run.from() >= to) {
                    continue;
                }
                if (run.from() > at) {
                    out.add(new StyledPiece(at, run.from(), style));
                    at = run.from();
                }
                int end = Math.min(run.to(), to);
                out.add(new StyledPiece(at, end, styled(run)));
                at = end;
            }
            if (at < to) {
                out.add(new StyledPiece(at, to, style));
            }
            return out;
        }

        /** The base style with a span over it: inline raw in the mono font at the raw size, as the Typst writer sets it. */
        private StyleAttributeMap styled(InlineText.Run run) {
            StyleAttributeMap.Builder b = StyleAttributeMap.builder().merge(style);
            if (run.mono()) {
                b.setFontFamily(DocumentFonts.MONO);
                if (style.getFontSize() != null) {
                    b.setFontSize(style.getFontSize() * TypstWriter.RAW_SCALE);
                }
            }
            if (run.bold()) {
                b.setBold(true);
            }
            if (run.italic()) {
                b.setItalic(true);
            }
            return b.build();
        }
    }

    /**
     * A stretch of a paragraph in one style.
     *
     * @param from  the first character
     * @param to    the character after the last
     * @param style its style
     */
    public record StyledPiece(int from, int to, StyleAttributeMap style) { }

    /**
     * A footnote read out of a spec's prose.
     *
     * @param line the source line its macro stands on
     * @param note the note
     */
    public record FootNote(StoredLine line, InlineText.Note note) { }

    /**
     * A spec with its inline markup read: what the pages draw.
     *
     * @param spec  the spec, its lines holding the drawn text and its paragraphs the inline spans
     * @param marks where its text marks footnotes, by offset in {@link Spec#plainText}
     * @param notes the footnotes it gives, in order; a repeated mark gives none
     */
    public record Inlined(Spec spec, List<InlineText.Mark> marks, List<FootNote> notes) { }

    /**
     * What to build a model from: the paragraphs in order. Kept by the surface so the model can
     * be rebuilt with another decoration.
     *
     * @param paragraphs the paragraphs
     */
    public record Spec(List<Paragraph> paragraphs) {

        /** @return a model over these paragraphs with the given decoration */
        public LineRunModel build(Decoration decoration) {
            return new LineRunModel(this, decoration);
        }

        /** @return the drawn text, paragraphs separated by {@code \n}: what a layout oracle's unit holds */
        public String plainText() {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < paragraphs.size(); i++) {
                if (i > 0) {
                    out.append('\n');
                }
                out.append(text(paragraphs.get(i)));
            }
            return out.toString();
        }

        /** @return the inline spans of every paragraph, by offset in {@link #plainText} */
        public List<InlineText.Run> runs() {
            List<InlineText.Run> all = new ArrayList<>();
            int at = 0;
            for (Paragraph p : paragraphs) {
                for (InlineText.Run run : p.runs()) {
                    all.add(run.over(run.from() + at, run.to() + at));
                }
                at += text(p).length() + 1;
            }
            return all;
        }

        /**
         * Reads the inline markup of a spec's paragraphs, a paragraph's lines read as one text, so
         * a span may cross them. Each line keeps the drawn characters from its own start to the
         * next line's, the space between included, so the paragraph needs no separator and a
         * line that draws nothing, all of it inside a note, simply has no share.
         *
         * @param numbering the document's footnote count
         * @return the spec as the pages draw it
         */
        public Inlined inlined(InlineText.Numbering numbering) {
            List<Paragraph> read = new ArrayList<>();
            List<InlineText.Mark> marks = new ArrayList<>();
            List<FootNote> notes = new ArrayList<>();
            int at = 0;
            for (Paragraph p : paragraphs) {
                StringBuilder source = new StringBuilder();
                int[] starts = new int[p.lines().size() + 1];
                for (int i = 0; i < p.lines().size(); i++) {
                    if (i > 0) {
                        source.append('\n');
                    }
                    starts[i] = source.length();
                    source.append(drawn(p, p.lines().get(i)));
                }
                starts[p.lines().size()] = source.length();
                InlineText inline = InlineText.of(source.toString(), numbering);
                String text = inline.text().replace('\n', ' ');
                List<Line> lines = new ArrayList<>();
                for (int i = 0; i < p.lines().size(); i++) {
                    int from = inline.drawnAt(starts[i]);
                    int to = inline.drawnAt(starts[i + 1]);
                    if (from < to || (i == 0 && p.lines().size() == 1)) {
                        lines.add(new Line(p.lines().get(i).line(), text.substring(from, to)));
                    }
                }
                if (lines.isEmpty()) {
                    lines.add(new Line(p.lines().getFirst().line(), ""));
                }
                read.add(new Paragraph(lines, "", p.style(), inline.runs()));
                marks.addAll(inline.marks(at));
                for (InlineText.Note note : inline.notes()) {
                    if (!note.repeat()) {
                        int line = 0;
                        while (line + 1 < p.lines().size() && starts[line + 1] <= note.source()) {
                            line++;
                        }
                        notes.add(new FootNote(p.lines().get(line).line(), note));
                    }
                }
                at += text.length() + 1;
            }
            return new Inlined(new Spec(read), marks, notes);
        }

        /** @return the drawn text of one paragraph: its lines' texts joined by the separator */
        public static String text(Paragraph p) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < p.lines().size(); i++) {
                if (i > 0) {
                    out.append(p.separator());
                }
                out.append(drawn(p, p.lines().get(i)));
            }
            return out.toString();
        }

        private static String drawn(Paragraph p, Line line) {
            return line.text().isEmpty() && p.lines().size() == 1 ? " " : line.text();
        }

        /**
         * One page line of this spec: the characters {@code [from, to)} of one paragraph's drawn
         * text as a spec of one paragraph, each source line keeping its share, so the page line
         * carries the provenance of every character it shows.
         *
         * @param paragraph  the paragraph index
         * @param from       the first character of the paragraph's text
         * @param to         the character after the last
         * @param hyphenated whether to end the line with a hyphen the layout inserted (drawn as part of the last source line's run)
         * @param fill       whether the line is justified: a line of a justified paragraph that is not the paragraph's last
         * @return the spec, of a paragraph with no lines when the range holds none
         */
        public Spec slice(int paragraph, int from, int to, boolean hyphenated, boolean fill) {
            if (paragraph < 0 || paragraph >= paragraphs.size()) {
                return new Spec(List.of());
            }
            Paragraph p = paragraphs.get(paragraph);
            List<Line> own = new ArrayList<>();
            int first = -1;
            int at = 0;
            for (int i = 0; i < p.lines().size(); i++) {
                if (i > 0) {
                    at += p.separator().length();
                }
                String text = drawn(p, p.lines().get(i));
                int start = at;
                int end = at + text.length();
                at = end;
                int s = Math.max(start, from);
                int e = Math.min(end, to);
                if (s < e || (text.isEmpty() && from <= start && start < to)) {
                    first = first < 0 ? s : first;
                    own.add(new Line(p.lines().get(i).line(), text.substring(s - start, Math.max(s, e) - start)));
                }
            }
            int end = to;
            if (hyphenated && !own.isEmpty() && !own.getLast().text().endsWith("-")) {
                // Typst adds the hyphen only where it broke inside a word; a break after a hyphen of the text's own adds none.
                Line last = own.getLast();
                own.set(own.size() - 1, new Line(last.line(), last.text() + "-"));
                end = to + 1;                                     // a hyphen takes the style of the word it breaks
            }
            List<InlineText.Run> runs = new ArrayList<>();
            for (InlineText.Run run : p.runs()) {
                int s = Math.max(run.from(), first);
                int e = Math.min(run.to() == to ? end : run.to(), end);
                if (s < e) {
                    runs.add(run.over(s - first, e - first));
                }
            }
            StyleAttributeMap.Builder style = StyleAttributeMap.builder().merge(p.style());
            if (fill) {
                style.set(JUSTIFY_FILL, Boolean.TRUE);
            } else if (p.style().getTextAlignment() == javafx.scene.text.TextAlignment.JUSTIFY) {
                style.setTextAlignment(javafx.scene.text.TextAlignment.LEFT);   // the last line of a justified paragraph is ragged
            }
            return new Spec(own.isEmpty() ? List.of() : List.of(new Paragraph(own, p.separator(), style.build(), runs)));
        }

        /** @return the stored lines in order, each once */
        public List<StoredLine> lines() {
            List<StoredLine> all = new ArrayList<>();
            for (Paragraph p : paragraphs) {
                for (Line l : p.lines()) {
                    all.add(l.line());
                }
            }
            return all;
        }
    }

    /** The Provenance layer's contribution to a run: a background tint, an underline, a highlight. */
    public interface Decoration {

        /** No decoration: the plain document. */
        Decoration NONE = new Decoration() { };

        /** @return the tint behind the run, or {@code null} for none */
        default Color tint(StoredLine line) {
            return null;
        }

        /** @return whether the run is underlined (in the review queue) */
        default boolean underline(StoredLine line) {
            return false;
        }

        /** @return whether the run is wavily underlined (refuted) */
        default boolean wavy(StoredLine line) {
            return false;
        }

        /** @return the highlight drawn over the run (a hovered commit's runs), or {@code null} */
        default Color highlight(StoredLine line) {
            return null;
        }
    }

    private final Spec spec;
    private final List<RichParagraph> paragraphs = new ArrayList<>();
    private final List<String> plain = new ArrayList<>();
    private final List<List<LineRun>> runsByParagraph = new ArrayList<>();
    private final List<LineRun> runs = new ArrayList<>();

    private LineRunModel(Spec spec, Decoration decoration) {
        this.spec = spec;
        for (int index = 0; index < spec.paragraphs().size(); index++) {
            Paragraph p = spec.paragraphs().get(index);
            RichParagraph.Builder builder = RichParagraph.builder();
            if (p.style().contains(StyleAttributeMap.TEXT_ALIGNMENT)) {
                // Alignment is a paragraph attribute: a justified page paragraph sets it on the paragraph, not its runs.
                builder.setParagraphAttributes(StyleAttributeMap.of(StyleAttributeMap.TEXT_ALIGNMENT, p.style().getTextAlignment()));
            }
            StringBuilder text = new StringBuilder();
            List<LineRun> own = new ArrayList<>();
            for (int i = 0; i < p.lines().size(); i++) {
                Line line = p.lines().get(i);
                if (i > 0 && !p.separator().isEmpty()) {
                    builder.addSegment(p.separator(), p.style());
                    text.append(p.separator());
                }
                int start = text.length();
                String drawn = line.text().isEmpty() && p.lines().size() == 1 ? " " : line.text();
                LineRun run = new LineRun(index, start, start + drawn.length(), line.line());
                StyleAttributeMap.Builder style = StyleAttributeMap.builder().merge(p.style()).set(LINE, run);
                if (decoration.underline(line.line())) {
                    style.setUnderline(true);
                }
                StyleAttributeMap lineStyle = style.build();
                if (p.runs().isEmpty()) {
                    builder.addSegment(drawn, lineStyle);
                } else {
                    for (StyledPiece piece : p.pieces(start, start + drawn.length())) {
                        builder.addSegment(drawn.substring(piece.from() - start, piece.to() - start),
                                overlay(piece.style(), run, decoration.underline(line.line())));
                    }
                }
                Color tint = decoration.tint(line.line());
                if (tint != null && !drawn.isEmpty()) {
                    builder.addHighlight(start, drawn.length(), tint);
                }
                Color highlight = decoration.highlight(line.line());
                if (highlight != null && !drawn.isEmpty()) {
                    builder.addHighlight(start, drawn.length(), highlight);
                }
                if (decoration.wavy(line.line()) && !drawn.isEmpty()) {
                    builder.addWavyUnderline(start, drawn.length(), Color.web("#c62828"));
                }
                text.append(drawn);
                own.add(run);
                runs.add(run);
            }
            if (Boolean.TRUE.equals(p.style().get(JUSTIFY_FILL))) {
                // A line of a justified paragraph: a filler wider than any page makes JavaFX wrap after
                // the text, and so justify it; the page clips the filler's own line away.
                builder.addSegment(" ", p.style());
                text.append(' ');
                builder.addInlineNode(() -> {
                    javafx.scene.layout.Region filler = new javafx.scene.layout.Region();
                    filler.setMinSize(4000, 1);
                    filler.setPrefSize(4000, 1);
                    filler.setMaxSize(4000, 1);
                    return filler;
                });
            }
            paragraphs.add(builder.build());
            plain.add(text.toString());
            runsByParagraph.add(List.copyOf(own));
        }
        if (paragraphs.isEmpty()) {
            paragraphs.add(RichParagraph.builder().build());
            plain.add("");
            runsByParagraph.add(List.of());
        }
    }

    /** A span's style with what every piece of a source line carries: its run, and the review queue's underline. */
    private static StyleAttributeMap overlay(StyleAttributeMap style, LineRun run, boolean underline) {
        StyleAttributeMap.Builder b = StyleAttributeMap.builder().merge(style).set(LINE, run);
        if (underline) {
            b.setUnderline(true);
        }
        return b.build();
    }

    /** @return the spec this model was built from */
    public Spec spec() {
        return spec;
    }

    /** @return every run in document order */
    public List<LineRun> runs() {
        return List.copyOf(runs);
    }

    /** @return the runs of one paragraph in order */
    public List<LineRun> runs(int paragraph) {
        return paragraph >= 0 && paragraph < runsByParagraph.size() ? runsByParagraph.get(paragraph) : List.of();
    }

    /**
     * The run holding a text position: the one whose span covers the offset, or, past the
     * paragraph's text, its last run. A position on the separator between two runs resolves to
     * the run before it.
     *
     * @param pos the position
     * @return the run, or empty when the paragraph has no runs
     */
    public Optional<LineRun> runAt(TextPos pos) {
        if (pos == null) {
            return Optional.empty();
        }
        List<LineRun> own = runs(pos.index());
        if (own.isEmpty()) {
            return Optional.empty();
        }
        LineRun before = null;
        for (LineRun run : own) {
            if (run.covers(pos.offset())) {
                return Optional.of(run);
            }
            if (run.end <= pos.offset()) {
                before = run;
            }
        }
        return Optional.of(before != null ? before : own.getLast());
    }

    /**
     * The runs a span touches, snapped to whole runs: from the run holding the span's start to
     * the run holding its end, in document order, whichever way round the two positions are.
     *
     * @param a one end of the span
     * @param b the other end
     * @return the runs, empty when neither end lands on one
     */
    public List<LineRun> runsBetween(TextPos a, TextPos b) {
        Optional<LineRun> first = runAt(a);
        Optional<LineRun> last = runAt(b);
        if (first.isEmpty() || last.isEmpty()) {
            return first.or(() -> last).map(List::of).orElse(List.of());
        }
        int from = runs.indexOf(first.get());
        int to = runs.indexOf(last.get());
        if (from > to) {
            int t = from;
            from = to;
            to = t;
        }
        return List.copyOf(runs.subList(from, to + 1));
    }

    /** @return the runs introduced by the STAMP with that nid */
    public List<LineRun> runsIntroducedBy(long stampNid) {
        return runs.stream().filter(r -> r.line().introduced() != null && r.line().introduced().nid() == stampNid).toList();
    }

    // ── StyledTextModel ───────────────────────────────────────────────────────

    @Override
    public int size() {
        return paragraphs.size();
    }

    @Override
    public String getPlainText(int index) {
        return plain.get(index);
    }

    @Override
    public RichParagraph getParagraph(int index) {
        return paragraphs.get(index);
    }

    @Override
    public StyleAttributeMap getStyleAttributeMap(StyleResolver resolver, TextPos pos, boolean forInsert) {
        return runAt(pos).map(run -> StyleAttributeMap.of(LINE, run)).orElse(StyleAttributeMap.EMPTY);
    }
}
