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

import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredAnnotation;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A stored document as the assistant reads it ({@link DocumentGraphTools}): the block tree in
 * document order, each block addressed by its path ({@code §3.2} is the second block inside the
 * third block of the document), each line by its ordinal ({@code L45}, its place in the
 * document), every line with the STAMP that introduced it and its annotations. Paths and
 * ordinals are what the assistant says back to name a block; they mean the same to the person
 * reading the card and never expose a store identifier. Pure over a {@link StoredDocument} and
 * a way to describe a STAMP, so its shapes are tested without a store.
 */
public final class DocumentOutline {

    /** Describes a STAMP for the assistant: {@code a1b2c3d 2026-10-04 Kerry} or {@code uncommitted, Komet Assistant}. */
    @FunctionalInterface
    public interface Describe {

        /** @return the stamp described, or a dash for none */
        String stamp(StampEntity<?> stamp);

        /** @return the commit's summary line, if known; nothing by default */
        default Optional<String> summary(StampEntity<?> stamp) {
            return Optional.empty();
        }
    }

    /**
     * One block of the tree, in document order.
     *
     * @param path   the block's path: {@code 0} for the document block (title, attributes), else {@code 3.2}
     * @param depth  how deep in the tree
     * @param block  the stored block
     * @param lines  the block's own lines, in order
     * @param blocks how many child blocks it holds
     */
    public record Node(String path, int depth, StoredBlock block, List<StoredLine> lines, int blocks) {

        /** @return whether the block's text can be replaced with propose_block_edit: it holds lines and no blocks */
        public boolean editable() {
            return blocks == 0 && !lines.isEmpty();
        }
    }

    private final StoredDocument document;
    private final List<Node> nodes = new ArrayList<>();
    private final Map<Long, Integer> ordinal = new HashMap<>();
    private final Map<String, Node> byPath = new HashMap<>();
    private final Map<Long, Node> byLine = new HashMap<>();

    /**
     * @param document the stored document, its pending edit included when it is to be read
     */
    public DocumentOutline(StoredDocument document) {
        this.document = document;
        for (int i = 0; i < document.lines().size(); i++) {
            ordinal.put(document.lines().get(i).nid(), i + 1);
        }
        if (document.root() != null) {
            walk(document.root(), "0", 0);
        }
    }

    private void walk(StoredBlock block, String path, int depth) {
        List<StoredLine> lines = new ArrayList<>();
        List<StoredBlock> children = new ArrayList<>();
        for (long member : block.members()) {
            Optional<StoredLine> line = document.line(member);
            if (line.isPresent()) {
                lines.add(line.get());
            } else {
                StoredBlock child = document.blocks().get(member);
                if (child != null) {
                    children.add(child);
                }
            }
        }
        Node node = new Node(path, depth, block, List.copyOf(lines), children.size());
        nodes.add(node);
        byPath.put(path, node);
        for (StoredLine line : lines) {
            byLine.put(line.nid(), node);
        }
        for (int i = 0; i < children.size(); i++) {
            walk(children.get(i), path.equals("0") ? Integer.toString(i + 1) : path + "." + (i + 1), depth + 1);
        }
    }

    /** @return the document */
    public StoredDocument document() {
        return document;
    }

    /** @return every block, in document order */
    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    /** @return a line's ordinal in the document, 1-based, or 0 */
    public int ordinal(StoredLine line) {
        return ordinal.getOrDefault(line.nid(), 0);
    }

