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

import design.knowledge.stamp.adoc.dialect.Block;
import dev.ikm.tinkar.entity.StampEntity;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The stamped document as the store holds it, as a tree: what the Graph view shows
 * (ike-lab-documents topic {@code dev-stamped-document-surface}; IKE-Network/ike-issues#1307).
 * The root is the host's document block; under it its sections, under those the blocks by
 * kind, under each block its lines, and under a line its inline references. An include line
 * the assembly resolved carries the child document's tree beneath it, so the graph's order is
 * the surface order the other views stack. A document the store holds no structure for lists
 * its lines flat under the root.
 *
 * <p>With a {@link DocumentHistory} per member, the graph folds time in as a further level:
 * a line's versions beneath it, oldest first and ending in the text as it stands; the lines
 * later commits retired, each after the surviving line it followed, so it stands where it
 * stood; and on every block the commits that touched its lines, oldest first.
 *
 * <p>It is a tree, not a DAG: a block has one parent, a line belongs to one block, and the
 * assembly shows each file once. A cross-reference is a leaf that names its target, never a
 * second parent. Every node knows the surface ordinals it spans, so selecting a node is
 * selecting its lines on the other views; a version spans its line's, a retired line none.
 *
 * <p>The graph is a pure function of the assembly and the histories: it reads nothing itself.
 */
public final class DocumentGraph {

    /**
     * What a node stands for. {@link #ELEMENT}, {@link #INLINE} and {@link #TEXT} occur only in the
     * {@linkplain #docbook DocBook projection}, which has no {@link #LINE} nodes.
     */
    public enum Kind { DOCUMENT, BLOCK, LINE, REFERENCE, VERSION, RETIRED, ELEMENT, INLINE, TEXT }

    /**
     * What a node of the {@linkplain #docbook DocBook projection} is as an element: its name, its
     * attributes, its text, and the lines it came from, which nothing else in the projection shows.
     *
     * @param element    the element in words: a block's kind, Title, Term, Row, Cell, Comment, Include, an inline element's, or {@code text}
     * @param attributes its attributes in order: those its markup lines carried (style, language, role, id…), or an inline element's
     * @param text       its text as plain text: a title's, a run's, an element's content; empty when it shows none
     * @param lines      the lines it came from, in order: its own markup and content lines, not its children's
     */
    public record Markup(String element, Map<String, String> attributes, String text, List<StoredLine> lines) { }

    /** How many characters of a line a block's label shows. */
    private static final int LABEL_CHARS = 72;

    /**
     * One node of the graph.
     *
     * @param kind      what it stands for
     * @param nid       the entity's nid: the document block's, the block's, the line element's or the reference semantic's
     * @param label     what the row says
     * @param detail    a second line for a tooltip: a path, an include note, a reference's text; empty when none
     * @param member    the member of the assembly the node belongs to
     * @param first     the smallest surface ordinal the node spans, {@code -1} when it spans no line
     * @param last      the largest surface ordinal the node spans, {@code -1} when it spans no line
     * @param stamp     the STAMP the row shows: a line's introducing STAMP, a block's latest, a version's commit, a retired line's retiring; {@code null} when none
     * @param block     the block, for a {@link Kind#DOCUMENT} or {@link Kind#BLOCK} node; else {@code null}
     * @param line      the line, for a {@link Kind#LINE} node; else {@code null}
     * @param reference the reference, for a {@link Kind#REFERENCE} node; else {@code null}
     * @param version   the version, for a {@link Kind#VERSION} node; else {@code null}
     * @param retired   the retired line, for a {@link Kind#RETIRED} node; else {@code null}
     * @param commits   for a block or document with history, the STAMPs of the commits that touched its lines, oldest first; else empty
     * @param markup    in the DocBook projection, the node as an element; else {@code null}
     * @param children  the nodes beneath, in document order
     * @param annotations in the DocBook projection, a stored sentence's annotations in force (IKE-Network/ike-issues#1313); else empty
     */
    public record Node(Kind kind, long nid, String label, String detail, DocumentAssembly.Member member, int first, int last,
                       StampEntity<?> stamp, StoredBlock block, StoredLine line, StoredReference reference,
                       DocumentHistory.Version version, DocumentHistory.RetiredLine retired, List<StampEntity<?>> commits, Markup markup,
                       List<Node> children, List<GitDocumentMaterializer.StoredAnnotation> annotations) {

        /** A node with no annotations of its own beyond its line's. */
        public Node(Kind kind, long nid, String label, String detail, DocumentAssembly.Member member, int first, int last,
                    StampEntity<?> stamp, StoredBlock block, StoredLine line, StoredReference reference,
                    DocumentHistory.Version version, DocumentHistory.RetiredLine retired, List<StampEntity<?>> commits, Markup markup,
                    List<Node> children) {
            this(kind, nid, label, detail, member, first, last, stamp, block, line, reference, version, retired, commits, markup, children,
                    List.of());
        }

        /** @return whether the node spans at least one line */
        public boolean spansLines() {
            return first >= 0;
        }

        /** @return whether the node spans the ordinal */
        public boolean spans(int ordinal) {
            return first >= 0 && ordinal >= first && ordinal <= last;
        }

        /** @return the selection of every line the node spans, if it spans any */
        public Optional<LineSelection> selection() {
            return first < 0 ? Optional.empty() : Optional.of(new LineSelection(first, last));
        }

        /** @return the block's kind for a document or block node, {@code null} otherwise */
        public Block.Kind blockKind() {
            return block == null ? null : block.kind();
        }

        /** @return whether the node is a section or the document itself: what opens expanded */
        public boolean structural() {
            return kind == Kind.DOCUMENT || (block != null && block.kind() == Block.Kind.SECTION);
        }

        /** @return whether the node's lines are uncommitted: a line edited in Komet, a block any of whose lines is, an uncommitted version */
        public boolean uncommitted() {
            if (version != null) {
                return version.change() == DocumentHistory.Change.UNCOMMITTED;
            }
            if (line != null) {
                return DocumentSurfaceView.uncommitted(line);
            }
            if (markup != null && markup.lines().stream().anyMatch(DocumentSurfaceView::uncommitted)) {
                return true;
            }
            return kind != Kind.RETIRED && children.stream().anyMatch(Node::uncommitted);
        }

        /** @return how many lines the node spans */
        public int lineCount() {
            return first < 0 ? 0 : last - first + 1;
        }
    }

    private final DocumentAssembly assembly;
    private final Function<DocumentAssembly.Member, DocumentHistory> histories;

    private DocumentGraph(DocumentAssembly assembly, Function<DocumentAssembly.Member, DocumentHistory> histories) {
        this.assembly = assembly;
        this.histories = histories;
    }

    /**
     * The graph of an assembly as it stands.
     *
     * @param assembly the assembly
     * @return the root: the host's document node
     */
    public static Node of(DocumentAssembly assembly) {
        return new DocumentGraph(assembly, null).document(assembly.host());
    }

    /**
     * The graph of an assembly with its history: versions under lines, retired lines in place,
     * commits on blocks.
     *
     * @param assembly  the assembly
     * @param histories each member's history; {@code null} or {@link DocumentHistory#NONE} for one without
     * @return the root: the host's document node
     */
    public static Node of(DocumentAssembly assembly, Function<DocumentAssembly.Member, DocumentHistory> histories) {
        return new DocumentGraph(assembly, histories).document(assembly.host());
    }

    /**
     * The DocBook projection of an assembly as it stands (IKE-Network/ike-issues#1313): blocks as
     * elements with their markup lines folded into their titles and attributes, a paragraph's lines
     * joined into text runs and inline elements, no line a node; see {@link DocBookProjection}.
     *
     * @param assembly the assembly
     * @return the root: the host's document element
     */
    public static Node docbook(DocumentAssembly assembly) {
        return DocBookProjection.of(assembly);
    }

    /**
     * The DocBook projection with stored sentences as nodes of their own, or flattened into their
     * paragraph's mixed content (IKE-Network/ike-issues#1313).
     *
     * @param assembly  the assembly
     * @param sentences whether a stored sentence is a node
     * @return the root: the host's document element
     */
    public static Node docbook(DocumentAssembly assembly, boolean sentences) {
        return DocBookProjection.of(assembly, sentences);
    }

    private DocumentHistory history(DocumentAssembly.Member member) {
        if (histories == null) {
            return null;
        }
        DocumentHistory history = histories.apply(member);
        return history == null ? DocumentHistory.NONE : history;
    }

    /** One member's tree: its root block, or its lines flat when the store holds no structure. */
    private Node document(DocumentAssembly.Member member) {
        StoredDocument document = member.document();
        DocumentHistory history = history(member);
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        if (title == null || title.isBlank()) {
            title = member.path().isEmpty() ? "Document" : member.fileName();
        }
        String detail = member.path().isEmpty() ? "" : member.path();
        List<Node> children = new ArrayList<>();
        if (history != null) {
            for (DocumentHistory.RetiredLine retired : history.retiredAtStart()) {
                children.add(retired(member, retired));
            }
        }
        if (document.root() == null) {
            for (StoredLine line : document.lines()) {
                children.add(line(member, line, history));
                addRetiredAfter(member, line, history, children);
            }
            return new Node(Kind.DOCUMENT, 0, title, detail.isEmpty() ? "no structure in the store; re-materialize to add it"
                    : detail + " — no structure in the store; re-materialize to add it", member, min(children), max(children), null,
                    null, null, null, null, null, commits(children), null, List.copyOf(children));
        }
        children.addAll(members(member, document.root(), history));
        return new Node(Kind.DOCUMENT, document.root().nid(), title, detail, member, min(children), max(children),
                document.root().stamp(), document.root(), null, null, null, null, commits(children), null, List.copyOf(children));
    }

    /** A block's node: its own lines and child blocks in document order. */
    private Node block(DocumentAssembly.Member member, StoredBlock block, DocumentHistory history) {
        List<Node> children = members(member, block, history);
        return new Node(Kind.BLOCK, block.nid(), label(block, children), block.parameters(), member, min(children), max(children),
                block.stamp(), block, null, null, null, null, commits(children), null, List.copyOf(children));
    }

    /**
     * The nodes of a block's members: a line's node or a child block's, in the order the block lists
     * them, retired lines in place. The graph shows the block's members as the store's tree holds
     * them, so a document stored as vertices shows the blank lines its blocks hold.
     */
    private List<Node> members(DocumentAssembly.Member member, StoredBlock block, DocumentHistory history) {
        StoredDocument document = member.document();
        List<Node> children = new ArrayList<>();
        for (long nid : block.tree()) {
            Optional<StoredLine> line = document.line(nid);
            if (line.isPresent()) {
                children.add(line(member, line.get(), history));
                addRetiredAfter(member, line.get(), history, children);
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    children.add(block(member, child, history));
                }
            }
        }
        return children;
    }

    private void addRetiredAfter(DocumentAssembly.Member member, StoredLine line, DocumentHistory history, List<Node> into) {
        if (history != null) {
            for (DocumentHistory.RetiredLine retired : history.retiredAfter(line.nid())) {
                into.add(retired(member, retired));
            }
        }
    }

    /** A line's node: with history its versions beneath, then its references, then, for a resolved include, the child document's tree. */
    private Node line(DocumentAssembly.Member member, StoredLine line, DocumentHistory history) {
        int ordinal = assembly.ordinal(line.nid());
        int index = assembly.indexInMember(ordinal);
        List<Node> children = new ArrayList<>();
        if (history != null) {
            for (DocumentHistory.Version version : history.versions(line.nid())) {
                children.add(new Node(Kind.VERSION, line.nid(), version.text(), version.word(), member, ordinal, ordinal,
                        version.stamp(), null, null, null, version, null, List.of(), null, List.of()));
            }
        }
        for (StoredReference reference : assembly.references().getOrDefault(line.nid(), List.of())) {
            children.add(new Node(Kind.REFERENCE, reference.nid(), referenceLabel(reference), reference.text() == null ? "" : reference.text(),
                    member, -1, -1, null, null, null, reference, null, null, List.of(), null, List.of()));
        }
        String detail = "";
        Optional<DocumentAssembly.Include> include = assembly.includeOf(line.nid());
        if (include.isPresent()) {
            if (include.get().resolved()) {
                children.add(document(include.get().child()));
            } else {
                detail = include.get().note();
            }
        }
        int last = ordinal;
        for (Node child : children) {
            if (child.last() > last) {
                last = child.last();
            }
        }
        if (index >= 0) {
            detail = (detail.isEmpty() ? "" : detail + "\n") + "line " + (index + 1) + (member.path().isEmpty() ? "" : " of " + member.fileName());
        }
        return new Node(Kind.LINE, line.nid(), line.text(), detail, member, ordinal, ordinal < 0 ? -1 : last, line.introduced(), null, line, null,
                null, null, List.of(), null, List.copyOf(children));
    }

    /** A retired line's node: its last text, the retiring commit as its STAMP, spanning no line of the document as it stands. */
    private Node retired(DocumentAssembly.Member member, DocumentHistory.RetiredLine retired) {
        return new Node(Kind.RETIRED, retired.nid(), retired.text(), "retired", member, -1, -1,
                retired.retired() != null ? retired.retired() : retired.introduced(), null, null, null, null, retired, List.of(), null, List.of());
    }

    /** The commits that touched the lines beneath: every version's and retired line's STAMP, once each, oldest first. */
    private List<StampEntity<?>> commits(List<Node> children) {
        if (histories == null) {
            return List.of();
        }
        Map<Long, StampEntity<?>> byNid = new LinkedHashMap<>();
        collectCommits(children, byNid);
        List<StampEntity<?>> commits = new ArrayList<>(byNid.values());
        commits.sort(Comparator.comparingLong(StampEntity::time));
        return List.copyOf(commits);
    }

    private static void collectCommits(List<Node> nodes, Map<Long, StampEntity<?>> into) {
        for (Node node : nodes) {
            if (node.kind() == Kind.DOCUMENT) {
                continue;                                         // an included document keeps its own commits
            }
            if ((node.kind() == Kind.VERSION || node.kind() == Kind.RETIRED) && node.stamp() != null) {
                into.putIfAbsent(node.stamp().nid(), node.stamp());
            }
            if (node.kind() == Kind.RETIRED && node.retired().introduced() != null) {
                into.putIfAbsent(node.retired().introduced().nid(), node.retired().introduced());
            }
            collectCommits(node.children(), into);
        }
    }

    /** A block's label: its kind, its title where it has one, else the start of its first line. */
    static String label(StoredBlock block, List<Node> children) {
        String kind = kindName(block.kind());
        if (block.kind() == Block.Kind.SECTION && !block.title().isEmpty()) {
            return block.title();
        }
        if (!block.title().isEmpty()) {
            return kind + ": " + block.title();
        }
        Optional<Node> firstLine = children.stream().filter(n -> n.kind() == Kind.LINE).findFirst();
        if (firstLine.isPresent()) {
            String text = firstLine.get().line().text().strip();
            if (block.kind() == Block.Kind.SECTION) {
                text = text.replaceFirst("^=+\\s*", "");
            }
            if (!text.isEmpty()) {
                return kind + ": " + abbreviate(text);
            }
        }
        return block.parameters().isEmpty() ? kind : kind + " " + block.parameters();
    }

    /** A reference's label: its kind and target, as written. */
    static String referenceLabel(StoredReference reference) {
        String kind = reference.kind() == null ? "reference" : reference.kind().name().toLowerCase().replace('_', ' ');
        return kind + " → " + reference.target();
    }

    /** The kind in words. */
    static String kindName(Block.Kind kind) {
        return switch (kind) {
            case DOCUMENT -> "Document";
            case SECTION -> "Section";
            case PARAGRAPH -> "Paragraph";
            case LISTING -> "Listing";
            case LITERAL -> "Literal";
            case EXAMPLE -> "Example";
            case QUOTE -> "Quote";
            case SIDEBAR -> "Sidebar";
            case OPEN -> "Open block";
            case ADMONITION -> "Admonition";
            case TABLE -> "Table";
            case TABLE_ROW -> "Row";
            case TABLE_CELL -> "Cell";
            case LIST -> "List";
            case LIST_ITEM -> "Item";
            case DIAGRAM -> "Diagram";
            case IMAGE -> "Image";
            case BIBLIOGRAPHY_ENTRY -> "Bibliography entry";
            case OPAQUE -> "Opaque";
        };
    }

    private static String abbreviate(String text) {
        return text.length() <= LABEL_CHARS ? text : text.substring(0, LABEL_CHARS - 1).stripTrailing() + "…";
    }

    private static int min(List<Node> children) {
        int min = -1;
        for (Node child : children) {
            if (child.first() >= 0 && (min < 0 || child.first() < min)) {
                min = child.first();
            }
        }
        return min;
    }

    private static int max(List<Node> children) {
        int max = -1;
        for (Node child : children) {
            if (child.last() > max) {
                max = child.last();
            }
        }
        return max;
    }
}
