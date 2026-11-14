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

import network.ike.komet.document.DecisionFigure;
import network.ike.komet.document.DecisionFigure.Mark;
import network.ike.komet.document.DecisionFigure.Rect;
import network.ike.komet.document.DecisionFigure.Text;
import network.ike.komet.document.DecisionFigureText;
import network.ike.komet.document.TextFigure;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An ANF statement as a page reads it (KEC, 2026-11-13): one line, under the group it is about.
 * The group, the statement's subject of information, is a heading; the line has what was
 * observed, the result beside it, and beneath it in small type how and when it was known. A
 * narrative statement has its quoted words where a result would be.
 *
 * <p>No field name and no interval notation reaches the reader. Equal bounds print as one value,
 * {@code [1982,1982] year} as 1982 and {@code [55,55] Joints} as 55 joints; an interval prints
 * as 30% to 70%; {@code [1,1] presence} prints as Present, a share as a percentage, and a timing
 * of {@code [0,30] month} as within 30 months.
 *
 * <p>Statements one directly after another read as a run: a statement about the group of the
 * statement before it does not print the heading again. The reader is store-free, as a page is:
 * it reads the block's lines, the concepts as their names.
 */
final class StatementLines {

    /** The role of a statement's figure. */
    static final String ROLE = "anf-statement";

    private static final double WIDTH = TextFigure.WIDTH;
    private static final double SIZE = 10;
    private static final double SMALL = 8.5;
    private static final double LINE = DecisionFigureText.lineHeight(SIZE);
    private static final double SMALL_LINE = DecisionFigureText.lineHeight(SMALL);
    /** How far a statement's line stands in from its group's heading. */
    private static final double INDENT = 10;
    /** The room the result's column takes at the right, and the gap before it. */
    private static final double RESULT = 132;
    private static final double GAP = 14;
    /** The colour of the rule beneath a line. */
    private static final String RULE = "#C8C8C8";

    /** A field: its name, then {@code ::} or {@code :}, then its value. */
    private static final Pattern FIELD = Pattern.compile("^\\s*([A-Za-z][\\w -]*?)\\s*::?\\s+(.*)$");
    /** A measure as a block writes it: its bounds, each closed or open, then its unit. */
    private static final Pattern INTERVAL = Pattern.compile("^([\\[(])\\s*([^,\\])]*?)\\s*,\\s*([^\\])]*?)\\s*([\\])])\\s*(.*)$");
    private static final Pattern TYPE = Pattern.compile("\\btype\\s*=\\s*\"?([\\w-]+)");

    private StatementLines() {
    }

    /**
     * A statement as its line prints it, every part already in a reader's words.
     *
     * @param type   the statement's type as its attribute line names it, {@code performance},
     *               {@code request} or {@code narrative}; empty when it names none
     * @param topic  what was observed or requested; empty for a block that names none
     * @param group  whom or what it is about, the subject of information; empty when it names none
     * @param result the result, or the requested result, in words; empty when it has none
     * @param within the timing, in words, set beneath the result; empty when it has none
     * @param how    the method by which it was known; empty when it has none
     * @param when   the statement's time; empty when it has none
     * @param note   a narrative statement's words, without their quotation marks; empty when it has none
     * @param rest   every other field, each as its name in plain words and its value
     */
    record Statement(String type, String topic, String group, String result, String within, String how, String when, String note,
                     List<String> rest) {
        Statement {
            rest = List.copyOf(rest);
        }
    }

    /**
     * Reads a statement from its block.
     *
     * @param parameters the block's attribute line
     * @param lines      the lines between the block's fences
     * @return the statement; its topic is empty when the lines name none
     */
    static Statement read(String parameters, List<String> lines) {
        Map<String, String> fields = new LinkedHashMap<>();
        String last = null;
        for (String line : lines) {
            String read = BlockPrint.named(line);
            String text = read.strip();
            if (text.isEmpty() || text.matches("-{2,}") || text.startsWith("[")) {
                continue;
            }
            Matcher field = FIELD.matcher(read);
            if (field.matches()) {
                last = field.group(1).strip().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                fields.merge(last, field.group(2).strip(), (was, more) -> was + "; " + more);
            } else if (last != null) {
                // a value that runs on to a second line
                fields.merge(last, text, (was, more) -> was + " " + more);
            }
        }
        Matcher type = TYPE.matcher(parameters == null ? "" : parameters);
        String topic = take(fields, "topic");
        String group = take(fields, "subject_of_information");
        String result = fields.containsKey("result") ? take(fields, "result") : take(fields, "requested_result");
        String within = take(fields, "timing");
        String how = take(fields, "method");
        String when = take(fields, "statement_time");
        String note = take(fields, "text");
        List<String> rest = new ArrayList<>();
        fields.forEach((name, value) -> rest.add(name.replace('_', ' ') + ": " + measure(value)));
        return new Statement(type.find() ? type.group(1).toLowerCase(Locale.ROOT) : "", topic, group, measure(result), timing(within), how,
                time(when), unquoted(note), rest);
    }

