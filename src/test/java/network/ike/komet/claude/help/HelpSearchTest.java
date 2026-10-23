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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Searching a help book's topics by words. */
class HelpSearchTest {

    private final HelpSearch search = new HelpSearch(List.of(
            new HelpSearch.Page("overview", "The card", "The card shows a document kept in git. Open a file from git to begin."),
            new HelpSearch.Page("kinds", "Kinds of data", "Ephemeral data is kept in git. Instance data is kept only in the store."),
            new HelpSearch.Page("stores", "Stores", "A memory-only store writes nothing.")));

    @Test
    void everyWordMustAppear() {
        assertEquals(List.of("kinds"), ids(search.find("instance git")));
        assertEquals(List.of(), ids(search.find("instance rocks")));
    }

    @Test
    void titleMatchesComeFirstThenTheMostUses() {
        assertEquals(List.of("stores", "kinds"), ids(search.find("store")));
        assertEquals(List.of("overview", "kinds"), ids(search.find("GIT")));
    }

    @Test
    void aBlankQueryFindsNothing() {
        assertEquals(List.of(), search.find("   "));
    }

    @Test
    void theSnippetShowsTheFirstUse() {
        String snippet = search.find("memory-only").getFirst().snippet();
        assertTrue(snippet.contains("memory-only store"), snippet);
    }

    private static List<String> ids(List<HelpSearch.Hit> hits) {
        return hits.stream().map(HelpSearch.Hit::topicId).toList();
    }
}
