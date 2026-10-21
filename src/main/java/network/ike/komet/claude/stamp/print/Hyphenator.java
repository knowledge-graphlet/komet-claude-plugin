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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a word may be hyphenated, as Typst decides it: Liang's algorithm (TeX82) over the
 * hyph-utf8 patterns for American English ({@code hyphenation/hyph-en-us.tex}, vendored with its
 * licence beside the fonts), with at least two characters kept before a hyphen and three after
 * it. This is exactly what Typst 0.15.1 does for {@code lang: "en"} through the Rust crate
 * hypher 0.1.5: the same pattern file, the same bounds (2, 3), the {@code \patterns} block only
 * and not the {@code \hyphenation} exceptions, each character lowercased on its own before the
 * patterns are matched. The points are the first pass's hyphenation candidates and the second
 * pass's way of naming the cut Typst made when a word's tail landed on the next line.
 *
 * <p>Typst hyphenates only the segments of a line-break opportunity's text that are whole
 * words of letters — a UAX #29 word made of alphabetic characters only, so {@code don't},
 * {@code e.g.} and {@code JDK27} are never hyphenated, {@code self-contained} on either side of
 * its hyphen — and never inside a URL; {@link #pointsIn(CharSequence, int, int)} applies the
 * same rule to a span of a unit's text.
 */
public final class Hyphenator {

    /** The least characters before a hyphen: hypher's left bound for English. */
    public static final int LEFT_MIN = 2;
    /** The least characters after a hyphen: hypher's right bound for English. */
    public static final int RIGHT_MIN = 3;

    private static final String PATTERNS = "/network/ike/komet/claude/hyphenation/hyph-en-us.tex";
    private static final int[] NONE = new int[0];
    private static final int CACHE_LIMIT = 50_000;

    private static volatile Hyphenator english;

    private final Node root = new Node();
    private final int leftMin;
    private final int rightMin;
    private final int patterns;
    private final Map<String, int[]> cache = new ConcurrentHashMap<>();

    private Hyphenator(String tex, int leftMin, int rightMin) {
        this.leftMin = Math.max(1, leftMin);
        this.rightMin = Math.max(1, rightMin);
        this.patterns = parse(tex);
    }

    /** @return the American English hyphenator Typst uses for {@code lang: "en"}, loaded once */
    public static Hyphenator english() {
        Hyphenator h = english;
        if (h == null) {
            synchronized (Hyphenator.class) {
                h = english;
                if (h == null) {
                    try (InputStream in = Hyphenator.class.getResourceAsStream(PATTERNS)) {
                        if (in == null) {
                            throw new IllegalStateException("the hyphenation patterns " + PATTERNS + " are missing");
                        }
                        h = of(new String(in.readAllBytes(), StandardCharsets.UTF_8), LEFT_MIN, RIGHT_MIN);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    english = h;
                }
            }
        }
        return h;
    }

    /**
     * A hyphenator over a TeX pattern file.
     *
     * @param tex      the file's text: its {@code \patterns{...}} block is read, {@code %} comments and all else skipped
     * @param leftMin  the least characters before a hyphen
     * @param rightMin the least characters after a hyphen
     * @return the hyphenator
     */
    public static Hyphenator of(String tex, int leftMin, int rightMin) {
        return new Hyphenator(tex, leftMin, rightMin);
    }

    /** @return how many patterns were read */
    public int patterns() {
        return patterns;
    }

    /**
     * The offsets of a word at which it may be hyphenated, ascending: a hyphen may follow the
     * characters before each offset. The word is taken as it is — no segmenting, see
     * {@link #pointsIn(CharSequence, int, int)} for that.
     *
     * @param word the word
     * @return the offsets in {@code char}s; empty when the word has no hyphenation point
     */
    public int[] points(String word) {
        int[] cached = cache.get(word);
        if (cached != null) {
            return cached.clone();
        }
        int[] points = compute(word);
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(word, points);
        return points.clone();
    }

    private int[] compute(String word) {
        int[] cps = word.codePoints().toArray();
        int n = cps.length;
        if (n < leftMin + rightMin) {
            return NONE;
        }
        // The dotted, lowercased word: a dot either side lets a pattern match the word's edges.
        int[] dotted = new int[n + 2];
        dotted[0] = '.';
        for (int i = 0; i < n; i++) {
            dotted[i + 1] = lower(cps[i]);
        }
        dotted[n + 1] = '.';
        int[] levels = new int[n - 1];                            // between dotted[k + 1] and dotted[k + 2]: before cps[k + 1]
        int minSplit = 1 + leftMin;
        int maxSplit = 1 + n - rightMin;
        for (int start = 0; start < dotted.length; start++) {
            Node node = root;
            for (int i = start; i < dotted.length; i++) {
                node = node.child(dotted[i]);
                if (node == null) {
                    break;
                }
                if (node.levels != null) {
                    for (int k = 0; k < node.levels.length; k++) {
                        int split = start + k;
                        if (node.levels[k] > 0 && split >= minSplit && split <= maxSplit) {
                            levels[split - 2] = Math.max(levels[split - 2], node.levels[k]);
                        }
                    }
                }
            }
        }
        List<Integer> out = new ArrayList<>();
        int offset = 0;
        for (int k = 0; k < n - 1; k++) {
            offset += Character.charCount(cps[k]);
            if (levels[k] % 2 == 1) {
                out.add(offset);
            }
        }
        return out.isEmpty() ? NONE : out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** A code point lowercased as hypher does it: only when the lowercase is one code point of the same UTF-8 length. */
    private static int lower(int cp) {
        if (cp < 128) {
            return Character.toLowerCase(cp);
        }
        String lower = new String(Character.toChars(cp)).toLowerCase(Locale.ROOT);
        if (lower.codePointCount(0, lower.length()) != 1) {
            return cp;
        }
        int l = lower.codePointAt(0);
        return utf8Length(l) == utf8Length(cp) ? l : cp;
    }

    private static int utf8Length(int cp) {
        return cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
    }

    /**
     * The hyphenation points inside a span of text, as Typst finds them between two line-break
     * opportunities: every UAX #29 word of the span made of letters only is hyphenated on its
     * own; a word holding an apostrophe, a period, a digit or an underscore is not, nor is
     * anything in a URL.
     *
     * @param text the text
     * @param from the span's first character
     * @param to   the character after its last
     * @return the offsets in the text at which a hyphen may be inserted, ascending; empty when none
     */
    public int[] pointsIn(CharSequence text, int from, int to) {
        String span = text.subSequence(from, to).toString();
        if (span.contains("://") || span.startsWith("www.")) {
            return NONE;
        }
        List<Integer> out = new ArrayList<>();
        for (int[] segment : segments(text, from, to)) {
            String word = text.subSequence(segment[0], segment[1]).toString();
            if (!word.codePoints().allMatch(Character::isAlphabetic)) {
                continue;
            }
            for (int point : points(word)) {
                out.add(segment[0] + point);
            }
        }
        return out.isEmpty() ? NONE : out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The words of a span by the UAX #29 word rules that matter here: letters, digits and
     * connectors run together; a period, colon, apostrophe or middle dot between two of them
     * joins them; marks and format characters attach to what precedes them; everything else
     * ends a word.
     *
     * @return the words as {@code {from, to}} over the text
     */
    static List<int[]> segments(CharSequence text, int from, int to) {
        List<int[]> out = new ArrayList<>();
        int start = -1;
        int i = from;
        while (i < to) {
            int cp = Character.codePointAt(text, i);
            int n = Character.charCount(cp);
            if (wordChar(cp)) {
                if (start < 0) {
                    start = i;
                }
                i += n;
                continue;
            }
            if (start >= 0 && attaches(cp)) {
                i += n;
                continue;
            }
            if (start >= 0 && mid(cp) && i + n < to && wordChar(Character.codePointAt(text, i + n))) {
                i += n;
                continue;
            }
            if (start >= 0) {
                out.add(new int[] {start, i});
                start = -1;
            }
            i += n;
        }
        if (start >= 0) {
            out.add(new int[] {start, to});
        }
        return out;
    }

    private static boolean wordChar(int cp) {
        return Character.isAlphabetic(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER
                || Character.getType(cp) == Character.CONNECTOR_PUNCTUATION;
    }

    private static boolean attaches(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.FORMAT;
    }

    /** UAX #29 MidLetter, MidNumLet, MidNum and Single_Quote: joins two word characters. */
    private static boolean mid(int cp) {
        return switch (cp) {
            case '.', ':', ',', ';', '\'', '‘', '’', '·', '·', '՟', '״', '․', '‧', '⁄',
                 ';', '։', '،', '؍', '٬', '߸', '︐', '︓', '︔', '﹐', '﹒', '﹔',
                 '﹕', '＇', '，', '．', '：', '；' -> true;
            default -> false;
        };
    }

    /** Reads the {@code \patterns{...}} block as hypher's generator does: whitespace-separated patterns, {@code %} to the line's end a comment. */
    private int parse(String tex) {
        int count = 0;
        int i = 0;
        int n = tex.length();
        while (i < n) {
            char c = tex.charAt(i++);
            if (c == '%') {
                while (i < n && tex.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '\\' && tex.startsWith("patterns{", i)) {
                i += "patterns{".length();
                while (i < n) {
                    int s = i;
                    while (i < n && tex.charAt(i) != '}' && tex.charAt(i) != '%' && !Character.isWhitespace(tex.charAt(i))) {
                        i++;
                    }
                    if (i > s) {
                        insert(tex.substring(s, i));
                        count++;
                    }
                    if (i >= n) {
                        break;
                    }
                    char d = tex.charAt(i++);
                    if (d == '}') {
                        break;
                    }
                    if (d == '%') {
                        while (i < n && tex.charAt(i) != '\n') {
                            i++;
                        }
                    } else {
                        while (i < n && Character.isWhitespace(tex.charAt(i))) {
                            i++;
                        }
                    }
                }
            }
        }
        return count;
    }

    /** Inserts a pattern such as {@code .ad4der} or {@code 4ab1c}: its letters walk the trie, its digits are the levels before each letter and after the last. */
    private void insert(String pattern) {
        int[] cps = pattern.codePoints().toArray();
        List<Integer> letters = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        int level = 0;
        for (int cp : cps) {
            if (cp >= '0' && cp <= '9') {
                level = cp - '0';
            } else {
                levels.add(level);
                letters.add(cp);
                level = 0;
            }
        }
        levels.add(level);
        Node node = root;
        for (int cp : letters) {
            node = node.children.computeIfAbsent(cp, k -> new Node());
        }
        node.levels = levels.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class Node {
        final Map<Integer, Node> children = new HashMap<>(2);
        int[] levels;

        Node child(int cp) {
            return children.get(cp);
        }
    }

    @Override
    public String toString() {
        return "Hyphenator[" + patterns + " patterns, bounds " + leftMin + "/" + rightMin + ", cached " + cache.size() + "]";
    }

    /** @return the word with a hyphen at every point, for tests and reports */
    public String show(String word) {
        int[] points = points(word);
        if (points.length == 0) {
            return word;
        }
        StringBuilder sb = new StringBuilder();
        int last = 0;
        for (int p : points) {
            sb.append(word, last, p).append('-');
            last = p;
        }
        return sb.append(word.substring(last)).toString();
    }

    static int[] none() {
        return Arrays.copyOf(NONE, 0);
    }
}
