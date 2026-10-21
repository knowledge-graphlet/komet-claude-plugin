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
import design.knowledge.stamp.adoc.model.AnnotationKind;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.StampEntity;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Popup;
import javafx.util.Duration;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.SideDecorator;
import jfx.incubator.scene.control.richtext.model.RichTextModel;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;
import network.ike.komet.claude.doc.DocumentBlock;
import network.ike.komet.claude.doc.DocumentSurface;
import network.ike.komet.claude.koncept.KonceptIdenticon;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredReference;
import network.ike.komet.claude.stamp.LineRunModel.LineRun;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The two views of a stamped document and the Provenance layer over them, on the plugin's
 * Document surface (ike-lab-documents topics {@code dev-stamped-document-surface} and
 * {@code dev-interaction-surface-family}; IKE-Network/ike-issues#1307).
 *
 * <p>The view does not draw its own body: it builds {@link DocumentBlock}s for a
 * {@link DocumentSurface} the card hosts through its {@code DocumentSurfaceArea}, so the
 * surface's one scroll axis, its header and footer regions, its margins, its expansion to the
 * paged preview and its print settings are the stamped document's too. The <em>Reading
 * view</em> is one block per document block — headings, paragraphs, lists, tables, listings,
 * admonitions — each a view-only {@link RichTextArea} over a {@link LineRunModel} with one
 * styled segment per source line; the left margin carries the introducing date, the right
 * margin the identicons and marks (marginalia that share the block's row and scroll with it);
 * the header carries the document's title and the section at the top of the viewport. The
 * <em>Source view</em> is the ledger, one paragraph per line in areas of
 * {@link #LEDGER_CHUNK} lines with the number, identicon and mark as a side decorator, stacked
 * on the same surface with the margins off.
 *
 * <p>What is shown is a {@link DocumentAssembly}: the host document and, in place of each of
 * its include lines that names a document the store holds, that child's blocks — each child
 * its own document with its own lines, STAMPs, annotations and commit summaries, stacked in
 * include order on the one surface. In the Source view the host's ledger lists each include
 * line followed by the child's own ledger, numbered as the child's file is. An include the
 * store cannot answer stays a line, with a note beneath it.
 *
 * <p>Hovering text resolves the position to its run and shows the line's provenance; hovering
 * an identicon in the margin highlights every run that commit introduced. A click selects a
 * line, a drag or shift-click a contiguous range, snapped to whole lines and allowed to cross
 * blocks; the right-click menu offers the acts on the selection, which the {@link Host}
 * performs. A block is edited in place: the same area flips to an editable model, one paragraph
 * per source line, and Save hands the text to the host.
 */
final class DocumentSurfaceView {

    /** What the surface asks of its card. */
    interface Host {
        ViewCalculator view();

        CommitSummaries summaries();

        void status(String text);

        /** Prepares an annotation of the current selection and asks the person to confirm it. */
        void annotate(AnnotationKind kind);

        /** Saves an in-place edit of a block as the person's pending edit. */
        void saveEdit(StoredBlock block, String text);

        /** Shows the history of the selected lines, pinned until closed. */
        void showHistory();

        /** The selection changed by a gesture. */
        void selectionChanged();

        /** The view asks its card to switch views — Edit block from the pages returns to the Reading view. */
        default void viewRequested(View view) { }

        /** The view asks its card to show the page a Page-layout block begins on. */
        default void revealPage(int page, double y) { }

        /** The member of the assembly whose block is at the top of the viewport. */
        void showingMember(DocumentAssembly.Member member);
    }

    /** The toggles of the Provenance layer. */
    enum Layer { MARGINALIA, TINTS, REVIEW_QUEUE, ANNOTATION_MARKS }

    /** The views: three on the surface's areas, and the Graph, a tree the card hosts in their place. */
    enum View { READING, SOURCE, PAGE_LAYOUT, GRAPH }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final Pattern MARKER = Pattern.compile("^\\s*(\\*+|-|\\.+|\\d+\\.)\\s+");
    private static final Color HOVER_HIGHLIGHT = Color.web("#FFE082");
    private static final Font SMALL = Font.font(11);
    /** Lines per ledger area: a content-height {@code RichTextArea} stops growing at the incubator's 10,000 px safeguard. */
    private static final int LEDGER_CHUNK = 200;

    /**
     * The text styles a build draws with: the screen's for the Reading view, a print theme's for
     * the Page layout view, where the body is the bundled Noto Serif at the theme's size and the
     * headings scale as the Typst writer scales them, so the two layouts shape the same glyphs.
     */
    record Styles(StyleAttributeMap prose, StyleAttributeMap mono, StyleAttributeMap monoGrey, StyleAttributeMap smallGrey,
                  StyleAttributeMap title, StyleAttributeMap bold, StyleAttributeMap cell, StyleAttributeMap headerCell, double base) {

        static final Styles SCREEN = new Styles(
                StyleAttributeMap.builder().setFontSize(14).build(),
                StyleAttributeMap.builder().setFontFamily("Menlo").setFontSize(12).build(),
                StyleAttributeMap.builder().setFontFamily("Menlo").setFontSize(12).setTextColor(Color.web("#8a8782")).build(),
                StyleAttributeMap.builder().setFontSize(11).setTextColor(Color.web("#8a8782")).build(),
                StyleAttributeMap.builder().setFontSize(24).setBold(true).build(),
                StyleAttributeMap.builder().setFontSize(14).setBold(true).build(),
                StyleAttributeMap.builder().setFontSize(13).build(),
                StyleAttributeMap.builder().setFontSize(13).setBold(true).build(), 14);

        /** A section heading's style: the screen's by level, or the theme's scale of 1.5, 1.25, 1.1 and 1 as Typst's. */
        StyleAttributeMap heading(int level) {
            if (this == SCREEN) {
                return StyleAttributeMap.builder().setFontSize(Math.max(13, 21 - 2 * level)).setBold(true).build();
            }
            double scale = switch (Math.max(1, level)) {
                case 1 -> 1.5;
                case 2 -> 1.25;
                case 3 -> 1.1;
                default -> 1.0;
            };
            StyleAttributeMap.Builder b = StyleAttributeMap.builder().merge(prose).setFontSize(base * scale).setBold(true)
                    .setTextAlignment(javafx.scene.text.TextAlignment.LEFT);
            if (level >= 4) {
                b.setItalic(true);
            }
            return b.build();
        }

        /** The print theme's styles: Noto Serif body at the theme size, Noto Sans Mono at 0.85 of it, a 1.8 title. */
        static Styles page(network.ike.komet.claude.doc.print.settings.DocumentTheme theme) {
            double base = theme.baseFontSize();
            String family = theme.fontFamily() == null ? null : network.ike.komet.claude.stamp.print.DocumentFonts.SERIF;
            StyleAttributeMap.Builder body = StyleAttributeMap.builder().setFontSize(base);
            if (family != null) {
                body.setFontFamily(family);
            }
            body.setTextAlignment(javafx.scene.text.TextAlignment.JUSTIFY);   // as Typst justifies; headings and cells override it
            StyleAttributeMap prose = body.build();
            StyleAttributeMap mono = StyleAttributeMap.builder().setFontFamily(network.ike.komet.claude.stamp.print.DocumentFonts.MONO).setFontSize(base * 0.85).build();
            return new Styles(prose, mono,
                    StyleAttributeMap.builder().merge(mono).setTextColor(Color.web("#8a8782")).build(),
                    StyleAttributeMap.builder().merge(prose).setFontSize(base * 0.8).setTextColor(Color.web("#8a8782")).build(),
                    StyleAttributeMap.builder().merge(prose).setFontSize(base * 1.8).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(),
                    StyleAttributeMap.builder().merge(prose).setBold(true).setTextAlignment(javafx.scene.text.TextAlignment.LEFT).build(), base);
        }
    }

    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ANCHOR_LINE = Pattern.compile("^\\[\\[[^\\]]*\\]\\]$");
    private static final Pattern BLOCK_TITLE_LINE = Pattern.compile("^\\.([^\\s.].*)$");

    /** The styles of the build in progress: the screen's, or a print theme's while the Page layout is built. */
    private Styles st = Styles.SCREEN;
    /** Where the build in progress adds its areas: the Reading view's, or the Page layout's. */
    private List<Area> target;
    /** Whether the build in progress is the Page layout: no front matter, comments or attribute lines, print spacing. */
    private boolean print;
    /** The index of the top-level block of the print model the build is in, as the Typst writer labels them. */
    private int modelOrdinal;
    /** How deep inside containers the print build is: only blocks outside any are top-level blocks of the model. */
    private int nesting;

    private final Host host;
    private final DocumentSurface surface;
    private final Map<Long, Image> badges = new HashMap<>();
    private final Set<Layer> layers = EnumSet.of(Layer.MARGINALIA, Layer.ANNOTATION_MARKS);
    private final List<Area> areas = new ArrayList<>();
    private final List<Area> sources = new ArrayList<>();
    /** The Page layout's units: one area per paragraph-like piece of the print model, what the layout oracles place. */
    private final List<Area> units = new ArrayList<>();
    /** The Page layout's line areas: one per laid-out line, what the page stack shows. */
    private final List<Area> pages = new ArrayList<>();
    private final Map<Node, Area> areaByNode = new HashMap<>();
    private final Map<String, double[]> metrics = new HashMap<>();
    private final Map<Long, Area> areasByBlock = new HashMap<>();
    private final Popup popover = new Popup();
    private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(350));
    private final ContextMenu menu = new ContextMenu();
    private final MenuItem editItem = new MenuItem("Edit block");
    private final Label headerTitle = new Label();
    private final Label headerSection = new Label();
    private final VBox header = new VBox(1, headerTitle, headerSection);

    private View view = View.READING;
    private DocumentAssembly assembly;
    private LineSelection selection;
    private boolean applying;
    private LineRun hovered;
    private Area editing;
    private double[] hoverAt;
    private RichTextArea hoverArea;

    DocumentSurfaceView(Host host, DocumentSurface surface) {
        this.host = host;
        this.surface = surface;
        this.target = areas;
        headerTitle.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
        headerTitle.setId("stampedDocumentHeaderTitle");
        headerSection.setFont(SMALL);
        headerSection.setStyle("-fx-text-fill: #5f6b7a;");
        headerSection.setId("stampedDocumentHeaderSection");
        header.setPadding(new Insets(4, 12, 4, 12));
        header.setId("stampedDocumentHeader");
        surface.setHeader(header);
        surface.scrollPositionProperty().addListener((obs, was, is) -> {
            hidePopover();
            // Content-height areas settle their heights a pulse or two after a scroll that follows a
            // model change; the section at the top is read once they have.
            javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(this::updateRunningHead));
        });
        popover.setAutoHide(false);
        popover.setAutoFix(true);
        hoverDelay.setOnFinished(e -> showPopover());
        buildMenu();
    }

    /** @return the shown view */
    View view() {
        return view;
    }

    /** Switches the view; the selection carries over. */
    void setView(View wanted) {
        if (wanted == view) {
            return;
        }
        if (editing != null) {
            cancelEdit(editing);
        }
        view = wanted;
        hidePopover();
        stack();
    }

    /** @return whether the layer's toggle is on */
    boolean layer(Layer layer) {
        return layers.contains(layer);
    }

    /** Turns a layer toggle on or off and redraws the layer. */
    void setLayer(Layer layer, boolean on) {
        if (on ? layers.add(layer) : layers.remove(layer)) {
            redecorate();
        }
    }

    /** @return the current selection, if any */
    Optional<LineSelection> selection() {
        return Optional.ofNullable(selection);
    }

    /** @return the selected lines in document order */
    List<StoredLine> selectedLines() {
        if (selection == null || assembly == null) {
            return List.of();
        }
        List<StoredLine> lines = assembly.lines();
        return lines.subList(Math.max(0, selection.min()), Math.min(lines.size(), selection.max() + 1));
    }

    /** @return the shown assembly, if one is */
    Optional<DocumentAssembly> assembly() {
        return Optional.ofNullable(assembly);
    }

    /** Sets the selection (by ordinals) and shows it in the current view. */
    void select(LineSelection wanted) {
        selection = wanted;
        render();
        reveal();
        host.selectionChanged();
    }

    /** Clears the selection. */
    void clearSelection() {
        selection = null;
        render();
        host.selectionChanged();
    }

    /** @return whether a block is being edited in place */
    boolean editing() {
        return editing != null;
    }

    /** @return the ordinal of a line by nid in surface order, or {@code -1} */
    int ordinal(long nid) {
        return assembly == null ? -1 : assembly.ordinal(nid);
    }

    // ── building ──────────────────────────────────────────────────────────────

    /** One area of a view: a block of the Reading view, or a chunk of the Source view's ledger. */
    private final class Area {
        final DocumentAssembly.Member member;
        final StoredBlock block;
        final List<StoredLine> lines;
        final LineRunModel.Spec spec;
        final RichTextArea area = new RichTextArea();
        final VBox holder = new VBox();
        final boolean editable;
        final String section;
        LineRunModel model;
        HBox marginalia;
        Label date;
        DocumentBlock documentBlock;
        long highlightedStamp;
        /** A ledger chunk's line numbers in its member's file, 1-based, parallel to {@link #lines}. */
        int[] numbers;
        /** In the Page layout: the top-level block of the print model this area belongs to, or -1. */
        int modelOrdinal = -1;
        /** In the Page layout: whether the area is prose that may split across pages. */
        boolean splittable;
        /** In the Page layout: whether the area is a heading that keeps with what follows. */
        boolean heading;
        /** A page line: the unit it is a line of, which line of it, the page, its left edge and baseline there. */
        int unit = -1;
        int lineOfUnit;
        int page = -1;
        double x;
        double baseline;
        /** A page line: whether it is the whole unit, wrapped by JavaFX, rather than one of the oracle's lines. */
        boolean whole;
        /** A structured block's kind, its source, and the control that reads it in place of the text; null otherwise. */
        StructuredBlockKind structured;
        StructuredBlockKind.Source structuredSource;
        Node structuredView;
        /** The kind's editor while one is open on this area. */
        StructuredBlockKind.Editor structuredEditor;

        Area(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, boolean editable, String section) {
            this.member = member;
            this.block = block;
            this.lines = lines;
            this.spec = spec;
            this.editable = editable;
            this.section = section;
            area.setEditable(false);
            area.setWrapText(true);
            area.setUseContentHeight(true);
            area.setDisplayCaret(false);
            area.setHighlightCurrentParagraph(false);
            area.setContentPadding(new Insets(2, 4, 2, 4));
            area.getStyleClass().add("doc-prose-block");
            wire(this);
            decorate();
        }

        void decorate() {
            model = spec.build(decoration(this));
            applying = true;
            try {
                area.setModel(model);
            } finally {
                applying = false;
            }
        }
    }

    /**
     * Shows an assembly: rebuilds both views from its members' lines and blocks and stacks the
     * current one on the surface. The selection is kept when the same lines are still there.
     *
     * @param shown the host document with its children resolved
     */
    void show(DocumentAssembly shown) {
        hidePopover();
        editing = null;
        assembly = shown;
        areas.clear();
        pages.clear();
        units.clear();
        areaByNode.clear();
        areasByBlock.clear();
        badges.clear();
        if (selection != null && (selection.max() >= shown.lines().size())) {
            selection = null;
        }
        StoredDocument document = shown.host().document();
        String title = document.attributes().isEmpty() ? "" : document.attributes().getFirst();
        renderMember(shown.host(), 0, "");
        if (title.isEmpty() && document.root() != null) {
            title = document.root().title();
        }
        headerTitle.setText(title.isEmpty() ? StampNames.name(host.view(), dev.ikm.tinkar.common.service.PrimitiveData.nid(document.anchor())) : title);
        headerSection.setText("");
        buildSource(shown);
        stack();
    }

    /** Renders one member's blocks, or its lines as a ledger when the store holds no structure for it. */
    private void renderMember(DocumentAssembly.Member member, int depth, String section) {
        StoredDocument document = member.document();
        if (document.root() == null) {
            for (StoredLine line : document.lines()) {
                add(member, null, List.of(line), paragraphs(List.of(line), "", st.mono(), null), depth, false, section);
            }
        } else {
            render(member, document.root(), depth, 0, section);
        }
    }

    /** Stacks the current view's blocks on the surface and draws the selection. */
    private void stack() {
        if (view == View.PAGE_LAYOUT || view == View.GRAPH) {
            return;                                              // the card shows the pages or the graph in the surface's place
        }
        if (view == View.READING) {
            surface.setMarginTracks(layers.contains(Layer.MARGINALIA) ? 2 : 0, layers.contains(Layer.MARGINALIA) ? 2 : 0);
            surface.setBlocks(areas.stream().map(a -> a.documentBlock).toList());
        } else {
            surface.setMarginTracks(0, 0);
            surface.setBlocks(sources.stream().map(a -> a.documentBlock).toList());
        }
        render();
        // The section at the top is read from laid-out bounds: two pulses after the stack joins layout.
        javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(this::updateRunningHead));
    }

    /** The header's section line: the section of the block at the top of the viewport; the host learns whose block it is in either view. */
    private void updateRunningHead() {
        List<Area> shown = view == View.PAGE_LAYOUT ? areas : shownAreas();
        if (shown.isEmpty()) {
            headerSection.setText(view == View.SOURCE ? "Source" : "");
            return;
        }
        int top = surface.topBlockIndex();
        if (top < 0 || top >= shown.size()) {
            return;
        }
        headerSection.setText(view == View.SOURCE ? "Source" : shown.get(top).section);
        host.showingMember(shown.get(top).member);
    }

    /**
     * The ledger: one paragraph per line, in areas of at most {@link #LEDGER_CHUNK} lines so no
     * area outgrows the safeguard. The lines run in surface order, so the host's ledger lists
     * each resolved include line followed by the child's own ledger; the areas break where the
     * member changes, each member's lines numbered as its file is, with the path above a
     * child's ledger and again where the host's resumes.
     */
    private void buildSource(DocumentAssembly shown) {
        sources.clear();
        List<StoredLine> lines = shown.lines();
        int chunkIndex = 0;
        int at = 0;
        DocumentAssembly.Member previous = null;
        while (at < lines.size()) {
            DocumentAssembly.Member member = shown.memberAt(at).orElseThrow();
            int end = at;
            while (end < lines.size() && end - at < LEDGER_CHUNK && shown.memberAt(end).orElseThrow() == member) {
                end++;
            }
            List<StoredLine> chunk = lines.subList(at, end);
            int[] numbers = new int[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                numbers[i] = shown.indexInMember(at + i) + 1;
            }
            List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
            for (StoredLine line : chunk) {
                paragraphs.add(new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line, line.text())), "", Styles.SCREEN.mono()));
            }
            Area source = new Area(member, null, chunk, new LineRunModel.Spec(paragraphs), false, "Source");
            source.numbers = numbers;
            source.area.setId(chunkIndex == 0 ? "stampedDocumentSource" : "stampedDocumentSource" + chunkIndex);
            source.area.setWrapText(false);
            source.area.setLeftDecorator(new LedgerDecorator(source));
            if (previous != null && previous != member) {
                Label path = new Label((member.host() || member.depth() < previous.depth() ? "↩ " : "↳ ") + member.path());
                path.setFont(Font.font("Menlo", 11));
                path.setStyle("-fx-text-fill: #5f6b7a;");
                path.setPadding(new Insets(6, 0, 2, 8));
                path.getStyleClass().add("stamped-document-member-path");
                source.holder.getChildren().add(path);
            }
            source.holder.getChildren().add(source.area);
            source.documentBlock = DocumentBlock.prose(source.holder, source.area, source.model,
                    chunk.stream().map(StoredLine::text).collect(Collectors.joining("\n")));
            sources.add(source);
            previous = member;
            chunkIndex++;
            at = end;
        }
    }

    /** The ledger's left column: the line's number in its file, introducing identicon, annotation mark. */
    private final class LedgerDecorator implements SideDecorator {
        private final Area chunk;

        LedgerDecorator(Area chunk) {
            this.chunk = chunk;
        }

        @Override
        public double getPrefWidth(double viewWidth) {
            return 96;
        }

        @Override
        public Node getMeasurementNode(int index) {
            return getNode(index);
        }

        @Override
        public Node getNode(int paragraph) {
            HBox row = new HBox(6);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(0, 4, 0, 2));
            boolean held = paragraph >= 0 && paragraph < chunk.lines.size();
            Label number = new Label(held ? Integer.toString(chunk.numbers[paragraph]) : "");
            number.setMinWidth(34);
            number.setAlignment(Pos.CENTER_RIGHT);
            number.setFont(Font.font("Menlo", 11));
            number.setStyle("-fx-text-fill: #8a8782;");
            row.getChildren().add(number);
            StoredLine line = held ? chunk.lines.get(paragraph) : null;
            if (line != null && line.introduced() != null && line.introduced().time() != Long.MAX_VALUE) {
                row.getChildren().add(identicon(line.introduced(), 14, null));
            } else {
                Label gap = new Label();
                gap.setMinWidth(14);
                row.getChildren().add(gap);
            }
            Label mark = new Label(line == null || !layers.contains(Layer.ANNOTATION_MARKS) ? " " : mark(line));
            mark.setMinWidth(14);
            mark.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 11));
            mark.setStyle(line == null ? "" : markStyle(line));
            row.getChildren().add(mark);
            return row;
        }
    }

    /** The line's annotation mark: refuted, affirmed, commented, or none. */
    static String mark(StoredLine line) {
        if (!line.refuters().isEmpty()) {
            return "✗";
        }
        if (!line.affirmers().isEmpty()) {
            return "✓";
        }
        return line.annotations().isEmpty() ? " " : "…";
    }

    static String markStyle(StoredLine line) {
        if (!line.refuters().isEmpty()) {
            return "-fx-text-fill: #c62828;";
        }
        if (!line.affirmers().isEmpty()) {
            return "-fx-text-fill: #2e7d32;";
        }
        return "-fx-text-fill: #8a8782;";
    }

    /** Renders one block of a member and its members into the Reading view's areas. */
    private void render(DocumentAssembly.Member member, StoredBlock block, int depth, int ordinal, String section) {
        StoredDocument document = member.document();
        List<StoredLine> own = new ArrayList<>();
        List<StoredBlock> children = new ArrayList<>();
        for (long nid : block.members()) {
            Optional<StoredLine> line = document.line(nid);
            if (line.isPresent()) {
                own.add(line.get());
            } else {
                StoredBlock child = document.blocks().get(nid);
                if (child != null) {
                    children.add(child);
                }
            }
        }
        switch (block.kind()) {
            case DOCUMENT -> {
                // A child's title is the section its own blocks sit under until its first section.
                String under = member.host() || block.title().isEmpty() ? section : block.title();
                List<StoredLine> heading = own.stream().filter(l -> l.text().startsWith("= ")).toList();
                if (!heading.isEmpty()) {
                    if (print) {
                        // The print model: the host's title is the document title, a child's a heading at its include offset.
                        modelOrdinal++;
                        StyleAttributeMap style = member.host() ? st.title() : st.heading(Math.max(1, offsetOf(member)));
                        Area title = add(member, block, heading, paragraphs(heading, " ", style, t -> t.startsWith("= ") ? t.substring(2) : t), depth, false, under);
                        if (title != null) {
                            title.heading = true;
                            title.splittable = false;
                        }
                    } else {
                        add(member, block, heading, paragraphs(heading, " ", st.title(), t -> t.startsWith("= ") ? t.substring(2) : t), depth, false, under);
                    }
                }
                List<StoredLine> front = own.stream().filter(l -> !l.text().startsWith("= "))
                        .filter(l -> !print || assembly.includeOf(l.nid()).map(DocumentAssembly.Include::resolved).orElse(false)).toList();
                if (!front.isEmpty()) {
                    add(member, block, front, paragraphs(front, "", st.monoGrey(), null), depth, false, under);
                }
                renderChildren(member, children, depth, under);
            }
            case SECTION -> {
                List<StoredLine> heading = own.stream().filter(l -> l.text().startsWith("=")).toList();
                // The print model offsets an included document's headings by its include's leveloffset.
                StyleAttributeMap style = st.heading(print ? block.level() + offsetOf(member) : block.level());
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                add(member, block, heading.isEmpty() ? own : heading, paragraphs(heading.isEmpty() ? own : heading, " ", style,
                        t -> t.replaceFirst("^=+\\s*", "")), depth, false, block.title());
                renderChildren(member, children, depth, block.title());
            }
            case PARAGRAPH -> {
                if (print && printable(own).stream().map(l -> printProse(l.text())).allMatch(String::isBlank)) {
                    return;                                      // the print model drops a paragraph of index terms alone
                }
                if (print && nesting == 0 && !printable(own).isEmpty()) {
                    modelOrdinal++;
                }
                add(member, block, own, paragraphs(own, " ", st.prose(), print ? DocumentSurfaceView::printProse : DocumentSurfaceView::prose), depth, true, section);
            }
            case ADMONITION -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                String label = block.parameters().replaceAll("[\\[\\]]", "");
                List<LineRunModel.Line> lines = new ArrayList<>();
                for (int i = 0; i < own.size(); i++) {
                    String text = prose(own.get(i).text());
                    if (i == 0) {
                        text = label + " — " + text.replaceFirst("^(NOTE|TIP|WARNING|IMPORTANT|CAUTION):\\s*", "");
                    }
                    lines.add(new LineRunModel.Line(own.get(i), text));
                }
                Area area = add(member, block, own, new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(lines, " ", st.prose()))), depth, true, section);
                if (area != null) {
                    area.area.setStyle("-fx-background-color: #FFF8E1; -fx-background-radius: 4;");
                }
                nesting++;
                renderChildren(member, children, depth + 1, section);
                nesting--;
            }
            case LISTING, LITERAL, DIAGRAM, OPAQUE -> {
                if (print && nesting == 0 && block.kind() != Block.Kind.OPAQUE) {
                    modelOrdinal++;
                }
                Area area = add(member, block, own, paragraphs(own, "", block.kind() == Block.Kind.OPAQUE ? st.monoGrey() : st.mono(), null), depth,
                        block.kind() == Block.Kind.LISTING || block.kind() == Block.Kind.LITERAL, section);
                if (area != null) {
                    if (block.kind() != Block.Kind.OPAQUE) {
                        area.area.setStyle("-fx-background-color: #F4F3F1; -fx-background-radius: 4;");
                    }
                    title(area, block);
                    if (!print) {
                        attachStructured(area, member.document(), block);
                    }
                }
            }
            case EXAMPLE, QUOTE, SIDEBAR, OPEN -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                if (!own.isEmpty() && !print) {
                    add(member, block, own, paragraphs(own, "", st.smallGrey(), null), depth, false, section);
                } else if (print && !block.title().isEmpty() && !own.isEmpty()) {
                    add(member, block, own.subList(0, 1), paragraphs(own.subList(0, 1), "", st.bold(), t -> block.title()), depth, false, section);
                }
                nesting++;
                renderChildren(member, children, depth + 1, section);
                nesting--;
            }
            case LIST -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                if (!own.isEmpty()) {
                    add(member, block, own, paragraphs(own, " ", st.bold(), t -> t.replaceFirst("^\\.", "")), depth, false, section);
                }
                nesting++;
                int n = 0;
                for (StoredBlock item : children) {
                    render(member, item, depth + 1, ++n, section);
                }
                nesting--;
            }
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> {
                String marker = block.kind() == Block.Kind.BIBLIOGRAPHY_ENTRY ? "▪" : ordinalMarker(own, ordinal, print ? depth : -1);
                List<LineRunModel.Line> lines = new ArrayList<>();
                for (int i = 0; i < own.size(); i++) {
                    String text = MARKER.matcher(own.get(i).text()).replaceFirst("").strip();
                    if (text.equals("+")) {
                        text = "";
                    }
                    lines.add(new LineRunModel.Line(own.get(i), i == 0 ? marker + " " + text : text));
                }
                add(member, block, own, new LineRunModel.Spec(List.of(new LineRunModel.Paragraph(lines, " ", st.prose()))), depth, true, section);
                renderChildren(member, children, depth + 1, section);
            }
            case TABLE -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                boolean header = block.parameters().contains("header");
                if (!own.isEmpty() && !print) {
                    Area delimiters = add(member, block, own, paragraphs(own, "", st.smallGrey(), null), depth, false, section);
                    if (delimiters != null) {
                        title(delimiters, block);
                    }
                } else if (print && !block.title().isEmpty()) {
                    // The caption, which Typst sets above the table: carried by the block's first line, or the first cell's.
                    List<StoredLine> carrier = !own.isEmpty() ? own.subList(0, 1) : children.stream()
                            .flatMap(row -> row.members().stream()).map(document::line).flatMap(Optional::stream).limit(1).toList();
                    if (!carrier.isEmpty()) {
                        // Straight to an area: the carrier may be the title line itself, which the print filter would drop.
                        addArea(member, block, carrier, paragraphs(carrier, "", st.bold(), t -> block.title()), depth, false, section);
                    }
                }
                int r = 0;
                for (StoredBlock row : children) {
                    List<StoredLine> cells = new ArrayList<>();
                    for (long nid : row.members()) {
                        document.line(nid).ifPresent(cells::add);
                    }
                    if (cells.isEmpty()) {
                        continue;
                    }
                    Area area = add(member, row, cells, paragraphs(cells, " ", header && r == 0 ? st.headerCell() : st.cell(), DocumentSurfaceView::cell), depth + 1, false, section);
                    if (area != null) {
                        area.area.setStyle("-fx-background-color: " + (header && r == 0 ? "#ECEAE6" : "#F4F3F1") + ";");
                    }
                    r++;
                }
            }
            case TABLE_ROW, TABLE_CELL -> renderChildren(member, children, depth, section);
            case IMAGE -> {
                if (print && nesting == 0) {
                    modelOrdinal++;
                }
                add(member, block, own, paragraphs(own, " ", st.prose(), t -> "🖼 " + t), depth, false, section);
            }
        }
    }

    /** The lines the print model keeps of a block: not its attribute, anchor, block-title or comment lines. */
    private static List<StoredLine> printable(List<StoredLine> lines) {
        return lines.stream().filter(l -> {
            String t = l.text().strip();
            return !t.startsWith("//") && !ATTRIBUTE_LINE.matcher(t).matches() && !ANCHOR_LINE.matcher(t).matches() && !BLOCK_TITLE_LINE.matcher(t).matches();
        }).toList();
    }

    /** The heading level offset a member's include lines carry, summed up its chain. */
    private int offsetOf(DocumentAssembly.Member member) {
        int offset = 0;
        for (DocumentAssembly.Include include : assembly.includes()) {
            if (include.resolved() && include.child() == member) {
                offset += network.ike.komet.claude.stamp.print.StoredDocumentModel.Child.levelOffset(include.line().text());
                offset += offsetOf(include.in());
                break;
            }
        }
        return offset;
    }

    private void renderChildren(DocumentAssembly.Member member, List<StoredBlock> children, int depth, String section) {
        int n = 0;
        for (StoredBlock child : children) {
            render(member, child, depth, ++n, section);
        }
    }

    /** The item's marker: its number, or the bullet — on pages Typst's bullet for the item's depth, on screen a plain one. */
    private static String ordinalMarker(List<StoredLine> own, int ordinal, int depth) {
        String bullet = depth < 0 ? "•" : network.ike.komet.claude.stamp.print.ModelUnits.bullet(Math.max(0, depth - 1));
        if (own.isEmpty()) {
            return bullet;
        }
        String first = own.getFirst().text().stripLeading();
        return !first.isEmpty() && (first.startsWith(".") || Character.isDigit(first.charAt(0))) ? ordinal + "." : bullet;
    }

    private static String prose(String text) {
        String t = text.strip();
        if (t.endsWith("\\")) {
            t = t.substring(0, t.length() - 1).stripTrailing();
        }
        return t;
    }

    private static final Pattern CONCEALED_TERM = Pattern.compile("indexterm:\\[[^\\]]*\\]|\\(\\(\\([^)]*\\)\\)\\)");
    private static final Pattern VISIBLE_TERM = Pattern.compile("indexterm2:\\[([^\\]]*)\\]|\\(\\(([^)]*)\\)\\)");

    /** A prose line as the pages draw it: concealed index terms, which print nothing, left out; visible ones as their text. */
    private static String printProse(String text) {
        String t = CONCEALED_TERM.matcher(text).replaceAll("");
        t = VISIBLE_TERM.matcher(t).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1) != null ? m.group(1) : m.group(2)));
        return prose(t.replaceAll("  +", " "));
    }

    /** A table line as drawn: the leading bar dropped, the cell bars shown as a light rule. */
    private static String cell(String text) {
        return text.strip().replaceFirst("^\\|\\s*", "").replace(" | ", "  │  ").replace("|", " │ ");
    }

    /** One paragraph per line, or one paragraph of all lines joined by the separator. */
    private static LineRunModel.Spec paragraphs(List<StoredLine> lines, String separator, StyleAttributeMap style,
                                                java.util.function.UnaryOperator<String> drawn) {
        List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
        if (separator.isEmpty()) {
            for (StoredLine line : lines) {
                paragraphs.add(new LineRunModel.Paragraph(List.of(new LineRunModel.Line(line, drawn == null ? line.text() : drawn.apply(line.text()))), "", style));
            }
        } else {
            List<LineRunModel.Line> runs = new ArrayList<>();
            for (StoredLine line : lines) {
                runs.add(new LineRunModel.Line(line, drawn == null ? line.text() : drawn.apply(line.text())));
            }
            paragraphs.add(new LineRunModel.Paragraph(runs, separator, style));
        }
        return new LineRunModel.Spec(paragraphs);
    }

    private void title(Area area, StoredBlock block) {
        if (!block.title().isEmpty()) {
            Label title = new Label(block.title());
            title.setFont(Font.font("System", javafx.scene.text.FontWeight.BOLD, 13));
            area.holder.getChildren().addFirst(title);
        }
    }

    /**
     * Adds a block's lines to the Reading view: one area, or, where a line is an include whose
     * child the store holds, the lines before it, the child's whole rendering in its place, and
     * the lines after — so an assembly reads as one document. A block cut by an include is not
     * edited in place as a whole, so its parts are not editable.
     *
     * @return the first area of the block's own lines, or {@code null} when every line was an include shown in place
     */
    private Area add(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section) {
        List<DocumentAssembly.Part> parts = assembly.parts(lines);
        if (parts.size() == 1 && !parts.getFirst().included()) {
            if (print) {
                if (block != null && block.kind() == Block.Kind.OPAQUE) {
                    return null;                                 // comments and attribute entries print nothing
                }
                List<StoredLine> kept = block != null && (block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.DOCUMENT) ? lines : printable(lines);
                if (kept.isEmpty()) {
                    return null;
                }
                return addArea(member, block, kept, kept.size() == lines.size() ? spec : restrict(spec, kept), depth, editable, section);
            }
            return addArea(member, block, lines, spec, depth, editable, section);
        }
        Area first = null;
        for (DocumentAssembly.Part part : parts) {
            if (part.included()) {
                DocumentAssembly.Member child = part.include().child();
                int before = target.size();
                renderMember(child, depth, section);
                if (target.size() > before && !print) {
                    Label path = new Label("↳ " + child.path());
                    path.setFont(Font.font("Menlo", 11));
                    path.setStyle("-fx-text-fill: #5f6b7a;");
                    path.setPadding(new Insets(8, 0, 0, 0));
                    path.getStyleClass().add("stamped-document-member-path");
                    target.get(before).holder.getChildren().addFirst(path);
                }
            } else {
                if (print && (block != null && block.kind() == Block.Kind.OPAQUE || printable(part.lines()).isEmpty())) {
                    continue;
                }
                List<StoredLine> kept = print ? printable(part.lines()) : part.lines();
                Area area = addArea(member, block, kept, restrict(spec, kept), depth, false, section);
                if (first == null) {
                    first = area;
                }
            }
        }
        return first;
    }

    /** The spec over a subset of its lines: each paragraph keeps the lines of the subset, paragraphs left empty are dropped. */
    private static LineRunModel.Spec restrict(LineRunModel.Spec spec, List<StoredLine> lines) {
        Set<Long> kept = lines.stream().map(StoredLine::nid).collect(Collectors.toSet());
        List<LineRunModel.Paragraph> paragraphs = new ArrayList<>();
        for (LineRunModel.Paragraph p : spec.paragraphs()) {
            List<LineRunModel.Line> own = p.lines().stream().filter(l -> kept.contains(l.line().nid())).toList();
            if (!own.isEmpty()) {
                paragraphs.add(new LineRunModel.Paragraph(own, p.separator(), p.style()));
            }
        }
        return new LineRunModel.Spec(paragraphs);
    }

    /** Adds one Reading-view area: the block's body with its references beneath, and its marginalia for the margins. */
    private Area addArea(DocumentAssembly.Member member, StoredBlock block, List<StoredLine> lines, LineRunModel.Spec spec, int depth, boolean editable, String section) {
        Area area = new Area(member, block, lines, spec, editable, section);
        target.add(area);
        if (block != null && editable && !print) {
            areasByBlock.put(block.nid(), area);
        }
        area.holder.getChildren().add(area.area);
        if (print) {
            area.modelOrdinal = modelOrdinal;
            Block.Kind kind = block == null ? Block.Kind.PARAGRAPH : block.kind();
            area.splittable = kind == Block.Kind.PARAGRAPH || kind == Block.Kind.LIST_ITEM || kind == Block.Kind.BIBLIOGRAPHY_ENTRY || kind == Block.Kind.ADMONITION;
            area.heading = area.heading || kind == Block.Kind.SECTION;
            area.holder.setPadding(printSpacing(kind, spec));
            area.holder.setFillWidth(true);
            area.holder.setMinWidth(0);
            area.documentBlock = DocumentBlock.prose(area.holder, area.area, area.model, spec.lines().stream().map(StoredLine::text).collect(Collectors.joining("\n")));
            return area;
        }
        Node references = references(lines);
        if (references != null) {
            area.holder.getChildren().add(references);
        }
        for (StoredLine line : lines) {
            assembly.includeOf(line.nid()).filter(i -> !i.resolved()).ifPresent(include -> {
                Label note = new Label("include " + include.target() + " — " + include.note());
                note.setFont(SMALL);
                note.setWrapText(true);
                note.setStyle("-fx-text-fill: #b26a00;");
                note.getStyleClass().add("stamped-document-include-note");
                area.holder.getChildren().add(note);
            });
        }
        area.holder.setFillWidth(true);
        area.holder.setMinWidth(0);
        area.holder.setSpacing(2);
        area.holder.setPadding(new Insets(0, 0, 0, depth * 18));
        area.date = new Label();
        area.date.setFont(SMALL);
        area.date.setStyle("-fx-text-fill: #5f6b7a;");
        area.date.setPadding(new Insets(3, 0, 0, 0));
        area.marginalia = new HBox(3);
        area.marginalia.setAlignment(Pos.TOP_LEFT);
        area.marginalia.getStyleClass().add("stamped-document-marginalia");
        fillMarginalia(area);
        area.documentBlock = DocumentBlock.prose(area.holder, area.area, area.model, spec.lines().stream().map(StoredLine::text).collect(Collectors.joining("\n")))
                .withMarginalia(dateBox(area), area.marginalia);
        return area;
    }

    /** The left margin: the first introducing date, right-aligned against the body. */
    private Node dateBox(Area area) {
        HBox box = new HBox(area.date);
        box.setAlignment(Pos.TOP_RIGHT);
        box.setPadding(new Insets(0, 4, 0, 0));
        return box;
    }

    /** The references on the block's lines, as one small line, or null when there are none. */
    private Node references(List<StoredLine> own) {
        List<String> parts = new ArrayList<>();
        for (StoredLine line : own) {
            for (StoredReference r : assembly.references().getOrDefault(line.nid(), List.of())) {
                parts.add(switch (r.kind()) {
                    case INDEX_TERM -> "⌕ " + r.target();
                    case CROSS_REFERENCE -> "↗ " + r.target() + (r.text().isEmpty() ? "" : " (" + r.text() + ")");
                    case CITATION -> "[" + r.target() + "]";
                    case FOOTNOTE -> "† " + r.target();
                    case LINK -> "🔗 " + r.target();
                    case ESTIMATE_MARK -> "≈ " + r.target();
                    case INCLUDE -> "include " + r.target();
                    case CONDITIONAL -> "if " + r.target();
                });
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        Label label = new Label(String.join("  ·  ", parts));
        label.setFont(SMALL);
        label.setWrapText(true);
        label.setStyle("-fx-text-fill: #5f6b7a;");
        return label;
    }

    /**
     * The space a print block keeps around itself, as the Typst writer's page sets it: paragraphs
     * 1.1em apart where lines are 0.65em apart (so 0.45em beyond a line), a heading 1.4em of its
     * own size above and 0.75em below, the title 1.2em below, list items tight, a table or a
     * verbatim block a paragraph's gap below.
     */
    private Insets printSpacing(Block.Kind kind, LineRunModel.Spec spec) {
        // Measured against Typst's label-to-label distances (PageFidelity): a Typst block's first line is
        // its cap height, 0.65em shorter than a JavaFX line, and the area carries 4pt of padding, so the
        // gaps here are what Typst's 1.1em paragraph spacing and heading spacing leave after that.
        // Second round of measurement: a one-line area is 8pt taller than its line, which is what Typst's
        // heading spacing amounts to, and a paragraph's 4pt of padding already covers the paragraph gap.
        double base = st.base();
        double gap = 0;
        return switch (kind) {
            case SECTION -> Insets.EMPTY;
            case DOCUMENT -> Insets.EMPTY;
            case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> new Insets(0, 0, 0, base * 1.5);
            case TABLE_ROW -> Insets.EMPTY;
            default -> new Insets(0, 0, gap, 0);
        };
    }

    /**
     * Builds the Page layout's areas for a print theme: the same line-run areas as the Reading
     * view, in the theme's Noto fonts and print spacing, without front matter, comments or
     * attribute lines, so the paged engine lays out what the Typst writer writes.
     *
     * @param theme the print theme
     */
    private void buildPages(network.ike.komet.claude.doc.print.settings.DocumentTheme theme) {
        if (editing != null) {
            cancelEdit(editing);
        }
        units.clear();
        pages.clear();
        areaByNode.clear();
        if (assembly == null) {
            return;
        }
        network.ike.komet.claude.stamp.print.DocumentFonts.loadForJavaFx();
        Styles was = st;
        List<Area> wasTarget = target;
        st = Styles.page(theme);
        target = units;
        print = true;
        modelOrdinal = -1;
        nesting = 0;
        try {
            renderMember(assembly.host(), 0, "");
        } finally {
            st = was;
            target = wasTarget;
            print = false;
        }
        // The selection is drawn on the pages once the engine has laid them out (the card calls
        // renderSelection then): a selection applied to an area before its first layout breaks the
        // incubator's selection highlight.
    }

    /** Draws the selection in the line areas of the pages; the card calls it once the page stack has built a sheet. */
    void renderSelection() {
        render();
    }

    /**
     * The Page layout's units for a layout oracle: the print areas built for the theme, each with
     * the text it draws, its font, and the spacing and keep rules the Typst writer gives its kind.
     *
     * @param theme the print theme
     * @return the units in document order
     */
    List<network.ike.komet.claude.stamp.print.LayoutOracle.Unit> pageUnits(network.ike.komet.claude.doc.print.settings.DocumentTheme theme) {
        buildPages(theme);
        List<network.ike.komet.claude.stamp.print.LayoutOracle.Unit> out = new ArrayList<>();
        double base = theme.baseFontSize();
        network.ike.komet.claude.stamp.print.LayoutOracle.Kind previous = null;
        for (int i = 0; i < units.size(); i++) {
            Area area = units.get(i);
            Block.Kind blockKind = area.block == null ? Block.Kind.PARAGRAPH : area.block.kind();
            network.ike.komet.claude.stamp.print.LayoutOracle.Kind kind = area.heading
                    ? (blockKind == Block.Kind.DOCUMENT ? network.ike.komet.claude.stamp.print.LayoutOracle.Kind.TITLE
                    : network.ike.komet.claude.stamp.print.LayoutOracle.Kind.HEADING)
                    : switch (blockKind) {
                case LIST_ITEM, BIBLIOGRAPHY_ENTRY -> network.ike.komet.claude.stamp.print.LayoutOracle.Kind.LIST_ITEM;
                case LISTING, LITERAL -> network.ike.komet.claude.stamp.print.LayoutOracle.Kind.VERBATIM;
                case TABLE_ROW -> network.ike.komet.claude.stamp.print.LayoutOracle.Kind.TABLE_ROW;
                case PARAGRAPH, ADMONITION, SECTION, DOCUMENT -> network.ike.komet.claude.stamp.print.LayoutOracle.Kind.PARAGRAPH;
                default -> network.ike.komet.claude.stamp.print.LayoutOracle.Kind.OTHER;
            };
            StyleAttributeMap style = area.spec.paragraphs().isEmpty() ? st.prose() : area.spec.paragraphs().getFirst().style();
            double size = style.getFontSize() == null ? base : style.getFontSize();
            boolean bold = style.isBold();
            double indent = kind == network.ike.komet.claude.stamp.print.LayoutOracle.Kind.LIST_ITEM ? base : 0;
            double[] spacing = network.ike.komet.claude.stamp.print.UnitSpacing.of(kind, previous, size, base,
                    kind == network.ike.komet.claude.stamp.print.LayoutOracle.Kind.OTHER && blockKind == Block.Kind.TABLE);
            double above = spacing[0];
            double below = spacing[1];
            out.add(new network.ike.komet.claude.stamp.print.LayoutOracle.Unit(i, area.modelOrdinal, kind, area.spec.plainText(), size, bold, indent,
                    above, below, area.heading));
            previous = kind;
        }
        return out;
    }

    /**
     * Builds the Page layout's line areas from a layout: for every line the oracle placed, a
     * line-run area over the characters of its unit's text, each source line keeping its share,
     * a hyphen where the oracle broke a word, justified where the paragraph is and the line is
     * not its last — the same hover, selection and context menu as the Reading view's areas.
     *
     * @param layout the oracle's answer for the units of {@link #pageUnits}
     * @param units  those units
     */
    void layoutLines(network.ike.komet.claude.stamp.print.LayoutOracle.Layout layout, List<network.ike.komet.claude.stamp.print.LayoutOracle.Unit> units) {
        pages.clear();
        areaByNode.clear();
        if (editing != null) {
            cancelEdit(editing);
        }
        for (int u = 0; u < units.size() && u < this.units.size(); u++) {
            Area unitArea = this.units.get(u);
            network.ike.komet.claude.stamp.print.LayoutOracle.Unit unit = units.get(u);
            List<network.ike.komet.claude.stamp.print.LayoutOracle.Line> own = new ArrayList<>(layout.ofUnit(u));
            own.sort(Comparator.comparingInt(network.ike.komet.claude.stamp.print.LayoutOracle.Line::from));
            for (int k = 0; k < own.size(); k++) {
                network.ike.komet.claude.stamp.print.LayoutOracle.Line line = own.get(k);
                LineRunModel.Spec spec;
                boolean whole = line.whole(unit) && unit.kind() != network.ike.komet.claude.stamp.print.LayoutOracle.Kind.VERBATIM
                        && unitArea.spec.paragraphs().size() == 1 && (k + 1 == own.size()) && k == 0
                        && (unit.kind() == network.ike.komet.claude.stamp.print.LayoutOracle.Kind.TABLE_ROW
                        || unit.kind() == network.ike.komet.claude.stamp.print.LayoutOracle.Kind.OTHER);
                if (whole) {
                    spec = unitArea.spec;
                } else {
                    int[] pa = unit.paragraphAt(line.from());
                    int paragraphStart = line.from() - pa[1];
                    boolean justified = pa[0] < unitArea.spec.paragraphs().size()
                            && unitArea.spec.paragraphs().get(pa[0]).style().getTextAlignment() == javafx.scene.text.TextAlignment.JUSTIFY;
                    boolean last = k + 1 >= own.size() || unit.paragraphAt(own.get(k + 1).from())[0] != pa[0];
                    // A line Typst ends at a hard break (AsciiDoc's trailing +) is ragged like a paragraph's last.
                    boolean forced = endsAtHardBreak(unitArea.spec, pa[0], line.to() - paragraphStart);
                    spec = unitArea.spec.slice(pa[0], pa[1], line.to() - paragraphStart, line.hyphenated(), justified && !last && !forced);
                }
                if (spec.paragraphs().isEmpty()) {
                    continue;
                }
                Area area = new Area(unitArea.member, unitArea.block, spec.lines(), spec, false, unitArea.section);
                area.modelOrdinal = unitArea.modelOrdinal;
                area.splittable = unitArea.splittable;
                area.heading = unitArea.heading;
                area.unit = u;
                area.lineOfUnit = k;
                area.page = line.page();
                area.x = line.x();
                area.baseline = line.baseline();
                area.whole = whole;
                // No chrome of the control's own on a page line: the sheet is the paper; a listing keeps its tint.
                String tint = unitArea.area.getStyle() != null && unitArea.area.getStyle().contains("#F4F3F1") ? "#F4F3F1" : "transparent";
                area.area.setStyle("-fx-background-color: " + tint + "; -fx-background-insets: 0; -fx-background-radius: 0; -fx-border-width: 0; -fx-padding: 0;");
                area.area.getStyleClass().add("stamped-page-line");
                area.area.setId("stampedDocumentPageLine" + pages.size());
                checkLine(area, line, unit, spec);
                pages.add(area);
                areaByNode.put(area.area, area);
            }
        }
        if (view == View.PAGE_LAYOUT) {
            render();
        }
    }

    /** Whether a paragraph's drawn text ends a source line at the offset, and that source line ends in a hard break. */
    private static boolean endsAtHardBreak(LineRunModel.Spec spec, int paragraph, int to) {
        if (paragraph >= spec.paragraphs().size()) {
            return false;
        }
        LineRunModel.Paragraph p = spec.paragraphs().get(paragraph);
        int at = 0;
        for (int i = 0; i < p.lines().size(); i++) {
            if (i > 0) {
                at += p.separator().length();
            }
            LineRunModel.Line line = p.lines().get(i);
            at += line.text().isEmpty() && p.lines().size() == 1 ? 1 : line.text().length();
            String source = line.line().text().stripTrailing();
            boolean hard = source.endsWith(" +") || source.endsWith("\\");
            if (hard && to >= at - 2 && to <= at) {
                return true;                                      // the break's own marker may trail the typst line's end
            }
            if (at >= to) {
                return false;
            }
        }
        return false;
    }

    /** The page lines' self-check: how many were laid, how many draw other text than Typst's line, how many start left of the measure. */
    private int linesChecked;
    private int linesTextMismatch;
    private int linesLeftOfMeasure;
    private final List<String> lineFindings = new ArrayList<>();

    /** Checks a page line against the oracle's: the text it draws is Typst's line, and it starts at or right of the measure's left edge. */
    private void checkLine(Area area, network.ike.komet.claude.stamp.print.LayoutOracle.Line line, network.ike.komet.claude.stamp.print.LayoutOracle.Unit unit,
                           LineRunModel.Spec spec) {
        linesChecked++;
        String drawn = LineRunModel.Spec.text(spec.paragraphs().getFirst()).stripTrailing();
        String expected = area.whole ? unit.text() : line.text(unit).stripTrailing();
        if (!drawn.equals(expected)) {
            linesTextMismatch++;
            if (lineFindings.size() < 8) {
                lineFindings.add("line " + pages.size() + " of unit " + unit.index() + " draws '" + drawn + "' for Typst's '" + expected + "'");
            }
        }
        if (line.x() < pageContentLeft - 0.5) {
            linesLeftOfMeasure++;
            if (lineFindings.size() < 8) {
                lineFindings.add("line " + pages.size() + " of unit " + unit.index() + " starts at x " + line.x() + ", left of the measure at " + pageContentLeft);
            }
        }
    }

    private double pageContentLeft;

    /** Resets the self-check for a layout about to be laid; the card tells the measure's left edge. */
    void beginLineCheck(double contentLeft) {
        pageContentLeft = contentLeft;
        linesChecked = 0;
        linesTextMismatch = 0;
        linesLeftOfMeasure = 0;
        lineFindings.clear();
    }

    /** @return the self-check's report, one line */
    String lineCheck() {
        return "page lines: " + linesChecked + " laid, " + linesTextMismatch + " drawing other text than Typst's line, " + linesLeftOfMeasure
                + " starting left of the measure" + (lineFindings.isEmpty() ? "" : "\n  " + String.join("\n  ", lineFindings));
    }

    /** @return the line areas on a page, placed for the page stack */
    List<PageStackView.Placed> linesOn(int page, double contentLeft, double contentWidth, double unitPitch) {
        List<PageStackView.Placed> out = new ArrayList<>();
        for (Area area : pages) {
            if (area.page != page) {
                continue;
            }
            StyleAttributeMap style = area.spec.paragraphs().getFirst().style();
            double[] m = metrics(style);
            double ascent = m[0] + 2 + 1;                            // the area's content padding above the first line, and a point of air
            // JavaFX lays the glyphs without kerning, so a line Typst fit by a hair may not fit here: the area
            // is as wide as the content, or as the line's natural width when that is wider, so the line's last
            // word stays on it and justification spreads the excess over the spaces.
            double available = contentLeft + contentWidth - area.x;
            double natural = area.whole ? 0 : naturalWidth(area);
            // The measure the line is laid in: the content's, or the line's own width when that is wider; two
            // spaces of slack so the trailing space before the justify filler never wraps the last word away.
            double slack = style.getFontSize() == null ? 8 : style.getFontSize() * 0.6 + 2;
            double width = Math.max(40, Math.max(available, natural + slack) + 8);
            // A line wider than Typst's measure is scaled down to it, so both edges stay where Typst put them.
            double scale = natural > available ? available / natural : 1;
            double height;
            boolean clip;
            if (area.whole) {
                // A unit JavaFX wraps itself: a row of cells or an image line; its height is its own.
                height = Region.USE_COMPUTED_SIZE;
                clip = false;
            } else {
                height = m[1] + 4 + 2;                              // one line and the padding; the justify filler's line is clipped away
                clip = true;
            }
            out.add(new PageStackView.Placed(area.area, area.x - 4, area.baseline, ascent, width, height, clip, scale));
        }
        return out;
    }

    /** @return the unit and line-of-unit a placed line stands for */
    int[] identity(PageStackView.Placed placed) {
        Area area = areaByNode.get(placed.node());
        return area == null ? new int[] {-1, -1} : new int[] {area.unit, area.lineOfUnit};
    }

    /** @return the marginalia of the units whose first line is on a page: the introducing identicons, with the hover highlight, and an edited mark */
    List<PageStackView.Mark> marginaliaOn(int page) {
        List<PageStackView.Mark> out = new ArrayList<>();
        for (Area area : pages) {
            if (area.page != page || area.lineOfUnit != 0) {
                continue;
            }
            Node node = unitMarginalia(area.unit);
            if (node != null) {
                out.add(new PageStackView.Mark(node, area.baseline, metrics(area.spec.paragraphs().getFirst().style())[0]));
            }
        }
        return out;
    }

    /** @return the section the running head names on a page: that of the first line on it, blank for none */
    String sectionOn(int page) {
        for (Area area : pages) {
            if (area.page == page) {
                return area.section == null ? "" : area.section;
            }
        }
        return "";
    }

    /** The width of a page line's text in its font, single spaces between words, as JavaFX lays it. */
    private double naturalWidth(Area line) {
        StyleAttributeMap style = line.spec.paragraphs().getFirst().style();
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.claude.stamp.print.DocumentFonts.SERIF : style.getFontFamily();
        javafx.scene.text.Text probe = new javafx.scene.text.Text(LineRunModel.Spec.text(line.spec.paragraphs().getFirst()));
        probe.setFont(Font.font(family, style.isBold() ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                style.isItalic() ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, size));
        return probe.getLayoutBounds().getWidth();
    }

    /** @return the ascent and the line height of a style's font, in points */
    private double[] metrics(StyleAttributeMap style) {
        double size = style.getFontSize() == null ? st.base() : style.getFontSize();
        String family = style.getFontFamily() == null ? network.ike.komet.claude.stamp.print.DocumentFonts.SERIF : style.getFontFamily();
        boolean bold = style.isBold();
        boolean italic = style.isItalic();
        String key = family + "|" + size + "|" + bold + "|" + italic;
        return metrics.computeIfAbsent(key, k -> {
            javafx.scene.text.Text probe = new javafx.scene.text.Text("Hgjp");
            probe.setFont(Font.font(family, bold ? javafx.scene.text.FontWeight.BOLD : javafx.scene.text.FontWeight.NORMAL,
                    italic ? javafx.scene.text.FontPosture.ITALIC : javafx.scene.text.FontPosture.REGULAR, size));
            return new double[] {probe.getBaselineOffset(), probe.getLayoutBounds().getHeight()};
        });
    }

    /** @return the Page layout's units' top-level print-model ordinals, for the fidelity report */
    int[] pageOrdinals() {
        return units.stream().mapToInt(a -> a.modelOrdinal).toArray();
    }

    /** The right-margin marginalia of a unit: its introducing identicons, with the hover highlight, and an edited mark. */
    private Node unitMarginalia(int index) {
        if (index < 0 || index >= units.size() || !layers.contains(Layer.MARGINALIA)) {
            return null;
        }
        Area area = units.get(index);
        Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
        for (StoredLine line : area.lines) {
            if (line.introduced() != null && line.introduced().time() != Long.MAX_VALUE) {
                distinct.putIfAbsent(line.introduced().nid(), line.introduced());
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
        stamps.sort(Comparator.comparingLong(StampEntity::time));
        FlowPane icons = new FlowPane(2, 2);
        icons.getStyleClass().add("stamped-document-page-marginalia");
        int shown = 0;
        for (StampEntity<?> stamp : stamps) {
            if (shown++ == 4) {
                Label more = new Label("+" + (stamps.size() - 4));
                more.setFont(Font.font(9));
                icons.getChildren().add(more);
                break;
            }
            icons.getChildren().add(identicon(stamp, 12, area));
        }
        if (area.lines.stream().anyMatch(DocumentSurfaceView::uncommitted)) {
            icons.getChildren().add(mark("edited", "#b26a00", "Edited in Komet, uncommitted"));
        }
        return icons;
    }

    // ── the Provenance layer ──────────────────────────────────────────────────

    /** The right margin: every introducing identicon, then the marks; the left margin's date beside it. */
    private void fillMarginalia(Area area) {
        HBox marginalia = area.marginalia;
        marginalia.getChildren().clear();
        List<StoredLine> lines = area.lines;
        Map<Long, StampEntity<?>> distinct = new LinkedHashMap<>();
        for (StoredLine line : lines) {
            if (line.introduced() != null) {
                distinct.putIfAbsent(line.introduced().nid(), line.introduced());
            }
        }
        List<StampEntity<?>> stamps = new ArrayList<>(distinct.values());
        stamps.removeIf(stamp -> stamp.time() == Long.MAX_VALUE);
        stamps.sort(Comparator.comparingLong(StampEntity::time));
        area.date.setText(stamps.isEmpty() ? "" : DAY.format(Instant.ofEpochMilli(stamps.getFirst().time())));
        FlowPane icons = new FlowPane(3, 3);
        icons.setPrefWrapLength(120);
        int shown = 0;
        for (StampEntity<?> stamp : stamps) {
            if (shown++ == 8) {
                Label more = new Label("+" + (stamps.size() - 8));
                more.setFont(SMALL);
                more.setStyle("-fx-text-fill: #5f6b7a;");
                icons.getChildren().add(more);
                break;
            }
            icons.getChildren().add(identicon(stamp, 18, area));
        }
        marginalia.getChildren().add(icons);
        long edited = lines.stream().filter(DocumentSurfaceView::uncommitted).count();
        if (edited > 0) {
            marginalia.getChildren().add(mark("edited", "#b26a00", edited + " of these " + lines.size() + " line(s) edited in Komet and not yet committed in git"));
        }
        if (layers.contains(Layer.ANNOTATION_MARKS)) {
            long affirmed = lines.stream().filter(l -> !l.affirmers().isEmpty()).count();
            long refuted = lines.stream().filter(l -> !l.refuters().isEmpty()).count();
            long commented = lines.stream().filter(l -> l.annotations().stream().anyMatch(a -> a.kind() == AnnotationKind.COMMENT)).count();
            if (refuted > 0) {
                marginalia.getChildren().add(mark("✗ " + refuted, "#c62828", refuted + " of " + lines.size() + " line(s) refuted"));
            }
            if (affirmed > 0) {
                marginalia.getChildren().add(mark(affirmed == lines.size() ? "✓" : "✓ " + affirmed + "/" + lines.size(), "#2e7d32",
                        affirmed == lines.size() ? "Every line of this block is affirmed" : affirmed + " of " + lines.size() + " lines affirmed"));
            }
            if (commented > 0) {
                marginalia.getChildren().add(mark("… " + commented, "#5f6b7a", commented + " line(s) commented on"));
            }
        }
        if (layers.contains(Layer.REVIEW_QUEUE)) {
            long queued = lines.stream().filter(DocumentSurfaceView::queued).count();
            if (queued > 0) {
                marginalia.getChildren().add(mark("◦ " + queued, "#b26a00", queued + " line(s) still to review: introduced, affirmed by no one"));
            }
        }
    }

    private static Label mark(String text, String colour, String tooltip) {
        Label mark = new Label(text);
        mark.setFont(SMALL);
        mark.setStyle("-fx-text-fill: " + colour + "; -fx-font-weight: bold;");
        mark.setTooltip(new Tooltip(tooltip));
        return mark;
    }

    /** Whether a line's latest version was written in Komet and is not yet committed. */
    static boolean uncommitted(StoredLine line) {
        return line.latest() != null && line.latest().time() == Long.MAX_VALUE;
    }

    /** Whether a line is in the review queue: introduced, committed, affirmed by no one (refuted lines stay in it). */
    static boolean queued(StoredLine line) {
        return line.introduced() != null && line.introduced().time() != Long.MAX_VALUE && line.affirmers().isEmpty();
    }

    /** An identicon of the STAMP; in a block's margin, hovering it highlights the runs that commit introduced. */
    private Node identicon(StampEntity<?> stamp, double size, Area area) {
        Image image = badges.computeIfAbsent(stamp.nid(), nid -> renderIdenticon(stamp));
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true);
        view.setFitHeight(size);
        if (area != null) {
            long introduced = area.lines.stream().filter(l -> l.introduced() != null && l.introduced().nid() == stamp.nid()).count();
            Tooltip.install(view, new Tooltip(StampNames.name(host.view(), stamp.authorNid()) + " · " + DAY.format(Instant.ofEpochMilli(stamp.time()))
                    + host.summaries().summary(stamp.nid()).map(s -> "\n" + s).orElse("")
                    + "\nIntroduced " + introduced + " of these " + area.lines.size() + " line(s)"));
            view.setOnMouseEntered(e -> {
                area.highlightedStamp = stamp.nid();
                area.decorate();
                applySelection(area);
            });
            view.setOnMouseExited(e -> {
                area.highlightedStamp = 0;
                area.decorate();
                applySelection(area);
            });
        }
        return view;
    }

    /** @return the STAMP's identicon, rendered once per STAMP */
    Image badge(StampEntity<?> stamp) {
        return badges.computeIfAbsent(stamp.nid(), nid -> renderIdenticon(stamp));
    }

    private Image renderIdenticon(StampEntity<?> stamp) {
        UUID seed = stamp.publicId().asUuidArray()[0];
        byte[] png = KonceptIdenticon.png(PublicIds.of(seed).idString(), KonceptIdenticon.DISPLAY_MODULE_SIZE);
        return new Image(new ByteArrayInputStream(png));
    }

    /** The commit's tint: a pastel hue derived from the STAMP's identicon seed, the same everywhere. */
    static Color tint(StampEntity<?> stamp) {
        UUID seed = stamp.publicId().asUuidArray()[0];
        int hue = (int) (Math.floorMod(seed.getMostSignificantBits(), 360L));
        return Color.hsb(hue, 0.22, 1.0);
    }

    /** Highlights every run of a commit, in every area, as a History view's selection does; 0 clears. */
    void highlightCommit(long stampNid) {
        for (Area area : areas) {
            if (area != editing) {
                area.highlightedStamp = stampNid;
                area.decorate();
                applySelection(area);
            }
        }
        for (Area source : sources) {
            source.highlightedStamp = stampNid;
            source.decorate();
            applySelection(source);
        }
        for (Area page : pages) {
            page.highlightedStamp = stampNid;
            page.decorate();
            applySelection(page);
        }
        if (stampNid != 0 && view == View.READING) {
            for (int i = 0; i < areas.size(); i++) {
                if (areas.get(i).lines.stream().anyMatch(l -> l.introduced() != null && l.introduced().nid() == stampNid)) {
                    surface.revealBlock(i);
                    break;
                }
            }
        }
    }

    private LineRunModel.Decoration decoration(Area area) {
        boolean tints = layers.contains(Layer.TINTS);
        boolean queue = layers.contains(Layer.REVIEW_QUEUE);
        boolean marks = layers.contains(Layer.ANNOTATION_MARKS);
        long highlighted = area.highlightedStamp;
        return new LineRunModel.Decoration() {
            @Override
            public Color tint(StoredLine line) {
                return tints && line.introduced() != null && line.introduced().time() != Long.MAX_VALUE ? DocumentSurfaceView.tint(line.introduced()) : null;
            }

            @Override
            public boolean underline(StoredLine line) {
                return queue && queued(line);
            }

            @Override
            public boolean wavy(StoredLine line) {
                return marks && !line.refuters().isEmpty();
            }

            @Override
            public Color highlight(StoredLine line) {
                return highlighted != 0 && line.introduced() != null && line.introduced().nid() == highlighted ? HOVER_HIGHLIGHT : null;
            }
        };
    }

    /** Redraws the layer over every area and the marginalia, restacks when the margins came or went, keeping the selection. */
    private void redecorate() {
        for (Area area : areas) {
            if (area != editing) {
                area.decorate();
            }
            fillMarginalia(area);
        }
        for (Area source : sources) {
            source.decorate();
            source.area.setLeftDecorator(new LedgerDecorator(source));
        }
        for (Area page : pages) {
            page.decorate();
        }
        stack();
    }

    // ── hover ─────────────────────────────────────────────────────────────────

    private void wire(Area area) {
        RichTextArea rta = area.area;
        rta.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (area == editing) {
                return;
            }
            Optional<LineRun> run = area.model.runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
            if (run.isEmpty()) {
                hidePopover();
                return;
            }
            if (hovered == null || hovered.nid() != run.get().nid()) {
                hidePopover();
                hovered = run.get();
                hoverAt = new double[] {e.getScreenX(), e.getScreenY()};
                hoverArea = rta;
                hoverDelay.playFromStart();
            }
        });
        rta.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hidePopover());
        rta.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> hidePopover());
        rta.selectionProperty().addListener((obs, was, is) -> snap(area, is));
        rta.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (area == editing) {
                return;
            }
            if (e.getButton() == MouseButton.SECONDARY) {
                e.consume();                                    // the context menu handles it; the caret stays
                return;
            }
            if (e.isShiftDown() && e.getButton() == MouseButton.PRIMARY && selection != null) {
                Optional<LineRun> run = area.model.runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
                if (run.isPresent()) {
                    selection = selection.extendTo(ordinal(run.get().nid()));
                    render();
                    host.selectionChanged();
                    e.consume();
                }
            }
        });
        rta.setOnContextMenuRequested(e -> {
            if (area == editing) {
                return;
            }
            hidePopover();
            Optional<LineRun> run = area.model.runAt(rta.getTextPosition(e.getScreenX(), e.getScreenY()));
            if (run.isPresent()) {
                int o = ordinal(run.get().nid());
                if (selection == null || !selection.contains(o)) {
                    selection = new LineSelection(o, o);
                    render();
                    host.selectionChanged();
                }
            }
            editItem.setDisable(editableBlockOfSelection().isEmpty() || editing != null);
            menu.show(rta, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private void showPopover() {
        if (hovered == null || hoverArea == null || hoverArea.getScene() == null || hoverArea.getScene().getWindow() == null) {
            return;
        }
        StoredLine line = assembly == null ? hovered.line() : assembly.line(hovered.nid()).orElse(hovered.line());
        Node content = ProvenancePopover.content(line, host.view(), host.summaries(), s -> badges.computeIfAbsent(s.nid(), n -> renderIdenticon(s)), null);
        content.setId("stampedDocumentProvenancePopover");
        popover.getContent().setAll(content);
        popover.show(hoverArea.getScene().getWindow(), hoverAt[0] + 14, hoverAt[1] + 18);
    }

    private void hidePopover() {
        hoverDelay.stop();
        hovered = null;
        if (popover.isShowing()) {
            popover.hide();
        }
    }

    // ── selection ─────────────────────────────────────────────────────────────

    /** Snaps a gesture's selection in one area to whole lines and makes it the document's. */
    private void snap(Area area, SelectionSegment is) {
        if (applying || area == editing || is == null || area.model == null) {
            return;
        }
        Optional<LineRun> anchor = area.model.runAt(is.getAnchor());
        Optional<LineRun> caret = area.model.runAt(is.getCaret());
        if (anchor.isEmpty() || caret.isEmpty()) {
            return;
        }
        LineSelection wanted = new LineSelection(ordinal(anchor.get().nid()), ordinal(caret.get().nid()));
        if (wanted.anchor() < 0 || wanted.caret() < 0) {
            return;
        }
        selection = wanted;
        render();
        host.selectionChanged();
    }

    /** Draws the selection in every area of the current view: whole runs, nothing elsewhere. */
    private void render() {
        for (Area area : shownAreas()) {
            applySelection(area);
        }
    }

    /** The areas of the view shown. */
    private List<Area> shownAreas() {
        return switch (view) {
            case READING -> areas;
            case SOURCE -> sources;
            case PAGE_LAYOUT -> pages;
            case GRAPH -> List.of();                             // the graph draws the selection itself
        };
    }

    private void applySelection(Area area) {
        if (area == editing || area.model == null) {
            return;
        }
        applying = true;
        try {
            LineRun first = null;
            LineRun last = null;
            if (selection != null) {
                for (LineRun run : area.model.runs()) {
                    if (selection.contains(ordinal(run.nid()))) {
                        if (first == null) {
                            first = run;
                        }
                        last = run;
                    }
                }
            }
            if (first == null) {
                area.area.clearSelection();
            } else if (selection.caret() >= selection.anchor()) {
                area.area.select(first.startPos(), last.endPos());
            } else {
                area.area.select(last.endPos(), first.startPos());
            }
        } finally {
            applying = false;
        }
    }

    /** Scrolls the first selected block into view. */
    private void reveal() {
        if (selection == null) {
            return;
        }
        List<Area> shown = shownAreas();
        for (int i = 0; i < shown.size(); i++) {
            if (shown.get(i).model.runs().stream().anyMatch(r -> selection.contains(ordinal(r.nid())))) {
                if (view == View.PAGE_LAYOUT) {
                    Area line = shown.get(i);
                    host.revealPage(line.page, line.baseline - metrics(line.spec.paragraphs().getFirst().style())[0]);
                } else {
                    surface.revealBlock(i);
                }
                return;
            }
        }
    }

    // ── the context menu ──────────────────────────────────────────────────────

    private void buildMenu() {
        menu.setId("stampedDocumentContextMenu");
        MenuItem affirm = new MenuItem("Affirm…");
        affirm.setId("stampedDocumentAffirm");
        affirm.setOnAction(e -> host.annotate(AnnotationKind.AFFIRMATION));
        MenuItem refute = new MenuItem("Refute…");
        refute.setId("stampedDocumentRefute");
        refute.setOnAction(e -> host.annotate(AnnotationKind.REFUTATION));
        MenuItem comment = new MenuItem("Comment…");
        comment.setId("stampedDocumentComment");
        comment.setOnAction(e -> host.annotate(AnnotationKind.COMMENT));
        editItem.setId("stampedDocumentEditBlock");
        editItem.setOnAction(e -> editSelectedBlock());
        MenuItem history = new MenuItem("Show history");
        history.setId("stampedDocumentShowHistory");
        history.setOnAction(e -> host.showHistory());
        menu.getItems().addAll(affirm, refute, comment, new SeparatorMenuItem(), editItem, history);
    }

    /** The editable block the selection starts in, if it starts in one. */
    private Optional<Area> editableBlockOfSelection() {
        if (selection == null || assembly == null || selection.min() < 0 || selection.min() >= assembly.lines().size()) {
            return Optional.empty();
        }
        long nid = assembly.lines().get(selection.min()).nid();
        return areas.stream().filter(a -> a.editable && a.lines.stream().anyMatch(l -> l.nid() == nid)).findFirst();
    }

    /** Opens the block the selection starts in for editing in place, in the Reading view. */
    void editSelectedBlock() {
        Optional<Area> area = editableBlockOfSelection();
        if (area.isEmpty()) {
            host.status("Select a line of a paragraph or listing to edit its block.");
            return;
        }
        if (editing != null) {
            host.status("Finish the edit in progress first: Save or Cancel it.");
            return;
        }
        if (view != View.READING) {
            host.viewRequested(View.READING);
            setView(View.READING);
        }
        startEdit(area.get());
    }

    /** Opens a block for editing by its nid, for the host's automation. */
    boolean editBlock(long blockNid) {
        Area area = areasByBlock.get(blockNid);
        if (area == null || editing != null) {
            return false;
        }
        if (view != View.READING) {
            host.viewRequested(View.READING);
            setView(View.READING);
        }
        startEdit(area);
        return true;
    }

    // ── editing in place ──────────────────────────────────────────────────────

    /** Flips the block's area to an editable model, one paragraph per source line, with Save and Cancel beneath. */
    private void startEdit(Area area) {
        hidePopover();
        if (area.structured != null) {
            Optional<StructuredBlockKind.Editor> editor = structuredEditor(area);
            if (editor.isPresent()) {
                startStructuredEdit(area, editor.get());
                return;
            }
            // No editor of its own: the block is edited as its source text.
            showStructured(area, false);
        }
        editing = area;
        RichTextArea rta = area.area;
        RichTextModel model = new RichTextModel();
        applying = true;
        try {
            rta.setModel(model);
            rta.appendText(area.lines.stream().map(StoredLine::text).collect(Collectors.joining("\n")), Styles.SCREEN.mono());
        } finally {
            applying = false;
        }
        rta.setEditable(true);
        rta.setDisplayCaret(true);
        rta.setWrapText(true);
        rta.setId("stampedDocumentBlockEditor");
        rta.setStyle("-fx-background-color: #FFFDF5; -fx-border-color: #b26a00; -fx-border-radius: 3;");
        Button save = new Button("Save");
        save.setId("stampedDocumentSaveEdit");
        Button cancel = new Button("Cancel");
        cancel.setId("stampedDocumentCancelEdit");
        Label hint = new Label("Saved edits stay uncommitted in Komet until you commit the document in git.");
        hint.setFont(SMALL);
        hint.setStyle("-fx-text-fill: #5f6b7a;");
        HBox buttons = new HBox(6, save, cancel, hint);
        buttons.setId("stampedDocumentEditButtons");
        buttons.setAlignment(Pos.CENTER_LEFT);
        area.holder.getChildren().add(area.holder.getChildren().indexOf(rta) + 1, buttons);
        cancel.setOnAction(e -> cancelEdit(area));
        save.setOnAction(e -> {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < rta.getParagraphCount(); i++) {
                if (i > 0) {
                    text.append('\n');
                }
                text.append(rta.getPlainText(i));
            }
            host.saveEdit(area.block, text.toString());
        });
        rta.requestFocus();
    }

    /** Cancels the in-place edit: the block returns to its runs. */
    void cancelEdit(Area area) {
        if (area == null || editing != area) {
            return;
        }
        if (area.structuredEditor != null) {
            area.holder.getChildren().remove(area.structuredEditor.node());
            area.holder.getChildren().removeIf(n -> "stampedDocumentEditButtons".equals(n.getId()));
            area.structuredEditor = null;
            editing = null;
            showStructured(area, true);
            return;
        }
        RichTextArea rta = area.area;
        rta.setEditable(false);
        rta.setDisplayCaret(false);
        rta.setId(null);
        rta.setStyle(area.block != null && area.block.kind() == Block.Kind.ADMONITION ? "-fx-background-color: #FFF8E1; -fx-background-radius: 4;"
                : area.block != null && (area.block.kind() == Block.Kind.LISTING || area.block.kind() == Block.Kind.LITERAL)
                ? "-fx-background-color: #F4F3F1; -fx-background-radius: 4;" : "");
        area.holder.getChildren().removeIf(n -> "stampedDocumentEditButtons".equals(n.getId()));
        editing = null;
        area.decorate();
        applySelection(area);
        if (area.structured != null) {
            showStructured(area, true);
        }
    }

    // ── structured blocks (IKE-Network/ike-issues#1312) ───────────────────────

    /**
     * Reads a listing block through its structured kind, when a registered kind names its style:
     * the kind's control takes the place of the block's text, which stays the area's model, so the
     * block's lines keep their identity, dates and annotations. A kind that fails to read the block
     * leaves the text in place and says why.
     */
    private void attachStructured(Area area, StoredDocument document, StoredBlock block) {
        Optional<StructuredBlockKind> kind = StructuredBlocks.kindOf(block);
        if (kind.isEmpty()) {
            return;
        }
        StructuredBlockKind.Source source = StructuredBlocks.source(document, block);
        Node view;
        try {
            view = kind.get().view(source, new StructuredBlockKind.Context(host.view()));
        } catch (RuntimeException failure) {
            host.status("The " + kind.get().name() + " block could not be read; it is shown as its text: " + failure.getMessage());
            return;
        }
        area.structured = kind.get();
        area.structuredSource = source;
        area.structuredView = view;
        view.getStyleClass().add("stamped-document-structured-block");
        view.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && area.editable && editing == null) {
                startEdit(area);
                e.consume();
            }
        });
        area.holder.getChildren().add(area.holder.getChildren().indexOf(area.area) + 1, view);
        showStructured(area, true);
    }

    /** Shows a structured block's control (true) or its text (false). */
    private static void showStructured(Area area, boolean structured) {
        area.area.setVisible(!structured);
        area.area.setManaged(!structured);
        if (area.structuredView != null) {
            area.structuredView.setVisible(structured);
            area.structuredView.setManaged(structured);
        }
    }

    private Optional<StructuredBlockKind.Editor> structuredEditor(Area area) {
        try {
            return area.structured.editor(area.structuredSource, new StructuredBlockKind.Context(host.view()));
        } catch (RuntimeException failure) {
            host.status("The " + area.structured.name() + " editor could not open; the block is edited as its text: " + failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Opens a structured block's own editor in place of its control, with Save and Cancel beneath.
     * Save writes the editor's content between the block's fences and saves the block's text as
     * the person's pending edit, the same edit an in-place text edit saves.
     */
    private void startStructuredEdit(Area area, StructuredBlockKind.Editor editor) {
        editing = area;
        area.structuredEditor = editor;
        Node node = editor.node();
        node.setId("stampedDocumentStructuredEditor");
        area.structuredView.setVisible(false);
        area.structuredView.setManaged(false);
        int at = area.holder.getChildren().indexOf(area.structuredView) + 1;
        area.holder.getChildren().add(at, node);
        Button save = new Button("Save");
        save.setId("stampedDocumentSaveEdit");
        Button cancel = new Button("Cancel");
        cancel.setId("stampedDocumentCancelEdit");
        Label hint = new Label("Saved edits stay uncommitted in Komet until you commit the document in git.");
        hint.setFont(SMALL);
        hint.setStyle("-fx-text-fill: #5f6b7a;");
        HBox buttons = new HBox(6, save, cancel, hint);
        buttons.setId("stampedDocumentEditButtons");
        buttons.setAlignment(Pos.CENTER_LEFT);
        area.holder.getChildren().add(at + 1, buttons);
        cancel.setOnAction(e -> cancelEdit(area));
        save.setOnAction(e -> {
            String content;
            try {
                content = editor.content();
            } catch (IllegalStateException incomplete) {
                host.status(incomplete.getMessage());
                return;
            }
            host.saveEdit(area.block, area.structuredSource.withContent(content));
        });
        node.requestFocus();
    }

    /** @return the control reading a structured block, for tests and automation */
    Optional<Node> structuredViewOfBlock(long blockNid) {
        return Optional.ofNullable(areasByBlock.get(blockNid)).map(a -> a.structuredView);
    }

    /** Cancels whatever edit is in progress. */
    void cancelEdit() {
        if (editing != null) {
            cancelEdit(editing);
        }
    }

    /** Highlights (or unhighlights) a block's row, for the restore dialog. */
    void highlight(long block, boolean on) {
        Area area = areasByBlock.get(block);
        if (area == null || area.documentBlock == null) {
            return;
        }
        Node row = area.documentBlock.stackNode();
        row.setStyle(on ? "-fx-background-color: #FFF3C4; -fx-border-color: #b26a00; -fx-border-width: 2; -fx-border-radius: 4;" : "");
        if (on) {
            row.setId("stampedDocumentRestoreHighlight");
        } else if ("stampedDocumentRestoreHighlight".equals(row.getId())) {
            row.setId(null);
        }
    }

    /** @return the model of the editable block holding the line, for tests and automation */
    Optional<StyledTextModel> modelOfBlock(long blockNid) {
        return Optional.ofNullable(areasByBlock.get(blockNid)).map(a -> a.model);
    }
}
