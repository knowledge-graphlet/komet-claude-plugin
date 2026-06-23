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

import dev.ikm.komet.preferences.KometPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Persists the ANF lift history as one AsciiDoc file per lift in the area's preferences-node
 * backing directory. Each {@code lift-NNNN.adoc} is a valid, human-readable document: a small
 * header carrying the narrative (Base64-encoded so multi-line text survives the single-line
 * attribute syntax), the timestamp, and the title, followed by the lift's {@link AnfAdoc} blocks.
 *
 * <p>These are <em>preference-node</em> writes (the area's own state), not knowledge-graph writes
 * — they persist the surface, never the store. One file per lift isolates a corrupt or oversized
 * lift from the rest of the history (a bad file is skipped, not fatal), and sidesteps the
 * preference value-size cap that a single concatenated blob would hit. The file-format helpers
 * ({@link #toFile}/{@link #fromFile}/{@link #splitBlocks}) are pure and hermetically testable; the
 * {@link #append}/{@link #restore} methods do the {@link KometPreferences} I/O.
 */
public final class AnfHistoryStore {

    private static final Logger LOG = LoggerFactory.getLogger(AnfHistoryStore.class);

    private static final String FILE_PREFIX = "lift-";
    private static final String FILE_SUFFIX = ".adoc";
    private static final String ATTR_EPOCH = ":lift-epoch-millis:";
    private static final String ATTR_TITLE = ":lift-title:";
    private static final String ATTR_NARRATIVE = ":lift-narrative:";
    /** Splits a multi-block document just before each {@code [anf,type=…]} block start. */
    private static final Pattern BLOCK_SPLIT = Pattern.compile("(?m)(?=^\\[anf,type=)");

    private AnfHistoryStore() {
    }

    /**
     * Appends one lift as {@code lift-NNNN.adoc} in the area's backing directory and flushes.
     *
     * @param preferences the area's preferences node
     * @param record      the lift to persist
     * @param index       the lift's zero-based position in the history (the file is zero-padded by it)
     * @return true if persisted; false if the node has no backing directory or the write failed
     *         (logged, never thrown)
     */
    public static boolean append(KometPreferences preferences, LiftRecord record, int index) {
        Optional<Path> dir = directory(preferences);
        if (dir.isEmpty()) {
            LOG.warn("ANF history: preferences node has no backing directory; lift not persisted");
            return false;
        }
        Path file = dir.get().resolve(String.format("%s%04d%s", FILE_PREFIX, index, FILE_SUFFIX));
        try {
            Files.writeString(file, toFile(record), StandardCharsets.UTF_8);
            preferences.flush();
            return true;
        } catch (IOException | BackingStoreException | RuntimeException e) {
            LOG.warn("ANF history: failed to persist lift {} ({})", index, e.toString());
            return false;
        }
    }

    /**
     * Reads every persisted lift, in index order. A file that cannot be read or parsed is skipped
     * with a warning rather than failing the whole restore.
     *
     * @param preferences the area's preferences node
     * @return the persisted lifts in order (empty when none, or no backing directory)
     */
    public static List<LiftRecord> restore(KometPreferences preferences) {
        Optional<Path> dir = directory(preferences);
        if (dir.isEmpty()) {
            return List.of();
        }
        List<LiftRecord> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir.get())) {
            List<Path> sorted = files
                    .filter(AnfHistoryStore::isLiftFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            for (Path file : sorted) {
                try {
                    records.add(fromFile(Files.readString(file, StandardCharsets.UTF_8)));
                } catch (RuntimeException bad) {
                    LOG.warn("ANF history: skipping unreadable lift file {} ({})",
                            file.getFileName(), bad.toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("ANF history: could not list history directory ({})", e.toString());
        }
        return records;
    }

    private static boolean isLiftFile(Path p) {
        String name = p.getFileName().toString();
        return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
    }

    private static Optional<Path> directory(KometPreferences preferences) {
        try {
            return preferences.directory();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    // ── Pure file format (hermetically testable) ─────────────────────────────

    /**
     * Renders a lift to its persisted file text: a header (epoch, title, Base64 narrative) and the
     * lift's adoc blocks.
     *
     * @param record the lift
     * @return the file content
     */
    static String toFile(LiftRecord record) {
        StringBuilder sb = new StringBuilder();
        sb.append(ATTR_EPOCH).append(' ').append(record.epochMillis()).append('\n');
        sb.append(ATTR_TITLE).append(' ').append(record.title().replace('\n', ' ')).append('\n');
        sb.append(ATTR_NARRATIVE).append(' ')
                .append(Base64.getEncoder().encodeToString(record.narrative().getBytes(StandardCharsets.UTF_8)))
                .append("\n\n");
        for (String block : record.anfBlocks()) {
            sb.append(block);
            if (!block.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Parses a persisted lift file back to a {@link LiftRecord}.
     *
     * @param content the file content
     * @return the lift record
     */
    static LiftRecord fromFile(String content) {
        long epoch = 0L;
        String title = "";
        String narrative = "";
        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        for (String line : content.split("\n", -1)) {
            if (inBody) {
                body.append(line).append('\n');
            } else if (line.startsWith(ATTR_EPOCH)) {
                epoch = parseLong(line.substring(ATTR_EPOCH.length()).trim());
            } else if (line.startsWith(ATTR_TITLE)) {
                title = line.substring(ATTR_TITLE.length()).trim();
            } else if (line.startsWith(ATTR_NARRATIVE)) {
                narrative = decode(line.substring(ATTR_NARRATIVE.length()).trim());
            } else if (line.isBlank()) {
                inBody = true;
            }
        }
        if (title.isBlank()) {
            title = LiftRecord.titleFrom(narrative);
        }
        return new LiftRecord(narrative, splitBlocks(body.toString()), epoch, title);
    }

    /**
     * Splits a multi-block document body into individual {@link AnfAdoc} blocks.
     *
     * @param body the document body (everything after the header)
     * @return one string per {@code [anf]} block
     */
    static List<String> splitBlocks(String body) {
        List<String> blocks = new ArrayList<>();
        for (String chunk : BLOCK_SPLIT.split(body)) {
            String trimmed = chunk.strip();
            if (trimmed.startsWith("[anf")) {
                blocks.add(trimmed + "\n");
            }
        }
        return blocks;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String decode(String base64) {
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
