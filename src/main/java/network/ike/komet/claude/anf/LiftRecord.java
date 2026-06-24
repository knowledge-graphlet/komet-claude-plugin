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
package network.ike.komet.claude.anf;

import java.util.List;
import java.util.Objects;

/**
 * One persisted lift in the history: the input narrative, the per-statement ANF-in-AsciiDoc
 * blocks (the durable canonical form — see {@link AnfAdoc}), a caller-supplied wall-clock
 * timestamp, and a short title for the history list.
 *
 * <p>The statements are held as their adoc blocks, not as live {@link AnfStatement} objects,
 * because the durable identity is the written {@code @id} and a statement's {@code nid}s are
 * machine-local; a block is re-parsed to a statement (re-resolving concepts) only when the lift
 * is reopened. The narrative is kept verbatim alongside — it is the un-round-trippable source
 * and the history-list subtitle.
 *
 * @param narrative   the input narrative text exactly as lifted; never null
 * @param anfBlocks   one ANF-in-adoc block per statement, in statement order; never null
 * @param epochMillis caller-supplied wall-clock millis at lift time ({@code System.currentTimeMillis()};
 *                    the lift engine has no clock, so the host supplies it)
 * @param title       a short label for the history list; never null
 */
public record LiftRecord(String narrative, List<String> anfBlocks, long epochMillis, String title) {

    /** Maximum characters of a derived title before it is elided. */
    private static final int TITLE_MAX = 60;

    /**
     * Validates and defensively copies the record.
     *
     * @throws NullPointerException if {@code narrative} or {@code title} is null
     */
    public LiftRecord {
        Objects.requireNonNull(narrative, "narrative");
        Objects.requireNonNull(title, "title");
        anfBlocks = (anfBlocks == null) ? List.of() : List.copyOf(anfBlocks);
    }

    /**
     * Derives a short, single-line history-list title from a narrative (its first non-blank line,
     * elided to {@value #TITLE_MAX} characters).
     *
     * @param narrative the narrative
     * @return a short title (never null)
     */
    public static String titleFrom(String narrative) {
        String firstLine = narrative == null ? "" : narrative.strip().lines()
                .filter(line -> !line.isBlank()).findFirst().orElse("").strip();
        return firstLine.length() <= TITLE_MAX ? firstLine : firstLine.substring(0, TITLE_MAX - 1) + "…";
    }
}
