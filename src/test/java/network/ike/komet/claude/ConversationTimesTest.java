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
package network.ike.komet.claude;

import network.ike.komet.claude.json.Json;
import network.ike.komet.document.surface.JournalStore.TurnRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The times of a conversation's turns, through the file that keeps them and the journal. */
class ConversationTimesTest {

    private static final Instant ASKED = Instant.parse("2026-11-23T19:41:07.125Z");
    private static final Instant ANSWERED = Instant.parse("2026-11-23T19:42:10.500Z");

    /** A conversation's file, as this Komet reads it. */
    record File(String id, List<Map<String, Object>> turns, List<String> times) {
    }

    /** A conversation's file, as a Komet from before the times reads it. */
    record EarlierFile(String id, List<Map<String, Object>> turns) {
    }

    private static Map<String, Object> file(Object times) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", "c1");
        dto.put("turns", List.of(Map.of("role", "user", "content", "two and two?"), Map.of("role", "assistant", "content", "four")));
        if (times != null) {
            dto.put("times", times);
        }
        return dto;
    }

    @Test
    void theFileKeepsEachTurnsTime() {
        String json = Json.stringify(file(ConversationTimes.saved(List.of(ASKED, ANSWERED))));
        File read = Json.parse(json, File.class);
        assertEquals(List.of(ASKED, ANSWERED), ConversationTimes.read(read.times(), read.turns().size()));
    }

    @Test
    void aTurnWithNoTimeStaysWithout() {
        String json = Json.stringify(file(ConversationTimes.saved(Arrays.asList(null, ANSWERED))));
        File read = Json.parse(json, File.class);
        assertEquals(Arrays.asList(null, ANSWERED), ConversationTimes.read(read.times(), 2));
    }

    @Test
    void aFileFromBeforeTheTimesReadsWithNone() {
        File read = Json.parse(Json.stringify(file(null)), File.class);
        assertEquals(Arrays.asList(null, null), ConversationTimes.read(read.times(), 2));
    }

    @Test
    void anEarlierKometReadsAFileWithTimes() {
        EarlierFile read = Json.parse(Json.stringify(file(ConversationTimes.saved(List.of(ASKED, ANSWERED)))), EarlierFile.class);
        assertEquals(2, read.turns().size());
    }

    @Test
    void thereIsOneTimeForEachTurnWhateverTheFileHas() {
        assertEquals(Arrays.asList(ASKED, null, null), ConversationTimes.read(List.of(ASKED.toString()), 3), "turns added by an earlier Komet");
        assertEquals(List.of(ASKED), ConversationTimes.read(List.of(ASKED.toString(), ANSWERED.toString()), 1));
        assertEquals(Arrays.asList(null, ANSWERED), ConversationTimes.read(List.of("yesterday", ANSWERED.toString()), 2), "what is not a time is none");
    }

    @Test
    void theJournalSaysWhenAReplyWasSaidAndNotWhenItsQuestionWasAsked() {
        TurnRecord question = new TurnRecord("two and two?", false, ANSWERED.toEpochMilli() - 1);
        TurnRecord reply = new TurnRecord("four", true, ANSWERED.toEpochMilli());
        assertEquals(ANSWERED, ConversationTimes.of(null, reply));
        assertNull(ConversationTimes.of(null, question), "its STAMP is when the reply landed");
        assertEquals(ASKED, ConversationTimes.of(ASKED, question), "the conversation's own time");
        assertEquals(ASKED, ConversationTimes.of(ASKED, reply), "the conversation's own time stands before the journal's");
        assertNull(ConversationTimes.of(null, new TurnRecord("four", true)), "a turn whose time is not known");
        assertNull(ConversationTimes.of(null, new TurnRecord("four", true, Long.MAX_VALUE)), "a turn not committed");
    }
}
