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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import network.ike.komet.claude.anf.AnfStatement;

/**
 * A note's clinical elements ({@code dev-anf-element-isolation}): the narrative, the elements
 * isolated from it, and the spans the author marked as carrying no clinical fact. Immutable: each
 * gesture of the first step, edit, split, merge, add, remove, mark not clinical, link, returns a
 * new set, so the surface keeps an undo history for free and a test reads the result directly.
 *
 * @param narrative   the note as written
 * @param elements    the elements, in the order of their first span
 * @param notClinical the spans covered without an element
 */
public record ElementSet(String narrative, List<ClinicalElement> elements, List<Span> notClinical) {

    public ElementSet {
        Objects.requireNonNull(narrative, "narrative");
        elements = elements == null ? List.of()
                : elements.stream().sorted((a, b) -> a.spans().get(0).compareTo(b.spans().get(0))).toList();
        notClinical = notClinical == null ? List.of() : notClinical.stream().sorted().distinct().toList();
    }

    public static ElementSet empty(String narrative) {
        return new ElementSet(narrative, List.of(), List.of());
    }

    public Optional<ClinicalElement> element(String id) {
        return elements.stream().filter(e -> e.id().equals(id)).findFirst();
    }

    // ── Coverage ─────────────────────────────────────────────────────────────

    /**
     * The stretches of the narrative no element and no not-clinical mark covers, each trimmed of
     * surrounding whitespace and punctuation and left out when nothing but those remains: what the
     * author still has to account for.
     */
    public List<Span> uncovered() {
        boolean[] covered = new boolean[narrative.length()];
        for (ClinicalElement element : elements) {
            for (Span span : element.spans()) {
                mark(covered, span);
            }
        }
        for (Span span : notClinical) {
            mark(covered, span);
        }
        List<Span> gaps = new ArrayList<>();
        int i = 0;
        while (i < covered.length) {
            if (covered[i]) {
                i++;
                continue;
            }
            int start = i;
            while (i < covered.length && !covered[i]) {
                i++;
            }
            trimmed(start, i).ifPresent(gaps::add);
        }
        return gaps;
    }

    /** Whether every stretch of the narrative with a letter or digit in it is accounted for. */
    public boolean fullyCovered() {
        return uncovered().isEmpty();
    }

    private static void mark(boolean[] covered, Span span) {
        for (int i = span.start(); i < Math.min(span.end(), covered.length); i++) {
            covered[i] = true;
        }
    }

    private Optional<Span> trimmed(int start, int end) {
        int s = start;
        int e = end;
        while (s < e && !Character.isLetterOrDigit(narrative.charAt(s))) {
            s++;
        }
        while (e > s && !Character.isLetterOrDigit(narrative.charAt(e - 1))) {
            e--;
        }
        return s < e ? Optional.of(new Span(s, e)) : Optional.empty();
    }

    // ── The gestures of step one ─────────────────────────────────────────────

    /** The next unused element id. */
    public String nextId() {
        int max = 0;
        for (ClinicalElement element : elements) {
            if (element.id().startsWith("el-")) {
                try {
                    max = Math.max(max, Integer.parseInt(element.id().substring(3)));
                } catch (NumberFormatException e) {
                    // an id the author chose
                }
            }
        }
        return "el-" + (max + 1);
    }

    /** These elements with one added, resting on the given spans. */
    public ElementSet add(String sentence, List<Span> spans) {
        List<ClinicalElement> next = new ArrayList<>(elements);
        next.add(ClinicalElement.proposed(nextId(), sentence, spans).withAccepted(true));
        return new ElementSet(narrative, next, notClinical);
    }

    /** These elements with one replaced. */
    public ElementSet replace(ClinicalElement element) {
        List<ClinicalElement> next = new ArrayList<>();
        for (ClinicalElement e : elements) {
            next.add(e.id().equals(element.id()) ? element : e);
        }
        return new ElementSet(narrative, next, notClinical);
    }

    /** These elements with one's sentence edited; a lifted element becomes stale. */
    public ElementSet edit(String id, String sentence) {
        return element(id).map(e -> replace(e.withSentence(sentence).withAccepted(true))).orElse(this);
    }

    public ElementSet accept(String id, boolean accepted) {
        return element(id).map(e -> replace(e.withAccepted(accepted))).orElse(this);
    }

