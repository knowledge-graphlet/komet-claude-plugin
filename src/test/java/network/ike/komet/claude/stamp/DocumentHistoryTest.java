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

import network.ike.komet.claude.stamp.DocumentHistory.Change;
import network.ike.komet.claude.stamp.DocumentHistory.Raw;
import network.ike.komet.claude.stamp.DocumentHistory.RetiredLine;
import network.ike.komet.claude.stamp.DocumentHistory.Version;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A document's history from the manifest orders and the line versions: changes named, retired lines placed. */
class DocumentHistoryTest {

    @Test
    void changesAreNamedFromTheVersionBefore() {
        DocumentHistory history = DocumentHistory.from(List.of(List.of(1L)), Map.of(
                1L, List.of(new Raw(null, "First.", true), new Raw(null, "First.", true), new Raw(null, "First, revised.", true))));

        List<Version> versions = history.versions(1L);
        assertEquals(List.of(Change.INTRODUCED, Change.REFLOWED, Change.RETEXTED), versions.stream().map(Version::change).toList());
        assertEquals(List.of("First.", "First.", "First, revised."), versions.stream().map(Version::text).toList());
        assertEquals("retexted", versions.getLast().word());
        assertTrue(history.retired().isEmpty());
        assertFalse(history.isEmpty());
    }

    @Test
    void aRetiredLineStandsAfterTheSurvivingLineItFollowed() {
        // Commit 1 wrote lines 1, 2, 3; commit 2 removed line 2; commit 3 removed line 1 and added line 4 at the front.
        DocumentHistory history = DocumentHistory.from(
                List.of(List.of(1L, 2L, 3L), List.of(1L, 3L), List.of(4L, 3L)),
                Map.of(1L, List.of(new Raw(null, "One.", true), new Raw(null, "One.", false)),
                        2L, List.of(new Raw(null, "Two.", true), new Raw(null, "Two.", false)),
                        3L, List.of(new Raw(null, "Three.", true)),
                        4L, List.of(new Raw(null, "Four.", true))));

        assertEquals(List.of(Change.INTRODUCED, Change.RETIRED), history.versions(2L).stream().map(Version::change).toList());
        assertEquals("Two.", history.versions(2L).getLast().text(), "a retirement keeps the text it removed");

        List<RetiredLine> retired = history.retired();
        assertEquals(2, retired.size());
        RetiredLine one = retired.stream().filter(r -> r.nid() == 1L).findFirst().orElseThrow();
        RetiredLine two = retired.stream().filter(r -> r.nid() == 2L).findFirst().orElseThrow();
        assertEquals("One.", one.text());
        assertEquals(0L, one.afterNid(), "line 1 led the document in the last manifest that listed it");
        assertEquals(0L, two.afterNid(), "line 2 followed only line 1 there, which is gone too: no survivor precedes it, so it leads as well");
        assertEquals(List.of(one, two), history.retiredAtStart(), "both lead, in the order they were met");
        assertEquals(List.of(), history.retiredAfter(3L));
        assertEquals(List.of(), history.retiredAfter(4L));
    }

    @Test
    void theNearestSurvivingLineIsTheAnchor() {
        // Commit 1 wrote 1, 2, 3, 4; commit 2 removed 2 and 3.
        DocumentHistory history = DocumentHistory.from(
                List.of(List.of(1L, 2L, 3L, 4L), List.of(1L, 4L)),
                Map.of(1L, List.of(new Raw(null, "One.", true)),
                        2L, List.of(new Raw(null, "Two.", true), new Raw(null, "Two.", false)),
                        3L, List.of(new Raw(null, "Three.", true), new Raw(null, "Three.", false)),
                        4L, List.of(new Raw(null, "Four.", true))));

        assertEquals(List.of(2L, 3L), history.retiredAfter(1L).stream().map(RetiredLine::nid).toList(),
                "both retired lines stand after line 1, in their order");
        assertTrue(history.retiredAtStart().isEmpty());
    }

    @Test
    void aLineTheManifestNeverListedHasNoVersions() {
        DocumentHistory history = DocumentHistory.from(List.of(List.of(1L)), Map.of(1L, List.of(new Raw(null, "One.", true))));
        assertEquals(List.of(), history.versions(9L));
        assertTrue(history.isEmpty(), "one version per line and nothing retired is no history to show");
        assertTrue(DocumentHistory.NONE.isEmpty());
    }
}
