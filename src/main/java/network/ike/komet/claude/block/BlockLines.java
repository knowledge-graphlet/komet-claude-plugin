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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The line grammar every property-graph block shares, read before any profile looks at it
 * ({@code dev-decision-tree-blocks}). A block is lines; indentation is the edge, a line's parent
 * being the nearest line above it with less indentation, in any consistent unit. Two forms:
 *
 * <pre>
 * word: head attr=value …      a vertex: its kind word, its head token and its attributes
 * word = value attr=value …    a property or a measure of the vertex above it
 * </pre>
 *
 * The header line ({@code [decision-tree]} or {@code [property-graph, profile=…]}) and the
 * listing delimiters are accepted and skipped, so a whole block or its bare body parses alike.
 * Everything after the word is kept as written for the profile to read; this class settles only
 * the shape. A line that is neither form is a problem, reported with its number, never thrown.
 */
public final class BlockLines {

    private BlockLines() {
    }

    /** Whether a line declares a vertex or a value of the vertex above. */
    public enum Form { VERTEX, VALUE }

    /**
     * One line of the block.
     *
     * @param number   the line's number in the text, from one
     * @param indent   its leading whitespace, in characters
     * @param form     vertex or value
     * @param word     the kind word or the value's key
     * @param rest     everything after the separator, as written and stripped
     * @param children the lines nested beneath it
     */
    public record Line(int number, int indent, Form form, String word, String rest, List<Line> children) {

        /** The rest split into words, bracketed labels and quoted text kept whole. */
        public List<String> words() {
            return Tokens.words(rest);
        }

        /** The head: the words before the first {@code name=value} attribute, joined by one space. */
        public String head() {
            StringBuilder head = new StringBuilder();
            for (String word : words()) {
                if (isAttribute(word)) {
                    break;
                }
                if (!head.isEmpty()) {
                    head.append(' ');
                }
                head.append(word);
            }
            return head.toString();
        }

        /** The attributes, {@code name=value}, in written order; a repeated name keeps its last value. */
        public Map<String, String> attributes() {
            Map<String, String> attributes = new LinkedHashMap<>();
            boolean inHead = true;
            for (String word : words()) {
                if (isAttribute(word)) {
                    inHead = false;
                    int eq = word.indexOf('=');
                    attributes.put(word.substring(0, eq), word.substring(eq + 1));
                } else if (!inHead) {
                    attributes.put(word, "");
                }
            }
            return attributes;
        }

        /** One attribute's value, if written. */
        public Optional<String> attribute(String name) {
            return Optional.ofNullable(attributes().get(name));
        }

        /** Whether a word is {@code name=value}; the {@code @id=} of a concept token belongs to the head, not here. */
        private static boolean isAttribute(String word) {
            int eq = word.indexOf('=');
            if (eq <= 0 || word.startsWith("k:") || word.startsWith("\"") || word.startsWith("@id=")) {
                return false;
            }
            return word.substring(0, eq).matches("@?[A-Za-z][A-Za-z0-9_-]*");
        }
    }

    /** The lines read, as a forest, and whatever could not be read. */
    public record Parsed(List<Line> roots, List<String> problems) {
    }

    private static final Pattern LINE = Pattern.compile("^([A-Za-z][A-Za-z0-9_-]*)\\s*(:|=)\\s*(.*)$");
    private static final Pattern HEADER = Pattern.compile("^\\[[A-Za-z].*]$");
    private static final Pattern DELIMITER = Pattern.compile("^-{2,}$");

    /** Reads a block or its body into its lines. */
    public static Parsed parse(String text) {
        List<Line> roots = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        if (text == null) {
            return new Parsed(roots, problems);
        }
        Deque<Line> open = new ArrayDeque<>();
        int number = 0;
        for (String raw : text.split("\n", -1)) {
            number++;
            String stripped = raw.strip();
            if (stripped.isEmpty() || HEADER.matcher(stripped).matches() || DELIMITER.matcher(stripped).matches()) {
                continue;
            }
            int indent = raw.length() - raw.stripLeading().length();
            Matcher m = LINE.matcher(stripped);
            if (!m.matches()) {
                problems.add("line " + number + ": neither a vertex (word: …) nor a value (word = …): " + stripped);
                continue;
            }
            Form form = ":".equals(m.group(2)) ? Form.VERTEX : Form.VALUE;
            Line line = new Line(number, indent, form, m.group(1), m.group(3).strip(), new ArrayList<>());
            while (!open.isEmpty() && open.peek().indent() >= indent) {
                open.pop();
            }
            if (open.isEmpty()) {
                roots.add(line);
            } else {
                open.peek().children().add(line);
            }
            open.push(line);
        }
        return new Parsed(roots, problems);
    }
}