    /**
     * Finds a block by what the assistant names it: {@code §3.2} or {@code 3.2} (its path), or
     * {@code L45} (the block holding line 45).
     *
     * @param name the name
     * @return the block, if there is one
     */
    public Optional<Node> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.strip();
        if (n.startsWith("§")) {
            n = n.substring(1).strip();
        }
        if (n.length() > 1 && (n.charAt(0) == 'L' || n.charAt(0) == 'l') && n.substring(1).chars().allMatch(Character::isDigit)) {
            int line = Integer.parseInt(n.substring(1));
            if (line < 1 || line > document.lines().size()) {
                return Optional.empty();
            }
            return Optional.ofNullable(byLine.get(document.lines().get(line - 1).nid()));
        }
        return Optional.ofNullable(byPath.get(n));
    }

    /** @return the document's title, or blank */
    public String title() {
        if (!document.attributes().isEmpty() && !document.attributes().getFirst().isBlank()) {
            return document.attributes().getFirst();
        }
        return document.root() == null || document.root().title() == null ? "" : document.root().title();
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    /**
     * A page of the outline.
     *
     * @param text  the blocks rendered
     * @param from  the first block shown, 0-based
     * @param next  the first block not shown
     * @param total the block count
     */
    public record Page(String text, int from, int next, int total) {

        /** @return whether blocks remain after this page */
        public boolean more() {
            return next < total;
        }
    }

    /**
     * The outline: blocks {@code from} (0-based) on, at most {@code max} of them and at most
     * {@code budget} characters (one block always), each with its lines when {@code withLines}.
     */
    public Page outline(int from, int max, int budget, boolean withLines, Describe describe) {
        StringBuilder out = new StringBuilder();
        int start = Math.clamp(from, 0, nodes.size());
        int i = start;
        int shown = 0;
        while (i < nodes.size() && shown < max) {
            StringBuilder one = new StringBuilder();
            block(nodes.get(i), withLines, false, describe, one);
            if (shown > 0 && out.length() + one.length() > budget) {
                break;
            }
            out.append(one);
            i++;
            shown++;
        }
        return new Page(out.toString(), start, i, nodes.size());
    }

    /** One block: its header and, when asked, its lines; {@code full} adds every annotation's text and the references. */
    void block(Node node, boolean withLines, boolean full, Describe describe, StringBuilder out) {
        StoredBlock b = node.block();
        String indent = "  ".repeat(node.depth());
        out.append(indent).append('§').append(node.path()).append(' ').append(b.kind());
        if (b.level() > 0) {
            out.append(" level ").append(b.level());
        }
        if (b.title() != null && !b.title().isBlank()) {
            out.append(" \"").append(b.title()).append('"');
        }
        if (b.parameters() != null && !b.parameters().isBlank()) {
            out.append(' ').append(b.parameters());
        }
        if (!node.lines().isEmpty()) {
            int first = ordinal(node.lines().getFirst());
            int last = ordinal(node.lines().getLast());
            out.append(first == last ? " L" + first : " L" + first + "-" + last);
        }
        if (node.blocks() > 0) {
            out.append(", ").append(node.blocks()).append(node.blocks() == 1 ? " block" : " blocks");
        }
        if (node.editable()) {
            out.append(", editable");
        }
        out.append('\n');
        if (!withLines) {
            return;
        }
        for (StoredLine line : node.lines()) {
            line(line, full, describe, indent + "  ", out);
        }
    }

    /** One line: ordinal, introducing STAMP, review marks, text; {@code full} adds the latest STAMP, annotations and references. */
    void line(StoredLine line, boolean full, Describe describe, String indent, StringBuilder out) {
        out.append(indent).append('L').append(ordinal(line)).append("  ").append(describe.stamp(line.introduced()))
                .append(marks(line)).append("  ").append(line.text()).append('\n');
        if (!full) {
            return;
        }
        if (line.introduced() != null) {
            describe.summary(line.introduced()).ifPresent(s -> out.append(indent).append("      introduced by commit ").append(s).append('\n'));
        }
        if (line.latest() != null && (line.introduced() == null || line.latest().nid() != line.introduced().nid())) {
            out.append(indent).append("      latest version ").append(describe.stamp(line.latest())).append('\n');
        }
        for (StoredAnnotation a : line.annotations()) {
            out.append(indent).append("      ").append(word(a.kind())).append(' ').append(describe.stamp(a.stamp()))
                    .append(a.retracted() ? ", retracted by " + describe.stamp(a.retractedBy()) : "")
                    .append(a.text() == null || a.text().isBlank() ? "" : ": " + oneLine(a.text())).append('\n');
        }
        for (StoredReference r : document.references().getOrDefault(line.nid(), List.of())) {
            out.append(indent).append("      reference ").append(r.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ')).append(' ')
                    .append(r.target()).append(r.text() == null || r.text().isBlank() ? "" : " (" + r.text() + ")").append('\n');
        }
    }

    private static String marks(StoredLine line) {
        StringBuilder m = new StringBuilder();
        long affirmed = line.inForce().stream().filter(a -> a.kind() == AnnotationKind.AFFIRMATION).count();
        long refuted = line.inForce().stream().filter(a -> a.kind() == AnnotationKind.REFUTATION).count();
        long commented = line.inForce().stream().filter(a -> a.kind() == AnnotationKind.COMMENT).count();
        long retracted = line.annotations().stream().filter(StoredAnnotation::retracted).count();
        if (affirmed > 0) {
            m.append(" ✓").append(affirmed > 1 ? Long.toString(affirmed) : "");
        }
        if (refuted > 0) {
            m.append(" ✗").append(refuted > 1 ? Long.toString(refuted) : "");
        }
        if (commented > 0) {
            m.append(" …").append(commented > 1 ? Long.toString(commented) : "");
        }
        if (retracted > 0) {
            m.append(" ↶").append(retracted > 1 ? Long.toString(retracted) : "");
        }
        if (line.latest() != null && line.latest().time() == Long.MAX_VALUE) {
            m.append(" ✎");
        }
        return m.toString();
    }

    private static String word(AnnotationKind kind) {
        return switch (kind) {
            case AFFIRMATION -> "affirmed by";
            case REFUTATION -> "refuted by";
            case COMMENT -> "commented on by";
            case RETRACTION -> "retracted by";
        };
    }

    private static String oneLine(String text) {
        String t = text.strip().replaceAll("\\s+", " ");
        return t.length() > 240 ? t.substring(0, 239) + "…" : t;
    }

    /**
     * Search results.
     *
     * @param text  the matches shown
     * @param count every match, shown or not
     */
    public record Matches(String text, int count) { }

    /**
     * The lines and block titles matching a query, case-insensitively, each with the block it lies in.
     *
     * @param query the text sought
     * @param max   at most this many shown
     */
    public Matches search(String query, int max, Describe describe) {
        String q = query.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Node node : nodes) {
            boolean titled = node.block().title() != null && node.block().title().toLowerCase(Locale.ROOT).contains(q);
            if (titled && node.lines().stream().noneMatch(l -> l.text().toLowerCase(Locale.ROOT).contains(q))) {
                if (count++ < max) {
                    out.append('§').append(node.path()).append(' ').append(node.block().kind()).append(" \"").append(node.block().title()).append("\"\n");
                }
            }
            for (StoredLine line : node.lines()) {
                if (line.text().toLowerCase(Locale.ROOT).contains(q)) {
                    if (count++ < max) {
                        out.append('§').append(node.path()).append(' ').append(node.block().kind()).append("  ");
                        line(line, false, describe, "", out);
                    }
                }
            }
        }
        return new Matches(out.toString(), count);
    }

    /**
     * Each line's STAMP from the store, for stamp_lines when no working tree is known: a summary
     * of the introducing STAMPs and the review queue, then lines {@code from}–{@code to}.
     */
    public String provenance(int from, int to, Describe describe) {
        StringBuilder out = new StringBuilder();
        List<StoredLine> lines = document.lines();
        Map<String, Integer> byStamp = new LinkedHashMap<>();
        int queued = 0;
        int affirmed = 0;
        int refuted = 0;
        for (StoredLine line : lines) {
            if (line.introduced() != null) {
                byStamp.merge(describe.stamp(line.introduced()), 1, Integer::sum);
            }
            if (!line.affirmers().isEmpty()) {
                affirmed++;
            } else if (line.introduced() != null) {
                queued++;
            }
            if (!line.refuters().isEmpty()) {
                refuted++;
            }
        }
        out.append(lines.size()).append(" lines, ").append(byStamp.size()).append(" introducing STAMPs, ").append(affirmed)
                .append(" lines affirmed, ").append(refuted).append(" refuted, ").append(queued).append(" in the review queue\n");
        out.append("STAMPs (lines introduced):\n");
        byStamp.forEach((stamp, n) -> out.append("  ").append(stamp).append("  ").append(n).append(" line(s)\n"));
        int first = Math.max(1, from);
        int last = Math.min(lines.size(), to);
        if (first > last) {
            out.append("Line ").append(first).append(" is beyond the document's ").append(lines.size()).append(" lines.\n");
            return out.toString();
        }
        out.append("Lines ").append(first).append('-').append(last).append(" (line  stamp  ✓=affirmed ✗=refuted …=commented ↶=retracted ✎=uncommitted  text):\n");
        for (int i = first; i <= last; i++) {
            line(lines.get(i - 1), false, describe, "", out);
        }
        if (last < lines.size()) {
            out.append("… ").append(lines.size() - last).append(" more line(s); pass from/to to see them.\n");
        }
        return out.toString();
    }
}
