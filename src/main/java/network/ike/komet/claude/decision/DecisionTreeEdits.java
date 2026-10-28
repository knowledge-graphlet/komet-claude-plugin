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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.block.Tokens;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The edits a what-if on a drawn decision tree makes to the block's text: a stated number moved,
 * the health price in the block's info moved. Each is a restatement of one token on one line,
 * so the block stays byte for byte what the assistant or the author wrote everywhere else, and
 * the edited text reads back through the same parser as the original. Nothing here touches a
 * derived number: a derivation moves when its operands do.
 */
public final class DecisionTreeEdits {

    /** The interval a value line states, right after its {@code =}. */
    private static final Pattern STATED = Pattern.compile("^(\\s*[A-Za-z][A-Za-z0-9_-]*\\s*=\\s*)[\\[(][^\\])]*[\\])]");
    private static final Pattern HEALTH_PRICE = Pattern.compile("(?i)\\bhealth-price=([^\\s,\\]]+)");

    private DecisionTreeEdits() {
    }

    /**
     * The body with the interval on one line restated as other bounds.
     *
     * @param body   the block's body
     * @param line   the one-based line, as the parse numbered it
     * @param bounds what the line is to state
     * @return the restated body, or empty when the line states no interval to move
     */
    public static Optional<String> restate(String body, int line, Bounds bounds) {
        String[] lines = body.split("\n", -1);
        if (line < 1 || line > lines.length) {
            return Optional.empty();
        }
        Matcher m = STATED.matcher(lines[line - 1]);
        if (!m.find()) {
            return Optional.empty();
        }
        String interval = Tokens.interval(finite(bounds.lower()), finite(bounds.upper()),
                !bounds.lowerOpen(), !bounds.upperOpen());
        lines[line - 1] = m.group(1) + interval + lines[line - 1].substring(m.end());
        return Optional.of(String.join("\n", lines));
    }

    /** The info string with its health price set, added when it had none. */
    public static String withHealthPrice(String info, double price) {
        String text = info == null ? DecisionTreeText.TAG : info.strip();
        String attribute = "health-price=" + Tokens.number(price);
        Matcher m = HEALTH_PRICE.matcher(text);
        if (m.find()) {
            return text.substring(0, m.start()) + attribute + text.substring(m.end());
        }
        return text + " " + attribute;
    }

    /**
     * Bounds from what a reader typed: one number, or an interval in the block's grammar; empty
     * when it is neither. Thousands separators and a leading currency sign are forgiven.
     */
    public static Optional<Bounds> typed(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String cleaned = text.strip().replace("$", "").replace("%", "").toLowerCase(Locale.ROOT);
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        boolean bracketed = cleaned.startsWith("[") || cleaned.startsWith("(");
        // an interval keeps its comma and loses its spaces; a number loses its thousands separators
        cleaned = bracketed ? cleaned.replace(" ", "") : cleaned.replace(",", "");
        Optional<Tokens.IntervalPrefix> interval = Tokens.intervalPrefix(cleaned);
        if (interval.isPresent() && interval.get().rest().isBlank()) {
            Tokens.Interval i = interval.get().interval();
            return Optional.of(new Bounds(
                    i.lower() == null ? Double.NEGATIVE_INFINITY : i.lower(),
                    i.upper() == null ? Double.POSITIVE_INFINITY : i.upper(),
                    !i.includeLower(), !i.includeUpper()));
        }
        try {
            return Optional.of(Bounds.point(Double.parseDouble(cleaned)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Double finite(double bound) {
        return Double.isInfinite(bound) ? null : bound;
    }
}
