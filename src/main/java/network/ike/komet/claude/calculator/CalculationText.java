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
package network.ike.komet.claude.calculator;

import dev.ikm.tinkar.entity.graph.adaptor.profile.ProfileFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import network.ike.komet.claude.block.BlockLines;
import network.ike.komet.claude.block.BlockLines.Form;
import network.ike.komet.claude.block.BlockLines.Line;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.Bounds;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.list.MutableList;

/**
 * The record block of the calculator profile in text ({@code dev-calculator-blocks}): the
 * {@code [calculate]} block that binds a document's statements to a calculator's inputs.
 *
 * <pre>
 * [calculate]
 * ----
 * calculate: k:AscvdRisk10[10-year ASCVD risk] @id=…
 *   bind = k:Age[Age] @id=… anf:… by latest
 *   bind = k:Sex[Sex] @id=… anf:…
 *   bind = k:TotalChol[] @id=… [213,213] k:MgPerDl[] @id=…        (a value stated inline)
 *   bind = k:Race[] @id=… k:White[] @id=…                         (a concept stated inline)
 *   bind = k:Hdl[] @id=… missing
 *   bind = k:Smoker[] @id=… clarify "smoking status is not in the note"
 *   assume = k:Sbp[] @id=… [130,130] k:MmHg[] @id=…
 * ----
 * </pre>
 *
 * <p>A statement is referenced as {@code anf:<identity>} and never restated; a {@code by} word
 * after it records the policy that found it, {@code latest}, so the resolved identity and the
 * policy are both in the text. Emission is deterministic: one line per input in the calculator's
 * order, bindings before assumptions. Parsing never refuses.
 */
public final class CalculationText {

    /** The block's language tag. */
    public static final String TAG = "calculate";

    private static final String INDENT = "  ";
    private static final String DELIMITER = "----";

    private CalculationText() {
    }

    public static boolean isTagged(String info) {
        String tag = info == null ? "" : Tokens.words(info).stream().findFirst().orElse("");
        return TAG.equals(tag);
    }

    /** What a parse produced. */
    public record Parsed(Optional<Calculation> calculation, ImmutableList<ProfileFinding> findings) {
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    public static String toAdoc(Calculation calculation, Optional<Calculator> calculator, Identities ids) {
        return "[" + TAG + "]\n" + DELIMITER + "\n" + body(calculation, calculator, ids) + DELIMITER + "\n";
    }

