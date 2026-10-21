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

import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An assembly as one document on the Stamped Document surface (ike-lab-documents topics
 * {@code dev-stamped-document-surface} and {@code dev-document-surface-pages};
 * IKE-Network/ike-issues#1307): the host document the person opened, and, wherever one of its
 * lines is an {@code include::} directive naming a file the store holds, that file's document
 * as a flowed child — its own lines, STAMPs, annotations and commit summaries, stacked in the
 * host's include order. A child that includes in turn is followed the same way, each file once,
 * so a file named twice or a cycle reads once, where it first appears.
 *
 * <p>Every line keeps the document it belongs to: a selection is located to one member and
 * that member's own line numbers, which is what an annotation trailer names. The surface order
 * — the host's lines with each resolved include line followed by its child's lines — is the
 * order both views stack and the ordinals a {@link LineSelection} runs over.
 *
 * <p>Resolution follows the materializer ({@link IncludeResolver}): the text's own attribute
 * entries, the repository conventions, then the including file's directory, against the paths
 * the store records. The assembly reads nothing itself; it is a pure function of the host, the
 * host's recorded path and a lookup of stored documents by repository-relative path.
 */
public final class DocumentAssembly {

    /** A root for resolving paths that are all repository-relative: nothing is read from it. */
    private static final Path RESOLUTION_ROOT = Path.of("/").toAbsolutePath();

    /**
     * One document on the surface: the host, or a child in include order.
     *
     * @param document   the document as the store holds it
     * @param path       its repository-relative path, forward slashes
     * @param depth      how many includes deep it sits ({@code 0} for the host)
     * @param includedBy the member whose include line brought it in, {@code null} for the host
     */
    public record Member(StoredDocument document, String path, int depth, Member includedBy) {

        /** @return whether this is the host, the document the person opened */
        public boolean host() {
            return includedBy == null;
        }

        /** @return the file name of the path */
        public String fileName() {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    /**
     * One include directive and where it led.
     *
     * @param in     the member whose line it is
     * @param line   the directive's line
     * @param target the target as written
     * @param child  the child it shows in place, or {@code null} when it stays a line
     * @param note   why it stays a line: not in the store, already shown, a cycle; empty when it resolved
     */
    public record Include(Member in, StoredLine line, String target, Member child, String note) {

        /** @return whether the include shows a child in place */
        public boolean resolved() {
            return child != null;
        }
    }

    /**
     * A selection located to one member: the member and the selection over that member's own
     * lines, from which the file's line numbers follow.
     *
     * @param member the member every selected line belongs to
     * @param local  the selection as ordinals into the member's lines
     */
    public record Located(Member member, LineSelection local) { }

    /**
     * A part of a block's lines as the Reading view shows them: a run of the block's own lines,
     * or a resolved include whose child's blocks stand where its line was.
     *
     * @param lines   the block's lines of this part, in order; empty for an include part
     * @param include the include shown in place of its line, or {@code null} for a text part
     */
    public record Part(List<StoredLine> lines, Include include) {

        /** @return whether the part stands for a child document */
        public boolean included() {
            return include != null;
        }
    }

    /** One line in surface order. */
    private record Entry(StoredLine line, Member member, int indexInMember) { }

    private final Member host;
    private final List<Member> members = new ArrayList<>();
    private final List<Include> includes = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<StoredLine> lines = new ArrayList<>();
    private final Map<Long, Integer> ordinals = new HashMap<>();
    private final Map<Long, StoredLine> linesByNid = new HashMap<>();
    private final Map<Long, Include> includesByLine = new HashMap<>();
    private final Map<Long, List<StoredReference>> references = new HashMap<>();
    private final Set<String> visited = new LinkedHashSet<>();
    private final Function<String, Optional<StoredDocument>> store;
    private final Map<String, Optional<StoredDocument>> looked = new HashMap<>();

    private DocumentAssembly(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store) {
        this.store = store;
        this.host = new Member(host, hostPath == null ? "" : hostPath, 0, null);
        if (!this.host.path().isEmpty()) {
            visited.add(this.host.path());
        }
        flatten(this.host);
    }

    /**
     * The assembly of a host document: its includes resolved against the store, each resolved
     * child flowed in place, children's includes followed in turn.
     *
     * @param host     the host document
     * @param hostPath the host's repository-relative path, or {@code null} when none is recorded (then nothing resolves)
     * @param store    a lookup of the store's documents by repository-relative path (forward slashes)
     * @return the assembly
     */
    public static DocumentAssembly of(StoredDocument host, String hostPath, Function<String, Optional<StoredDocument>> store) {
        return new DocumentAssembly(host, hostPath, store);
    }

    /**
     * A document alone: its include lines stay lines.
     *
     * @param document the document
     * @param path     its recorded path, or {@code null}
     * @return the assembly of one member
     */
    public static DocumentAssembly single(StoredDocument document, String path) {
        return new DocumentAssembly(document, path, p -> Optional.empty());
    }

    /** Appends a member's lines in surface order, each resolved include line followed by its child's. */
    private void flatten(Member member) {
        members.add(member);
        StoredDocument document = member.document();
        references.putAll(document.references());
        Map<Integer, IncludeResolver.Resolution> byLine = new HashMap<>();
        if (!member.path().isEmpty()) {
            List<String> texts = document.lines().stream().map(StoredLine::text).toList();
            if (!IncludeResolver.includes(texts).isEmpty()) {
                for (IncludeResolver.Resolution r : IncludeResolver.resolve(RESOLUTION_ROOT, Path.of(member.path()), texts, this::stored)) {
                    byLine.put(r.line(), r);
                }
            }
        }
        for (int i = 0; i < document.lines().size(); i++) {
            StoredLine line = document.lines().get(i);
            entries.add(new Entry(line, member, i));
            lines.add(line);
            ordinals.putIfAbsent(line.nid(), lines.size() - 1);
            linesByNid.putIfAbsent(line.nid(), line);
            IncludeResolver.Resolution resolution = byLine.get(i + 1);
            if (resolution == null) {
                continue;
            }
            Include include = follow(member, line, resolution);
            includes.add(include);
            includesByLine.put(line.nid(), include);
            if (include.resolved()) {
                flatten(include.child());
            }
        }
    }

    /** Where one include leads: a child flowed in place, or a line with a note. */
    private Include follow(Member member, StoredLine line, IncludeResolver.Resolution resolution) {
        if (resolution.resolved().isEmpty()) {
            // The resolver names what it tried against the store; that is the note, not the attribute arithmetic behind it.
            String reason = resolution.reason();
            int tried = reason.indexOf("tried ");
            return new Include(member, line, resolution.target(), null,
                    tried >= 0 ? "not in the store (" + reason.substring(tried) + ")" : "not resolved: " + reason);
        }
        String path = resolution.resolved().get().toString().replace('\\', '/');
        if (!visited.add(path)) {
            return new Include(member, line, resolution.target(), null, "shown once, where it first appears: " + path);
        }
        Optional<StoredDocument> child = stored(path);
        if (child.isEmpty()) {
            return new Include(member, line, resolution.target(), null, "not in the store: " + path);
        }
        return new Include(member, line, resolution.target(), new Member(child.get(), path, member.depth() + 1, member), "");
    }

    private boolean stored(Path path) {
        return stored(path.toString().replace('\\', '/')).isPresent();
    }

    private Optional<StoredDocument> stored(String path) {
        return looked.computeIfAbsent(path, store);
    }

    // ── what the surface reads ────────────────────────────────────────────────

    /** @return the host, the document the person opened */
    public Member host() {
        return host;
    }

    /** @return every member in surface order, the host first */
    public List<Member> members() {
        return List.copyOf(members);
    }

    /** @return the members shown in place of an include line, in include order */
    public List<Member> children() {
        return members.stream().filter(m -> !m.host()).toList();
    }

    /** @return every include directive met, in surface order, resolved or not */
    public List<Include> includes() {
        return List.copyOf(includes);
    }

    /** @return the includes that stayed lines, with their notes */
    public List<Include> unresolved() {
        return includes.stream().filter(i -> !i.resolved()).toList();
    }

    /** @return every line in surface order */
    public List<StoredLine> lines() {
        return List.copyOf(lines);
    }

    /** @return the ordinal of a line in surface order, or {@code -1} */
    public int ordinal(long nid) {
        return ordinals.getOrDefault(nid, -1);
    }

    /** @return the line with that nid, if any member holds it */
    public Optional<StoredLine> line(long nid) {
        return Optional.ofNullable(linesByNid.get(nid));
    }

    /** @return the member the line with that ordinal belongs to */
    public Optional<Member> memberAt(int ordinal) {
        return ordinal < 0 || ordinal >= entries.size() ? Optional.empty() : Optional.of(entries.get(ordinal).member());
    }

    /** @return the member holding the line with that nid */
    public Optional<Member> memberOf(long nid) {
        return memberAt(ordinal(nid));
    }

    /** @return the index of the line with that ordinal within its member's lines, or {@code -1} */
    public int indexInMember(int ordinal) {
        return ordinal < 0 || ordinal >= entries.size() ? -1 : entries.get(ordinal).indexInMember();
    }

    /** @return the include directive on that line, if the line is one */
    public Optional<Include> includeOf(long lineNid) {
        return Optional.ofNullable(includesByLine.get(lineNid));
    }

    /** @return the active references of every member, by the nid of the line they occur on */
    public Map<Long, List<StoredReference>> references() {
        return references;
    }

    /**
     * Locates a selection to the one member every selected line belongs to.
     *
     * @param selection the selection in surface ordinals
     * @return the member and the selection over its own lines, or empty when the selection is
     *         out of range or spans more than one member
     */
    public Optional<Located> locate(LineSelection selection) {
        if (selection == null || selection.min() < 0 || selection.max() >= entries.size()) {
            return Optional.empty();
        }
        Member member = entries.get(selection.min()).member();
        for (int o = selection.min(); o <= selection.max(); o++) {
            if (entries.get(o).member() != member) {
                return Optional.empty();
            }
        }
        return Optional.of(new Located(member,
                new LineSelection(entries.get(selection.anchor()).indexInMember(), entries.get(selection.caret()).indexInMember())));
    }

    /** @return how many members a selection touches */
    public int membersTouched(LineSelection selection) {
        if (selection == null) {
            return 0;
        }
        Set<Member> touched = new LinkedHashSet<>();
        for (int o = Math.max(0, selection.min()); o <= Math.min(entries.size() - 1, selection.max()); o++) {
            touched.add(entries.get(o).member());
        }
        return touched.size();
    }

    /**
     * The ordinal, in surface order, of a member's line by its position in that member.
     *
     * @param member the member
     * @param index  the index into the member's lines
     * @return the ordinal, or {@code -1}
     */
    public int ordinalIn(Member member, int index) {
        return index < 0 || index >= member.document().lines().size() ? -1 : ordinal(member.document().lines().get(index).nid());
    }

    /**
     * A block's lines split for the Reading view: runs of the block's own lines, and, where a
     * line is an include shown in place, a part standing for the child. An include that stayed a
     * line stays in its run; the view notes it there.
     *
     * @param blockLines the block's lines in order
     * @return the parts in order; one text part when nothing is shown in place
     */
    public List<Part> parts(List<StoredLine> blockLines) {
        List<Part> parts = new ArrayList<>();
        List<StoredLine> run = new ArrayList<>();
        for (StoredLine line : blockLines) {
            Include include = includesByLine.get(line.nid());
            if (include != null && include.resolved()) {
                if (!run.isEmpty()) {
                    parts.add(new Part(List.copyOf(run), null));
                    run.clear();
                }
                parts.add(new Part(List.of(), include));
            } else {
                run.add(line);
            }
        }
        if (!run.isEmpty() || parts.isEmpty()) {
            parts.add(new Part(List.copyOf(run), null));
        }
        return parts;
    }

    /** @return the members by path, in surface order */
    public Map<String, Member> byPath() {
        Map<String, Member> byPath = new LinkedHashMap<>();
        for (Member m : members) {
            byPath.putIfAbsent(m.path(), m);
        }
        return byPath;
    }
}
