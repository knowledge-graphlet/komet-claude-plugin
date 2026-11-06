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

import network.ike.komet.claude.benefitrisk.EffectsTable.Alternative;
import network.ike.komet.claude.benefitrisk.EffectsTable.Arm;
import network.ike.komet.claude.benefitrisk.EffectsTable.Cited;
import network.ike.komet.claude.benefitrisk.EffectsTable.Counted;
import network.ike.komet.claude.benefitrisk.EffectsTable.Elicited;
import network.ike.komet.claude.benefitrisk.EffectsTable.Found;
import network.ike.komet.claude.benefitrisk.EffectsTable.Measured;
import network.ike.komet.claude.benefitrisk.EffectsTable.Reported;
import network.ike.komet.claude.benefitrisk.Findings.Finding;
import network.ike.komet.claude.benefitrisk.ValueTree.Better;
import network.ike.komet.claude.benefitrisk.ValueTree.Kind;
import network.ike.komet.claude.benefitrisk.ValueTree.Leaf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An effects table read against its value tree: one row per leaf, so a leaf with no evidence still
 * appears, each row's effect computed from its arms and oriented by the tree's {@code better=},
 * and the alternative it favors computed, never stored.
 *
 * @param first    the alternative a positive effect favors
 * @param second   the other
 * @param rows     the rows, in the tree's order
 * @param findings what the two blocks do not say, or say that does not agree
 */
public record EffectsReading(Alternative first, Alternative second, List<Row> rows, List<String> findings) {

    /** What an arm says when a criterion does not arise for its alternative: it passes a gate. */
    private static final String NOT_APPLICABLE = "not-applicable";

    /** What a row can say about the choice. */
    public enum Verdict { FIRST, SECOND, NOT_SEPARATED, NOT_DETERMINABLE, GATE_PASSED, GATE_FAILED }

    /**
     * One row.
     *
     * @param leaf    the tree's leaf
     * @param firstArm  what is known of the first alternative
     * @param secondArm what is known of the second
     * @param effect  the effect, when both arms have data to compute one from
     * @param method  how it was computed
     * @param verdict what the row says
     * @param favors  the verdict in words
     */
    public record Row(Leaf leaf, Optional<Arm> firstArm, Optional<Arm> secondArm, Optional<EffectSize> effect, String method,
                      Verdict verdict, String favors) {
    }

    /**
     * Reads a table against its tree.
     *
     * @param tree  the value tree
     * @param table the effects table
     * @return the reading, or empty when the table does not name two alternatives
     */
    public static Optional<EffectsReading> of(ValueTree tree, EffectsTable table) {
        return of(tree, table, new Findings(List.of(), List.of()), Integer.MAX_VALUE);
    }

    /**
     * Reads a table against its tree as the record stood in a year: an arm that cites findings says
     * what those in print by then say, and has no evidence while none is.
     *
     * @param tree     the value tree
     * @param table    the effects table
     * @param held     the findings its arms may cite
     * @param year     the year
     * @return the reading, or empty when the table does not name two alternatives
     */
    public static Optional<EffectsReading> of(ValueTree tree, EffectsTable table, Findings held, int year) {
        if (table.alternatives().size() != 2) {
            return Optional.empty();
        }
        Alternative first = table.alternatives().get(0);
        Alternative second = table.alternatives().get(1);
        List<String> problems = new ArrayList<>(table.findings());
        List<Row> rows = new ArrayList<>();
        Set<String> leaves = new java.util.HashSet<>();
        for (Leaf leaf : tree.leaves()) {
            leaves.add(leaf.part().id());
            Map<String, Arm> arms = table.rows().getOrDefault(leaf.part().id(), Map.of());
            Optional<Arm> a = resolved(arms.get(first.id()), held, year);
            Optional<Arm> b = resolved(arms.get(second.id()), held, year);
            if (evidenceless(a) && evidenceless(b)) {
                problems.add("No evidence for " + leaf.part().name() + ".");
            }
            rows.add(row(leaf, a, b, first, second));
            for (Arm arm : List.of(a, b).stream().flatMap(Optional::stream).toList()) {
                if (arm.source().isEmpty() && !notApplicable(arm)) {
                    problems.add(leaf.part().name() + " has a value with no source.");
                }
            }
        }
        for (String id : table.rows().keySet()) {
            if (!leaves.contains(id)) {
                problems.add("The row " + id + " has no leaf in the value tree.");
            }
        }
        return Optional.of(new EffectsReading(first, second, rows, problems));
    }

    /** An arm as it stood in a year: cited findings read, or nothing while none is in print. */
    private static Optional<Arm> resolved(Arm arm, Findings held, int year) {
        if (!(arm instanceof Cited cited)) {
            return Optional.ofNullable(arm);
        }
        List<Finding> known = cited.findings().stream().map(held::byId).flatMap(Optional::stream).filter(f -> f.inPrint() <= year).toList();
        if (known.isEmpty()) {
            return Optional.empty();
        }
        String words = String.join("; ", known.stream().map(Finding::cell).distinct().toList());
        String sources = String.join("; ", known.stream().map(Finding::source).distinct().toList());
        return Optional.of(new Reported(words, known.stream().map(Finding::result).flatMap(Optional::stream).findFirst(), Optional.of(sources)));
    }