    /** The body: the calculator's inputs in its order when it is known, else the lines' order. */
    public static String body(Calculation calculation, Optional<Calculator> calculator, Identities ids) {
        StringBuilder sb = new StringBuilder();
        sb.append("calculate:");
        concept(sb, ids, calculation.calculatorNid());
        sb.append('\n');
        List<Long> order = new ArrayList<>();
        calculator.flatMap(Calculator::calculator).ifPresent(root -> root.inputs().forEach(input -> order.add(input.quantity())));
        for (Calculation.Line line : calculation.lines()) {
            if (!order.contains(line.inputQuantity())) {
                order.add(line.inputQuantity());
            }
        }
        for (long input : order) {
            for (Calculation.Line line : calculation.linesFor(input)) {
                if (!(line.spec() instanceof Calculation.Assume)) {
                    line(sb, ids, line);
                }
            }
        }
        for (long input : order) {
            for (Calculation.Line line : calculation.linesFor(input)) {
                if (line.spec() instanceof Calculation.Assume) {
                    line(sb, ids, line);
                }
            }
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, Identities ids, Calculation.Line line) {
        sb.append(INDENT).append(line.spec() instanceof Calculation.Assume ? "assume =" : "bind =");
        concept(sb, ids, line.inputQuantity());
        switch (line.spec()) {
            case Calculation.Statement statement -> {
                ids.identifier(statement.statementNid()).ifPresent(id -> sb.append(" anf:").append(id));
                statement.policy().ifPresent(policy -> sb.append(" by ").append(policy));
            }
            case Calculation.Inline inline -> value(sb, ids, inline.value());
            case Calculation.Missing missing -> sb.append(" missing");
            case Calculation.Clarify clarify -> sb.append(" clarify ").append(Tokens.quote(clarify.question()));
            case Calculation.Assume assume -> value(sb, ids, assume.value());
        }
        sb.append('\n');
    }

    private static void value(StringBuilder sb, Identities ids, Binding.Supplied value) {
        if (value.conceptValue().isPresent()) {
            concept(sb, ids, value.conceptValue().getAsLong());
            return;
        }
        Bounds bounds = value.bounds();
        sb.append(' ').append(Tokens.interval(finite(bounds.lower()), finite(bounds.upper()), !bounds.lowerOpen(), !bounds.upperOpen()));
        if (value.semanticNid() != 0) {
            concept(sb, ids, value.semanticNid());
        }
    }

    private static void concept(StringBuilder sb, Identities ids, long nid) {
        if (nid == 0) {
            return;
        }
        ids.identifier(nid).ifPresent(identifier -> sb.append(' ').append(Tokens.concept(ids.label(nid), identifier)));
    }

    private static Double finite(double bound) {
        return Double.isInfinite(bound) ? null : bound;
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    public static Parsed parse(String text, Identities ids) {
        MutableList<ProfileFinding> findings = Lists.mutable.empty();
        Set<String> localSymbols = new LinkedHashSet<>();
        BlockLines.Parsed lines = BlockLines.parse(text);
        lines.problems().forEach(problem -> findings.add(ProfileFinding.ofTree(problem)));
        if (lines.roots().size() != 1) {
            findings.add(ProfileFinding.ofTree("a calculation has one root line, not " + lines.roots().size()));
            return new Parsed(Optional.empty(), findings.toImmutable());
        }
        Line root = lines.roots().get(0);
        if (root.form() != Form.VERTEX || !"calculate".equals(root.word())) {
            findings.add(finding(root, "the root is not a calculate line"));
            return new Parsed(Optional.empty(), findings.toImmutable());
        }
        Resolver resolver = new Resolver(ids, findings, localSymbols);
        long calculator = resolver.concept(root, root.head(), "a calculator");
        MutableList<Calculation.Line> result = Lists.mutable.empty();
        for (Line child : root.children()) {
            if (child.form() != Form.VALUE || !("bind".equals(child.word()) || "assume".equals(child.word()))) {
                findings.add(finding(child, "a calculation holds bind and assume lines, not " + child.word()));
                continue;
            }
            boolean assume = "assume".equals(child.word());
            List<String> words = Tokens.words(child.head());
            if (words.isEmpty() || !words.get(0).startsWith("k:")) {
                findings.add(finding(child, "a " + child.word() + " line names the input first"));
                continue;
            }
            String inputText = words.get(0);
            int next = 1;
            if (words.size() > 1 && words.get(1).startsWith("@id=")) {
                inputText += " " + words.get(1);
                next = 2;
            }
            long input = resolver.concept(child, inputText, "an input");
            List<String> rest = words.subList(next, words.size());
            Optional<Calculation.Spec> spec = assume ? assumeSpec(child, rest, resolver, findings)
                    : bindSpec(child, rest, resolver, findings);
            spec.ifPresent(s -> result.add(new Calculation.Line(input, s)));
            if (!child.children().isEmpty()) {
                findings.add(finding(child, "a " + child.word() + " line has nothing beneath it"));
            }
        }
        if (!localSymbols.isEmpty()) {
            findings.add(ProfileFinding.ofTree(localSymbols.size()
                    + " identifiers name nothing in the knowledge base and are read as concepts local to the block: "
                    + String.join(", ", localSymbols)));
        }
        return new Parsed(Optional.of(new Calculation(calculator, result.toImmutable())), findings.toImmutable());
    }

    private static Optional<Calculation.Spec> bindSpec(Line line, List<String> words, Resolver resolver,
                                                       MutableList<ProfileFinding> findings) {
        if (words.isEmpty()) {
            findings.add(finding(line, "a bind line says what binds: anf:<identity>, a value, missing or clarify"));
            return Optional.empty();
        }
        String first = words.get(0);
        if (first.startsWith("anf:")) {
            String identifier = first.substring("anf:".length());
            if (identifier.startsWith("@id=")) {
                identifier = identifier.substring("@id=".length());
            }
            OptionalLong statement = resolver.resolve(line, identifier, "a statement");
            Optional<String> policy = Optional.empty();
            if (words.size() >= 3 && "by".equals(words.get(1))) {
                policy = Optional.of(words.get(2));
            } else if (words.size() > 1) {
                findings.add(finding(line, "an unexpected word after a statement: " + words.get(1)));
            }
            return statement.isPresent() ? Optional.of(new Calculation.Statement(statement.getAsLong(), policy)) : Optional.empty();
        }
        switch (first.toLowerCase(Locale.ROOT)) {
            case "missing" -> {
                return Optional.of(new Calculation.Missing());
            }
            case "clarify" -> {
                String question = words.size() > 1 ? Tokens.unquote(String.join(" ", words.subList(1, words.size()))) : "";
                return Optional.of(new Calculation.Clarify(question));
            }
            default -> {
                return supplied(line, words, resolver, findings, false).map(Calculation.Inline::new);
            }
        }
    }

    private static Optional<Calculation.Spec> assumeSpec(Line line, List<String> words, Resolver resolver,
                                                         MutableList<ProfileFinding> findings) {
        return supplied(line, words, resolver, findings, true).map(Calculation.Assume::new);
    }

    /** An inline value: an interval with an optional semantic, or a concept. */
    private static Optional<Binding.Supplied> supplied(Line line, List<String> words, Resolver resolver,
                                                       MutableList<ProfileFinding> findings, boolean assumption) {
        if (words.isEmpty()) {
            findings.add(finding(line, "a value is an interval with its semantic, or a concept"));
            return Optional.empty();
        }
        String first = words.get(0);
        if (first.startsWith("[") || first.startsWith("(")) {
            Optional<Tokens.IntervalPrefix> prefix = Tokens.intervalPrefix(first);
            if (prefix.isEmpty()) {
                findings.add(finding(line, "not an interval: " + first));
                return Optional.empty();
            }
            Tokens.Interval interval = prefix.get().interval();
            Bounds bounds = new Bounds(
                    interval.lower() == null ? Double.NEGATIVE_INFINITY : interval.lower(),
                    interval.upper() == null ? Double.POSITIVE_INFINITY : interval.upper(),
                    !interval.includeLower(), !interval.includeUpper());
            long semantic = 0;
            if (words.size() > 1) {
                String text = words.get(1);
                if (words.size() > 2 && words.get(2).startsWith("@id=")) {
                    text += " " + words.get(2);
                }
                semantic = resolver.concept(line, text, "a value's measure semantic");
            }
            return Optional.of(new Binding.Supplied(bounds, semantic, OptionalLong.empty(), assumption));
        }
        if (first.startsWith("k:")) {
            String text = first;
            if (words.size() > 1 && words.get(1).startsWith("@id=")) {
                text += " " + words.get(1);
            }
            long concept = resolver.concept(line, text, "a concept value");
            return Optional.of(new Binding.Supplied(Bounds.point(1), 0, OptionalLong.of(concept), assumption));
        }
        findings.add(finding(line, "an unexpected word: " + first));
        return Optional.empty();
    }

    private static ProfileFinding finding(Line line, String message) {
        return ProfileFinding.ofTree("line " + line.number() + ": " + message);
    }

    private record Resolver(Identities ids, MutableList<ProfileFinding> findings, Set<String> localSymbols) {
        long concept(Line line, String text, String what) {
            Optional<Tokens.Concept> token = Tokens.concept(text);
            if (token.isEmpty()) {
                findings.add(finding(line, what + " is not a k: token: " + text));
                return 0;
            }
            if (token.get().identifier().isEmpty()) {
                findings.add(finding(line, what + " carries no @id: " + text));
                return 0;
            }
            String label = token.get().label() != null && !token.get().label().isBlank() ? token.get().label()
                    : token.get().key();
            return resolve(line, token.get().identifier().get(), label, what).orElse(0L);
        }

        OptionalLong resolve(Line line, String identifier, String what) {
            return resolve(line, identifier, identifier, what);
        }

        OptionalLong resolve(Line line, String identifier, String label, String what) {
            String id = identifier.strip();
            if (id.isEmpty()) {
                return OptionalLong.empty();
            }
            OptionalLong nid = ids.nid(id);
            if (nid.isPresent()) {
                return nid;
            }
            OptionalLong local = ids.local(id, label);
            if (local.isPresent()) {
                localSymbols.add(id);
                return local;
            }
            findings.add(finding(line, what + " does not resolve: " + id));
            return nid;
        }
    }
}
