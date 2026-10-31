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
package network.ike.komet.claude.anf.element;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;

/**
 * Clinical elements in text ({@code dev-anf-element-isolation}). An element is an {@code anf}
 * block whose header names its sentence and its spans; its body is the ANF statement once lifted
 * and empty before, so an element not yet lifted is already a committable block and a lift's diff
 * is the body it adds:
 *
 * <pre>
 * [anf,type=performance,id=el-1,element="The patient is 62 years old.",from="quote:A 62-year-old man#1",accepted]
 * --
 * topic:: k:ChronologicalAge[Chronological age] &#64;id=…
 * result:: [62,62] k:Year[year] &#64;id=…
 * --
 * </pre>
 *
 * Spans the author marked as carrying no clinical fact are one block of their own,
 * {@code [anf-not-clinical,from="quote:…#1|quote:…#1"]}. The same attributes read from a Markdown
 * fence's info string, space-separated, so the document surface and the lift's history share one
 * reader.
 */
public final class ElementText {

    private static final String DELIMITER = "--";
    private static final String NOT_CLINICAL = "anf-not-clinical";

    private ElementText() {
    }

    // ── Emit ─────────────────────────────────────────────────────────────────

    /** The blocks of an element set: one per element, in order, then the not-clinical marks. */
    public static List<String> toBlocks(ElementSet set) {
        List<String> blocks = new ArrayList<>();
        for (ClinicalElement element : set.elements()) {
            blocks.add(toBlock(element, set.narrative()));
        }
        if (!set.notClinical().isEmpty()) {
            blocks.add("[" + NOT_CLINICAL + ",from=" + quoted(anchors(set.notClinical(), set.narrative())) + "]\n"
                    + DELIMITER + "\n" + DELIMITER + "\n");
        }
        return blocks;
    }

    /** One element's block. */
    public static String toBlock(ClinicalElement element, String narrative) {
        StringBuilder header = new StringBuilder("[anf,type=");
        header.append(element.statement().map(s -> s.statementType().name().toLowerCase(Locale.ROOT)).orElse("performance"));
        header.append(",id=").append(element.id());
        header.append(",element=").append(quoted(element.sentence()));
        header.append(",from=").append(quoted(anchors(element.spans(), narrative)));
        element.liftedSentence().ifPresent(s -> header.append(",lifted=").append(quoted(s)));
        if (!element.links().isEmpty()) {
            header.append(",links=").append(quoted(String.join(";", element.links())));
        }
        element.reason().ifPresent(r -> header.append(",reason=").append(quoted(r)));
        if (element.accepted()) {
            header.append(",accepted");
        }
        header.append(']');
        String body = element.statement().map(s -> bodyOf(AnfAdoc.toAdoc(s))).orElse("");
        return header + "\n" + DELIMITER + "\n" + body + DELIMITER + "\n";
    }

    /** The anchors of spans, joined by {@code |}. */
    public static String anchors(List<Span> spans, String narrative) {
        return String.join("|", spans.stream().map(s -> s.quoteAnchor(narrative)).toList());
    }