    private static String take(Map<String, String> fields, String name) {
        String value = fields.remove(name);
        return value == null ? "" : value;
    }

    private static String unquoted(String text) {
        String t = text.strip();
        return t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"") ? t.substring(1, t.length() - 1).strip() : t;
    }

    /**
     * A statement's figure.
     *
     * @param statement the statement
     * @param heading   whether its group is printed above it: true for the first statement about
     *                  a group, false for one that follows a statement about the same group
     * @return the figure
     */
    static DecisionFigure figure(Statement statement, boolean heading) {
        List<Mark> marks = new ArrayList<>();
        double y = 0;
        if (heading && !statement.group().isEmpty()) {
            for (String line : DecisionFigureText.wrap(statement.group(), SIZE, WIDTH)) {
                marks.add(new Text(0, y, line, SIZE, false, true, DecisionFigureText.CAPTION, "statement-group"));
                y += LINE;
            }
            y += 2;
        }
        List<String> result = statement.result().isEmpty() ? List.of() : DecisionFigureText.wrap(statement.result(), SIZE, RESULT);
        List<String> within = statement.within().isEmpty() ? List.of() : DecisionFigureText.wrap(statement.within(), SMALL, RESULT);
        boolean column = !result.isEmpty() || !within.isEmpty();
        double room = WIDTH - INDENT - (column ? RESULT + GAP : 0);
        double top = y + 2;
        double left = top;
        for (String line : DecisionFigureText.wrap(statement.topic(), SIZE, room)) {
            marks.add(new Text(INDENT, left, line, SIZE, false, false, DecisionFigureText.INK, "statement-topic"));
            left += LINE;
        }
        String detail = detail(statement);
        if (!detail.isEmpty()) {
            for (String line : DecisionFigureText.wrap(detail, SMALL, room)) {
                marks.add(new Text(INDENT, left, line, SMALL, false, false, DecisionFigureText.CAPTION, "statement-detail"));
                left += SMALL_LINE;
            }
        }
        double right = top;
        double at = WIDTH - RESULT;
        for (String line : result) {
            marks.add(new Text(at, right, line, SIZE, false, false, DecisionFigureText.INK, "statement-result"));
            right += LINE;
        }
        for (String line : within) {
            marks.add(new Text(at, right, line, SMALL, false, false, DecisionFigureText.CAPTION, "statement-timing"));
            right += SMALL_LINE;
        }
        y = Math.max(left, right) + 3;
        marks.add(new Rect(INDENT, y, WIDTH - INDENT, 0.5, RULE, 1, null, "statement-rule"));
        return new DecisionFigure(WIDTH, y + 0.5, ROLE, marks);
    }

    /** What stands beneath the topic in small type: the narrative's words, how it was known, when, and any other field. */
    static String detail(Statement statement) {
        List<String> parts = new ArrayList<>();
        if (!statement.note().isEmpty()) {
            parts.add("\u201C" + statement.note() + "\u201D");
        }
        if ("request".equals(statement.type())) {
            parts.add("requested");
        }
        if (!statement.how().isEmpty()) {
            parts.add(statement.how());
        }
        if (!statement.when().isEmpty()) {
            parts.add(statement.when());
        }
        parts.addAll(statement.rest());
        return String.join(" \u00B7 ", parts);
    }

    /**
     * A measure in a reader's words: present or absent, a percentage, an amount of money, or a
     * number with its unit; an interval as its two ends, a bound on one side as "at least" or
     * "at most". Text that is not a measure is returned as it stands.
     *
     * @param written the measure as the block writes it, its concepts already read as their names
     * @return the words
     */
    static String measure(String written) {
        String text = written == null ? "" : written.strip();
        Matcher m = INTERVAL.matcher(text);
        if (!m.matches() || !bound(m.group(2)) || !bound(m.group(3))) {
            return text;
        }
        Double lower = number(m.group(2));
        Double upper = number(m.group(3));
        boolean lowerOpen = m.group(1).equals("(");
        boolean upperOpen = m.group(4).equals(")");
        String unit = m.group(5).strip();
        String named = unit.toLowerCase(Locale.ROOT);
        if (named.startsWith("presence")) {
            if (lower != null && upper != null && lower == 0 && upper == 0) {
                return "Absent";
            }
            if (lower != null && lower == 1 && (upper == null || upper == 1)) {
                return "Present";
            }
            // the interval a record writes when it does not determine the result
            if (lower != null && upper != null && lower == 0 && upper == 1) {
                return "Not determined";
            }
        }
        boolean share = (named.contains("share") || named.contains("fraction") || named.contains("probability") || named.contains("proportion"))
                && (lower == null || (lower >= 0 && lower <= 1)) && (upper == null || (upper >= 0 && upper <= 1));
        String symbol = DecisionFigureText.symbolFor(unit);
        Function<Double, String> one = share ? value -> DecisionFigureText.percent(value, false)
                : !symbol.isEmpty() ? value -> (value < 0 ? "\u2212" : "") + symbol + DecisionFigureText.shown(Math.abs(value), false, true)
                : value -> DecisionFigureText.shown(value, false, false);
        boolean bare = share || !symbol.isEmpty() || unit.isEmpty() || named.startsWith("count");
        return ends(lower, upper, lowerOpen, upperOpen, one) + (bare ? "" : " " + unitWords(unit, lower, upper));
    }

    /**
     * A statement's time in a reader's words: a year as the year, with no separator in it, and
     * a span as its two ends.
     *
     * @param written the time as the block writes it
     * @return the words
     */
    static String time(String written) {
        String text = written == null ? "" : written.strip();
        Matcher m = INTERVAL.matcher(text);
        if (!m.matches() || !bound(m.group(2)) || !bound(m.group(3))) {
            return text;
        }
        Double lower = number(m.group(2));
        Double upper = number(m.group(3));
        String unit = m.group(5).strip();
        String suffix = unit.isEmpty() || unit.equalsIgnoreCase("year") ? "" : " " + unitWords(unit, lower, upper);
        if (lower != null && lower.equals(upper)) {
            return whole(lower) + suffix;
        }
        if (lower != null && upper != null) {
            return whole(lower) + " to " + whole(upper) + suffix;
        }
        if (lower != null) {
            return "since " + whole(lower) + suffix;
        }
        return upper == null ? "" : "until " + whole(upper) + suffix;
    }

    /**
     * A timing in a reader's words: a span from nothing as "within", one point in time as "at",
     * any other as a measure reads.
     *
     * @param written the timing as the block writes it
     * @return the words
     */
    static String timing(String written) {
        String text = written == null ? "" : written.strip();
        Matcher m = INTERVAL.matcher(text);
        if (!m.matches() || !bound(m.group(2)) || !bound(m.group(3))) {
            return text;
        }
        Double lower = number(m.group(2));
        Double upper = number(m.group(3));
        String unit = m.group(5).strip();
        if (lower != null && upper != null && lower == 0 && upper > 0) {
            return "within " + DecisionFigureText.shown(upper, false, false) + (unit.isEmpty() ? "" : " " + unitWords(unit, upper, upper));
        }
        if (lower != null && lower.equals(upper)) {
            return "at " + measure(text);
        }
        return measure(text);
    }

    private static String ends(Double lower, Double upper, boolean lowerOpen, boolean upperOpen, Function<Double, String> one) {
        if (lower != null && lower.equals(upper)) {
            return one.apply(lower);
        }
        if (lower != null && upper != null) {
            return one.apply(lower) + " to " + one.apply(upper);
        }
        if (lower != null) {
            return (lowerOpen ? "more than " : "at least ") + one.apply(lower);
        }
        return upper == null ? "any" : (upperOpen ? "less than " : "at most ") + one.apply(upper);
    }

    /**
     * A unit as it reads after a number: a name that begins with one capital begins in lower
     * case, since it follows the number in a phrase, and a one-word unit in lower case is plural
     * beside any number but one. A name that begins with an abbreviation is left as it is.
     */
    private static String unitWords(String unit, Double lower, Double upper) {
        String words = unit.length() > 1 && Character.isUpperCase(unit.charAt(0)) && Character.isLowerCase(unit.charAt(1))
                ? Character.toLowerCase(unit.charAt(0)) + unit.substring(1) : unit;
        boolean one = lower != null && lower.equals(upper) && lower == 1;
        return !one && words.matches("[a-z]+") && !words.endsWith("s") ? words + "s" : words;
    }

    /** Whether a bound is one a measure may have: a number, nothing, or infinity. */
    private static boolean bound(String text) {
        String t = text.strip();
        if (t.isEmpty() || t.matches("(?i)[+\\-\u2212]?(inf|infinity|\u221E)")) {
            return true;
        }
        try {
            Double.parseDouble(t.replace(",", ""));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** A bound as a number; null for one that is unbounded. */
    private static Double number(String text) {
        String t = text.strip();
        if (t.isEmpty() || t.matches("(?i)[+\\-\u2212]?(inf|infinity|\u221E)")) {
            return null;
        }
        return Double.parseDouble(t.replace(",", ""));
    }

    /** A number as a year or a day reads: a whole number has no separator in it. */
    private static String whole(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