    /** These elements with one removed, and every link to it dropped. */
    public ElementSet remove(String id) {
        List<ClinicalElement> next = new ArrayList<>();
        for (ClinicalElement e : elements) {
            if (!e.id().equals(id)) {
                next.add(e.links().contains(id) ? e.withLinks(e.links().stream().filter(l -> !l.equals(id)).toList()) : e);
            }
        }
        return new ElementSet(narrative, next, notClinical);
    }

    /**
     * These elements with one split in two: the original keeps its id, spans and lift with the
     * first sentence, and a new element rests on the same spans with the second, since one span
     * may hold two facts. The author then narrows either element's spans if they differ.
     */
    public ElementSet split(String id, String first, String second) {
        Optional<ClinicalElement> found = element(id);
        if (found.isEmpty()) {
            return this;
        }
        ClinicalElement original = found.get();
        List<ClinicalElement> next = new ArrayList<>();
        for (ClinicalElement e : elements) {
            next.add(e.id().equals(id) ? e.withSentence(first).withAccepted(true) : e);
        }
        next.add(ClinicalElement.proposed(nextId(), second, original.spans()).withAccepted(true));
        return new ElementSet(narrative, next, notClinical);
    }

    /**
     * These elements with two merged into one: the first keeps its id, takes the given sentence,
     * the union of both spans and of both links, and loses its lift, since the fact it states has
     * changed; the second is removed.
     */
    public ElementSet merge(String keepId, String dropId, String sentence) {
        Optional<ClinicalElement> keep = element(keepId);
        Optional<ClinicalElement> drop = element(dropId);
        if (keep.isEmpty() || drop.isEmpty() || keepId.equals(dropId)) {
            return this;
        }
        List<Span> spans = new ArrayList<>(keep.get().spans());
        spans.addAll(drop.get().spans());
        Set<String> links = new LinkedHashSet<>(keep.get().links());
        links.addAll(drop.get().links());
        links.remove(keepId);
        links.remove(dropId);
        ClinicalElement merged = new ClinicalElement(keepId, sentence, spans, List.copyOf(links), true,
                Optional.empty(), Optional.empty(), Optional.empty());
        ElementSet without = remove(dropId);
        List<ClinicalElement> next = new ArrayList<>();
        for (ClinicalElement e : without.elements()) {
            ClinicalElement relinked = e.links().contains(dropId)
                    ? e.withLinks(e.links().stream().map(l -> l.equals(dropId) ? keepId : l).distinct().toList()) : e;
            next.add(relinked.id().equals(keepId) ? merged : relinked);
        }
        return new ElementSet(narrative, next, notClinical);
    }

    /** These elements with a span marked as carrying no clinical fact. */
    public ElementSet markNotClinical(Span span) {
        List<Span> next = new ArrayList<>(notClinical);
        next.add(span);
        return new ElementSet(narrative, elements, next);
    }

    /** These elements with every not-clinical mark that overlaps a span removed. */
    public ElementSet unmarkNotClinical(Span span) {
        return new ElementSet(narrative, elements, notClinical.stream().filter(s -> !s.overlaps(span)).toList());
    }

    /** These elements with two linked, each naming the other. */
    public ElementSet link(String a, String b) {
        if (a.equals(b) || element(a).isEmpty() || element(b).isEmpty()) {
            return this;
        }
        ElementSet next = this;
        for (String[] pair : new String[][] {{a, b}, {b, a}}) {
            ClinicalElement e = next.element(pair[0]).orElseThrow();
            if (!e.links().contains(pair[1])) {
                List<String> links = new ArrayList<>(e.links());
                links.add(pair[1]);
                next = next.replace(e.withLinks(links));
            }
        }
        return next;
    }

    /** These elements with one lifted to a statement. */
    public ElementSet lifted(String id, AnfStatement statement) {
        return element(id).map(e -> replace(e.lifted(statement))).orElse(this);
    }

    /** Whether every element has been reviewed: the gate on lifting them all at once. */
    public boolean allAccepted() {
        return !elements.isEmpty() && elements.stream().allMatch(ClinicalElement::accepted);
    }

    /** The elements a lift of all would lift: those not lifted and those stale. */
    public List<ClinicalElement> toLift() {
        return elements.stream()
                .filter(e -> e.state() == ElementState.NOT_LIFTED || e.state() == ElementState.STALE)
                .toList();
    }

    /** The elements resting on a span that contains an offset of the narrative. */
    public List<ClinicalElement> at(int offset) {
        return elements.stream().filter(e -> e.spans().stream().anyMatch(s -> s.contains(offset))).toList();
    }
}
