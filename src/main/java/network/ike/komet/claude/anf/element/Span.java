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
import java.util.List;
import java.util.Optional;

/**
 * A range of a narrative's characters, {@code [start, end)}, that a clinical element rests on
 * ({@code dev-anf-element-isolation}). Stand-off: the narrative is never changed, and a span is
 * written as an anchor that finds it again, {@code quote:<text>#<occurrence>} in a conversation.
 */
public record Span(int start, int end) implements Comparable<Span> {

    public Span {
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("a span is a non-empty range: [" + start + ", " + end + ")");
        }
    }

    /** The narrative's text over this span. */
    public String text(String narrative) {
        return narrative.substring(start, Math.min(end, narrative.length()));
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Span other) {
        return start < other.end && other.start < end;
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    @Override
    public int compareTo(Span other) {
        return start != other.start ? Integer.compare(start, other.start) : Integer.compare(end, other.end);
    }

    // ── Anchors ──────────────────────────────────────────────────────────────

    private static final String QUOTE = "quote:";

    /**
     * The anchor that finds this span again in a narrative with no segment identity: the quoted
     * text and which occurrence of it, counted from one.
     */
    public String quoteAnchor(String narrative) {
        String text = text(narrative);
        int occurrence = 0;
        int from = 0;
        while (true) {
            int at = narrative.indexOf(text, from);
            if (at < 0 || at > start) {
                break;
            }
            occurrence++;
            if (at == start) {
                break;
            }
            from = at + 1;
        }
        return QUOTE + text + "#" + Math.max(1, occurrence);
    }

    /**
     * The span a quote anchor names in a narrative: its text's given occurrence, exactly, or with
     * case and runs of whitespace ignored when the exact text is absent. Empty when it is absent
     * either way.
     */
    public static Optional<Span> resolve(String anchor, String narrative) {
        if (anchor == null || !anchor.startsWith(QUOTE)) {
            return Optional.empty();
        }
        String body = anchor.substring(QUOTE.length());
        int occurrence = 1;
        int hash = body.lastIndexOf('#');
        if (hash > 0) {
            try {
                occurrence = Integer.parseInt(body.substring(hash + 1).strip());
                body = body.substring(0, hash);
            } catch (NumberFormatException e) {
                // a # that is part of the quoted text
            }
        }
        return find(narrative, body, occurrence);
    }

    /** The span of a quoted text's occurrence (counted from one) in a narrative. */
    public static Optional<Span> find(String narrative, String quote, int occurrence) {
        if (narrative == null || quote == null || quote.isBlank() || occurrence < 1) {
            return Optional.empty();
        }
        String text = quote.strip();
        List<Span> exact = occurrences(narrative, text, false);
        if (exact.size() >= occurrence) {
            return Optional.of(exact.get(occurrence - 1));
        }
        List<Span> loose = occurrences(narrative, text, true);
        return loose.size() >= occurrence ? Optional.of(loose.get(occurrence - 1)) : Optional.empty();
    }

    private static List<Span> occurrences(String narrative, String text, boolean loose) {
        List<Span> found = new ArrayList<>();
        if (!loose) {
            int from = 0;
            while (true) {
                int at = narrative.indexOf(text, from);
                if (at < 0) {
                    break;
                }
                found.add(new Span(at, at + text.length()));
                from = at + 1;
            }
            return found;
        }
        // case-insensitive, any run of whitespace in the quote matching any run in the narrative
        StringBuilder pattern = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (!pattern.isEmpty()) {
                pattern.append("\\s+");
            }
            pattern.append(java.util.regex.Pattern.quote(word));
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(pattern.toString(), java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
                .matcher(narrative);
        while (m.find()) {
            found.add(new Span(m.start(), m.end()));
        }
        return found;
    }
}
