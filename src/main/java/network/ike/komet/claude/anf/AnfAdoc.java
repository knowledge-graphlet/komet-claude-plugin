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
package network.ike.komet.claude.anf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lossless ANF-in-AsciiDoc canonical form: it projects a validated {@link AnfStatement}
 * to a self-contained {@code [anf]} block and reads one back. This is the durable artifact the
 * lift history persists — a person can read and edit it, a build could render it, and it
 * reconstructs the same concepts regardless of the coordinate it is later read under, because
 * concept <em>identity</em> (an SCTID or UUID) is written into the block at lift time. See
 * {@code dev-anf-adoc-representation}.
 *
 * <h2>Grammar</h2>
 * <pre>
 * [anf,type=performance|request|narrative]
 * --
 * topic::                     k:Key[label] &#64;id=&lt;identifier&gt;
 * subject_of_information::     k:Key[label] &#64;id=&lt;identifier&gt;
 * result::                    [1,1] k:Presence[] &#64;id=705057003
 * status:: health_risk:: body_site:: method:: laterality::   (performance slots)
 * normal_range::              [lo,hi] k:Unit[label] &#64;id=&lt;identifier&gt;
 * requested_result:: priority::                              (request slots)
 * repetition_period_start:: …_period_duration:: …_event_separation:: …_event_duration:: …_event_frequency::
 * conditional_trigger::       (repeated, one per list element)
 * purpose::                   (repeated, any kind)
 * timing::                    [lo,hi] k:Unit[label] &#64;id=…
 * text::                      "free narrative text"
 * --
 * </pre>
 *
 * <h2>Slot encoding (the sealed three-way)</h2>
 * <ul>
 *   <li><b>Grounded</b> — {@code k:Key[label] @id=<uuid,uuid,…>}; the {@code @id} is the concept's
 *       {@code PublicId} UUID array (comma-joined), the durable identity the badge/identicon is drawn
 *       from — <em>not</em> an SCTID or any single terminology code. It is re-resolved on parse
 *       through the supplied {@link ConceptResolver}. The {@code Key} and {@code label} are human aids
 *       and are not trusted on parse.</li>
 *   <li><b>Candidate</b> — {@code candidate[label] disp=<DISPOSITION> near=<id>;<id> text="…"};
 *       no {@code @id}, because a candidate has no concept yet (honest).</li>
 *   <li><b>Clarify</b> — {@code clarify[field] "question"}.</li>
 * </ul>
 *
 * <h2>Losslessness</h2>
 * Round-trips by identity, not label: a grounded slot's {@code nid}/{@code label} are view-derived
 * and recomputed from the live store on parse (house rule); the written {@code @id} is the stable
 * key. Candidate and clarify fields round-trip verbatim. A result's interval (bounds, inclusivity,
 * unbounded {@code ±inf}) and its measure semantic round-trip. A grounded {@code @id} that no longer
 * resolves (or an offline parse with no view) becomes an explicit unresolved {@link AnfSlot.Clarify}
 * marker carrying the dead identifier — never a silent drop. Narrative text is carried as text and
 * stays text. The deferred envelope axes (subject of record, author, time) are not modeled, so they
 * are not emitted.
 */
public final class AnfAdoc {

    private AnfAdoc() {
    }

    /**
     * Re-resolves a written identifier (SCTID or UUID) back to a grounded slot against the live
     * store. Decouples {@link AnfAdoc} from the graph tools so the serializer/parser is pure and
     * hermetically testable; the host supplies {@code id -> GraphTools.resolveConcept(id, view)}.
     */
    @FunctionalInterface
    public interface ConceptResolver {
        /**
         * Resolves an identifier to a grounded slot.
         *
         * @param identifier the written SCTID or UUID
         * @return the grounded slot, or empty if it does not resolve
         */
        Optional<AnfSlot.Grounded> resolve(String identifier);
    }

    private static final String OPEN = "--";

    // ── Serialize ────────────────────────────────────────────────────────────

