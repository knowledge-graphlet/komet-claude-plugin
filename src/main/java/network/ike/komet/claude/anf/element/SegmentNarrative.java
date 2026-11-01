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
package network.ike.komet.claude.anf.element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A narrative made of a stamped document's stored sentences ({@code dev-anf-element-isolation},
 * "The written form"; the sentences of {@code design-2026-10-21-stored-text-segments}): the
 * sentences of each paragraph joined by a space, the paragraphs by a blank line. A span anchors
 * to the sentence it lies in and the column range it covers there, with the words it covered:
 * {@code segment:<sentence id>[2,19]=62-year-old man}. The sentence id is the sentence vertex's
 * identifier, which the store derives from the sentence's segment key, the commit that introduced
 * it, the file's path and its position, so every store that materializes the repository names it
 * alike. A span across two sentences is one anchor per sentence.
 *
 * <p>Found again, an anchor needs only its own sentence: an edit to another sentence moves the
 * narrative's offsets but not the column range. When its own sentence is revised, the anchor
 * follows it: the words are looked for in the revised sentence, nearest the old columns, and an
 * anchor whose words the sentence no longer holds is not found, which the lift reports.
 */
public final class SegmentNarrative implements Anchoring {

    private static final String SEGMENT = "segment:";
    private static final String SENTENCE_SEPARATOR = " ";
    private static final String PARAGRAPH_SEPARATOR = "\n\n";

    /** One stored sentence: its vertex's identifier and its text. */
    public record Sentence(String id, String text) {
        public Sentence {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(text, "text");
        }
    }

    /** A sentence where the narrative holds it. */
    private record Placed(Sentence sentence, int start) {
        int end() {
            return start + sentence.text().length();
        }
    }

    /** A segment anchor read back: the sentence, the column range and the words it covered. */
    record Anchor(String id, int from, int to, String words) {
    }

    private final String narrative;
    private final List<Placed> placed;

    private SegmentNarrative(String narrative, List<Placed> placed) {
        this.narrative = narrative;
        this.placed = placed;
    }

