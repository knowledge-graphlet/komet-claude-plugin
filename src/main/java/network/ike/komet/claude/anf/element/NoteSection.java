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

import network.ike.komet.claude.stamp.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A section of a note, the level above its elements in the lift region's element tree. A note's
 * sections follow its headings: a paragraph of one short line without closing punctuation, or a
 * short first line ending in a colon, titles the text up to the next heading. A note with no
 * headings is sectioned by its paragraphs, each titled by its first words.
 *
 * @param title the heading, or the section's first words
 * @param range the narrative the section covers, heading included
 */
public record NoteSection(String title, Span range) {

    private static final Pattern PARAGRAPH = Pattern.compile("\\S[\\s\\S]*?(?=\\n\\s*\\n|\\s*$)");
    private static final int HEADING_LENGTH = 60;
    private static final int TITLE_WORDS = 6;

    /** Whether an offset of the narrative falls in this section. */
    public boolean holds(int offset) {
        return offset >= range.start() && offset < range.end();
    }

    /** The sections of a narrative, in order, together covering all of its text. */
    public static List<NoteSection> of(String narrative) {
        List<Span> paragraphs = new ArrayList<>();
        Matcher m = PARAGRAPH.matcher(narrative);
        while (m.find()) {
            paragraphs.add(new Span(m.start(), m.end()));
        }
        if (paragraphs.isEmpty()) {
            return narrative.isEmpty() ? List.of() : List.of(new NoteSection(firstWords(narrative), new Span(0, narrative.length())));
        }
        List<String> headings = paragraphs.stream().map(p -> heading(p.text(narrative))).toList();
        boolean headed = headings.stream().anyMatch(h -> h != null);
        List<NoteSection> sections = new ArrayList<>();
        int start = 0;
        String title = null;
        for (int i = 0; i < paragraphs.size(); i++) {
            String heading = headings.get(i);
            boolean opens = headed ? heading != null : true;
            if (opens && i > 0) {
                sections.add(new NoteSection(title, new Span(start, paragraphs.get(i).start())));
                start = paragraphs.get(i).start();
                title = null;
            }
            if (title == null) {
                title = heading != null ? heading : firstWords(paragraphs.get(i).text(narrative));
            }
        }
        sections.add(new NoteSection(title, new Span(start, Math.max(start + 1, narrative.length()))));
        return sections;
    }

    /** A paragraph's heading: its one short line without closing punctuation, or a short first line ending in a colon. */
    private static String heading(String paragraph) {
        String first = paragraph.lines().findFirst().orElse("").strip();
        boolean oneLine = paragraph.strip().lines().count() == 1;
        if (oneLine && first.length() <= HEADING_LENGTH && !first.matches(".*[.?!:;,]$")) {
            return first;
        }
        if (first.endsWith(":") && first.length() <= HEADING_LENGTH / 2 + 1) {
            return first.substring(0, first.length() - 1).strip();
        }
        return null;
    }

    private static String firstWords(String text) {
        String[] words = text.strip().split("\\s+");
        if (words.length <= TITLE_WORDS) {
            return String.join(" ", words);
        }
        return String.join(" ", java.util.Arrays.copyOf(words, TITLE_WORDS)) + "…";
    }
}
