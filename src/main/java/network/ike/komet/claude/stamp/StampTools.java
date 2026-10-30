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

import design.knowledge.stamp.adoc.git.AnnotationCommit;
import design.knowledge.stamp.adoc.git.GitRepo;
import design.knowledge.stamp.adoc.model.Annotation;
import design.knowledge.stamp.adoc.model.AnnotationKind;
import design.knowledge.stamp.adoc.model.AnnotationTarget;
import design.knowledge.stamp.adoc.model.LineRange;
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
 * graph tools. {@code stamp_lines} answers who introduced the lines of a committed file and
 * who has affirmed, refuted or commented on them; {@code propose_annotation} prepares an
 * annotation — the hashed {@code Annotation:} trailers and the text — or a retraction of an
 * earlier annotation commit, and hands it to the Stamped Document card for the person to confirm
 * with a signed commit (IKE-Network/ike-issues#1300). The assistant never writes the commit itself: an affirmation
 * or refutation is a person's non-repudiable statement, signed with their key, and a tool
 * signing it on their behalf would record an act they did not perform.
 *
 * <p>Both tools default to the file shown in the most recent Stamped Document card and accept
 * an explicit {@code file}, absolute or relative to that card's repository root. The card's
 * selection is their default subject (ike-lab-documents topic
 * {@code dev-stamped-document-surface}): {@code stamp_lines} without a range reads the selected
 * lines, and {@code propose_annotation} without ranges proposes for them.
 */
public final class StampTools {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final int DEFAULT_LINE_LIMIT = 60;

    private StampTools() { }

    /** Where the tools find the open card, if any; the supplier may return {@code null}. */
    public interface CardLocator extends Supplier<StampedDocumentCard> { }

    /**
     * @param card locates the Stamped Document card the tools default to and hand proposals to
     * @return the two provenance tools
     */
    public static List<AnthropicTool> tools(CardLocator card) {
        return List.of(stampLines(card), proposeAnnotation(card));
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
                return "Read the STAMP provenance of a STAMPed document's lines: for each line, the commit (STAMP) "
                        + "that introduced it — short SHA, date, author — and whether anyone has affirmed, refuted or "
                        + "commented on it, plus the STAMP list and the lines still in the review queue (introduced but "
                        + "affirmed by no one). Call this before answering who wrote, changed, reviewed, disputed or "
                        + "concurred with a passage, or what remains to review. Defaults to the document shown in the "
                        + "Stamped Document card. When the card has a working tree the provenance is read from git, with "
                        + "stale annotations named; when it has none it is read from the store's per-line STAMPs, which "
                        + "is just as current for what was materialized. Pass file to read a file of a working tree "
                        + "instead. Pass from/to to see a range; without them the lines selected in the card are listed, "
                        + "or the first " + DEFAULT_LINE_LIMIT + " when nothing is selected.";
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
                    StampedDocumentCard open = card.get();
                    if (str(input, "file") == null && open != null && !hasWorkingTree(open) && open.shown().isPresent()) {
                        return fromStore(open, integer(input, "from"), integer(input, "to"));
                    }
                    Target target = Target.resolve(open, str(input, "file"));
                    Report report = Report.read(target);
                    Integer from = integer(input, "from");
                    Integer to = integer(input, "to");
                    if (from == null && to == null && str(input, "file") == null && open != null) {
                        List<int[]> selected = open.selectedLineNumbers();        // the selection is the default subject
                        if (!selected.isEmpty()) {
                            from = selected.getFirst()[0];
                            to = selected.getLast()[1];
                        }
                    }
                    return report.render(from, to);
                } catch (IllegalArgumentException | IOException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    // ── propose_annotation ────────────────────────────────────────────────────

    static AnthropicTool proposeAnnotation(CardLocator card) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "propose_annotation";
            }

            @Override
            public String description() {
                return "Prepare an annotation of specific lines of a committed file — an affirmation (the person has "
                        + "reviewed the lines and stands behind them), a refutation (they dispute them, with the reason "
                        + "as text), or a comment (no stance) — compute the Annotation: trailers (kind, path, line "
                        + "range, anchoring revision, content hash) and hand them with the text to the Stamped Document "
                        + "card, which asks the person to confirm with a signed commit. Or prepare a retraction: kind "
                        + "retract with commit, the SHA of an earlier annotation commit as stamp_lines lists it, withdraws "
                        + "every annotation that commit recorded, so its lines read as before it (a judgment it had replaced "
                        + "does not come back; a later annotation stands). Nothing is recorded until they "
                        + "confirm; you cannot and must not annotate on their behalf, because an annotation is their "
                        + "signed statement. Use it only when the person asks to affirm, concur with, sign off, "
                        + "dispute, refute or comment on lines they have reviewed, or to take back, withdraw or retract an "
                        + "annotation of theirs, after showing them what the lines "
                        + "say. Ranges are 1-based and inclusive; omit them to propose for the lines selected in the "
                        + "card. An annotation is a signed git commit in the file's repository, so the card needs a "
                        + "working tree for the document (its Repository panel shows one); without one, say so and point "
                        + "the person at Open file… or Clone… in the card.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return obj("type", "object",
                        "properties", obj(
                                "file", strProp("Path of the committed file, absolute or relative to the open card's repository root. Omit for the card's file."),
                                "kind", obj("type", "string", "enum", List.of("affirm", "refute", "comment", "retract"),
                                        "description", "affirm: the person stands behind the lines; refute: they dispute them; comment: no stance; "
                                                + "retract: withdraw an earlier annotation commit named by commit."),
                                "commit", strProp("For retract: the SHA (7 or more hex digits) of the annotation commit to withdraw, as stamp_lines lists it."),
                                "ranges", obj("type", "array",
                                        "description", "The line ranges the person reviewed. Omit to use the card's selection.",
                                        "items", obj("type", "object",
                                                "properties", obj("from", intProp("First line, 1-based."), "to", intProp("Last line, inclusive.")),
                                                "required", List.of("from", "to"))),
                                "text", strProp("The annotation text in the person's words, which becomes the commit body; required to refute, and to retract (why). They can edit it in the confirmation.")),
                        "required", List.of("kind"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    AnnotationKind kind = kind(str(input, "kind"));
                    String text = str(input, "text");
                    if (kind == AnnotationKind.REFUTATION && (text == null || text.isBlank())) {
                        throw new IllegalArgumentException("a refutation needs text: the person's reason for disputing the lines");
                    }
                    if (kind == AnnotationKind.RETRACTION && (text == null || text.isBlank())) {
                        throw new IllegalArgumentException("a retraction needs text: the person's reason for withdrawing the annotation");
                    }
                    StampedDocumentCard open = card.get();
                    if (str(input, "file") == null && open != null && !hasWorkingTree(open) && open.shown().isPresent()) {
                        throw new IllegalArgumentException(noWorkingTree(open)
                                + " An annotation is a signed git commit in the document's repository, so it needs that working tree: "
                                + "ask the person to choose Open file… (a clone they already have) or Clone… in the card's Repository "
                                + "panel, then propose again.");
                    }
                    Target target = Target.resolve(open, str(input, "file"));
                    if (kind == AnnotationKind.RETRACTION) {
                        return proposeRetraction(open, target, str(input, "commit"), text);
                    }
                    List<int[]> ranges = input.get("ranges") == null && open != null && str(input, "file") == null
                            ? open.selectedLineNumbers() : ranges(input);
                    if (ranges.isEmpty()) {
                        throw new IllegalArgumentException("no ranges given and no lines are selected in the Stamped Document card");
                    }
                    List<Annotation> annotations = new ArrayList<>();
                    try (GitRepo repo = GitRepo.open(target.root())) {
                        String head = repo.headRevision();
                        List<String> content = repo.headLines(target.relative());
                        for (int[] r : ranges) {
                            annotations.add(Annotation.of(kind, LineRange.of(target.relative(), r[0], r[1], head, content)));
                        }
                    }
                    String trailers = String.join("\n", annotations.stream().map(Annotation::trailer).toList());
                    boolean handedOff = open != null && open.showsFile(target.root(), target.relative());
                    if (handedOff) {
                        open.proposeAnnotation(kind, ranges, text);
                    }
                    return "Prepared " + annotations.size() + " trailer(s) for " + AnnotationCommit.subject(annotations) + ":\n" + trailers
                            + (handedOff
                                ? "\n\nThe Stamped Document card has selected these lines and is asking the person to confirm the "
                                  + StampedDocumentCard.displayName(kind).toLowerCase() + " with a signed commit. Nothing is recorded until they do; tell "
                                  + "them the confirmation is waiting in the card."
                                : "\n\nNo Stamped Document card shows this file, so nothing was handed off. Ask the person to open "
                                  + target.relative() + " in a Stamped Document card, select lines "
                                  + ranges.stream().map(r -> r[0] + "-" + r[1]).reduce((a, b) -> a + ", " + b).orElse("")
                                  + ", and choose " + (kind == AnnotationKind.COMMENT ? "Comment" : kind.verb()) + " from their menu.");
                } catch (IllegalArgumentException | IOException e) {
                    return "Error: " + e.getMessage();
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }

    /**
     * A retraction: the commit named must be an annotation commit still in force on the file, as the
     * walker reads it; the trailer names it in full, and the card asks the person to confirm.
     */
    static String proposeRetraction(StampedDocumentCard open, Target target, String commit, String text) throws IOException {
        if (commit == null || commit.strip().length() < 7 || !commit.strip().matches("[0-9a-fA-F]{7,40}")) {
            throw new IllegalArgumentException("a retraction names the annotation commit to withdraw: pass commit, its SHA of 7 or more hex digits, as stamp_lines lists it");
        }
        String wanted = commit.strip().toLowerCase(Locale.ROOT);
        Report report = Report.read(target);
        List<StampRecord> matches = report.bridge().stamps().stamps().values().stream()
                .filter(r -> r.commitSha().startsWith(wanted))
                .filter(r -> !r.affirmed().isEmpty() || !r.refuted().isEmpty() || !r.commented().isEmpty())
                .toList();
        if (matches.size() != 1) {
            throw new IllegalArgumentException(matches.isEmpty()
                    ? "no annotation in force on " + target.relative() + " was recorded by commit " + wanted + "; stamp_lines lists the STAMPs that affirmed, refuted or commented"
                    : wanted + " names " + matches.size() + " commits; give more digits");
        }
        StampRecord retracted = matches.getFirst();
        Annotation annotation = Annotation.of(AnnotationKind.RETRACTION, new AnnotationTarget.Commit(retracted.commitSha()));
        boolean handedOff = open != null && open.showsFile(target.root(), target.relative());
        if (handedOff) {
            open.proposeRetraction(retracted.commitSha(), text);
        }
        return "Prepared the retraction of " + retracted.commitSha().substring(0, 7) + " (" + retracted.summary() + ", by " + retracted.author().name()
                + "):\n" + annotation.trailer()
                + (handedOff
                    ? "\n\nThe Stamped Document card is asking the person to confirm the retraction with a signed commit. Nothing is "
                      + "recorded until they do; tell them the confirmation is waiting in the card."
                    : "\n\nNo Stamped Document card shows this file, so nothing was handed off. Ask the person to open "
                      + target.relative() + " in a Stamped Document card, select a line the annotation covers, and choose Retract… from its menu.");
    }

    /** The tool's kind word as an annotation kind. */
    static AnnotationKind kind(String word) {
        return switch (word == null ? "" : word.strip().toLowerCase(Locale.ROOT)) {
            case "affirm", "affirmation" -> AnnotationKind.AFFIRMATION;
            case "refute", "refutation" -> AnnotationKind.REFUTATION;
            case "comment" -> AnnotationKind.COMMENT;
            case "retract", "retraction" -> AnnotationKind.RETRACTION;
            default -> throw new IllegalArgumentException("kind must be affirm, refute, comment or retract, not " + word);
        };
    }

    /** @return whether the card knows a working tree that holds the shown document's file */
    static boolean hasWorkingTree(StampedDocumentCard card) {
        return card.currentFile().map(f -> card.repoRoot() != null && Files.isRegularFile(card.repoRoot().resolve(f))).orElse(false);
    }

    /** What to say when the card shows a document but knows no working tree for it. */
    static String noWorkingTree(StampedDocumentCard card) {
        return "The Stamped Document card shows " + card.currentDocument().orElse("a document")
                + " from the store but has no working tree for it (its Repository panel says no working tree holds the "
                + "document's file); the document itself is fully in the store.";
    }

    /** stamp_lines without a working tree: the store's per-line STAMPs for the card's document. */
    static String fromStore(StampedDocumentCard card, Integer from, Integer to) {
        GitDocumentMaterializer.StoredDocument document = GitDocumentMaterializer.load(card.shown().get().anchor(), true)
                .orElse(card.shown().get());
        DocumentOutline outline = new DocumentOutline(document);
        if (from == null && to == null) {
            List<int[]> selected = card.selectedLineNumbers();
            if (!selected.isEmpty()) {
                from = selected.getFirst()[0];
                to = selected.getLast()[1];
            }
        }
        int first = from == null ? 1 : from;
        int last = to == null ? (from == null ? Math.min(document.lines().size(), DEFAULT_LINE_LIMIT) : document.lines().size()) : to;
        return card.currentDocument().orElse("The document") + " — provenance read from the store's per-line STAMPs (the card "
                + "has no working tree for this document, so commit records and stale annotations from git are not shown; "
                + "annotating needs a working tree: Open file… or Clone… in the card).\n"
                + outline.provenance(first, last, DocumentGraphTools.describe(card.view(), card.summaries()));
    }

    // ── target resolution ─────────────────────────────────────────────────────

    /** The repository root and the file within it that a tool call addresses. */
    record Target(Path root, Path relative) {

        static Target resolve(StampedDocumentCard card, String file) {
            if (file == null || file.isBlank()) {
                if (card == null || card.shown().isEmpty()) {
                    throw new IllegalArgumentException("no Stamped Document card shows a document and no file was given; read a stored "
                            + "document with read_document (document=<name>), or pass file, a path in a git working tree");
                }
                if (!hasWorkingTree(card)) {
                    throw new IllegalArgumentException(noWorkingTree(card) + " Pass file, a path in a git working tree, or ask the "
                            + "person to choose Open file… or Clone… in the card.");
                }
                return new Target(card.repoRoot(), card.currentFile().get());
            }
            Path given = Path.of(file);
            if (!given.isAbsolute()) {
                if (card == null || card.repoRoot() == null) {
                    throw new IllegalArgumentException("relative path " + file + " needs a Stamped Document card with a working tree to resolve against; pass an absolute path");
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
                  StampRecord[] introducer, Map<Integer, List<StampRecord>> affirmers, Map<Integer, List<StampRecord>> refuters,
                  Map<Integer, List<StampRecord>> commenters) {

        static Report read(Target target) throws IOException {
            GitStampBridge bridge = GitStampBridge.read(target.root(), target.relative());
            try (GitRepo repo = GitRepo.open(target.root())) {
                List<String> lines = repo.headLines(target.relative());
                StampRecord[] introducer = new StampRecord[lines.size() + 1];
                Map<Integer, List<StampRecord>> affirmers = new LinkedHashMap<>();
                Map<Integer, List<StampRecord>> refuters = new LinkedHashMap<>();
                Map<Integer, List<StampRecord>> commenters = new LinkedHashMap<>();
                for (StampRecord r : bridge.stamps().stamps().values()) {
                    r.introduced().forEach((int line) -> { if (line < introducer.length) introducer[line] = r; });
                    r.affirmed().forEach((int line) -> affirmers.computeIfAbsent(line, k -> new ArrayList<>()).add(r));
                    r.refuted().forEach((int line) -> refuters.computeIfAbsent(line, k -> new ArrayList<>()).add(r));
                    r.commented().forEach((int line) -> commenters.computeIfAbsent(line, k -> new ArrayList<>()).add(r));
                }
                return new Report(target, bridge, repo.headRevision(), repo.branch(), lines, introducer, affirmers, refuters, commenters);
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
                    .append(affirmers.size()).append(" lines affirmed, ").append(refuters.size()).append(" refuted, ")
                    .append(queued).append(" in the review queue\n");
            out.append("STAMPs (newest first):\n");
            for (StampRecord r : byTime) {
                GitStampTerms.GitStamp terms = bridge.terms().get(r.id());
                String author = terms == null ? r.author().name() : terms.author().description();
                out.append("  ").append(r.commitSha(), 0, 7).append(' ').append(DAY.format(r.time())).append(' ').append(author)
                        .append(r.introduced().isEmpty() ? "" : "  introduced " + r.introduced().getCardinality() + " line(s) " + ranges(r.introduced()))
                        .append(r.affirmed().isEmpty() ? "" : "  affirmed " + ranges(r.affirmed()) + (r.signed() ? " (signed)" : " (unsigned)"))
                        .append(r.refuted().isEmpty() ? "" : "  refuted " + ranges(r.refuted()) + (r.signed() ? " (signed)" : " (unsigned)"))
                        .append(r.commented().isEmpty() ? "" : "  commented on " + ranges(r.commented()))
                        .append("  ").append(r.summary()).append('\n');
            }
            if (!queue.isEmpty()) {
                out.append("Review queue (introduced, affirmed by no one; refuted lines included): ")
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
            out.append("Lines ").append(first).append('-').append(last).append(" (line  stamp  ✗=refuted ✓=affirmed …=commented  text):\n");
            for (int line = first; line <= last; line++) {
                StampRecord r = introducer[line];
                String mark = refuters.containsKey(line) ? "✗" : affirmers.containsKey(line) ? "✓" : commenters.containsKey(line) ? "…" : " ";
                out.append(String.format(Locale.ROOT, "%5d  %s %s  %s%n", line, r == null ? "       " : r.commitSha().substring(0, 7),
                        mark, lines.get(line - 1)));
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
