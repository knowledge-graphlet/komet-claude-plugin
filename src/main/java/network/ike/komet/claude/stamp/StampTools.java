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

import design.knowledge.stamp.adoc.git.AffirmationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Affirmation;
import design.knowledge.stamp.adoc.model.StampRecord;
import network.ike.komet.claude.anthropic.AnthropicTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The assistant's provenance tools ({@code IKE-Network/ike-issues#468}), registered beside the
 * graph tools. {@code stamp_lines} answers who introduced and who affirmed the lines of a
 * committed file; {@code propose_affirmation} prepares a concurrence — the hashed
 * {@code Affirms:} trailers — and hands it to the STAMP Provenance card for the person to
 * confirm with a signed commit. The assistant never writes the commit itself: an affirmation
 * is a person's non-repudiable "I concur", signed with their key, and a tool signing it on
 * their behalf would record an act they did not perform.
 *
 * <p>Both tools default to the file open in the most recent STAMP Provenance card and accept
 * an explicit {@code file}, absolute or relative to that card's repository root.
 */
public final class StampTools {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final int DEFAULT_LINE_LIMIT = 60;

    private StampTools() { }

    /** Where the tools find the open card, if any; the supplier may return {@code null}. */
    public interface CardLocator extends Supplier<StampProvenanceCard> { }

    /**
     * @param card locates the STAMP Provenance card the tools default to and hand proposals to
     * @return the two provenance tools
     */
    public static List<AnthropicTool> tools(CardLocator card) {
        return List.of(stampLines(card), proposeAffirmation(card));
    }

    // ── stamp_lines ───────────────────────────────────────────────────────────

