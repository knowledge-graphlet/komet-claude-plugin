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
package network.ike.komet.claude.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tokens the structured blocks share: the {@code k:} concept token with its identity, the
 * ANF interval, numbers as the blocks write them, and the quoting of free text. The ANF block
 * ({@code AnfAdoc}) and the property-graph block ({@code DecisionTreeText}) parse and emit
 * through these, so a concept or a measure reads the same in both.
 *
 * <ul>
 *   <li><b>Concept</b>: {@code k:Key[label] @id=<identifier>}. The key and the label are human
 *       aids, never trusted on parse; the identifier is the identity, a {@code PublicId}'s
 *       comma-joined UUIDs or an SCTID, re-resolved through the live store.</li>
 *   <li><b>Interval</b>: {@code [lo,hi]}, with {@code (} or {@code )} for an open bound and
 *       {@code -inf} or {@code inf} for an unbounded side.</li>
 * </ul>
 */
public final class Tokens {

    private Tokens() {
    }

    /** A concept token as written: its key, its label, and its identifier when it carries one. */
    public record Concept(String key, String label, Optional<String> identifier) {
    }

    /** An interval as written; a null bound is unbounded. */
    public record Interval(Double lower, Double upper, boolean includeLower, boolean includeUpper) {
    }

    /** An interval at the head of a text, and whatever follows it. */
    public record IntervalPrefix(Interval interval, String rest) {
    }

    private static final Pattern CONCEPT = Pattern.compile("^k:([^\\[\\s]*)\\[(.*?)]\\s*(?:@id=(\\S+))?\\s*$");
    private static final Pattern INTERVAL = Pattern.compile("^([\\[(])\\s*(\\S+?)\\s*,\\s*(\\S+?)\\s*([\\])])\\s*(.*)$");

    // ── Parse ────────────────────────────────────────────────────────────────

    /** Reads a whole text as a concept token. */
    public static Optional<Concept> concept(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = CONCEPT.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Concept(m.group(1), m.group(2), Optional.ofNullable(m.group(3))));
    }

    /** Reads an interval at the head of a text, leaving the rest. */
    public static Optional<IntervalPrefix> intervalPrefix(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = INTERVAL.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        Interval interval = new Interval(bound(m.group(2)), bound(m.group(3)),
                "[".equals(m.group(1)), "]".equals(m.group(4)));
        return Optional.of(new IntervalPrefix(interval, m.group(5).strip()));
    }

    /** Parses an interval bound: {@code ±inf} and {@code inf} denote an unbounded side (null). */
    public static Double bound(String s) {
        String t = s.trim();
        if (t.equals("inf") || t.equals("+inf") || t.equals("-inf")) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Splits a text into words on whitespace, keeping bracketed labels and quoted text whole, so
     * {@code k:Scan[Scan now?] @id=x basis=cited} is three words.
     */
    public static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                word.append(c);
                if (c == '\\' && i + 1 < text.length()) {
                    word.append(text.charAt(++i));
                } else if (c == '"') {
                    quoted = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    quoted = true;
                    word.append(c);
                }
                case '[', '(' -> {
                    // a bracket opens a label or an interval; a parenthesis opens an open-ended
                    // interval, (lo,hi], and either closer ends it, so [0,5) is one word
                    depth++;
                    word.append(c);
                }
                case ']', ')' -> {
                    depth = Math.max(0, depth - 1);
                    word.append(c);
                }
                case ' ', '\t' -> {
                    if (depth > 0) {
                        word.append(c);
                    } else if (!word.isEmpty()) {
                        words.add(word.toString());
                        word.setLength(0);
                    }
                }
                default -> word.append(c);
            }
        }
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    /** Writes a concept token; a null identifier writes none. */
    public static String concept(String label, String identifier) {
        String token = "k:" + keyOf(label) + "[" + label + "]";
        return identifier == null ? token : token + " @id=" + identifier;
    }

    /** Writes an interval; a null bound writes as unbounded. */
    public static String interval(Double lower, Double upper, boolean includeLower, boolean includeUpper) {
        String lo = lower == null ? "-inf" : number(lower);
        String hi = upper == null ? "inf" : number(upper);
        return (includeLower ? "[" : "(") + lo + "," + hi + (includeUpper ? "]" : ")");
    }

    /** Writes a number: an integral value without a fraction, any other exactly as Java prints it, so it reads back unchanged. */
    public static String number(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /** A synthetic CamelCase key from a label: a human and identicon aid only, never parsed. */
    public static String keyOf(String label) {
        String base = (label == null ? "" : label).replaceAll("\\(.*?\\)", " ");
        StringBuilder k = new StringBuilder();
        for (String part : base.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                k.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return k.isEmpty() ? "Concept" : k.toString();
    }

    public static String quote(String s) {
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    public static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
