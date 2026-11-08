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
package network.ike.komet.claude.stamp;

import network.ike.komet.claude.stamp.SegmentNarrative;

import network.ike.komet.claude.stamp.Span;

import java.util.List;
import java.util.Optional;

/**
 * How an element's spans are written and found again ({@code dev-anf-element-isolation}, "The
 * written form"). In a conversation, where the narrative has no identity below its text, a span
 * is its quoted text and occurrence, {@code quote:A 62-year-old man#1}. In a stamped document a
 * span is a column range of a stored sentence, {@link SegmentNarrative}, so it survives an edit
 * elsewhere in the note and follows its sentence through a revision.
 */
public interface Anchoring {

    /** The narrative the spans are offsets into. */
    String narrative();

    /** The anchors of one span, joined by {@code |}: a span across two sentences is one anchor per sentence. */
    String anchor(Span span);

    /** The span one anchor finds in the narrative; empty when it no longer matches, which is a finding. */
    Optional<Span> resolve(String anchor);

    /** The anchors of several spans, joined by {@code |}. */
    default String anchors(List<Span> spans) {
        return String.join("|", spans.stream().map(this::anchor).toList());
    }

    /** Quote anchors over a narrative with no segments. */
    static Anchoring quotes(String narrative) {
        return new Anchoring() {
            @Override
            public String narrative() {
                return narrative;
            }

            @Override
            public String anchor(Span span) {
                return span.quoteAnchor(narrative);
            }

            @Override
            public Optional<Span> resolve(String anchor) {
                // a segment anchor read where its document is not at hand: its words, where they first occur
                Optional<String> words = SegmentNarrative.words(anchor);
                return words.isPresent() ? Span.find(narrative, words.get(), 1) : Span.resolve(anchor, narrative);
            }
        };
    }
}