    /**
     * Projects a statement to its ANF-in-AsciiDoc block, encoding identifiers at write time.
     *
     * @param s the validated statement; must not be null
     * @return the {@code [anf]} block text (ending with a newline)
     */
    public static String toAdoc(AnfStatement s) {
        StringBuilder sb = new StringBuilder();
        sb.append("[anf,type=").append(s.statementType().name().toLowerCase(Locale.ROOT)).append("]\n");
        sb.append(OPEN).append('\n');
        appendSlot(sb, "topic", s.topic());
        appendSlot(sb, "subject_of_information", s.subjectOfInformation());
        switch (s.circumstance()) {
            case Circumstance.Performance p -> {
                appendResult(sb, "result", p.result());
                appendSlot(sb, "status", p.status());
                appendSlot(sb, "health_risk", p.healthRisk());
                appendResult(sb, "normal_range", p.normalRange());
                appendSlot(sb, "body_site", p.bodySite());
                appendSlot(sb, "method", p.method());
                appendSlot(sb, "laterality", p.laterality());
                appendShared(sb, p.timing(), p.purpose());
            }
            case Circumstance.Request r -> {
                appendResult(sb, "requested_result", r.requestedResult());
                appendSlot(sb, "priority", r.priority());
                appendSlot(sb, "method", r.method());
                appendRepetition(sb, r.repetition());
                for (AnfSlot trigger : r.conditionalTrigger()) {
                    appendSlot(sb, "conditional_trigger", trigger);
                }
                appendShared(sb, r.timing(), r.purpose());
            }
            case Circumstance.Narrative n -> {
                sb.append("text:: ").append(quote(n.text())).append('\n');
                appendShared(sb, n.timing(), n.purpose());
            }
        }
        sb.append(OPEN).append('\n');
        return sb.toString();
    }

    private static void appendShared(StringBuilder sb, AnfStatement.Result timing, List<AnfSlot> purpose) {
        appendResult(sb, "timing", timing);
        for (AnfSlot p : purpose) {
            appendSlot(sb, "purpose", p);
        }
    }

    private static void appendRepetition(StringBuilder sb, Circumstance.Repetition rep) {
        if (rep == null) {
            return;
        }
        appendResult(sb, "repetition_period_start", rep.periodStart());
        appendResult(sb, "repetition_period_duration", rep.periodDuration());
        appendResult(sb, "repetition_event_separation", rep.eventSeparation());
        appendResult(sb, "repetition_event_duration", rep.eventDuration());
        appendResult(sb, "repetition_event_frequency", rep.eventFrequency());
    }

    private static void appendSlot(StringBuilder sb, String name, AnfSlot slot) {
        if (slot != null) {
            sb.append(name).append(":: ").append(slotToString(slot)).append('\n');
        }
    }

    private static void appendResult(StringBuilder sb, String name, AnfStatement.Result r) {
        if (r != null) {
            sb.append(name).append(":: ").append(interval(r)).append(' ')
                    .append(slotToString(r.measureSemantic())).append('\n');
        }
    }

    private static String slotToString(AnfSlot slot) {
        return switch (slot) {
            case AnfSlot.Grounded g -> "k:" + keyOf(g.label()) + "[" + g.label() + "] @id=" + g.publicId();
            case AnfSlot.Candidate c -> {
                StringBuilder cb = new StringBuilder("candidate[").append(c.provisionalLabel())
                        .append("] disp=").append(c.disposition().name());
                if (!c.nearestMatches().isEmpty()) {
                    cb.append(" near=").append(String.join(";", c.nearestMatches()));
                }
                if (c.text() != null && !c.text().isBlank()) {
                    cb.append(" text=").append(quote(c.text()));
                }
                yield cb.toString();
            }
            case AnfSlot.Clarify c -> "clarify[" + c.field() + "] " + quote(c.question());
        };
    }

    private static String interval(AnfStatement.Result r) {
        String lo = (r.lowerBound() == null) ? "-inf" : trim(r.lowerBound());
        String hi = (r.upperBound() == null) ? "inf" : trim(r.upperBound());
        return (r.includeLowerBound() ? "[" : "(") + lo + "," + hi + (r.includeUpperBound() ? "]" : ")");
    }

