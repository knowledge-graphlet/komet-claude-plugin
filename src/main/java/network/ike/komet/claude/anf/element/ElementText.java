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
        return toBlocks(set, Anchoring.quotes(set.narrative()));
    }

    /** The blocks of an element set, its spans written by {@code anchoring}: quotes, or a stamped document's sentence segments. */
    public static List<String> toBlocks(ElementSet set, Anchoring anchoring) {
        List<String> blocks = new ArrayList<>();
        for (ClinicalElement element : set.elements()) {
            blocks.add(toBlock(element, anchoring));
        }
        if (!set.notClinical().isEmpty()) {
            blocks.add("[" + NOT_CLINICAL + ",from=" + quoted(anchoring.anchors(set.notClinical())) + "]\n"
                    + DELIMITER + "\n" + DELIMITER + "\n");
        }
        return blocks;
    }

    /** One element's block. */
    public static String toBlock(ClinicalElement element, String narrative) {
        return toBlock(element, Anchoring.quotes(narrative));
    }

    /** One element's block, its spans written by {@code anchoring}. */
    public static String toBlock(ClinicalElement element, Anchoring anchoring) {
        StringBuilder header = new StringBuilder("[anf,type=");
        header.append(element.statement().map(s -> s.statementType().name().toLowerCase(Locale.ROOT)).orElse("performance"));
        header.append(",id=").append(element.id());
        header.append(",element=").append(quoted(element.sentence()));
        header.append(",from=").append(quoted(anchoring.anchors(element.spans())));
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
        return Anchoring.quotes(narrative).anchors(spans);
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
        return parse(blocks, Anchoring.quotes(narrative), resolver);
    }

    /** The element set the blocks hold, their spans found by {@code anchoring}; an element none of whose spans is found is left out. */
    public static Optional<ElementSet> parse(List<String> blocks, Anchoring anchoring, AnfAdoc.ConceptResolver resolver) {
        String narrative = anchoring.narrative();
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
                notClinical.addAll(spans(attributes.get("from"), anchoring));
                continue;
            }
            if (!attributes.containsKey("anf") || !attributes.containsKey("element")) {
                continue;
            }
            any = true;
            element(attributes, bodyOf(block), anchoring, resolver).ifPresent(elements::add);
        }
        return any ? Optional.of(new ElementSet(narrative, elements, notClinical)) : Optional.empty();
    }

    /** One element from a block's attributes and body; empty when none of its spans is found in the narrative. */
    public static Optional<ClinicalElement> element(Map<String, String> attributes, String body, String narrative,
                                                    AnfAdoc.ConceptResolver resolver) {
        return element(attributes, body, Anchoring.quotes(narrative), resolver);
    }

    /** One element, its spans found by {@code anchoring}; empty when none of them is found. */
    public static Optional<ClinicalElement> element(Map<String, String> attributes, String body, Anchoring anchoring,
                                                    AnfAdoc.ConceptResolver resolver) {
        List<Span> spans = spans(attributes.get("from"), anchoring);
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
        return spans(from, Anchoring.quotes(narrative));
    }

    /** The spans a {@code from} value's anchors find through {@code anchoring}, skipping any that no longer match. */
    public static List<Span> spans(String from, Anchoring anchoring) {
        List<Span> spans = new ArrayList<>();
        if (from == null || from.isBlank()) {
            return spans;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            anchoring.resolve(anchor.strip()).ifPresent(spans::add);
        }
        return spans;
    }

    /**
     * The sentences of element blocks that {@code anchoring} no longer finds any span of: the
     * findings of a stamped document revised since its elements were isolated.
     */
    public static List<String> unresolved(List<String> blocks, Anchoring anchoring) {
        List<String> lost = new ArrayList<>();
        for (String block : blocks) {
            String header = block.strip().lines().findFirst().orElse("");
            if (!header.startsWith("[") || !header.endsWith("]")) {
                continue;
            }
            Map<String, String> attributes = attributes(header.substring(1, header.length() - 1));
            if (attributes.containsKey("anf") && attributes.containsKey("element")
                    && spans(attributes.get("from"), anchoring).isEmpty()) {
                lost.add(attributes.get("element"));
            }
        }
        return lost;
    }

    /** The anchors of a {@code from} value, as written. */
    public static List<String> anchorsOf(String from) {
        List<String> anchors = new ArrayList<>();
        if (from != null && !from.isBlank()) {
            for (String anchor : from.split("\\|(?=quote:|segment:)")) {
                anchors.add(anchor.strip());
            }
        }
        return anchors;
    }

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
            Optional<String> segmentWords = SegmentNarrative.words(a);
            if (segmentWords.isPresent()) {
                quotes.add(new Quote(segmentWords.get(), 1));
            } else if (a.startsWith("quote:")) {
                String body = a.substring("quote:".length());
                int hash = body.lastIndexOf('#');
                boolean numbered = hash > 0 && body.substring(hash + 1).matches("\\d+");
                quotes.add(new Quote(numbered ? body.substring(0, hash) : body,
                        numbered ? Math.max(1, Integer.parseInt(body.substring(hash + 1))) : 1));
            }
        }
        return quotes;
    }

    /** The quoted texts a {@code from} value names, a segment anchor's words among them, for a surface with no narrative to resolve them in. */
    public static List<String> quotes(String from) {
        List<String> quotes = new ArrayList<>();
        if (from == null || from.isBlank()) {
            return quotes;
        }
        for (String anchor : from.split("\\|(?=quote:|segment:)")) {
            String a = anchor.strip();
            Optional<String> segmentWords = SegmentNarrative.words(a);
            if (segmentWords.isPresent()) {
                quotes.add(segmentWords.get());
            } else if (a.startsWith("quote:")) {
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
