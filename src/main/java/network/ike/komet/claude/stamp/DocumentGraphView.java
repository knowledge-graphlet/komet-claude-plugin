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
import dev.ikm.komet.framework.controls.KonceptKindResolver;
import dev.ikm.komet.framework.dnd.DragRegistry;
import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.komet.framework.dnd.KonceptDragGlyph;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.animation.PauseTransition;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;
import javafx.util.Duration;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import network.ike.komet.claude.stamp.DocumentGraph.Kind;
import network.ike.komet.claude.stamp.DocumentSurfaceView.Layer;
import network.ike.komet.claude.stamp.StoredNodeFacts.NodeFacts;
import network.ike.komet.claude.stamp.StoredNodeFacts.Property;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Graph view of a stamped document (ike-lab-documents topics
 * {@code dev-stamped-document-surface} and {@code kom-substrate-property-graph};
 * IKE-Network/ike-issues#1307): a {@link TreeView} over the {@link DocumentGraph} of the shown
 * assembly, drawn by the property graph's convention. Every row is one of three things:
 *
 * <ul>
 * <li>a <em>node</em>: its kind sigil and identicon first, as the component badge draws a
 * component ({@code dev-component-badge}: a concept bare, a semantic marked {@code S}), then
 * its meaning as the store names it, the pattern's meaning concept, refined by its first
 * component-valued property, then a grey preview of its first string property, then the
 * Provenance layer's marginalia;</li>
 * <li>a <em>property</em> of the node above it: the field's meaning as the key, the value;</li>
 * <li>an <em>edge</em> from the node above it: upward, the component it references and the
 * block it is a member of, each named; downward, its children are the nodes nested beneath,
 * each tagged with the edge it hangs from — member, reference, included document, version.</li>
 * </ul>
 *
 * <p>A node's properties and upward edges are read from the store through the {@link Host},
 * generically from the pattern's field definitions, so the rows say what the store holds and
 * nothing the view made up; where the store cannot answer, the row shows the guardrail's
 * {@code ?} sigil and the graph's own label. Sections and documents open expanded, blocks and
 * lines collapsed, so the first screen is the outline.
 *
 * <p>The selection is the document's: selecting rows selects the lines their nodes span on
 * every other view, a property or edge row standing for its node; a selection made on another
 * view is shown here, ancestors expanded. The context menu offers the acts of the selection
 * and copies a node's public id; a double click on a block or a line opens the block for
 * editing in the Reading view. In {@link Mode#HISTORY History mode} a line's versions and the
 * lines later commits retired join the tree as nodes, and a block's marginalia carries the
 * strip of commits that touched it. In {@link Mode#DOCBOOK DocBook mode} the tree is
 * {@link DocumentGraph#docbook the DocBook projection}: elements and their attributes as a
 * DocBook editor's structure view shows them, a paragraph's lines joined into text and inline
 * elements; a node with no entity of its own selects the lines it came from.
 */
final class DocumentGraphView {

    /**
     * Latest: the document as it stands. History: with its versions, retired lines and commits
     * folded in. DocBook: as it stands, as elements, markup folded into them and lines into text.
     */
    enum Mode { LATEST, HISTORY, DOCBOOK }

    /** What the graph asks of its card: the selection, the layer, the store's facts, and the acts on a selection. */
    interface Host {
        ViewCalculator view();

        CommitSummaries summaries();

        /** @return what the store says the node is, or empty where it cannot say */
        Optional<NodeFacts> facts(DocumentGraph.Node node);

        /** @return a member's history from the store, {@link DocumentHistory#NONE} when it has none */
        DocumentHistory history(DocumentAssembly.Member member);

        /** Highlights every run a commit introduced on the other views; {@code 0} clears. */
        void highlightCommit(long stampNid);

        /** Says something in the status line. */
        void status(String text);

        /** @return the STAMP's identicon, from the card's cache */
        Image identicon(StampEntity<?> stamp);

        /** @return whether the layer's toggle is on */
        boolean layer(Layer layer);

        /** @return the document's selection, if any */
        Optional<LineSelection> selection();

        /** Makes the lines the document's selection. */
        void select(LineSelection selection);

        /** Clears the document's selection. */
        void clearSelection();

        /** Prepares an annotation of the current selection and asks the person to confirm it. */
        void annotate(AnnotationKind kind);

        /** Shows the history of the selected lines. */
        void showHistory();

        /** Opens the block the selection starts in for editing, in the Reading view. */
        void editSelectedBlock();

        /**
         * Asks for a sentence's new text and edits it (IKE-Network/ike-issues#1313).
         *
         * @param sentence the sentence's vertex nid
         * @param text     its text as stored
         */
        void editSentence(long sentence, String text);

        /** The member of the assembly the person is looking at. */
        void showingMember(DocumentAssembly.Member member);
    }

    /** One row of the tree: a node, one of its properties, or one of its upward edges. */
    sealed interface Row permits NodeRow, PropertyRow, EdgeRow {
        /** @return the node the row belongs to */
        DocumentGraph.Node node();
    }

    /**
     * A node, tagged with the edge it hangs from its parent by.
     *
     * @param node  the node
     * @param edge  the downward edge's name: member, reference, included document, version, retired member; empty for the root
     * @param facts what the store says it is, {@code null} where it cannot say
     */
    record NodeRow(DocumentGraph.Node node, String edge, NodeFacts facts) implements Row { }

    /** One property of the node above: key and value as the store keys them. */
    record PropertyRow(DocumentGraph.Node node, Property property) implements Row { }

    /** One upward edge of the node above: its name and the component it leads to. */
    record EdgeRow(DocumentGraph.Node node, String name, String target, long targetNid) implements Row { }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final Font SMALL = Font.font(11);
    private static final Font SIGIL = Font.font("System", FontWeight.BOLD, 12);
    private static final double IDENTICON = 14;
    /** How many commits a block's strip shows before it counts the rest. */
    private static final int STRIP = 12;
    /** How many characters of a property the node's row previews. */
    private static final int PREVIEW = 96;
    /** How many lines of a verbatim body a row shows before it says how many more there are. */
    static final int VERBATIM_LINES = 16;

    private final Host host;
    private final TreeView<Row> tree = new TreeView<>();
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem editItem = new MenuItem("Edit block");
    private final MenuItem editSentenceItem = new MenuItem("Edit sentence…");
    private final MenuItem copyId = new MenuItem("Copy public id");
    private final Popup popover = new Popup();
    private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(350));
    /** Every node row in document order, the lines' by their surface ordinal. */
    private final List<TreeItem<Row>> nodeItems = new ArrayList<>();
    /** For each surface ordinal, the deepest node rows that span it: the lines' rows, or in DocBook mode the text and elements made of it. */
    private final Map<Integer, List<TreeItem<Row>>> lineItems = new HashMap<>();
    private final Map<String, Image> identicons = new HashMap<>();
    private DocumentGraph.Node root;
    private DocumentAssembly assembly;
    private Mode mode = Mode.LATEST;
    private boolean applying;
    private DocumentGraph.Node hovered;
    private double[] hoverAt;

    DocumentGraphView(Host host) {
        this.host = host;
        tree.setId("stampedDocumentGraph");
        tree.getStyleClass().add("stamped-document-graph");
        tree.setShowRoot(true);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(tv -> new GraphCell());
        tree.getSelectionModel().getSelectedItems().addListener((ListChangeListener<TreeItem<Row>>) c -> selectionMade());
        tree.setContextMenu(menu);
        menu.setOnShowing(e -> {
            hidePopover();
            editItem.setDisable(editableSelection().isEmpty());
            editSentenceItem.setDisable(selectedSentence().isEmpty());
            copyId.setDisable(selectedFacts().isEmpty());
        });
        popover.setAutoHide(false);
        popover.setAutoFix(true);
        hoverDelay.setOnFinished(e -> showPopover());
        buildMenu();
    }

    /** @return the node the card hosts */
    Node node() {
        return tree;
    }

    /** @return the tree, for the card's automation and tests */
    TreeView<Row> tree() {
        return tree;
    }

    /** @return the graph's root, once an assembly is shown */
    Optional<DocumentGraph.Node> root() {
        return Optional.ofNullable(root);
    }

    /** @return the mode shown */
    Mode mode() {
        return mode;
    }

    /** Whether DocBook mode shows stored sentences as nodes. */
    private boolean sentences = true;

    /**
     * Shows stored sentences as nodes of their own in DocBook mode, or flattens them into their
     * paragraph's mixed content (IKE-Network/ike-issues#1313).
     *
     * @param shown whether a sentence is a node
     */
    void setSentences(boolean shown) {
        if (shown == sentences) {
            return;
        }
        sentences = shown;
        if (assembly != null && mode == Mode.DOCBOOK) {
            show(assembly);
        }
    }

    /** Switches between the document as it stands and its history; the tree is rebuilt and the selection shown again. */
    void setMode(Mode wanted) {
        if (wanted == mode) {
            return;
        }
        mode = wanted;
        if (assembly != null) {
            show(assembly);
        }
    }

    /**
     * Shows an assembly: its graph, sections expanded, the document's selection reflected.
     *
     * @param assembly the assembly, or {@code null} to show nothing
     */
    void show(DocumentAssembly assembly) {
        hidePopover();
        nodeItems.clear();
        lineItems.clear();
        this.assembly = assembly;
        applying = true;
        try {
            tree.getSelectionModel().clearSelection();
            host.highlightCommit(0);
            if (assembly == null) {
                root = null;
                tree.setRoot(null);
                return;
            }
            root = switch (mode) {
                case LATEST -> DocumentGraph.of(assembly);
                case HISTORY -> DocumentGraph.of(assembly, host::history);
                case DOCBOOK -> DocumentGraph.docbook(assembly, sentences);
            };
            tree.setRoot(item(root, "", null));
        } finally {
            applying = false;
        }
        showSelection();
    }

    /**
     * Builds a node's item: its property rows, its upward edges, then its children as node
     * rows tagged with the edge they hang from. Sections and documents open expanded.
     */
    private TreeItem<Row> item(DocumentGraph.Node node, String edge, DocumentGraph.Node parent) {
        NodeFacts facts = node.nid() == 0 ? null : host.facts(node).orElse(null);
        TreeItem<Row> item = new TreeItem<>(new NodeRow(node, edge, facts));
        nodeItems.add(item);
        if (parent == null || parent.kind() != Kind.INLINE) {
            for (int ordinal : ownOrdinals(node)) {
                lineItems.computeIfAbsent(ordinal, k -> new ArrayList<>()).add(item);
            }
        }
        if (mode == Mode.DOCBOOK) {
            // An element's rows are its attributes, as a structure view shows them; its content follows as nodes.
            if (node.markup() != null) {
                node.markup().attributes().forEach((key, value) -> item.getChildren().add(new TreeItem<>(new PropertyRow(node, new Property(key, value, 0)))));
            }
            for (DocumentGraph.Node child : node.children()) {
                item.getChildren().add(item(child, "", node));
            }
            item.setExpanded(node.kind() == Kind.DOCUMENT || node.kind() == Kind.BLOCK);
            return item;
        }
        if (facts != null) {
            for (Property property : facts.properties()) {
                item.getChildren().add(new TreeItem<>(new PropertyRow(node, property)));
            }
            if (facts.referencedNid() != 0) {
                item.getChildren().add(new TreeItem<>(new EdgeRow(node, "referenced component", facts.referencedName(), facts.referencedNid())));
            }
        } else if (node.kind() == Kind.VERSION) {
            item.getChildren().add(new TreeItem<>(new PropertyRow(node, new Property(versionKey(parent), node.version().text(), 0))));
        }
        if (parent != null && parent.block() != null && node.kind() != Kind.VERSION) {
            item.getChildren().add(new TreeItem<>(new EdgeRow(node, "member of", meaningOf(parent), parent.nid())));
        }
        for (DocumentGraph.Node child : node.children()) {
            item.getChildren().add(item(child, edgeTo(child), node));
        }
        item.setExpanded(node.structural());
        return item;
    }

    /**
     * The ordinals a node is the deepest row for, which a selection made on another view selects
     * it by: a line's own; an inline element's every one it spans, its content never; a text
     * run's, a title's or cell's, every one it spans that no child does; a block's or document's
     * only the markup lines the DocBook projection folded into it. A version is never one: its
     * line is.
     */
    private List<Integer> ownOrdinals(DocumentGraph.Node node) {
        List<Integer> ordinals = new ArrayList<>();
        if (node.kind() == Kind.VERSION || node.first() < 0) {
            return ordinals;
        }
        if (node.kind() == Kind.BLOCK || node.kind() == Kind.DOCUMENT) {
            if (node.markup() == null) {
                return ordinals;
            }
            for (GitDocumentMaterializer.StoredLine line : node.markup().lines()) {
                int ordinal = assembly.ordinal(line.nid());
                if (ordinal >= 0 && !line.text().isBlank() && node.children().stream().noneMatch(c -> c.spans(ordinal))) {
                    ordinals.add(ordinal);
                }
            }
            return ordinals;
        }
        for (int o = node.first(); o <= node.last(); o++) {
            int ordinal = o;
            if (node.kind() == Kind.INLINE || node.children().stream().noneMatch(c -> c.kind() != Kind.VERSION && c.spans(ordinal))) {
                ordinals.add(o);
            }
        }
        return ordinals;
    }

    /** The key a version's text sits under: the line's first string property, as the store keys it. */
    private String versionKey(DocumentGraph.Node line) {
        if (line != null && line.nid() != 0) {
            Optional<NodeFacts> facts = host.facts(line);
            if (facts.isPresent() && facts.get().primary().isPresent()) {
                return facts.get().primary().get().key();
            }
        }
        return "text";
    }

    /** The downward edge a child hangs from its parent by. */
    private static String edgeTo(DocumentGraph.Node child) {
        return switch (child.kind()) {
            case DOCUMENT -> "included document";
            case BLOCK, LINE -> "member";
            case REFERENCE -> "reference";
            case VERSION -> "version";
            case RETIRED -> "retired member";
            case ELEMENT, INLINE, TEXT -> "content";
        };
    }

    /** A node's meaning as the store names it, or the graph's label where the store cannot say. */
    private String meaningOf(DocumentGraph.Node node) {
        if (node.nid() != 0) {
            Optional<NodeFacts> facts = host.facts(node);
            if (facts.isPresent()) {
                NodeFacts f = facts.get();
                return f.refinement().map(r -> f.meaning() + " · " + r.value()).orElse(f.meaning());
            }
        }
        return node.label();
    }

    /** Redraws the rows after a layer toggle. */
    void relayer() {
        tree.refresh();
    }

    /**
     * Shows the document's selection: the one node spanning exactly its lines where there is
     * one, else the deepest rows of every line it spans (in DocBook mode the text and inline
     * elements on a line, or the block a folded markup line belongs to), ancestors expanded, the
     * first scrolled into view.
     */
    void showSelection() {
        if (applying || root == null) {
            return;
        }
        Optional<LineSelection> selection = host.selection();
        applying = true;
        try {
            tree.getSelectionModel().clearSelection();
            if (selection.isEmpty()) {
                return;
            }
            int min = selection.get().min();
            int max = selection.get().max();
            TreeItem<Row> exact = null;
            for (TreeItem<Row> item : nodeItems) {
                DocumentGraph.Node node = item.getValue().node();
                if (node.kind() != Kind.VERSION && node.kind() != Kind.TEXT && node.kind() != Kind.INLINE && node.first() == min && node.last() == max) {
                    exact = item;
                    break;
                }
            }
            if (exact != null) {
                expandAncestors(exact);
                tree.getSelectionModel().select(exact);
                tree.scrollTo(tree.getRow(exact));
                return;
            }
            TreeItem<Row> first = null;
            for (int o = min; o <= max; o++) {
                for (TreeItem<Row> item : lineItems.getOrDefault(o, List.of())) {
                    expandAncestors(item);
                    tree.getSelectionModel().select(item);
                    if (first == null) {
                        first = item;
                    }
                }
            }
            if (first != null) {
                tree.scrollTo(tree.getRow(first));
            }
        } finally {
            applying = false;
        }
    }

    private static void expandAncestors(TreeItem<Row> item) {
        for (TreeItem<Row> parent = item.getParent(); parent != null; parent = parent.getParent()) {
            parent.setExpanded(true);
        }
    }

    /**
     * The rows selected by a gesture become the document's selection: the lines their nodes
     * span, as one range, a property or edge row standing for its node. One selected version
     * or retired line also highlights its commit's runs on the other views; a retired line
     * alone leaves the document's selection as it was, since the document as it stands does
     * not hold it.
     */
    private void selectionMade() {
        if (applying) {
            return;
        }
        int min = -1;
        int max = -1;
        DocumentAssembly.Member member = null;
        List<TreeItem<Row>> selected = tree.getSelectionModel().getSelectedItems();
        DocumentGraph.Node only = selected.size() == 1 && selected.getFirst() != null ? selected.getFirst().getValue().node() : null;
        boolean retiredOnly = false;
        for (TreeItem<Row> item : selected) {
            if (item == null) {
                continue;
            }
            DocumentGraph.Node node = item.getValue().node();
            if (member == null) {
                member = node.member();
            }
            if (node.kind() == Kind.RETIRED) {
                retiredOnly = true;
            }
            if (!node.spansLines()) {
                continue;
            }
            retiredOnly = false;
            if (min < 0 || node.first() < min) {
                min = node.first();
            }
            if (node.last() > max) {
                max = node.last();
            }
        }
        applying = true;
        try {
            if (min < 0) {
                if (!retiredOnly && host.selection().isPresent()) {
                    host.clearSelection();
                }
            } else {
                host.select(new LineSelection(min, max));
            }
            if (only != null && (only.kind() == Kind.VERSION || only.kind() == Kind.RETIRED) && only.stamp() != null) {
                host.highlightCommit(only.stamp().nid());
                if (only.kind() == Kind.RETIRED) {
                    host.status("Retired " + when(only.stamp()) + ": the document as it stands no longer holds this line.");
                } else {
                    host.status(only.version().word().substring(0, 1).toUpperCase() + only.version().word().substring(1) + " " + when(only.stamp())
                            + host.summaries().summary(only.stamp().nid()).map(s -> " — " + s).orElse(""));
                }
            } else {
                host.highlightCommit(0);
            }
        } finally {
            applying = false;
        }
        if (member != null) {
            host.showingMember(member);
        }
    }

    private String when(StampEntity<?> stamp) {
        String by = host.view() == null ? "" : " by " + StampNames.name(host.view(), stamp.authorNid());
        return stamp.time() == Long.MAX_VALUE ? "in Komet, uncommitted" + by : "on " + DAY.format(Instant.ofEpochMilli(stamp.time())) + by;
    }

    /** The selected node a block edit can start from: a line of a block, or a block, with lines. */
    /** The selected node when it is a stored sentence of DocBook mode. */
    Optional<DocumentGraph.Node> selectedSentence() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null || !(item.getValue() instanceof NodeRow row)) {
            return Optional.empty();
        }
        DocumentGraph.Node node = row.node();
        return node.nid() != 0 && node.markup() != null && "Sentence".equals(node.markup().element()) ? Optional.of(node) : Optional.empty();
    }

    private Optional<DocumentGraph.Node> editableSelection() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return Optional.empty();
        }
        DocumentGraph.Node node = item.getValue().node();
        return node.spansLines() && node.kind() != Kind.DOCUMENT && node.kind() != Kind.VERSION ? Optional.of(node) : Optional.empty();
    }

    /** The facts of the selected row's node, when the store has them. */
    private Optional<NodeFacts> selectedFacts() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return Optional.empty();
        }
        for (TreeItem<Row> at = item; at != null; at = at.getParent()) {
            if (at.getValue() instanceof NodeRow row && row.facts() != null) {
                return Optional.of(row.facts());
            }
        }
        return Optional.empty();
    }

    private void buildMenu() {
        menu.setId("stampedDocumentGraphContextMenu");
        MenuItem affirm = new MenuItem("Affirm…");
        affirm.setId("stampedDocumentGraphAffirm");
        affirm.setOnAction(e -> host.annotate(AnnotationKind.AFFIRMATION));
        MenuItem refute = new MenuItem("Refute…");
        refute.setId("stampedDocumentGraphRefute");
        refute.setOnAction(e -> host.annotate(AnnotationKind.REFUTATION));
        MenuItem comment = new MenuItem("Comment…");
        comment.setId("stampedDocumentGraphComment");
        comment.setOnAction(e -> host.annotate(AnnotationKind.COMMENT));
        editItem.setId("stampedDocumentGraphEditBlock");
        editItem.setOnAction(e -> host.editSelectedBlock());
        editSentenceItem.setId("stampedDocumentGraphEditSentence");
        editSentenceItem.setOnAction(e -> selectedSentence().ifPresent(n -> host.editSentence(n.nid(), n.markup().text())));
        MenuItem history = new MenuItem("Show history");
        history.setId("stampedDocumentGraphShowHistory");
        history.setOnAction(e -> host.showHistory());
        copyId.setId("stampedDocumentGraphCopyId");
        copyId.setOnAction(e -> selectedFacts().ifPresent(facts -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(facts.publicId().idString());
            Clipboard.getSystemClipboard().setContent(content);
            host.status("Copied " + facts.publicId().idString());
        }));
        menu.getItems().addAll(affirm, refute, comment, new SeparatorMenuItem(), editItem, editSentenceItem, history, new SeparatorMenuItem(), copyId);
    }

    // ── provenance on hover ───────────────────────────────────────────────────

    private void hover(Row row, MouseEvent e) {
        if (!(row instanceof NodeRow nodeRow) || provenanceLine(nodeRow.node()) == null) {
            hidePopover();
            return;
        }
        if (hovered != nodeRow.node()) {
            hidePopover();
            hovered = nodeRow.node();
            hoverAt = new double[] {e.getScreenX(), e.getScreenY()};
            hoverDelay.playFromStart();
        }
    }

    private void showPopover() {
        if (hovered == null || tree.getScene() == null || tree.getScene().getWindow() == null) {
            return;
        }
        Node content = ProvenancePopover.content(provenanceLine(hovered), host.view(), host.summaries(), host::identicon, null);
        content.setId("stampedDocumentProvenancePopover");
        popover.getContent().setAll(content);
        popover.show(tree.getScene().getWindow(), hoverAt[0] + 14, hoverAt[1] + 18);
    }

    /** The line whose provenance a node's popover shows: a line node's own, a text run's or inline element's first; none for a block. */
    private static GitDocumentMaterializer.StoredLine provenanceLine(DocumentGraph.Node node) {
        if (node.line() != null) {
            return node.line();
        }
        if ((node.kind() == Kind.TEXT || node.kind() == Kind.INLINE) && !node.markup().lines().isEmpty()) {
            return node.markup().lines().getFirst();
        }
        return null;
    }

    private void hidePopover() {
        hoverDelay.stop();
        hovered = null;
        if (popover.isShowing()) {
            popover.hide();
        }
    }

    /** A component's identicon from its public id, rendered once per id. */
    private Image identicon(PublicId id) {
        return identicons.computeIfAbsent(id.idString(), key -> new Image(new ByteArrayInputStream(
                KonceptIdenticon.png(PublicIds.of(id.asUuidArray()[0]).idString(), KonceptIdenticon.DISPLAY_MODULE_SIZE))));
    }

    /**
     * A verbatim body's text as a row shows it: its lines as written, blank ones included, the
     * first {@value #VERBATIM_LINES} of them and then how many more there are.
     */
    static String verbatim(String body) {
        List<String> lines = body.lines().toList();
        if (lines.size() <= VERBATIM_LINES) {
            return String.join("\n", lines);
        }
        int more = lines.size() - VERBATIM_LINES;
        return String.join("\n", lines.subList(0, VERBATIM_LINES)) + "\n… " + more + (more == 1 ? " more line" : " more lines");
    }

    private static String abbreviate(String text) {
        String one = text.replace('\n', ' ');
        return one.length() <= PREVIEW ? one : one.substring(0, PREVIEW - 1).stripTrailing() + "…";
    }

    // ── the cell ──────────────────────────────────────────────────────────────

    /**
     * One row. A node row: the kind sigil and identicon, the meaning, the preview, the
     * marginalia; a property row: key and value; an edge row: arrow, name and target. A node
     * with an entity behind it drags as that entity, as the pattern navigator's rows do.
     */
    private final class GraphCell extends TreeCell<Row> {

        GraphCell() {
            getStyleClass().add("stamped-document-graph-cell");
            setOnDragDetected(this::dragDetected);
            addEventHandler(MouseEvent.MOUSE_MOVED, e -> hover(getItem(), e));
            addEventHandler(MouseEvent.MOUSE_EXITED, e -> hidePopover());
            addEventHandler(MouseEvent.MOUSE_PRESSED, e -> hidePopover());
            addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    DocumentGraph.Node node = getItem().node();
                    if (node.spansLines() && node.kind() != Kind.DOCUMENT && node.kind() != Kind.VERSION) {
                        e.consume();
                        host.editSelectedBlock();
                    }
                }
            });
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            getStyleClass().removeAll("node", "element", "property", "edge", "retired", "version");
            setText(null);
            setGraphic(null);
            setTooltip(null);
            setStyle("");
            setUnderline(false);
            if (empty || row == null) {
                return;
            }
            switch (row) {
                case NodeRow node -> nodeRow(node);
                case PropertyRow property -> propertyRow(property);
                case EdgeRow edge -> edgeRow(edge);
            }
        }

        private void nodeRow(NodeRow row) {
            DocumentGraph.Node node = row.node();
            NodeFacts facts = row.facts();
            if (node.markup() != null) {
                elementRow(node, facts);
                return;
            }
            getStyleClass().add("node");
            if (node.kind() == Kind.RETIRED) {
                getStyleClass().add("retired");
            } else if (node.kind() == Kind.VERSION) {
                getStyleClass().add("version");
            }
            HBox box = new HBox(5);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (!row.edge().isEmpty()) {
                box.getChildren().add(caption(row.edge(), "#8a8782"));
            }
            // What the node is: the kind sigil and the identicon of its public id, or the guardrail's ? where the store cannot say.
            String sigil = facts == null ? "?" : facts.sigil();
            if (!sigil.isEmpty()) {
                Label mark = new Label(sigil);
                mark.setFont(SIGIL);
                mark.setStyle("-fx-text-fill: " + (facts == null ? "#c62828" : "#34465f") + ";");
                box.getChildren().add(mark);
            }
            if (facts != null) {
                ImageView badge = new ImageView(identicon(facts.publicId()));
                badge.setPreserveRatio(true);
                badge.setFitHeight(IDENTICON);
                box.getChildren().add(badge);
            }
            // Its meaning, refined by its first component-valued property; then a preview of its first string property.
            Label meaning = new Label(facts == null ? node.label() : facts.meaning());
            meaning.setFont(Font.font("System", node.structural() ? FontWeight.BOLD : FontWeight.NORMAL, 13));
            if (node.kind() == Kind.RETIRED) {
                meaning.setStyle("-fx-text-fill: #8a8782;");
            }
            box.getChildren().add(meaning);
            if (facts != null) {
                facts.refinement().ifPresent(r -> box.getChildren().add(caption("· " + r.value(), "#34465f")));
                Optional<Property> primary = facts.primary();
                if (node.kind() == Kind.VERSION) {
                    box.getChildren().add(preview(node.version().text()));
                } else if (primary.isPresent()) {
                    box.getChildren().add(preview(primary.get().value()));
                }
            } else if (node.kind() == Kind.VERSION) {
                box.getChildren().add(preview(node.version().text()));
            } else if (node.kind() == Kind.LINE || node.kind() == Kind.RETIRED) {
                box.getChildren().add(preview(node.label()));
                meaning.setText(node.kind() == Kind.RETIRED ? "retired line" : "line");
            }
            Node marginalia = marginalia(node);
            if (marginalia != null) {
                box.getChildren().add(marginalia);
            }
            setGraphic(box);
            setTooltip(tooltip(node, facts));
            boolean queue = host.layer(Layer.REVIEW_QUEUE) && node.line() != null && DocumentSurfaceView.queued(node.line());
            setUnderline(queue);
            StringBuilder style = new StringBuilder();
            if (host.layer(Layer.TINTS) && node.stamp() != null && node.stamp().time() != Long.MAX_VALUE && node.kind() == Kind.LINE) {
                Color tint = DocumentSurfaceView.tint(node.stamp());
                style.append(String.format("-fx-background-color: rgba(%d, %d, %d, 0.6);",
                        (int) (tint.getRed() * 255), (int) (tint.getGreen() * 255), (int) (tint.getBlue() * 255)));
            }
            if (node.uncommitted()) {
                style.append("-fx-font-style: italic;");
            }
            setStyle(style.toString());
        }

        /**
         * A row of the DocBook projection: an element by name, its text previewed, as a structure
         * view shows it; a block or document keeps its sigil and identicon, the vertex it is; a text
         * run is its text; an inline element without text shows where it points.
         */
        private void elementRow(DocumentGraph.Node node, NodeFacts facts) {
            getStyleClass().addAll("node", "element");
            DocumentGraph.Markup markup = node.markup();
            HBox box = new HBox(5);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (facts != null) {
                if (!facts.sigil().isEmpty()) {
                    Label mark = new Label(facts.sigil());
                    mark.setFont(SIGIL);
                    mark.setStyle("-fx-text-fill: #34465f;");
                    box.getChildren().add(mark);
                }
                ImageView badge = new ImageView(identicon(facts.publicId()));
                badge.setPreserveRatio(true);
                badge.setFitHeight(IDENTICON);
                box.getChildren().add(badge);
            }
            if (node.kind() == Kind.TEXT && markup.text().indexOf('\n') >= 0) {
                // A verbatim body, the only text the projection keeps line breaks in: shown as its lines,
                // as a DocBook editor shows a program listing.
                Label text = new Label(verbatim(markup.text()));
                text.setFont(Font.font("Menlo", 12));
                text.setStyle("-fx-text-fill: #2f2f2f;");
                text.setWrapText(false);
                box.getChildren().add(text);
            } else if (node.kind() == Kind.TEXT) {
                Label text = new Label(abbreviate(markup.text().strip()));
                text.setFont(Font.font("System", 12));
                text.setStyle("-fx-text-fill: #2f2f2f;");
                box.getChildren().add(text);
            } else {
                Label name = new Label(markup.element());
                boolean inline = node.kind() == Kind.INLINE;
                name.setFont(Font.font("System", node.structural() ? FontWeight.BOLD : FontWeight.NORMAL, inline ? 12 : 13));
                name.setStyle("-fx-text-fill: " + (inline ? "#6a4c93" : "#1f3a5f") + ";");
                box.getChildren().add(name);
                if (!markup.text().isBlank()) {
                    box.getChildren().add(preview(markup.text()));
                } else if (!markup.attributes().isEmpty()) {
                    box.getChildren().add(caption("→ " + markup.attributes().values().iterator().next(), "#34465f"));
                }
            }
            Node marginalia = marginalia(node);
            if (marginalia != null) {
                box.getChildren().add(marginalia);
            }
            setGraphic(box);
            setTooltip(tooltip(node, facts));
            StringBuilder style = new StringBuilder();
            if (host.layer(Layer.TINTS) && node.stamp() != null && node.stamp().time() != Long.MAX_VALUE
                    && (node.kind() == Kind.TEXT || node.kind() == Kind.INLINE)) {
                Color tint = DocumentSurfaceView.tint(node.stamp());
                style.append(String.format("-fx-background-color: rgba(%d, %d, %d, 0.6);",
                        (int) (tint.getRed() * 255), (int) (tint.getGreen() * 255), (int) (tint.getBlue() * 255)));
            }
            if (node.uncommitted()) {
                style.append("-fx-font-style: italic;");
            }
            setStyle(style.toString());
        }

        private void propertyRow(PropertyRow row) {
            getStyleClass().add("property");
            HBox box = new HBox(6);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            box.getChildren().add(caption(row.property().key(), "#5f6b7a"));
            Label value = new Label(row.property().value());
            value.setFont(Font.font(row.property().targetNid() != 0 ? "System" : "Menlo", 12));
            box.getChildren().add(value);
            setGraphic(box);
            if (row.property().value().length() > PREVIEW) {
                setTooltip(new Tooltip(row.property().value()));
            }
        }

        private void edgeRow(EdgeRow row) {
            getStyleClass().add("edge");
            HBox box = new HBox(6);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            box.getChildren().add(caption("↑ " + row.name(), "#5f6b7a"));
            Label target = new Label(row.target());
            target.setFont(Font.font(12));
            box.getChildren().add(target);
            setGraphic(box);
        }

        private Label caption(String text, String color) {
            Label label = new Label(text);
            label.setFont(SMALL);
            label.setStyle("-fx-text-fill: " + color + ";");
            return label;
        }

        private Label preview(String text) {
            Label label = new Label(abbreviate(text));
            label.setFont(Font.font("Menlo", 12));
            label.setStyle("-fx-text-fill: #5f6b7a;");
            return label;
        }

        /**
         * The identicon and date of the row's STAMP, and the line's annotation mark, as the layer
         * draws them; a version's change in a word; a block's strip of commits in History mode.
         */
        private Node marginalia(DocumentGraph.Node node) {
            HBox box = new HBox(4);
            box.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            if (node.kind() == Kind.VERSION || node.kind() == Kind.RETIRED) {
                if (node.stamp() != null) {
                    ImageView badge = new ImageView(host.identicon(node.stamp()));
                    badge.setPreserveRatio(true);
                    badge.setFitHeight(IDENTICON);
                    box.getChildren().add(badge);
                    box.getChildren().add(caption(node.stamp().time() == Long.MAX_VALUE ? "uncommitted" : DAY.format(Instant.ofEpochMilli(node.stamp().time())), "#8a8782"));
                }
                box.getChildren().add(caption(node.kind() == Kind.RETIRED ? "retired" : node.version().word(), changeColor(node)));
                return box;
            }
            if (!node.commits().isEmpty() && node.kind() != Kind.DOCUMENT) {
                HBox strip = new HBox(1);
                strip.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                int shown = 0;
                for (StampEntity<?> commit : node.commits()) {
                    if (shown++ == STRIP) {
                        strip.getChildren().add(caption("+" + (node.commits().size() - STRIP), "#8a8782"));
                        break;
                    }
                    ImageView badge = new ImageView(host.identicon(commit));
                    badge.setPreserveRatio(true);
                    badge.setFitHeight(IDENTICON - 2);
                    Tooltip.install(badge, new Tooltip(when(commit) + host.summaries().summary(commit.nid()).map(s -> "\n" + s).orElse("")));
                    badge.setOnMouseEntered(e -> host.highlightCommit(commit.nid()));
                    badge.setOnMouseExited(e -> host.highlightCommit(0));
                    strip.getChildren().add(badge);
                }
                box.getChildren().add(strip);
            }
            if (host.layer(Layer.MARGINALIA) && node.stamp() != null) {
                ImageView badge = new ImageView(host.identicon(node.stamp()));
                badge.setPreserveRatio(true);
                badge.setFitHeight(IDENTICON);
                box.getChildren().add(badge);
                box.getChildren().add(caption(node.stamp().time() == Long.MAX_VALUE ? "uncommitted" : DAY.format(Instant.ofEpochMilli(node.stamp().time())), "#8a8782"));
            }
            if (host.layer(Layer.ANNOTATION_MARKS) && node.line() != null && !node.line().annotations().isEmpty()) {
                Label mark = new Label(DocumentSurfaceView.mark(node.line()));
                mark.setFont(SMALL);
                mark.setStyle(DocumentSurfaceView.markStyle(node.line()));
                box.getChildren().add(mark);
            } else if (host.layer(Layer.ANNOTATION_MARKS) && !node.annotations().isEmpty()) {
                // a stored sentence's own judgments (IKE-Network/ike-issues#1313), marked as a line's are
                GitDocumentMaterializer.StoredLine judged = new GitDocumentMaterializer.StoredLine(node.nid(), "", null, null, node.annotations());
                Label mark = new Label(DocumentSurfaceView.mark(judged));
                mark.setFont(SMALL);
                mark.setStyle(DocumentSurfaceView.markStyle(judged));
                box.getChildren().add(mark);
            }
            return box.getChildren().isEmpty() ? null : box;
        }

        private String changeColor(DocumentGraph.Node node) {
            DocumentHistory.Change change = node.kind() == Kind.RETIRED ? DocumentHistory.Change.RETIRED : node.version().change();
            return switch (change) {
                case INTRODUCED -> "#2e7d32";
                case RETEXTED -> "#1565c0";
                case REFLOWED -> "#8a8782";
                case RETIRED -> "#c62828";
                case UNCOMMITTED -> "#b26a00";
            };
        }

        private Tooltip tooltip(DocumentGraph.Node node, NodeFacts facts) {
            StringBuilder text = new StringBuilder();
            if (node.markup() != null && node.kind() != Kind.TEXT) {
                text.append(node.markup().element());
                node.markup().attributes().forEach((key, value) -> text.append("\n  ").append(key).append(" = ").append(value));
                if (facts != null) {
                    text.append('\n');
                }
            }
            if (facts != null) {
                text.append(facts.patternName()).append('\n').append(facts.publicId().idString());
            } else if (node.nid() != 0) {
                text.append("not in the store");
            }
            if (!node.detail().isEmpty()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(node.detail());
            }
            if (node.spansLines() && node.kind() != Kind.VERSION) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(node.lineCount()).append(node.lineCount() == 1 ? " line" : " lines");
            }
            if (!node.commits().isEmpty()) {
                text.append('\n').append(node.commits().size()).append(node.commits().size() == 1 ? " commit" : " commits");
            }
            if (node.stamp() != null && host.view() != null) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(StampNames.name(host.view(), node.stamp().authorNid()));
                if (node.stamp().time() != Long.MAX_VALUE) {
                    text.append(" · ").append(DAY.format(Instant.ofEpochMilli(node.stamp().time())));
                }
                host.summaries().summary(node.stamp().nid()).ifPresent(s -> text.append('\n').append(s));
            }
            return text.isEmpty() ? null : new Tooltip(text.toString());
        }

        /**
         * A node with an entity behind it drags as that entity, as Komet's
         * {@code DragDetectedCellEventHandler} drags a pattern navigator's row: the canonical
         * glyph as the drag view, the entity on the clipboard. Done here, not through that
         * handler, because it holds an {@code int} nid and the store's nids are 64-bit. A
         * property or edge row, a reference and a document the store holds no structure for do not drag.
         */
        private void dragDetected(MouseEvent e) {
            if (!(getItem() instanceof NodeRow row)) {
                return;
            }
            DocumentGraph.Node node = row.node();
            if (node.nid() == 0 || (node.kind() != Kind.LINE && node.block() == null)) {
                return;
            }
            Optional<Entity<?>> entity = EntityHandle.get(node.nid()).entity().map(found -> (Entity<?>) found);
            if (entity.isEmpty()) {
                return;
            }
            long nid = entity.get().nid();
            Dragboard db = startDragAndDrop(TransferMode.COPY);
            KonceptDragGlyph.setDragView(db, KonceptKindResolver.resolve(nid, null), PrimitiveData.publicId(nid), StampNames.name(host.view(), nid), false);
            db.setContent(new KometClipboard(entity.get()));
            DragRegistry.dragStart();
            e.consume();
        }
    }
}