    private static String trim(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /** A synthetic CamelCase key from a label — a human/identicon aid only; never parsed. */
    private static String keyOf(String label) {
        String base = label.replaceAll("\\(.*?\\)", " ");
        StringBuilder k = new StringBuilder();
        for (String part : base.split("[^A-Za-z0-9]+")) {
            if (!part.isEmpty()) {
                k.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return k.isEmpty() ? "Concept" : k.toString();
    }

    private static String quote(String s) {
        String escaped = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    // ── Parse ─────────────────────────────────────────────────────────────────

    private static final Pattern TYPE = Pattern.compile("type=(performance|request|narrative)");
    private static final Pattern GROUNDED = Pattern.compile("^k:[^\\[]*\\[(.*)\\]\\s*@id=(\\S+)\\s*$");
    private static final Pattern CANDIDATE = Pattern.compile(
            "^candidate\\[(.*?)\\]\\s*disp=(\\S+)(?:\\s+near=(\\S+))?(?:\\s+text=(\".*\"))?\\s*$");
    private static final Pattern CLARIFY = Pattern.compile("^clarify\\[(.*?)\\]\\s*(\".*\")\\s*$");
    private static final Pattern RESULT = Pattern.compile(
            "^([\\[(])\\s*(\\S+?)\\s*,\\s*(\\S+?)\\s*([\\])])\\s+(.*)$");

    /**
     * Reads an ANF-in-AsciiDoc block back to a validated statement. Grounded slots re-resolve
     * through {@code resolver}; a written identifier that no longer resolves — or an offline parse
     * ({@code resolver == null}, used by structural tests) — becomes an explicit unresolved
     * {@link AnfSlot.Clarify} marker carrying the dead identifier, never a silent drop.
     *
     * @param block    the {@code [anf]} block text
     * @param resolver re-resolves a written identifier to a grounded slot; null for an offline,
     *                 structure-only parse
     * @return the reconstructed statement, or null if the block has no recognizable type
     */
    public static AnfStatement parse(String block, ConceptResolver resolver) {
        if (block == null) {
            return null;
        }
        AnfStatement.Type type = null;
        Map<String, List<String>> raw = new LinkedHashMap<>();
        boolean inBlock = false;
        for (String line : block.split("\n", -1)) {
            String t = line.strip();
            if (t.startsWith("[anf")) {
                Matcher m = TYPE.matcher(t);
                if (m.find()) {
                    type = AnfStatement.Type.valueOf(m.group(1).toUpperCase(Locale.ROOT));
                }
                continue;
            }
            if (t.equals(OPEN)) {
                inBlock = !inBlock;
                continue;
            }
            if (!inBlock || t.isEmpty()) {
                continue;
            }
            int idx = t.indexOf("::");
            if (idx < 0) {
                continue;
            }
            raw.computeIfAbsent(t.substring(0, idx).strip(), k -> new ArrayList<>())
                    .add(t.substring(idx + 2).strip());
        }
        if (type == null) {
            return null;
        }

        AnfSlot topic = firstSlot(raw, "topic", resolver);
        AnfSlot subject = firstSlot(raw, "subject_of_information", resolver);
        Circumstance circumstance = switch (type) {
            case PERFORMANCE -> new Circumstance.Performance(
                    firstResult(raw, "timing", resolver), slotList(raw, "purpose", resolver),
                    firstSlot(raw, "status", resolver), firstResult(raw, "result", resolver),
                    firstSlot(raw, "health_risk", resolver), firstResult(raw, "normal_range", resolver),
                    firstSlot(raw, "body_site", resolver), firstSlot(raw, "method", resolver),
                    firstSlot(raw, "laterality", resolver));
            case REQUEST -> new Circumstance.Request(
                    firstResult(raw, "timing", resolver), slotList(raw, "purpose", resolver),
                    firstSlot(raw, "priority", resolver), firstResult(raw, "requested_result", resolver),
                    parseRepetition(raw, resolver), slotList(raw, "conditional_trigger", resolver),
                    firstSlot(raw, "method", resolver));
            case NARRATIVE -> new Circumstance.Narrative(
                    firstResult(raw, "timing", resolver), slotList(raw, "purpose", resolver),
                    firstText(raw, "text"));
        };
        return new AnfStatement(type, topic, subject, circumstance);
    }

    private static String firstText(Map<String, List<String>> raw, String name) {
        List<String> values = raw.get(name);
        return (values == null || values.isEmpty()) ? "" : unquote(values.get(0));
    }

    private static AnfSlot firstSlot(Map<String, List<String>> raw, String name, ConceptResolver resolver) {
        List<String> values = raw.get(name);
        return (values == null || values.isEmpty()) ? null : parseSlot(values.get(0), resolver);
    }

    private static List<AnfSlot> slotList(Map<String, List<String>> raw, String name, ConceptResolver resolver) {
        List<String> values = raw.get(name);
        if (values == null) {
            return List.of();
        }
        List<AnfSlot> slots = new ArrayList<>();
        for (String value : values) {
            AnfSlot slot = parseSlot(value, resolver);
            if (slot != null) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private static AnfStatement.Result firstResult(Map<String, List<String>> raw, String name, ConceptResolver resolver) {
        List<String> values = raw.get(name);
        return (values == null || values.isEmpty()) ? null : parseResult(values.get(0), resolver);
    }

    private static Circumstance.Repetition parseRepetition(Map<String, List<String>> raw, ConceptResolver resolver) {
        AnfStatement.Result ps = firstResult(raw, "repetition_period_start", resolver);
        AnfStatement.Result pd = firstResult(raw, "repetition_period_duration", resolver);
        AnfStatement.Result es = firstResult(raw, "repetition_event_separation", resolver);
        AnfStatement.Result ed = firstResult(raw, "repetition_event_duration", resolver);
        AnfStatement.Result ef = firstResult(raw, "repetition_event_frequency", resolver);
        if (ps == null && pd == null && es == null && ed == null && ef == null) {
            return null;
        }
        return new Circumstance.Repetition(ps, pd, es, ed, ef);
    }

    private static AnfSlot parseSlot(String value, ConceptResolver resolver) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher candidate = CANDIDATE.matcher(value);
        if (candidate.matches()) {
            String label = candidate.group(1);
            AnfSlot.Disposition disposition = parseDisposition(candidate.group(2));
            List<String> nearest = (candidate.group(3) == null || candidate.group(3).isBlank())
                    ? List.of() : List.of(candidate.group(3).split(";"));
            String text = (candidate.group(4) == null) ? label : unquote(candidate.group(4));
            return new AnfSlot.Candidate(text, label, nearest, disposition);
        }
        Matcher clarify = CLARIFY.matcher(value);
        if (clarify.matches()) {
            return new AnfSlot.Clarify(clarify.group(1), unquote(clarify.group(2)));
        }
        Matcher grounded = GROUNDED.matcher(value);
        if (grounded.matches()) {
            String label = grounded.group(1);
            String id = grounded.group(2);
            if (resolver != null) {
                Optional<AnfSlot.Grounded> resolved = resolver.resolve(id);
                if (resolved != null && resolved.isPresent()) {
                    return resolved.get();
                }
            }
            return new AnfSlot.Clarify("unresolved", "unresolved identifier: " + id + " [" + label + "]");
        }
        return null;
    }

    private static AnfStatement.Result parseResult(String value, ConceptResolver resolver) {
        if (value == null) {
            return null;
        }
        Matcher m = RESULT.matcher(value.strip());
        if (!m.matches()) {
            return null;
        }
        boolean includeLower = "[".equals(m.group(1));
        Double lower = parseBound(m.group(2));
        Double upper = parseBound(m.group(3));
        boolean includeUpper = "]".equals(m.group(4));
        AnfSlot measure = parseSlot(m.group(5), resolver);
        if (measure == null) {
            return null;
        }
        return new AnfStatement.Result(lower, upper, includeLower, includeUpper, measure);
    }

    /** Parses an interval bound: {@code ±inf} (and {@code inf}) denote an unbounded side (null). */
    private static Double parseBound(String s) {
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

    private static AnfSlot.Disposition parseDisposition(String s) {
        if (s == null) {
            return AnfSlot.Disposition.PENDING;
        }
        try {
            return AnfSlot.Disposition.valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            return AnfSlot.Disposition.PENDING;
        }
    }
}
