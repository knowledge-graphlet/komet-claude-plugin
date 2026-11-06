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
package network.ike.komet.claude.benefitrisk;

/**
 * An effect on a common scale with its 95% confidence interval, oriented so that a positive effect
 * favors the first alternative.
 *
 * @param central the effect
 * @param lower   its lower limit
 * @param upper   its upper limit
 */
public record EffectSize(double central, double lower, double upper) {

    /** The normal quantile of a 95% interval, to the precision the published formulas use. */
    private static final double Z = 1.959964;
    /** Chinn's divisor from a log odds ratio to the scale of a standardized mean difference. */
    private static final double LOG_ODDS_TO_EFFECT = 1.81;

    /**
     * Hedges' g of two independent samples, first minus second, with the small-sample correction.
     *
     * @param m1 the first mean
     * @param s1 the first standard deviation
     * @param n1 the first sample's size
     * @param m2 the second mean
     * @param s2 the second standard deviation
     * @param n2 the second sample's size
     * @return the effect, higher in the first sample being positive
     */
    public static EffectSize hedgesG(double m1, double s1, int n1, double m2, double s2, int n2) {
        double pooled = Math.sqrt(((n1 - 1) * s1 * s1 + (n2 - 1) * s2 * s2) / (n1 + n2 - 2));
        double correction = 1 - 3 / (4.0 * (n1 + n2 - 2) - 1);
        double g = correction * (m1 - m2) / pooled;
        double se = Math.sqrt((double) (n1 + n2) / (n1 * n2) + g * g / (2.0 * (n1 + n2)));
        return new EffectSize(g, g - Z * se, g + Z * se);
    }

    /**
     * An odds ratio of two counts, first against second, moved to the common scale by dividing its
     * logarithm by 1.81.
     *
     * @param a  the first sample's events
     * @param n1 the first sample's size
     * @param b  the second sample's events
     * @param n2 the second sample's size
     * @return the effect, more events in the first sample being positive
     */
    public static EffectSize oddsRatio(int a, int n1, int b, int n2) {
        double log = Math.log((double) a * (n2 - b) / ((double) (n1 - a) * b));
        double se = Math.sqrt(1.0 / a + 1.0 / (n1 - a) + 1.0 / b + 1.0 / (n2 - b));
        return new EffectSize(log / LOG_ODDS_TO_EFFECT, (log - Z * se) / LOG_ODDS_TO_EFFECT, (log + Z * se) / LOG_ODDS_TO_EFFECT);
    }

    /** The same effect the other way round, for a criterion where lower is better. */
    public EffectSize reversed() {
        return new EffectSize(-central, -upper, -lower);
    }

    /** Whether the interval leaves zero out, so the data separate the alternatives on this row. */
    public boolean separated() {
        return lower > 0 || upper < 0;
    }

    /** The effect and interval as the table prints them, {@code +1.18 (+0.32, +2.05)}. */
    public String words() {
        return signed(central) + " (" + signed(lower) + ", " + signed(upper) + ")";
    }

    /** A number with its sign, to two places, with a true minus. */
    static String signed(double value) {
        double rounded = Math.round(value * 100) / 100.0;
        return (rounded < 0 ? "−" : "+") + String.format(java.util.Locale.ROOT, "%.2f", Math.abs(rounded));
    }
}
