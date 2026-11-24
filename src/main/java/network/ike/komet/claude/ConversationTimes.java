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

import network.ike.komet.document.ConversationFile;
import network.ike.komet.document.surface.JournalStore;
import network.ike.komet.document.surface.MarkdownRichText;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * When the turns of a conversation were said, as its file records them: one time for each turn, in the
 * order of the turns, as ISO-8601, blank where it was not recorded. A file written before the times
 * were kept has none, and an older Komet that saves the conversation drops them.
 */
final class ConversationTimes {

    private ConversationTimes() {
    }

    /**
     * The times as the file records them.
     *
     * @param times one for each turn; {@code null} where not recorded
     * @return the text of each, to the millisecond
     */
    static List<String> saved(List<Instant> times) {
        return times.stream().map(at -> at == null ? "" : at.truncatedTo(ChronoUnit.MILLIS).toString()).toList();
    }

    /**
     * The times a file records.
     *
     * @param saved the text of each, as {@link #saved} writes it; {@code null} when the file has none
     * @param turns how many turns the conversation has
     * @return one for each turn; {@code null} where the file records none, or what it records is not a time
     */
    static List<Instant> read(List<String> saved, int turns) {
        List<Instant> times = new ArrayList<>(turns);
        for (int i = 0; i < turns; i++) {
            String text = saved == null || i >= saved.size() ? null : saved.get(i);
            times.add(text == null || text.isBlank() ? null : parsed(text));
        }
        return times;
    }

    private static Instant parsed(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeException notATime) {
            return null;
        }
    }

    /**
     * When a turn read from the conversation's journal was said. The journal writes both turns of an
     * exchange when the reply lands, so the STAMP of a reply says when it was said; the STAMP of a
     * question does not say when it was asked, and its time is known only when the conversation kept it.
     *
     * @param kept the time the conversation's file keeps for the turn, or {@code null}
     * @param turn the turn
     * @return the time, or {@code null} when it is not known
     */
    static Instant of(Instant kept, JournalStore.TurnRecord turn) {
        if (kept != null) {
            return kept;
        }
        boolean known = turn.time() > 0 && turn.time() < Long.MAX_VALUE;
        return turn.assistantAuthored() && known ? Instant.ofEpochMilli(turn.time()) : null;
    }

    /**
     * A conversation's turns as a file saves them, from its journal. Each has the time the conversation
     * keeps of it, which is what its heading shows; a turn it keeps no time of has the time of its STAMP
     * in the journal, which is when the exchange it is part of was recorded.
     *
     * @param journal the journal's turns
     * @param shown   the turns the card shows, in the same order
     * @return a turn for each of the journal's
     */
    static List<ConversationFile.Turn> turns(List<JournalStore.TurnRecord> journal, List<MarkdownRichText.Entry> shown) {
        List<ConversationFile.Turn> turns = new ArrayList<>(journal.size());
        for (int i = 0; i < journal.size(); i++) {
            JournalStore.TurnRecord turn = journal.get(i);
            MarkdownRichText.Entry entry = i < shown.size() ? shown.get(i) : null;
            boolean same = entry != null && (entry.role() == MarkdownRichText.Role.ASSISTANT) == turn.assistantAuthored();
            turns.add(new ConversationFile.Turn(turn.markdown(), turn.assistantAuthored(),
                    same && entry.saidAt() != null ? entry.saidAt().toEpochMilli() : turn.time()));
        }
        return turns;
    }

    /**
     * A conversation's turns as a file saves them, from what a card shows.
     *
     * @param shown the turns the card shows
     * @return a turn for each, with the time the conversation keeps of it, or 0
     */
    static List<ConversationFile.Turn> turns(List<MarkdownRichText.Entry> shown) {
        return shown.stream().map(entry -> new ConversationFile.Turn(entry.content(), entry.role() == MarkdownRichText.Role.ASSISTANT,
                entry.saidAt() == null ? 0 : entry.saidAt().toEpochMilli())).toList();
    }
}
