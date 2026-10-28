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

import java.util.Locale;

/**
 * The bounds of an ANF measure, and the interval arithmetic a rollback does on them: a lower and
 * an upper bound, each open or closed. A point value is the bounds that meet. Sums and products
 * follow Moore's interval arithmetic, so the result of a rollback is an honest range, wider than
 * the truth where one input appears on several branches (the dependency problem, accepted:
 * {@code dev-decision-tree-blocks}).
 *
 * @param lower     the lower bound
 * @param upper     the upper bound
 * @param lowerOpen whether the lower bound is excluded
 * @param upperOpen whether the upper bound is excluded
 */
public record Bounds(double lower, double upper, boolean lowerOpen, boolean upperOpen) {

    /** Tolerance for comparing bounds that arithmetic has rounded. */
    static final double EPSILON = 1e-9;

    public Bounds {
        if (Double.isNaN(lower) != Double.isNaN(upper)) {
            throw new IllegalArgumentException("one bound is not a number");
        }
        if (lower > upper + EPSILON) {
            throw new IllegalArgumentException("lower bound " + lower + " exceeds upper bound " + upper);
        }
    }

    /** No bounds at all: what a derived measure states before its derivation runs. */
    public static Bounds none() {
        return new Bounds(Double.NaN, Double.NaN, false, false);
    }

    /** Whether these are no bounds. */
    public boolean absent() {
        return Double.isNaN(lower);
    }

    /** The bounds that meet at one value. */
    public static Bounds point(double value) {
        return new Bounds(value, value, false, false);
    }

    /** Closed bounds. */
    public static Bounds of(double lower, double upper) {
        return new Bounds(lower, upper, false, false);
    }

    public boolean isPoint() {
        return lower == upper;
    }

    public double width() {
        return upper - lower;
    }

    public double midpoint() {
        return (lower + upper) / 2;
    }

    public Bounds plus(Bounds other) {
        return new Bounds(lower + other.lower, upper + other.upper,
                lowerOpen || other.lowerOpen, upperOpen || other.upperOpen);
    }

    /** Multiplies by a non-negative scalar. */
    public Bounds scale(double factor) {
        if (factor < 0) {
            throw new IllegalArgumentException("a scale factor is not negative: " + factor);
        }
        return new Bounds(lower * factor, upper * factor, lowerOpen, upperOpen);
    }

    /** The interval product: the least and greatest of the four endpoint products. */
    public Bounds times(Bounds other) {
        double[] products = {lower * other.lower, lower * other.upper, upper * other.lower, upper * other.upper};
        boolean[] open = {lowerOpen || other.lowerOpen, lowerOpen || other.upperOpen,
                upperOpen || other.lowerOpen, upperOpen || other.upperOpen};
        int least = 0;
        int greatest = 0;
        for (int i = 1; i < 4; i++) {
            if (products[i] < products[least] || (products[i] == products[least] && !open[i])) {
                least = i;
            }
            if (products[i] > products[greatest] || (products[i] == products[greatest] && !open[i])) {
                greatest = i;
            }
        }
        return new Bounds(products[least], products[greatest], open[least], open[greatest]);
    }

    /** The interval quotient, for a divisor that does not cross zero: the least and greatest endpoint quotients. */
    public Bounds dividedBy(Bounds divisor) {
        if (divisor.lower <= 0 && divisor.upper >= 0) {
            throw new ArithmeticException("a divisor crosses zero: " + divisor);
        }
        double[] quotients = {lower / divisor.lower, lower / divisor.upper, upper / divisor.lower, upper / divisor.upper};
        boolean[] open = {lowerOpen || divisor.lowerOpen, lowerOpen || divisor.upperOpen,
                upperOpen || divisor.lowerOpen, upperOpen || divisor.upperOpen};
        int least = 0;
        int greatest = 0;
        for (int i = 1; i < 4; i++) {
            if (quotients[i] < quotients[least] || (quotients[i] == quotients[least] && !open[i])) {
                least = i;
            }
            if (quotients[i] > quotients[greatest] || (quotients[i] == quotients[greatest] && !open[i])) {
                greatest = i;
            }
        }
        return new Bounds(quotients[least], quotients[greatest], open[least], open[greatest]);
    }

    /** One minus these bounds: the complement of a share. */
    public Bounds complement() {
        return new Bounds(1 - upper, 1 - lower, upperOpen, lowerOpen);
    }

    /** These bounds held within a floor and a ceiling. */
    public Bounds clip(double floor, double ceiling) {
        double lo = Math.max(floor, Math.min(ceiling, lower));
        double hi = Math.max(floor, Math.min(ceiling, upper));
        return new Bounds(lo, hi, lowerOpen && lo == lower, upperOpen && hi == upper);
    }

    /** The least of two bounds, bound by bound: what a decision maker can be sure to pay at worst and at best. */
    public Bounds least(Bounds other) {
        boolean lo = lower < other.lower ? lowerOpen : other.lower < lower ? other.lowerOpen : lowerOpen && other.lowerOpen;
        boolean hi = upper < other.upper ? upperOpen : other.upper < upper ? other.upperOpen : upperOpen && other.upperOpen;
        return new Bounds(Math.min(lower, other.lower), Math.min(upper, other.upper), lo, hi);
    }

    /** Whether every value in these bounds is at most every value in the other's: a sure choice when costs are minimized. */
    public boolean noGreaterThan(Bounds other) {
        return upper <= other.lower + EPSILON;
    }

    /** Whether a value lies within these bounds, honoring open ends. */
    public boolean contains(double value) {
        boolean aboveLower = lowerOpen ? value > lower : value >= lower - EPSILON;
        boolean belowUpper = upperOpen ? value < upper : value <= upper + EPSILON;
        return aboveLower && belowUpper;
    }

    @Override
    public String toString() {
        if (isPoint()) {
            return format(lower);
        }
        return (lowerOpen ? "(" : "[") + format(lower) + ", " + format(upper) + (upperOpen ? ")" : "]");
    }

    private static String format(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%,d", (long) value);
        }
        return String.format(Locale.ROOT, "%,.4f", value);
    }
}
