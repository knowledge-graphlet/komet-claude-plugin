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

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
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
        return ems * size * (bold ? 1.05 : 1) * FACE.get();
    }

    /** How much wider the face being measured for runs than the Helvetica-like one {@link #width} estimates. */
    private static final ThreadLocal<Double> FACE = ThreadLocal.withInitial(() -> 1.0);
    /** Noto Serif, the page's face, over the estimate. */
    private static final double SERIF = 1.12;

    /**
     * Builds something measured for the page's serif rather than the estimate's sans, so its
     * labels and wrapped lines stand clear of each other and of its edge in that face.
     *
     * @param build what to build
     * @param <T>   what it builds
     * @return what it built
     */
    public static <T> T inSerif(java.util.function.Supplier<T> build) {
        FACE.set(SERIF);
        try {
            return build.get();
        } finally {
            FACE.remove();
        }
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

    /**
     * A share as a reader says it: a computed one as a percentage to three significant figures,
     * no wider, so a threshold of 0.017463 reads 1.75%; a stated one as the block wrote it, so
     * 0.214 reads 21.4% and 0.9 reads 90%.
     */
    public static String percent(double share, boolean computed) {
        if (Double.isNaN(share)) {
            return "?";
        }
        if (computed) {
            return shown(share * 100, true, false) + "%";
        }
        return group(BigDecimal.valueOf(share).movePointRight(2).stripTrailingZeros()) + "%";
    }

    /** A computed share as a percentage; see {@link #percent(double, boolean)}. */
    public static String percent(double share) {
        return percent(share, true);
    }

    /**
     * The sign a money unit is written with when it has a familiar one, from the unit's name:
     * a dollar's {@code $}, a euro's {@code €}, a pound's {@code £}, a yen's {@code ¥}; empty
     * for any other, which then prints its name.
     */
    public static String symbolFor(String unitName) {
        if (unitName == null) {
            return "";
        }
        String name = unitName.toLowerCase(Locale.ROOT);
        if (name.contains("dollar") || name.equals("usd")) {
            return "$";
        }
        if (name.contains("euro") || name.equals("eur")) {
            return "\u20AC";
        }
        if (name.contains("pound") || name.equals("gbp")) {
            return "\u00A3";
        }
        if (name.contains("yen") || name.equals("jpy")) {
            return "\u00A5";
        }
        return "";
    }

    /**
     * A measure's bounds as a chip on the tree prints them, in the measure's unit: a share as a
     * percentage, money after its symbol, anything else followed by its unit's name; an interval
     * in brackets. A stated number prints as the block wrote it, a computed one no wider than it
     * earns, as {@link #shown} prints them.
     */
    public static String chip(double lower, double upper, boolean lowerOpen, boolean upperOpen, boolean computed,
                              Unit unit, String symbol) {
        String prefix = unit == Unit.MONEY && symbol != null ? symbol : "";
        String suffix = unit == Unit.PLAIN && symbol != null && !symbol.isBlank() ? " " + symbol : "";
        if (lower == upper) {
            return one(lower, computed, unit, prefix) + suffix;
        }
        return (lowerOpen ? "(" : "[") + one(lower, computed, unit, prefix) + ", " + one(upper, computed, unit, prefix)
                + (upperOpen ? ")" : "]") + suffix;
    }

    private static String one(double value, boolean computed, Unit unit, String prefix) {
        return switch (unit) {
            case SHARE -> percent(value, computed);
            case MONEY -> (value < 0 ? "\u2212" : "") + prefix + shown(Math.abs(value), computed, true);
            case PLAIN -> shown(value, computed, false);
        };
    }

    /**
     * A quantity on a tornado's caption or threshold, with its unit: a share as a percentage, money
     * after its symbol to the whole unit, anything else to three significant figures followed by
     * its unit's name when it has one. A negative amount of money carries its sign before the
     * symbol.
     */
    public static String quantity(double value, Unit unit, String symbol) {
        if (Double.isNaN(value)) {
            return "?";
        }
        return switch (unit) {
            case SHARE -> percent(value);
            case MONEY -> (value < 0 ? "−" : "") + (symbol == null ? "" : symbol) + shown(Math.abs(value), true, true);
            case PLAIN -> shown(value, true, false) + (symbol == null || symbol.isBlank() ? "" : " " + symbol);
        };
    }

    /** What a swept quantity is: a share of those at a node, money, or anything else with a unit of its own. */
    public enum Unit { SHARE, MONEY, PLAIN }

    /**
     * A measure's value as its chip shows it. A stated number prints as the block wrote it and no
     * wider; a computed one is held to what its inputs can carry: money to the whole unit of its
     * currency, a share or a quantity to three significant figures, since a product of two-figure
     * assumptions never earns a fifth decimal.
     */
    public static String shown(double value, boolean computed, boolean money) {
        if (Double.isNaN(value)) {
            return "?";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "∞" : "−∞";
        }
        if (computed && money) {
            return String.format(Locale.ROOT, "%,d", Math.round(value));
        }
        BigDecimal number = computed
                ? new BigDecimal(value).round(new MathContext(3, RoundingMode.HALF_UP))
                : BigDecimal.valueOf(value);
        return group(number.stripTrailingZeros());
    }

    /**
     * A value at a step's resolution: as many decimals as the step has, so a slider stepping by
     * 0.05 shows 0.35, never 0.34999999.
     */
    public static String atStep(double value, double step) {
        int decimals = Math.max(0, new BigDecimal(Double.toString(step)).stripTrailingZeros().scale());
        return group(BigDecimal.valueOf(value).setScale(decimals, RoundingMode.HALF_UP));
    }

    /**
     * A count of a cohort: whole from ten up, to two significant figures beneath, so 214.2 reads
     * 214, 3.31 reads 3.3, 2.04 reads 2 and 0.41 reads 0.41.
     */
    public static String count(double count) {
        if (Double.isNaN(count)) {
            return "?";
        }
        if (count >= 10) {
            return String.format(Locale.ROOT, "%,d", Math.round(count));
        }
        return group(new BigDecimal(count).round(new MathContext(2, RoundingMode.HALF_UP)).stripTrailingZeros());
    }

    /** How many of a cohort go a way, as a branch says it: "214 of 1,000", or "all 1,000" when every one does. */
    public static String ofCohort(double count, double size) {
        if (Math.abs(count - size) < 1e-9) {
            return "all " + count(size);
        }
        // the legend says how many of the cohort go that way; the run says the count alone
        return count(count);
    }

    /**
     * Where a cohort that takes one alternative ends up, as one sentence: the outcomes largest
     * first, each with its count and, where fewer than a third arrive, how rare it is as one in so
     * many. The outcome names and counts are given in order.
     */
    public static String tally(String alternative, double size, List<String> names, List<Double> counts) {
        return tally(alternative, false, size, names, counts);
    }

    /** Where a cohort ends up, entering a tree of consequences with no choice at its root when {@code entering}. */
    public static String tally(String alternative, boolean entering, double size, List<String> names, List<Double> counts) {
        StringBuilder sb = new StringBuilder("Of ").append(count(size)).append(entering ? " who reach \u201C" : " who take \u201C")
                .append(alternative).append("\u201D: ");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(i == names.size() - 1 ? " and " : ", ");
            }
            double n = counts.get(i);
            sb.append(count(n)).append(' ').append(names.get(i));
            if (n > 0 && n < size / 3) {
                sb.append(" (1 in ").append(count(size / n)).append(')');
            }
        }
        return sb.append('.').toString();
    }

    /**
     * What the choice at the root trades, as one sentence: how the chosen alternative stands
     * against the next best in money and in health, and the price of a healthy year at which
     * they tie, against the price the tree was read at.
     *
     * @param money   how much more money the chosen costs, printed with its sign dropped; negative means less
     * @param health  how much more healthy life the chosen keeps, printed with its sign dropped; negative means less
     */
    public static String tradeoff(String chosen, String other, double money, String moneyText, double health, String healthText,
                                  String priceText, String pricedText) {
        return tradeoff(chosen, other, money, moneyText, health, healthText, priceText, pricedText, false, List.of());
    }

    /**
     * The trade with what the tree counts weighed in: {@code countedAgainst} when the chosen
     * alternative brings more of some counted unit than the next best, so that it does not win
     * outright however health is priced; and a clause per counted unit that differs, already
     * worded, which follows the sentence.
     */
    public static String tradeoff(String chosen, String other, double money, String moneyText, double health, String healthText,
                                  String priceText, String pricedText, boolean countedAgainst, List<String> clauses) {
        String c = "\u201C" + chosen + "\u201D";
        String o = "\u201C" + other + "\u201D";
        boolean dearer = money > 1e-9;
        boolean cheaper = money < -1e-9;
        boolean healthier = health > 1e-9;
        boolean sicker = health < -1e-9;
        String sentence;
        boolean withHealth = healthText != null && !healthText.isEmpty();
        if (!dearer && !sicker) {
            String moneyPart = cheaper ? "costs " + moneyText + " less than " + o : "costs no more than " + o;
            String healthPart = !withHealth ? "" : healthier ? " and spares " + healthText : " and loses no more healthy life";
            sentence = c + " " + moneyPart + healthPart
                    + (countedAgainst ? "." : withHealth ? ": it wins at any price of a healthy year." : ": it wins outright.");
        } else if (dearer && healthier) {
            sentence = c + " costs " + moneyText + " more than " + o + " and spares " + healthText
                    + ": a healthy year bought at " + priceText + ", against the " + pricedText + " it is priced at here.";
        } else if (cheaper && sicker) {
            sentence = c + " costs " + moneyText + " less than " + o + " and gives up " + healthText
                    + ": a healthy year given up for " + priceText + ", against the " + pricedText + " it is priced at here.";
        } else {
            sentence = c + " costs " + moneyText + " more than " + o + " and gives up " + healthText + ".";
        }
        StringBuilder sb = new StringBuilder(sentence);
        for (String clause : clauses) {
            sb.append(' ').append(clause);
        }
        return sb.toString();
    }

    /**
     * A clause for one counted unit: who spares how many, per how many cases, and at what in
     * money each, when the other side pays for it. "“scan” spares 3.3 ruptures per 1,000, at
     * $590,000 each."; "“wear test” would spare 185,400 patients with an implant removed per
     * 100, at $54 each."
     */
    public static String countClause(String sparer, boolean chosenSpares, String countText, String unit, String perText,
                                     String eachText) {
        String s = "\u201C" + sparer + "\u201D";
        String verb = chosenSpares ? " spares " : " would spare ";
        return s + verb + countText + " " + unit + " per " + perText + (eachText == null ? "." : ", at " + eachText + " each.");
    }

    /** When nothing is priced: the choice is left open, and the ledger alone weighs the alternatives. */
    public static final String UNPRICED = "Nothing here is priced, so the choice is left open: the ledger beneath weighs the"
            + " alternatives in what they count.";

    /** A plain decimal with its thousands grouped. */
    private static String group(BigDecimal number) {
        String plain = number.toPlainString();
        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "" : plain.substring(dot);
        boolean negative = whole.startsWith("-");
        if (negative) {
            whole = whole.substring(1);
        }
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) {
                grouped.append(',');
            }
            grouped.append(whole.charAt(i));
        }
        return (negative ? "-" : "") + grouped + fraction;
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