    /** The narrative of paragraphs of sentences, in order; empty paragraphs are skipped. */
    public static SegmentNarrative of(List<List<Sentence>> paragraphs) {
        StringBuilder text = new StringBuilder();
        List<Placed> placed = new ArrayList<>();
        for (List<Sentence> paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(PARAGRAPH_SEPARATOR);
            }
            for (int i = 0; i < paragraph.size(); i++) {
                if (i > 0) {
                    text.append(SENTENCE_SEPARATOR);
                }
                placed.add(new Placed(paragraph.get(i), text.length()));
                text.append(paragraph.get(i).text());
            }
        }
        return new SegmentNarrative(text.toString(), List.copyOf(placed));
    }

    @Override
    public String narrative() {
        return narrative;
    }

    /** The sentences, in narrative order. */
    public List<Sentence> sentences() {
        return placed.stream().map(Placed::sentence).toList();
    }

    /**
     * One sentence's part of a span: the sentence, the columns of its text the span covers, from
     * inclusive to exclusive, and the words there.
     */
    public record Piece(Sentence sentence, int from, int to, String words) {

        /** @return whether the span covers the whole sentence */
        public boolean whole() {
            return from == 0 && to == sentence.text().length();
        }
    }

    /**
     * The parts of sentences a span covers, in order; separators between sentences, and stretches
     * with no letter or digit, belong to none.
     */
    public List<Piece> pieces(Span span) {
        List<Piece> pieces = new ArrayList<>();
        for (Placed p : placed) {
            int from = Math.max(span.start(), p.start());
            int to = Math.min(span.end(), p.end());
            if (to <= from) {
                continue;
            }
            String words = narrative.substring(from, to);
            if (words.chars().noneMatch(Character::isLetterOrDigit)) {
                continue;
            }
            pieces.add(new Piece(p.sentence(), from - p.start(), to - p.start(), words));
        }
        return pieces;
    }

    /**
     * Where columns of a sentence lie in the narrative.
     *
     * @param sentenceId the sentence's identifier
     * @param from       the first column of its text
     * @param to         one past the last
     * @return the span; empty when the narrative does not hold the sentence or the columns do not fit it
     */
    public Optional<Span> span(String sentenceId, int from, int to) {
        for (Placed p : placed) {
            if (p.sentence().id().equals(sentenceId) && from >= 0 && from < to && to <= p.sentence().text().length()) {
                return Optional.of(new Span(p.start() + from, p.start() + to));
            }
        }
        return Optional.empty();
    }

    @Override
    public String anchor(Span span) {
        List<String> anchors = pieces(span).stream()
                .map(p -> SEGMENT + p.sentence().id() + "[" + p.from() + "," + p.to() + "]=" + p.words())
                .toList();
        // a span between sentences, in their separators alone, has no sentence to anchor to
        return anchors.isEmpty() ? span.quoteAnchor(narrative) : String.join("|", anchors);
    }

    @Override
    public Optional<Span> resolve(String anchor) {
        if (anchor == null) {
            return Optional.empty();
        }
        if (!anchor.startsWith(SEGMENT)) {
            return Span.resolve(anchor, narrative);
        }
        Optional<Anchor> read = read(anchor);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        Anchor a = read.get();
        for (Placed p : placed) {
            if (!p.sentence().id().equals(a.id())) {
                continue;
            }
            String text = p.sentence().text();
            if (a.to() <= text.length() && a.from() < a.to() && text.substring(a.from(), a.to()).equals(a.words())) {
                return Optional.of(new Span(p.start() + a.from(), p.start() + a.to()));
            }
            // the sentence was revised: follow the words to where they are now, nearest the old columns
            int at = nearest(text, a.words(), a.from(), false);
            if (at < 0) {
                at = nearest(text, a.words(), a.from(), true);
            }
            return at < 0 ? Optional.empty() : Optional.of(new Span(p.start() + at, p.start() + at + a.words().length()));
        }
        return Optional.empty();
    }

    /** Whether this narrative holds the sentence an anchor names. */
    public boolean holds(String anchor) {
        return read(anchor).map(a -> placed.stream().anyMatch(p -> p.sentence().id().equals(a.id()))).orElse(false);
    }

    /** The sentence ids a {@code from} value's segment anchors name, in order. */
    public static List<String> ids(String from) {
        List<String> ids = new ArrayList<>();
        if (from == null) {
            return ids;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            read(anchor.strip()).map(Anchor::id).filter(id -> !ids.contains(id)).ifPresent(ids::add);
        }
        return ids;
    }

    /** The words a segment anchor covered; empty for any other anchor. */
    public static Optional<String> words(String anchor) {
        return read(anchor).map(Anchor::words);
    }

    /** Reads {@code segment:<id>[from,to]=<words>}. */
    static Optional<Anchor> read(String anchor) {
        if (anchor == null || !anchor.startsWith(SEGMENT)) {
            return Optional.empty();
        }
        int open = anchor.indexOf('[', SEGMENT.length());
        int close = open < 0 ? -1 : anchor.indexOf(']', open);
        if (open < 0 || close < 0 || close + 1 >= anchor.length() || anchor.charAt(close + 1) != '=') {
            return Optional.empty();
        }
        String[] range = anchor.substring(open + 1, close).split(",");
        if (range.length != 2) {
            return Optional.empty();
        }
        try {
            String words = anchor.substring(close + 2);
            return words.isEmpty() ? Optional.empty() : Optional.of(new Anchor(anchor.substring(SEGMENT.length(), open),
                    Integer.parseInt(range[0].strip()), Integer.parseInt(range[1].strip()), words));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The start of the occurrence of {@code words} in {@code text} nearest {@code column}, or -1. */
    private static int nearest(String text, String words, int column, boolean ignoreCase) {
        String haystack = ignoreCase ? text.toLowerCase(Locale.ROOT) : text;
        String needle = ignoreCase ? words.toLowerCase(Locale.ROOT) : words;
        if (haystack.length() != text.length()) {
            return -1;
        }
        int best = -1;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            if (best < 0 || Math.abs(i - column) < Math.abs(best - column)) {
                best = i;
            }
        }
        return best;
    }
}
