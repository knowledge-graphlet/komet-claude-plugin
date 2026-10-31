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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;

/**
 * One fact of a note ({@code dev-anf-element-isolation}): the fact as one plain, standalone
 * sentence, the narrative spans it rests on, the elements it is linked to, and, once lifted, the
 * ANF statement it became and the sentence it was lifted from. An element names at least one span:
 * one with none would be an inference, which the ANF guide forbids, so the record refuses it.
 *
 * @param id             the element's name in its note, {@code el-1}
 * @param sentence       the fact as one sentence
 * @param spans          the narrative ranges it rests on, at least one, in order
 * @param links          the ids of elements this one qualifies or is qualified by
 * @param accepted       whether the author has reviewed it
 * @param statement      the ANF statement it was lifted to, when lifted
 * @param liftedSentence the sentence as it stood when lifted, to tell a stale lift
 * @param reason         why the isolation split or merged here, when it said
 */
public record ClinicalElement(String id, String sentence, List<Span> spans, List<String> links, boolean accepted,
                              Optional<AnfStatement> statement, Optional<String> liftedSentence,
                              Optional<String> reason) {

    public ClinicalElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sentence, "sentence");
        if (sentence.isBlank()) {
            throw new IllegalArgumentException("an element states its fact as a sentence");
        }
        if (spans == null || spans.isEmpty()) {
            throw new IllegalArgumentException("an element rests on at least one span of the narrative");
        }
        spans = spans.stream().sorted().distinct().toList();
        links = links == null ? List.of() : List.copyOf(links);
        statement = statement == null ? Optional.empty() : statement;
        liftedSentence = liftedSentence == null ? Optional.empty() : liftedSentence;
        reason = reason == null ? Optional.empty() : reason;
    }

    /** A proposed element, not yet reviewed or lifted. */
    public static ClinicalElement proposed(String id, String sentence, List<Span> spans) {
        return new ClinicalElement(id, sentence.strip(), spans, List.of(), false, Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    public ElementState state() {
        if (statement.isEmpty()) {
            return ElementState.NOT_LIFTED;
        }
        if (liftedSentence.isPresent() && !liftedSentence.get().equals(sentence)) {
            return ElementState.STALE;
        }
        return needsAttention(statement.get()) ? ElementState.NEEDS_ATTENTION : ElementState.LIFTED;
    }

    /** Whether a statement holds a candidate concept or a clarification. */
    static boolean needsAttention(AnfStatement statement) {
        String adoc = AnfAdoc.toAdoc(statement);
        return adoc.contains(":: candidate[") || adoc.contains(":: clarify[");
    }

    public ClinicalElement withSentence(String newSentence) {
        return new ClinicalElement(id, newSentence.strip(), spans, links, accepted, statement, liftedSentence, reason);
    }

    public ClinicalElement withSpans(List<Span> newSpans) {
        return new ClinicalElement(id, sentence, newSpans, links, accepted, statement, liftedSentence, reason);
    }

    public ClinicalElement withLinks(List<String> newLinks) {
        return new ClinicalElement(id, sentence, spans, newLinks, accepted, statement, liftedSentence, reason);
    }

    public ClinicalElement withAccepted(boolean isAccepted) {
        return new ClinicalElement(id, sentence, spans, links, isAccepted, statement, liftedSentence, reason);
    }

    public ClinicalElement withReason(String why) {
        return new ClinicalElement(id, sentence, spans, links, accepted, statement, liftedSentence,
                why == null || why.isBlank() ? Optional.empty() : Optional.of(why.strip()));
    }

    /** This element lifted to a statement from its current sentence. */
    public ClinicalElement lifted(AnfStatement lifted) {
        return new ClinicalElement(id, sentence, spans, links, accepted, Optional.of(lifted), Optional.of(sentence), reason);
    }

    /** This element with its statement edited in place: the lift's sentence is unchanged, so the state follows the edit. */
    public ClinicalElement withStatement(AnfStatement edited) {
        return new ClinicalElement(id, sentence, spans, links, accepted, Optional.of(edited),
                liftedSentence.isPresent() ? liftedSentence : Optional.of(sentence), reason);
    }

    /** This element with its statement kept as it is for the current sentence: the author accepts a stale lift. */
    public ClinicalElement acceptLift() {
        return statement.isEmpty() ? this
                : new ClinicalElement(id, sentence, spans, links, accepted, statement, Optional.of(sentence), reason);
    }

    /** The narrative text of each span, in order. */
    public List<String> quotes(String narrative) {
        return spans.stream().map(span -> span.text(narrative)).toList();
    }
}