    private static boolean notApplicable(Arm arm) {
        return arm instanceof Found found && found.result().equalsIgnoreCase(NOT_APPLICABLE);
    }

    private static boolean evidenceless(Optional<Arm> arm) {
        return arm.isEmpty() || notApplicable(arm.get());
    }

    private static Row row(Leaf leaf, Optional<Arm> a, Optional<Arm> b, Alternative first, Alternative second) {
        if (leaf.part().kind() == Kind.GATE) {
            return gate(leaf, a, b, first, second);
        }
        Optional<EffectSize> effect = Optional.empty();
        String method = "";
        if (a.orElse(null) instanceof Measured m && b.orElse(null) instanceof Measured n) {
            effect = Optional.of(EffectSize.hedgesG(Double.parseDouble(m.mean()), Double.parseDouble(m.sd()), m.n(),
                    Double.parseDouble(n.mean()), Double.parseDouble(n.sd()), n.n()));
            method = "Hedges' g";
        } else if (a.orElse(null) instanceof Counted m && b.orElse(null) instanceof Counted n
                && m.events() > 0 && n.events() > 0 && m.events() < m.of() && n.events() < n.of()) {
            effect = Optional.of(EffectSize.oddsRatio(m.events(), m.of(), n.events(), n.of()));
            method = "odds ratio divided by 1.81";
        }
        if (effect.isEmpty()) {
            return new Row(leaf, a, b, effect, method, Verdict.NOT_DETERMINABLE, "Not determinable");
        }
        EffectSize oriented = leaf.part().better().orElse(Better.HIGHER) == Better.LOWER ? effect.get().reversed() : effect.get();
        if (!oriented.separated()) {
            return new Row(leaf, a, b, Optional.of(oriented), method, Verdict.NOT_SEPARATED, "Not separated");
        }
        return oriented.central() > 0
                ? new Row(leaf, a, b, Optional.of(oriented), method, Verdict.FIRST, first.name())
                : new Row(leaf, a, b, Optional.of(oriented), method, Verdict.SECOND, second.name());
    }

    private static Row gate(Leaf leaf, Optional<Arm> a, Optional<Arm> b, Alternative first, Alternative second) {
        String pass = leaf.part().pass().orElse("");
        boolean firstPasses = passes(a, pass);
        boolean secondPasses = passes(b, pass);
        if (a.isEmpty() || b.isEmpty()) {
            return new Row(leaf, a, b, Optional.empty(), "", Verdict.NOT_DETERMINABLE, "Not determinable");
        }
        if (firstPasses && secondPasses) {
            return new Row(leaf, a, b, Optional.empty(), "", Verdict.GATE_PASSED, "Both pass");
        }
        String failed = !firstPasses && !secondPasses ? "Neither passes"
                : (firstPasses ? second : first).name() + " does not pass";
        return new Row(leaf, a, b, Optional.empty(), "", Verdict.GATE_FAILED, failed);
    }

    private static boolean passes(Optional<Arm> arm, String pass) {
        return switch (arm.orElse(null)) {
            case Found found -> found.result().equalsIgnoreCase(pass) || notApplicable(found);
            case Reported reported -> reported.result().filter(pass::equalsIgnoreCase).isPresent();
            case null, default -> false;
        };
    }

    /** What one arm's cell says. */
    public static String words(Optional<Arm> arm) {
        return switch (arm.orElse(null)) {
            case Measured m -> m.mean() + " (SD " + m.sd() + ", n=" + m.n() + ")";
            case Counted c -> c.events() + " of " + c.of();
            case Elicited e -> "No study; elicited range " + e.low() + " to " + e.high() + ", most likely " + e.likely();
            case Found f -> f.result().substring(0, 1).toUpperCase(java.util.Locale.ROOT) + f.result().substring(1).replace('-', ' ');
            case Reported r -> r.words();
            case Cited c -> "Not read";
            case null -> "No evidence";
        };
    }

    /** One sentence saying how the rows fall, which the reader would otherwise count. */
    public String summary() {
        long a = count(Verdict.FIRST);
        long b = count(Verdict.SECOND);
        long open = count(Verdict.NOT_SEPARATED);
        long none = rows.stream().filter(r -> r.leaf().part().kind() != Kind.GATE && r.verdict() == Verdict.NOT_DETERMINABLE).count();
        return a + " favor " + first.name() + ", " + b + " favor " + second.name() + ", " + open + " not separated, "
                + none + " with nothing to compute from.";
    }

    private long count(Verdict verdict) {
        return rows.stream().filter(r -> r.verdict() == verdict).count();
    }

    /** What the effects are, for the line beneath a view of them. */
    public String legend() {
        return "A positive effect favors " + first.name() + ". Limits are 95% confidence intervals."
                + " Continuous rows are Hedges' g; a count row is an odds ratio whose logarithm is divided by 1.81.";
    }
}
