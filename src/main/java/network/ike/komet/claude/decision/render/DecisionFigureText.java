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
package network.ike.komet.claude.decision.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a figure builder knows about text without a font engine: an estimate of a run's width
 * from the average widths of a Helvetica-like sans face, in em per character, so the geometry a
 * layout computes from label widths is the same on every machine and in every medium, and the
 * figure's palette and number formats, which the live JavaFX surface shares.
 *
 * <p>Kept word for word in step with its twin in {@code ike-docs/koncept-asciidoc-extension}.
 */
public final class DecisionFigureText {

    private DecisionFigureText() {
    }

    /** The ink of ordinary text and outlines. */
    public static final String INK = "#333333";
    /** The colour of the chosen alternative, and of a tornado bar where the base choice holds. */
    public static final String CHOSEN = "#1f3b73";
    /** The colour of a gap: a value the rollback could not determine, or a bar where another alternative wins. */
    public static final String GAP = "#b5541a";
    /** The colour of a flip and its threshold. */
    public static final String HOT = "#d98b1a";
    /** The colour of a choice left open. */
    public static final String OPEN = "#9a9a9a";
    /** The colour of an axis and its labels. */
    public static final String AXIS = "#777777";
    /** The colour of a derived value, which the reader cannot move. */
    public static final String DERIVED = "#5a5a5a";
    /** The colour of a caption. */
    public static final String CAPTION = "#666666";
    public static final String DECISION_FILL = "#e8eef9";
    public static final String CHANCE_FILL = "#fff3e0";
    public static final String OUTCOME_FILL = "#eaf4e8";
    public static final String FOLDED_FILL = "#d9d9d9";

    /** The font family the writers name, whose average widths the estimate is of. */
    public static final String FAMILY = "Helvetica, Arial, sans-serif";

    /** The height of a line of text of a size, as the builders space lines. */
    public static double lineHeight(double size) {
        return Math.ceil(size * 1.35);
    }

    /** Where the baseline of a line of text of a size sits beneath its top. */
    public static double ascent(double size) {
        return size * 0.8;
    }

    /**
     * The estimated width of a run of text: each character by its class in a Helvetica-like
     * face, bold a twentieth wider, a space between words narrow.
     */
    public static double width(String text, double size, boolean bold) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double ems = 0;
        for (int i = 0; i < text.length(); i++) {
            ems += em(text.charAt(i));
        }
        return ems * size * (bold ? 1.05 : 1);
    }

    private static double em(char c) {
        if (c == ' ') {
            return 0.28;
        }
        if ("il.,:;'|!".indexOf(c) >= 0) {
            return 0.25;
        }
        if ("jtfrI()[]-".indexOf(c) >= 0) {
            return 0.33;
        }
        if ("mwMW".indexOf(c) >= 0) {
            return 0.86;
        }
        if (Character.isUpperCase(c)) {
            return 0.68;
        }
        if (Character.isDigit(c)) {
            return 0.56;
        }
        if (c > 0x2000) {
            return 0.9; // an arrow, a check, a dagger: drawn wide in most faces
        }
        return 0.54;
    }

    /** The text, or as much of it as fits a width with an ellipsis. */
    public static String fit(String text, double size, boolean bold, double maxWidth) {
        if (width(text, size, bold) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        String kept = text;
        while (!kept.isEmpty() && width(kept + ellipsis, size, bold) > maxWidth) {
            kept = kept.substring(0, kept.length() - 1);
        }
        return kept.stripTrailing() + ellipsis;
    }

    /** The text broken into lines no wider than a width, at spaces. */
    public static List<String> wrap(String text, double size, double maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(candidate, size, false) > maxWidth) {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * A measure's value as a threshold or a caption reads it: whole above a hundred, two decimals
     * above one, four beneath, since a flip found by bisection is only as exact as its range.
     */
    public static String value(double value) {
        double magnitude = Math.abs(value);
        if (magnitude >= 100) {
            return String.format(Locale.ROOT, "%,d", Math.round(value));
        }
        if (magnitude >= 1) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    /** A length as the writers print it: at most two decimals, no trailing zeros. */
    public static String length(double value) {
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }
}
