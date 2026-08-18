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
package network.ike.komet.claude.ui;

import network.ike.komet.claude.ui.MarkdownDiff.Kind;
import network.ike.komet.claude.ui.MarkdownDiff.Run;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The word-level diff beneath the track-changes view: runs must reconstruct the base from
 * EQUAL+DELETED and the revision from EQUAL+INSERTED — the two invariants every rendering
 * depends on — with word (not character) granularity and line breaks as first-class tokens.
 */
class MarkdownDiffTest {

    private static String join(List<Run> runs, Kind excluded) {
        StringBuilder text = new StringBuilder();
        for (Run run : runs) {
            if (run.kind() != excluded) {
                text.append(run.text());
            }
        }
        return text.toString();
    }

    @Test
    void runsReconstructBothSides() {
        String base = "Lead with the answer.\nThen the concepts as a table.";
        String revised = "Lead with the ANSWER first.\nThen the supporting concepts as a table.";
        List<Run> runs = MarkdownDiff.runs(base, revised);
        assertEquals(base, join(runs, Kind.INSERTED), "EQUAL + DELETED is exactly the base");
        assertEquals(revised, join(runs, Kind.DELETED), "EQUAL + INSERTED is exactly the revision");
    }

    @Test
    void untouchedTextIsOneEqualRun() {
        List<Run> runs = MarkdownDiff.runs("Same text.", "Same text.");
        assertEquals(1, runs.size());
        assertEquals(Kind.EQUAL, runs.get(0).kind());
    }

    @Test
    void wordsChangeAsWordsNotCharacters() {
        List<Run> runs = MarkdownDiff.runs("the graph says", "the store says");
        assertTrue(runs.stream().anyMatch(run ->
                        run.kind() == Kind.DELETED && run.text().equals("graph")),
                "the removed word is a whole token");
        assertTrue(runs.stream().anyMatch(run ->
                        run.kind() == Kind.INSERTED && run.text().equals("store")),
                "the added word is a whole token");
    }

    @Test
    void emptySidesDegradeToPureInsertOrDelete() {
        List<Run> inserted = MarkdownDiff.runs("", "All new.");
        assertEquals(1, inserted.size());
        assertEquals(Kind.INSERTED, inserted.get(0).kind());
        List<Run> deleted = MarkdownDiff.runs("All gone.", null);
        assertEquals(1, deleted.size());
        assertEquals(Kind.DELETED, deleted.get(0).kind());
        assertTrue(MarkdownDiff.runs(null, null).isEmpty());
    }

    @Test
    void lineBreaksAreTokensSoParagraphMovesDiffCleanly() {
        String base = "One line only.";
        String revised = "One line\nonly.";
        List<Run> runs = MarkdownDiff.runs(base, revised);
        assertEquals(base, join(runs, Kind.INSERTED));
        assertEquals(revised, join(runs, Kind.DELETED));
        assertTrue(runs.stream().anyMatch(run ->
                        run.kind() == Kind.INSERTED && run.text().contains("\n")),
                "the introduced line break is part of an inserted run");
    }
}
