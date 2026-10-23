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
package network.ike.komet.claude.help;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Searches a help book's topics by the words a person types: a topic matches when every word
 * appears in its title or text, case-insensitively, and topics whose title holds the words come
 * first, then those that use them most. A help book is a dozen topics, read whole, so the search
 * holds their text and scans it.
 */
final class HelpSearch {

    /**
     * A topic as the search reads it.
     *
     * @param topicId the topic's identifier
     * @param title   its title
     * @param text    its text as read, without markup
     */
    record Page(String topicId, String title, String text) {
    }

    /**
     * A topic that matched.
     *
     * @param topicId the topic's identifier
     * @param title   its title
     * @param snippet the text around the first word's first use in it
     */
    record Hit(String topicId, String title, String snippet) {
    }

    private static final int SNIPPET_BEFORE = 40;
    private static final int SNIPPET_LENGTH = 140;

    private final List<Page> pages;

    HelpSearch(List<Page> pages) {
        this.pages = List.copyOf(pages);
    }

    /**
     * Finds the topics that use every word of a query.
     *
     * @param query the words, separated by spaces
     * @return the matching topics, best first; empty for a blank query
     */
    List<Hit> find(String query) {
        String[] words = Arrays.stream(query.toLowerCase(Locale.ROOT).strip().split("\\s+"))
                .filter(word -> !word.isEmpty()).toArray(String[]::new);
        if (words.length == 0) {
            return List.of();
        }
        record Scored(Hit hit, int titleWords, int uses, int order) { }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            Page page = pages.get(i);
            String title = page.title().toLowerCase(Locale.ROOT);
            String text = page.text().toLowerCase(Locale.ROOT);
            int titleWords = 0;
            int uses = 0;
            boolean all = true;
            for (String word : words) {
                boolean inTitle = title.contains(word);
                int count = count(text, word);
                if (!inTitle && count == 0) {
                    all = false;
                    break;
                }
                titleWords += inTitle ? 1 : 0;
                uses += count;
            }
            if (all) {
                scored.add(new Scored(new Hit(page.topicId(), page.title(), snippet(page.text(), text, words[0])), titleWords, uses, i));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::titleWords).reversed()
                .thenComparing(Comparator.comparingInt(Scored::uses).reversed())
                .thenComparingInt(Scored::order));
        return scored.stream().map(Scored::hit).toList();
    }

    private static int count(String text, String word) {
        int count = 0;
        for (int at = text.indexOf(word); at >= 0; at = text.indexOf(word, at + word.length())) {
            count++;
        }
        return count;
    }

    private static String snippet(String text, String lower, String word) {
        String flat = text.replaceAll("\\s+", " ");
        int at = lower.replaceAll("\\s+", " ").indexOf(word);
        if (at < 0) {
            return flat.length() <= SNIPPET_LENGTH ? flat : flat.substring(0, SNIPPET_LENGTH).stripTrailing() + "…";
        }
        int start = Math.max(0, at - SNIPPET_BEFORE);
        int space = flat.lastIndexOf(' ', start);
        start = start == 0 || space < 0 ? start : space + 1;
        int end = Math.min(flat.length(), start + SNIPPET_LENGTH);
        return (start > 0 ? "…" : "") + flat.substring(start, end).strip() + (end < flat.length() ? "…" : "");
    }
}
