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

import design.knowledge.stamp.adoc.dialect.InlineMarkup;
import design.knowledge.stamp.adoc.dialect.InlineMarkup.Span;
import network.ike.komet.claude.stamp.AsciiDocMarkdown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prose as a page prints it: AsciiDoc inline markup read once, by {@link InlineMarkup}, into the
 * text that is drawn and the styled runs over it, with the markup gone. The Typst writer writes
 * from it and the page draws from it, so the PDF and the Page layout cannot read a span
 * differently, and an oracle measures a word in the font it is drawn in, not by counting
 * backticks.
 *
 * <p>What prints: strong, emphasis and monospace, in any nesting; a link and a cross reference
 * as their text, a bare cross reference as its target's words ({@code <<anf-data-types>>}
 * prints "anf data types", as the help drawer does); a curved quotation; a visible index term
 * as its text; a footnote as its mark, its text going to the foot of the page. Anchors and
 * concealed index terms print nothing. An attribute reference stays as written. A trailing
 * {@code +} stays too: on a page it stands for the hard break, outside every line's range.
 *
 * @param text    the drawn text; a footnote's mark is its number in superscript digits
 * @param runs    the styled ranges of the text, ascending; what they leave out is plain
 * @param notes   the footnotes marked in the text, in order
 * @param offsets for each offset of the source, and its end, the offset of the drawn text it became
 */
public record InlineText(String text, List<Run> runs, List<Note> notes, int[] offsets) {

    private static final String SUPERSCRIPT = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    public InlineText {
        Objects.requireNonNull(text, "text is null");
        runs = List.copyOf(runs);
        notes = List.copyOf(notes);
    }

    /**
     * A styled range of the text.
     *
     * @param from   the first character
     * @param to     the character after the last
     * @param mono   whether it is set as inline raw, in the mono font at {@link TypstWriter#RAW_SCALE} of the size
     * @param bold   whether it is strong
     * @param italic whether it is emphasized
     * @param href   the link it is, or {@code null}
     */
    public record Run(int from, int to, boolean mono, boolean bold, boolean italic, String href) {

        /** @return the same style over another range */
        public Run over(int newFrom, int newTo) {
            return new Run(newFrom, newTo, mono, bold, italic, href);
        }

        private static final Run PLAIN = new Run(0, 0, false, false, false, null);

        private Run withMono() {
            return new Run(from, to, true, bold, italic, href);
        }

        private Run withBold() {
            return new Run(from, to, mono, true, italic, href);
        }

        private Run withItalic() {
            return new Run(from, to, mono, bold, true, href);
        }

        private Run withHref(String link) {
            return new Run(from, to, mono, bold, italic, link);
        }

        private boolean sameStyle(Run other) {
            return mono == other.mono && bold == other.bold && italic == other.italic && Objects.equals(href, other.href);
        }
    }

    /**
     * A footnote: where its mark stands, and its text.
     *
     * @param number the printed number
     * @param from   the mark's first character in the text
     * @param to     the character after the mark
     * @param id     the name given in {@code footnote:name[...]}, or {@code null}
     * @param repeat whether this mark points at a note already given, as {@code footnote:name[]} does
     * @param body   the note's text
     * @param source the source offset of the note's macro
     */
    public record Note(int number, int from, int to, String id, boolean repeat, InlineText body, int source) {

        /** @return the note as the foot of the page draws it: its number in superscript digits, then its text */
        public InlineText drawn() {
            String mark = superscript(number);
            List<Run> shifted = body.runs().stream().map(r -> r.over(r.from() + mark.length(), r.to() + mark.length())).toList();
            return new InlineText(mark + body.text(), shifted, List.of(), new int[0]);
        }
    }

    /** Where a footnote's mark stands in a unit's text, and the note's number. */
    public record Mark(int at, int note) { }

    /** A document's footnote count: one per document, whichever way it is read. */
    public static final class Numbering {
        private int last;
        private final Map<String, Integer> named = new HashMap<>();

        private int next(String id) {
            last++;
            if (id != null) {
                named.put(id, last);
            }
            return last;
        }
    }

    /**
     * Reads prose on its own, its notes numbered from 1.
     *
     * @param markup the text, physical lines joined by {@code \n}
     * @return the printed text
     */
    public static InlineText of(String markup) {
        return of(markup, new Numbering());
    }

    /**
     * Reads prose, continuing a document's footnote numbers.
     *
     * @param markup    the text, physical lines joined by {@code \n}
     * @param numbering the document's count
     * @return the printed text
     */
    public static InlineText of(String markup, Numbering numbering) {
        Reader reader = new Reader(markup, numbering);
        reader.walk(InlineMarkup.parse(markup), Run.PLAIN);
        return reader.done(markup.length());
    }

