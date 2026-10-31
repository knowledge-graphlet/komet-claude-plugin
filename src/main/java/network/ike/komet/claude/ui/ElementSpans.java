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
package network.ike.komet.claude.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import network.ike.komet.claude.anf.element.ElementColours;
import network.ike.komet.claude.anf.element.ElementText;

/**
 * Where a document's clinical elements rest in its narrative ({@code dev-anf-element-isolation},
 * "What the reader sees"). Before a turn renders, {@link #prepare} reads its element fences, each
 * {@code anf} block naming an {@code element=} and the {@code from=} quotes it rests on, and its
 * {@code anf-not-clinical} marks; then, as the turn's text runs are drawn in order, {@link #marks}
 * says which characters of each run are an element's span, to be underlined in the element's
 * colour, which are marked not clinical, and which, in a run that holds a span, support nothing
 * and are shaded. A quote names its occurrence ({@code quote:man#2}), so occurrences are counted
 * across the turn's runs in order. The colours and the block that carries the level switch stay
 * known after the turn is drawn, since a block's node is built later, when it is laid out.
 */
public final class ElementSpans {

    /** How a stretch of a run is drawn. */
    public enum Kind {
        /** No element's span, in a run that holds none: drawn as it is. */
        PLAIN,
        /** An element's span: underlined in its colour. */
        SPAN,
        /** Marked as carrying no clinical fact: drawn as it is. */
        NOT_CLINICAL,
        /** Words, in a run that holds a span, that support no element and are not marked: shaded. */
        UNCOVERED
    }

    /** One stretch of a run: {@code [start, end)}, how it is drawn, and the element's colour for a span. */
    public record Mark(int start, int end, Kind kind, String colour) {
    }

    /** An element fence or a not-clinical mark, as the turn's Markdown writes it. */
    record Fence(String info, boolean element, List<ElementText.Quote> quotes, String colour) {
    }

    private final Map<String, String> colours = new HashMap<>();
    private final Set<String> hosts = new HashSet<>();
    private List<Fence> active = List.of();
    private final Map<String, Integer> seen = new HashMap<>();

    /** Reads a turn's element fences before it renders, and starts counting its quotes afresh. */
    public synchronized void prepare(String markdown) {
        active = scan(markdown);
        seen.clear();
        boolean first = true;
        for (Fence fence : active) {
            if (fence.element()) {
                colours.put(fence.info(), fence.colour());
                if (first) {
                    hosts.add(fence.info());
                    first = false;
                }
            }
        }
    }

    /** The colour of the element a fence names, as {@link #prepare} numbered it. */
    public synchronized Optional<String> colour(String info) {
        return Optional.ofNullable(info == null ? null : colours.get(info.strip()));
    }

    /** Whether a fence is its turn's first element, which carries the level switch. */
    public synchronized boolean isSwitchHost(String info) {
        return info != null && hosts.contains(info.strip());
    }

    /** Whether the turn being drawn has any element spans: if not, no run needs marking. */
    public synchronized boolean hasSpans() {
        return !active.isEmpty();
    }