    static AnthropicTool stampLines(CardLocator card) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "stamp_lines";
            }

            @Override
            public String description() {
                return "Read the STAMP provenance of a committed file in a git repository: for each line, the commit "
                        + "(STAMP) that introduced it — short SHA, date, author — and who has affirmed it, plus the "
                        + "file's STAMP list, the lines still in the review queue (introduced but affirmed by no one), "
                        + "and any affirmation that no longer matches its lines. Call this before answering who "
                        + "wrote, changed, reviewed or concurred with a passage, or what remains to review. Defaults "
                        + "to the file open in the STAMP Provenance card; pass file to read another. Pass from/to "
                        + "to see a range; without them the first " + DEFAULT_LINE_LIMIT + " lines are listed.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj(
                                "file", strProp("Path of the committed file, absolute or relative to the open card's repository root. Omit for the card's file."),
                                "from", intProp("First line to list, 1-based."),
                                "to", intProp("Last line to list, inclusive.")),
                        "required", List.of());
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    Target target = Target.resolve(card.get(), str(input, "file"));
                    Report report = Report.read(target);
                    Integer from = integer(input, "from");
                    Integer to = integer(input, "to");
                    return report.render(from, to);
                } catch (IllegalArgumentException | IOException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── propose_affirmation ───────────────────────────────────────────────────

    static AnthropicTool proposeAffirmation(CardLocator card) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_affirmation";
            }

            @Override
            public String description() {
                return "Prepare a concurrence with specific lines of a committed file: compute the Affirms: trailers "
                        + "(path, line range, anchoring revision, content hash) and hand them to the STAMP Provenance "
                        + "card, which asks the person to confirm with a signed commit. Nothing is recorded until they "
                        + "confirm; you cannot and must not affirm on their behalf, because an affirmation is their "
                        + "signed statement. Use it only when the person asks to affirm, concur with, or sign off "
                        + "lines they have reviewed, after showing them what the lines say. Ranges are 1-based and "
                        + "inclusive; lines must be committed (the card shows HEAD).";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj(
                                "file", strProp("Path of the committed file, absolute or relative to the open card's repository root. Omit for the card's file."),
                                "ranges", obj("type", "array",
                                        "description", "The line ranges the person reviewed and concurs with.",
                                        "items", obj("type", "object",
                                                "properties", obj("from", intProp("First line, 1-based."), "to", intProp("Last line, inclusive.")),
                                                "required", List.of("from", "to"))),
                                "note", strProp("Optional one-line reason the person gave, shown in the confirmation.")),
                        "required", List.of("ranges"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    StampProvenanceCard open = card.get();
                    Target target = Target.resolve(open, str(input, "file"));
                    List<int[]> ranges = ranges(input);
                    List<Affirmation> affirmations = new ArrayList<>();
                    try (GitRepo repo = GitRepo.open(target.root())) {
                        String head = repo.headRevision();
                        List<String> content = repo.headLines(target.relative());
                        for (int[] r : ranges) {
                            affirmations.add(Affirmation.of(target.relative(), r[0], r[1], head, content));
                        }
                    }
                    String trailers = String.join("\n", affirmations.stream().map(Affirmation::trailer).toList());
                    String note = str(input, "note");
                    boolean handedOff = open != null && open.showsFile(target.root(), target.relative());
                    if (handedOff) {
                        open.proposeAffirmation(ranges, note);
                    }
                    return "Prepared " + affirmations.size() + " trailer(s) for " + AffirmationCommit.subject(affirmations) + ":\n" + trailers
                            + (handedOff
                                ? "\n\nThe STAMP Provenance card has ticked these lines and is asking the person to confirm with a "
                                  + "signed commit. Nothing is recorded until they do; tell them the confirmation is waiting in the card."
                                : "\n\nNo STAMP Provenance card shows this file, so nothing was handed off. Ask the person to open "
                                  + target.relative() + " in a STAMP Provenance card, tick lines "
                                  + ranges.stream().map(r -> r[0] + "-" + r[1]).reduce((a, b) -> a + ", " + b).orElse("")
                                  + ", and choose Affirm Selected.");
                } catch (IllegalArgumentException | IOException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── target resolution ─────────────────────────────────────────────────────

    /** The repository root and the file within it that a tool call addresses. */
    record Target(Path root, Path relative) {

        static Target resolve(StampProvenanceCard card, String file) {
            if (file == null || file.isBlank()) {
                if (card == null || card.currentFile().isEmpty()) {
                    throw new IllegalArgumentException("no file given and no STAMP Provenance card has a file open; pass file");
                }
                return new Target(card.repoRoot(), card.currentFile().get());
            }
            Path given = Path.of(file);
            if (!given.isAbsolute()) {
                if (card == null || card.repoRoot() == null) {
                    throw new IllegalArgumentException("relative path " + file + " needs an open STAMP Provenance card to resolve against; pass an absolute path");
                }
                given = card.repoRoot().resolve(given);
            }
            given = given.toAbsolutePath().normalize();
            if (!Files.isRegularFile(given)) {
                throw new IllegalArgumentException(given + " is not a file");
            }
            Optional<Path> root = GitRepo.findRoot(given);
            if (root.isEmpty()) {
                throw new IllegalArgumentException(given + " is not inside a git working tree");
            }
            return new Target(root.get(), root.get().relativize(given));
        }
    }

    // ── the report ────────────────────────────────────────────────────────────

    /** One file's provenance, read once and rendered compactly. */
    record Report(Target target, GitStampBridge bridge, String head, String branch, List<String> lines,
                  StampRecord[] introducer, Map<Integer, List<StampRecord>> affirmers) {

        static Report read(Target target) throws IOException {
            GitStampBridge bridge = GitStampBridge.read(target.root(), target.relative());
            try (GitRepo repo = GitRepo.open(target.root())) {
                List<String> lines = repo.headLines(target.relative());
                StampRecord[] introducer = new StampRecord[lines.size() + 1];
                Map<Integer, List<StampRecord>> affirmers = new LinkedHashMap<>();
                for (StampRecord r : bridge.stamps().stamps().values()) {
                    r.introduced().forEach((int line) -> { if (line < introducer.length) introducer[line] = r; });
                    r.affirmed().forEach((int line) -> affirmers.computeIfAbsent(line, k -> new ArrayList<>()).add(r));
                }
                return new Report(target, bridge, repo.headRevision(), repo.branch(), lines, introducer, affirmers);
            }
        }

        String render(Integer from, Integer to) {
            StringBuilder out = new StringBuilder();
            List<StampRecord> byTime = new ArrayList<>(bridge.stamps().stamps().values());
            byTime.sort(Comparator.comparing(StampRecord::time).reversed());
            List<int[]> queue = queue();
            int queued = queue.stream().mapToInt(r -> r[1] - r[0] + 1).sum();
            out.append(target.relative()).append(" @ ").append(head, 0, Math.min(7, head.length())).append(" on ").append(branch)
                    .append(" — ").append(lines.size()).append(" lines, ").append(byTime.size()).append(" STAMPs, ")
                    .append(affirmers.size()).append(" lines affirmed, ").append(queued).append(" in the review queue\n");
            out.append("STAMPs (newest first):\n");
            for (StampRecord r : byTime) {
                GitStampTerms.GitStamp terms = bridge.terms().get(r.id());
                String author = terms == null ? r.author().name() : terms.author().description();
                out.append("  ").append(r.commitSha(), 0, 7).append(' ').append(DAY.format(r.time())).append(' ').append(author)
                        .append(r.introduced().isEmpty() ? "" : "  introduced " + r.introduced().getCardinality() + " line(s) " + ranges(r.introduced()))
                        .append(r.affirmed().isEmpty() ? "" : "  affirmed " + ranges(r.affirmed()) + (r.signed() ? " (signed)" : " (unsigned)"))
                        .append("  ").append(r.summary()).append('\n');
            }
            if (!queue.isEmpty()) {
                out.append("Review queue (introduced, affirmed by no one): ")
                        .append(queue.stream().map(r -> r[0] == r[1] ? Integer.toString(r[0]) : r[0] + "-" + r[1]).reduce((a, b) -> a + ", " + b).orElse(""))
                        .append('\n');
            }
            for (String problem : bridge.stamps().problems()) {
                out.append("Not applied: ").append(problem).append('\n');
            }
            int first = from == null ? 1 : Math.max(1, from);
            int last = to == null ? (from == null ? Math.min(lines.size(), DEFAULT_LINE_LIMIT) : lines.size()) : Math.min(lines.size(), to);
            if (first > lines.size()) {
                out.append("Line ").append(first).append(" is beyond the file's ").append(lines.size()).append(" lines.\n");
                return out.toString();
            }
            out.append("Lines ").append(first).append('-').append(last).append(" (line  stamp  ✓=affirmed  text):\n");
            for (int line = first; line <= last; line++) {
                StampRecord r = introducer[line];
                List<StampRecord> who = affirmers.get(line);
                out.append(String.format(Locale.ROOT, "%5d  %s %s  %s%n", line, r == null ? "       " : r.commitSha().substring(0, 7),
                        who == null ? " " : "✓", lines.get(line - 1)));
            }
            if (to == null && from == null && last < lines.size()) {
                out.append("… ").append(lines.size() - last).append(" more line(s); pass from/to to see them.\n");
            }
            return out.toString();
        }

        /** Contiguous ranges of lines that have a STAMP but no affirmation. */
        List<int[]> queue() {
            List<int[]> ranges = new ArrayList<>();
            int[] current = null;
            for (int line = 1; line <= lines.size(); line++) {
                boolean queued = introducer[line] != null && !affirmers.containsKey(line);
                if (queued && current != null && current[1] == line - 1) {
                    current[1] = line;
                } else if (queued) {
                    current = new int[] {line, line};
                    ranges.add(current);
                }
            }
            return ranges;
        }

        private static String ranges(org.roaringbitmap.RoaringBitmap lines) {
            List<String> parts = new ArrayList<>();
            int start = -1;
            int previous = -1;
            for (int line : lines) {
                if (start < 0) {
                    start = previous = line;
                } else if (line == previous + 1) {
                    previous = line;
                } else {
                    parts.add(start == previous ? Integer.toString(start) : start + "-" + previous);
                    start = previous = line;
                }
            }
            if (start >= 0) {
                parts.add(start == previous ? Integer.toString(start) : start + "-" + previous);
            }
            return String.join(",", parts);
        }
    }

    // ── input helpers ─────────────────────────────────────────────────────────

    static List<int[]> ranges(Map<String, Object> input) {
        Object raw = input.get("ranges");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("ranges must be a non-empty array of {from, to}");
        }
        List<int[]> ranges = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException("each range must be an object with from and to");
            }
            Integer from = integer(castMap(m), "from");
            Integer to = integer(castMap(m), "to");
            if (from == null || to == null || from < 1 || to < from) {
                throw new IllegalArgumentException("range must have 1 <= from <= to, got " + m);
            }
            ranges.add(new int[] {from, to});
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        return ranges;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    private static Integer integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, got " + v);
        }
    }

    private static Map<String, Object> obj(Object... keyValues) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> strProp(String description) {
        return obj("type", "string", "description", description);
    }

    private static Map<String, Object> intProp(String description) {
        return obj("type", "integer", "description", description);
    }
}