    /** @return text with no markup at all, read as it stands */
    public static InlineText literal(String text) {
        int[] offsets = new int[text.length() + 1];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = i;
        }
        return new InlineText(text, List.of(), List.of(), offsets);
    }

    /** @return the number in superscript digits */
    public static String superscript(int number) {
        StringBuilder out = new StringBuilder();
        for (char c : Integer.toString(number).toCharArray()) {
            out.append(SUPERSCRIPT.charAt(c - '0'));
        }
        return out.toString();
    }

    /** @return the offset of the drawn text a source offset became */
    public int drawnAt(int source) {
        return offsets[Math.clamp(source, 0, offsets.length - 1)];
    }

    /** @return the marks of the notes, shifted by an offset in the text they end up in */
    public List<Mark> marks(int shift) {
        return notes.stream().map(n -> new Mark(n.from() + shift, n.number())).toList();
    }

    /** Reads the spans of one text, or of one footnote's body. */
    private static final class Reader {
        private final String source;
        private final Numbering numbering;
        private final StringBuilder out = new StringBuilder();
        private final List<Run> runs = new ArrayList<>();
        private final List<Note> notes = new ArrayList<>();
        private final int[] offsets;
        private int at;

        Reader(String source, Numbering numbering) {
            this.source = source;
            this.numbering = numbering;
            this.offsets = new int[source.length() + 1];
        }

        void walk(List<Span> spans, Run style) {
            for (Span span : spans) {
                skipTo(span.start());
                switch (span.element()) {
                    case TEXT -> text(span, style);
                    case STRONG -> walk(span.children(), style.withBold());
                    case EMPHASIS -> walk(span.children(), style.withItalic());
                    case MONOSPACE -> walk(span.children(), style.withMono());
                    case CROSS_REFERENCE -> {
                        if (span.children().isEmpty()) {
                            emit(AsciiDocMarkdown.topicOf(span.attributes().getOrDefault("linkend", "")).replace('-', ' '), style);
                        } else {
                            walk(span.children(), style);
                        }
                    }
                    case LINK -> {
                        String href = span.attributes().getOrDefault("href", "");
                        if (span.children().isEmpty()) {
                            emit(href, style.withHref(href));
                        } else {
                            walk(span.children(), style.withHref(href));
                        }
                    }
                    case FOOTNOTE -> note(span);
                    case LINE_BREAK -> emit("+", style);
                    case QUOTATION -> {
                        boolean twice = "double".equals(span.attributes().get("marks"));
                        emit(twice ? "“" : "‘", style);
                        walk(span.children(), style);
                        emit(twice ? "”" : "’", style);
                    }
                    case INDEX_TERM -> walk(span.children(), style);      // a concealed term has none
                    case ANCHOR -> { }
                    default -> {
                        if (span.children().isEmpty()) {
                            emit(span.text(), style);
                        } else {
                            walk(span.children(), style);
                        }
                    }
                }
                skipTo(span.end());
            }
        }

        /** A text run: its characters, each kept against its source offset; an escape's backslash becomes nothing. */
        private void text(Span span, Run style) {
            String text = span.text();
            int i = span.start();
            for (int k = 0; k < text.length(); k++) {
                char c = text.charAt(k);
                while (i < span.end() && source.charAt(i) != c) {
                    offsets[i++] = out.length();
                }
                if (i < span.end()) {
                    offsets[i++] = out.length();
                }
                emit(String.valueOf(c), style);
            }
            at = Math.max(at, i);
        }

        /** A footnote's mark, which Typst sets tight against the word before: the space between goes. */
        private void note(Span span) {
            while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
                out.setLength(out.length() - 1);
            }
            while (!runs.isEmpty() && runs.getLast().to() > out.length()) {
                Run run = runs.removeLast();
                if (run.from() < out.length()) {
                    runs.add(run.over(run.from(), out.length()));
                }
            }
            String id = span.attributes().get("id");
            boolean repeat = id != null && span.children().isEmpty() && numbering.named.containsKey(id);
            if (!repeat && id != null && numbering.named.containsKey(id)) {
                id = null;                                        // a name given twice: Typst's label must stay unique
            }
            int number = repeat ? numbering.named.get(id) : numbering.next(id);
            Reader reader = new Reader(source, numbering);
            reader.at = span.start();
            reader.walk(span.children(), Run.PLAIN);
            String mark = superscript(number);
            int from = out.length();
            out.append(mark);
            notes.add(new Note(number, from, out.length(), id, repeat, reader.done(span.end()), span.start()));
        }

        private void emit(String text, Run style) {
            int from = out.length();
            out.append(text);
            if (style == Run.PLAIN) {
                return;
            }
            Run last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && last.to() == from && last.sameStyle(style)) {
                runs.set(runs.size() - 1, last.over(last.from(), out.length()));
            } else {
                runs.add(style.over(from, out.length()));
            }
        }

        private void skipTo(int to) {
            while (at < to) {
                offsets[at++] = out.length();
            }
        }

        InlineText done(int end) {
            skipTo(end);
            for (int i = at; i < offsets.length; i++) {
                offsets[i] = out.length();
            }
            // Spaces given up to a footnote's mark were counted when they were read.
            int ceiling = out.length();
            for (int i = offsets.length - 1; i >= 0; i--) {
                ceiling = Math.min(ceiling, offsets[i]);
                offsets[i] = ceiling;
            }
            return new InlineText(out.toString(), runs, notes, offsets);
        }
    }
}