    /** The fences of a turn's Markdown, in order: element fences numbered for colour, then not-clinical marks among them. */
    static List<Fence> scan(String markdown) {
        List<Fence> fences = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) {
            return fences;
        }
        String fenceMark = null;
        int index = 0;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (fenceMark != null) {
                char mark = fenceMark.charAt(0);
                if (trimmed.startsWith(fenceMark) && trimmed.chars().allMatch(c -> c == mark)) {
                    fenceMark = null;
                }
                continue;
            }
            if (!(trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                continue;
            }
            int n = 0;
            while (n < trimmed.length() && trimmed.charAt(n) == trimmed.charAt(0)) {
                n++;
            }
            fenceMark = trimmed.substring(0, n);
            String info = trimmed.substring(n).strip();
            String first = info.split("[\\s,]", 2)[0];
            Map<String, String> attributes = ElementText.attributes(info);
            List<ElementText.Quote> quotes = ElementText.quoteAnchors(attributes.get("from"));
            if (quotes.isEmpty()) {
                continue;
            }
            if (first.equals("anf") && attributes.containsKey("element")) {
                fences.add(new Fence(info, true, quotes, ElementColours.of(index++)));
            } else if (first.equals("anf-not-clinical")) {
                fences.add(new Fence(info, false, quotes, null));
            }
        }
        return fences;
    }

    /**
     * How the next text run of the turn is drawn, stretch by stretch, covering the whole run.
     * An overlap goes to the earlier fence; an element's span outranks a not-clinical mark.
     */
    public synchronized List<Mark> marks(String run) {
        if (run == null || run.isEmpty()) {
            return List.of();
        }
        if (active.isEmpty()) {
            return List.of(new Mark(0, run.length(), Kind.PLAIN, null));
        }
        // where each quoted text occurs in this run, numbered on from the occurrences in earlier runs
        Map<String, List<Integer>> found = new LinkedHashMap<>();
        for (Fence fence : active) {
            for (ElementText.Quote quote : fence.quotes()) {
                found.computeIfAbsent(quote.text(), text -> occurrences(run, text));
            }
        }
        int[] owner = new int[run.length()];
        java.util.Arrays.fill(owner, -1);
        List<Fence> ordered = new ArrayList<>(active.stream().filter(Fence::element).toList());
        ordered.addAll(active.stream().filter(f -> !f.element()).toList());
        for (int f = 0; f < ordered.size(); f++) {
            Fence fence = ordered.get(f);
            for (ElementText.Quote quote : fence.quotes()) {
                List<Integer> at = found.get(quote.text());
                int index = quote.occurrence() - 1 - seen.getOrDefault(quote.text(), 0);
                if (index < 0 || index >= at.size()) {
                    continue;
                }
                int start = at.get(index);
                for (int i = start; i < start + quote.text().length(); i++) {
                    if (owner[i] < 0) {
                        owner[i] = f;
                    }
                }
            }
        }
        found.forEach((text, at) -> seen.merge(text, at.size(), Integer::sum));
        boolean anySpan = false;
        for (int o : owner) {
            anySpan |= o >= 0 && ordered.get(o).element();
        }
        List<Mark> marks = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= run.length(); i++) {
            if (i == run.length() || owner[i] != owner[start]
                    || (owner[i] < 0 && anySpan && isWordy(run.charAt(i)) != isWordy(run.charAt(start)))) {
                marks.add(mark(start, i, owner[start], ordered, anySpan, run));
                start = i;
            }
        }
        return merge(marks);
    }

    private static Mark mark(int start, int end, int owner, List<Fence> ordered, boolean anySpan, String run) {
        if (owner >= 0) {
            Fence fence = ordered.get(owner);
            return fence.element() ? new Mark(start, end, Kind.SPAN, fence.colour()) : new Mark(start, end, Kind.NOT_CLINICAL, null);
        }
        boolean wordy = run.substring(start, end).chars().anyMatch(c -> Character.isLetterOrDigit(c));
        return new Mark(start, end, anySpan && wordy ? Kind.UNCOVERED : Kind.PLAIN, null);
    }

    /** Joins neighbouring stretches drawn alike, so a shaded phrase is one run with its spaces. */
    private static List<Mark> merge(List<Mark> marks) {
        List<Mark> merged = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            Mark m = marks.get(i);
            // a space between two shaded words is shaded with them
            if (m.kind() == Kind.PLAIN && !merged.isEmpty() && merged.getLast().kind() == Kind.UNCOVERED
                    && i + 1 < marks.size() && marks.get(i + 1).kind() == Kind.UNCOVERED) {
                m = new Mark(m.start(), m.end(), Kind.UNCOVERED, null);
            }
            if (!merged.isEmpty() && merged.getLast().kind() == m.kind()
                    && java.util.Objects.equals(merged.getLast().colour(), m.colour())) {
                Mark last = merged.removeLast();
                merged.add(new Mark(last.start(), m.end(), m.kind(), m.colour()));
            } else {
                merged.add(m);
            }
        }
        return merged;
    }

    private static boolean isWordy(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** Every start of {@code text} in {@code run} as whole words, ignoring case, not overlapping. */
    private static List<Integer> occurrences(String run, String text) {
        List<Integer> at = new ArrayList<>();
        if (text.isEmpty()) {
            return at;
        }
        String haystack = run.toLowerCase(Locale.ROOT);
        String needle = text.toLowerCase(Locale.ROOT);
        if (haystack.length() != run.length()) {
            return at;
        }
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            int end = i + needle.length();
            // a quote is whole words: "man" is not the end of "woman"
            boolean starts = i == 0 || !Character.isLetterOrDigit(run.charAt(i - 1)) || !Character.isLetterOrDigit(run.charAt(i));
            boolean ends = end == run.length() || !Character.isLetterOrDigit(run.charAt(end))
                    || !Character.isLetterOrDigit(run.charAt(end - 1));
            if (starts && ends) {
                at.add(i);
                i = haystack.indexOf(needle, end);
            } else {
                i = haystack.indexOf(needle, i + 1);
            }
        }
        return at;
    }
}