    /** What lies between a block's delimiters, each line ending in a newline. */
    static String bodyOf(String block) {
        StringBuilder body = new StringBuilder();
        boolean in = false;
        for (String line : block.split("\n", -1)) {
            if (line.strip().equals(DELIMITER)) {
                in = !in;
                continue;
            }
            if (in) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ── Parse ────────────────────────────────────────────────────────────────

    /**
     * The element set the blocks hold, when any of them is an element; empty for a lift written
     * before elements, whose blocks are statements alone.
     */
    public static Optional<ElementSet> parse(List<String> blocks, String narrative, AnfAdoc.ConceptResolver resolver) {
        List<ClinicalElement> elements = new ArrayList<>();
        List<Span> notClinical = new ArrayList<>();
        boolean any = false;
        for (String block : blocks) {
            String header = block.strip().lines().findFirst().orElse("");
            if (!header.startsWith("[") || !header.endsWith("]")) {
                continue;
            }
            Map<String, String> attributes = attributes(header.substring(1, header.length() - 1));
            if (attributes.containsKey(NOT_CLINICAL)) {
                any = true;
                notClinical.addAll(spans(attributes.get("from"), narrative));
                continue;
            }
            if (!attributes.containsKey("anf") || !attributes.containsKey("element")) {
                continue;
            }
            any = true;
            element(attributes, bodyOf(block), narrative, resolver).ifPresent(elements::add);
        }
        return any ? Optional.of(new ElementSet(narrative, elements, notClinical)) : Optional.empty();
    }

    /** One element from a block's attributes and body; empty when none of its spans is found in the narrative. */
    public static Optional<ClinicalElement> element(Map<String, String> attributes, String body, String narrative,
                                                    AnfAdoc.ConceptResolver resolver) {
        List<Span> spans = spans(attributes.get("from"), narrative);
        String sentence = attributes.getOrDefault("element", "");
        if (spans.isEmpty() || sentence.isBlank()) {
            return Optional.empty();
        }
        Optional<AnfStatement> statement = Optional.empty();
        if (!body.isBlank()) {
            String type = attributes.getOrDefault("type", "performance");
            statement = Optional.ofNullable(AnfAdoc.parse("[anf,type=" + type + "]\n--\n" + body + "--\n", resolver));
        }
        List<String> links = attributes.containsKey("links") && !attributes.get("links").isBlank()
                ? List.of(attributes.get("links").split(";")) : List.of();
        String reason = attributes.get("reason");
        return Optional.of(new ClinicalElement(attributes.getOrDefault("id", "el-?"), sentence, spans, links,
                attributes.containsKey("accepted"), statement,
                statement.isPresent() ? Optional.ofNullable(attributes.getOrDefault("lifted", sentence)) : Optional.empty(),
                reason == null || reason.isBlank() ? Optional.empty() : Optional.of(reason)));
    }

    /** The spans a {@code from} value's anchors find in a narrative, skipping any that no longer match. */
    public static List<Span> spans(String from, String narrative) {
        List<Span> spans = new ArrayList<>();
        if (from == null || from.isBlank()) {
            return spans;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            Span.resolve(anchor.strip(), narrative).ifPresent(spans::add);
        }
        return spans;
    }

    /** The quoted texts a {@code from} value names, for a surface with no narrative to resolve them in. */
    /** A quoted span as an anchor names it: its text and which occurrence of that text it is, from 1. */
    public record Quote(String text, int occurrence) {
    }

    /** The quotes of a {@code from} attribute's quote anchors, each with its occurrence; segment anchors are skipped. */
    public static List<Quote> quoteAnchors(String from) {
        List<Quote> quotes = new ArrayList<>();
        if (from == null || from.isBlank()) {
            return quotes;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            String a = anchor.strip();
            if (a.startsWith("quote:")) {
                String body = a.substring("quote:".length());
                int hash = body.lastIndexOf('#');
                boolean numbered = hash > 0 && body.substring(hash + 1).matches("\\d+");
                quotes.add(new Quote(numbered ? body.substring(0, hash) : body,
                        numbered ? Math.max(1, Integer.parseInt(body.substring(hash + 1))) : 1));
            }
        }
        return quotes;
    }

    public static List<String> quotes(String from) {
        List<String> quotes = new ArrayList<>();
        if (from == null || from.isBlank()) {
            return quotes;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            String a = anchor.strip();
            if (a.startsWith("quote:")) {
                String body = a.substring("quote:".length());
                int hash = body.lastIndexOf('#');
                quotes.add(hash > 0 && body.substring(hash + 1).matches("\\d+") ? body.substring(0, hash) : body);
            }
        }
        return quotes;
    }

    /**
     * The attributes of a block header's content or a fence's info string: names with or without
     * a value, separated by commas or spaces, a value quoted when it holds either, with a backslash
     * escaping a quote. The first bare name, the block's kind, maps to the empty string.
     */
    public static Map<String, String> attributes(String text) {
        Map<String, String> attributes = new LinkedHashMap<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            while (i < n && (text.charAt(i) == ',' || Character.isWhitespace(text.charAt(i)))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int nameStart = i;
            while (i < n && text.charAt(i) != '=' && text.charAt(i) != ',' && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            String name = text.substring(nameStart, i);
            String value = "";
            if (i < n && text.charAt(i) == '=') {
                i++;
                if (i < n && text.charAt(i) == '"') {
                    i++;
                    StringBuilder v = new StringBuilder();
                    while (i < n && text.charAt(i) != '"') {
                        if (text.charAt(i) == '\\' && i + 1 < n) {
                            i++;
                        }
                        v.append(text.charAt(i));
                        i++;
                    }
                    i++;
                    value = v.toString();
                } else {
                    int valueStart = i;
                    while (i < n && text.charAt(i) != ',' && !Character.isWhitespace(text.charAt(i))) {
                        i++;
                    }
                    value = text.substring(valueStart, i);
                }
            }
            if (!name.isEmpty()) {
                attributes.putIfAbsent(name, value);
            }
        }
        return attributes;
    }
}
