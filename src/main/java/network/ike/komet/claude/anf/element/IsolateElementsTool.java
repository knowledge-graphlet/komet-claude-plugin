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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import network.ike.komet.claude.anthropic.AnthropicTool;

/**
 * The forced-decision tool of step one ({@code dev-anf-element-isolation}): the model hands back
 * the note's clinical elements, each a sentence and the quoted words it rests on, and the spans it
 * judged to carry no clinical fact. The handler resolves every quote in the narrative and refuses
 * the call, naming each problem, when an element quotes nothing, quotes words the note does not
 * hold, or states no sentence: an element with no span would be an inference. Nothing here touches
 * the knowledge base; this step reads the note.
 */
public final class IsolateElementsTool implements AnthropicTool {

    public static final String NAME = "isolate_elements";

    private final String narrative;
    private final Consumer<ElementSet> sink;

    public IsolateElementsTool(String narrative, Consumer<ElementSet> sink) {
        this.narrative = Objects.requireNonNull(narrative, "narrative");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Call this exactly once with every clinical element of the note. An element is one fact, "
                + "stated as one plain standalone sentence with its subject, and the exact words of the note "
                + "it rests on, quoted verbatim. One quote may support several elements; one element may "
                + "quote several places. Put every stretch of the note that states no clinical fact in "
                + "not_clinical. Never state a fact the note does not.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> quote = obj("type", "object", "properties", obj(
                "quote", obj("type", "string", "description", "The words of the note, exactly as written."),
                "occurrence", obj("type", "integer", "description",
                        "Which occurrence of those words, counting from 1, when they appear more than once.")),
                "required", List.of("quote"));
        Map<String, Object> element = obj("type", "object", "properties", obj(
                "sentence", obj("type", "string", "description",
                        "The fact as one plain sentence with its subject, polarity and any missing result explicit: "
                                + "'The patient is 62 years old.', 'COVID-19 is absent.', "
                                + "'HDL cholesterol has no result: it was not measured.'"),
                "spans", obj("type", "array", "items", quote, "minItems", 1),
                "links", obj("type", "array", "items", obj("type", "integer"), "description",
                        "Indexes (from 0) of other elements this one qualifies, such as a body position for a "
                                + "blood pressure."),
                "reason", obj("type", "string", "description",
                        "Why you split or merged here, in the independence test's terms, when it is not obvious.")),
                "required", List.of("sentence", "spans"));
        return obj("type", "object", "properties", obj(
                        "elements", obj("type", "array", "items", element),
                        "not_clinical", obj("type", "array", "items", quote)),
                "required", List.of("elements"));
    }

    @Override
    public String execute(Map<String, Object> input) {
        Result result = read(input, narrative);
        if (!result.problems().isEmpty()) {
            return "The elements were refused: " + String.join("; ", result.problems())
                    + ". Correct them and call " + NAME + " again with the whole list.";
        }
        sink.accept(result.set().orElseThrow());
        int n = result.set().get().elements().size();
        return "recorded " + n + (n == 1 ? " element" : " elements");
    }

    /** What a call reads as: the element set, or the problems that refuse it. */
    public record Result(Optional<ElementSet> set, List<String> problems) {
    }

    /** Reads a call's input against a narrative; pure, for the handler and for a test. */
    public static Result read(Map<String, Object> input, String narrative) {
        List<String> problems = new ArrayList<>();
        List<Object> raw = list(input, "elements");
        if (raw.isEmpty()) {
            problems.add("no elements were given");
        }
        List<ClinicalElement> elements = new ArrayList<>();
        List<List<Integer>> rawLinks = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> e = map(raw.get(i));
            String sentence = str(e, "sentence");
            List<Span> spans = new ArrayList<>();
            for (Object q : list(e, "spans")) {
                quoteSpan(map(q), narrative, "element " + i, problems).ifPresent(spans::add);
            }
            if (sentence.isBlank()) {
                problems.add("element " + i + " states no sentence");
            }
            if (list(e, "spans").isEmpty()) {
                problems.add("element " + i + " (" + sentence + ") quotes no words of the note; an element with no "
                        + "quote would be an inference");
            }
            List<Integer> links = new ArrayList<>();
            for (Object l : list(e, "links")) {
                if (l instanceof Number number) {
                    links.add(number.intValue());
                }
            }
            rawLinks.add(links);
            if (!sentence.isBlank() && !spans.isEmpty()) {
                elements.add(ClinicalElement.proposed("el-" + (i + 1), sentence, spans).withReason(str(e, "reason")));
            } else {
                elements.add(null);
            }
        }
        List<Span> notClinical = new ArrayList<>();
        for (Object q : list(input, "not_clinical")) {
            quoteSpan(map(q), narrative, "not_clinical", problems).ifPresent(notClinical::add);
        }
        if (!problems.isEmpty()) {
            return new Result(Optional.empty(), problems);
        }
        List<ClinicalElement> linked = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            List<String> ids = new ArrayList<>();
            for (int target : rawLinks.get(i)) {
                if (target >= 0 && target < elements.size() && target != i) {
                    ids.add("el-" + (target + 1));
                }
            }
            linked.add(elements.get(i).withLinks(ids));
        }
        ElementSet set = new ElementSet(narrative, linked, notClinical);
        // links are symmetric in the set
        for (ClinicalElement e : List.copyOf(set.elements())) {
            for (String target : e.links()) {
                set = set.link(e.id(), target);
            }
        }
        return new Result(Optional.of(set), List.of());
    }

    private static Optional<Span> quoteSpan(Map<String, Object> q, String narrative, String where, List<String> problems) {
        String quote = str(q, "quote");
        int occurrence = q.get("occurrence") instanceof Number number ? number.intValue() : 1;
        Optional<Span> span = Span.find(narrative, quote, occurrence);
        if (span.isEmpty()) {
            problems.add(where + " quotes \"" + quote + "\""
                    + (occurrence > 1 ? " (occurrence " + occurrence + ")" : "") + ", which the note does not hold");
        }
        return span;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v.toString().strip();
    }

    private static List<Object> list(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List<?> l ? new ArrayList<>(l) : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }
}
