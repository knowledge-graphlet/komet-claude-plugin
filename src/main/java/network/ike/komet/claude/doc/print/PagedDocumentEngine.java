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
package network.ike.komet.claude.doc.print;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import network.ike.komet.claude.doc.DocumentBlock;
import network.ike.komet.claude.doc.print.PrintSource.PrintBlock;
import network.ike.komet.claude.doc.print.settings.DocumentTheme;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PageNumberPlacement;
import network.ike.komet.claude.doc.print.settings.PrintSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Turns a {@link PrintSource}'s blocks — a conversation's turns, or a stamped document's stored
 * blocks — into paged, printable page nodes for a given page geometry: the shared engine behind
 * both the print preview and the {@link javafx.print.PrinterJob} loop, so the two are the same
 * layout (WYSIWYG).
 *
 * <p>It asks the source for a fresh print content column built for the theme (its serif body and
 * font size) rather than reparenting the live on-screen surface, measures every block in a
 * throwaway CSS-loaded {@link Scene}, asks {@link Pagination} where the page breaks fall (keeping
 * atomic blocks together and headings with what follows), then builds each page as margins +
 * optional running head and footer furniture + a clipped band of the column translated into view.
 * The running head names the document and the section at the top of the page — the section of
 * the first block that begins on it. Because printing
 * renders one page at a time, the single content column is reparented into each page as it is built;
 * give a print job its own engine instance so it never disturbs a live preview's engine.
 *
 * <p>Must be used on the JavaFX application thread. Call {@link #layout(double, double)} once for a
 * page geometry before {@link #pageCount()} or {@link #buildPage(int)}.
 */
public final class PagedDocumentEngine {

    /** Height reserved for a running-head or footer band, as a multiple of the base font size. */
    private static final double FURNITURE_LINES = 2.2;

    private final PrintSource source;
    private final ViewCalculator viewCalc;
    private final String runningHead;
    private final PrintSettings settings;
    private final String cssUrl;

    // Laid-out state, valid after layout(...).
    private VBox column;
    private double columnHeight;
    private double pageWidth;
    private double pageHeight;
    private double contentLeft;
    private double contentTop;
    private double contentWidth;
    private double bandHeight;
    private double headerBandHeight;
    private double footerBandHeight;
    private int pageCount = 1;
    /** The section each block falls under, parallel to the laid-out blocks. */
    private String[] sections = new String[0];
    /** The page each block begins on, parallel to the laid-out blocks. */
    private int[] pageOfBlock = new int[0];
    /** Each block's top within the spacer-padded column, in points, parallel to the laid-out blocks. */
    private double[] blockTops = new double[0];
    /** Each block's laid-out height, in points, parallel to the laid-out blocks. */
    private double[] blockHeights = new double[0];
    /** Builds a block's right-margin marginalia for a page, or {@code null}; set by a host that has some. */
    private IntFunction<Node> marginalia;
    /** The pitch of a body line for the widow and orphan rule, in points; 0 keeps the plain plan. */
    private double lineHeight;
    /** A live parent the column is measured in, so the stylesheets that will render it also size it; {@code null} measures in a throwaway scene. */
    private Pane measurementHost;

    /**
     * Measures the column inside a live parent rather than a throwaway scene: a page shown in an
     * application whose stylesheets size text differently from the defaults must be measured
     * under those stylesheets, or its blocks overlap. The column is added unmanaged and
     * invisible for the measurement and removed again.
     *
     * @param host a parent in the scene the pages will be shown in, or {@code null}
     */
    public void setMeasurementHost(Pane host) {
        this.measurementHost = host;
    }

    /** The line pitch of the body text as a multiple of the base font size — Noto Serif's ascender plus descender. */
    public static final double LINE_PITCH = 1.362;

    /**
     * Installs the marginalia a page draws beside its blocks, in the right margin at each block's
     * top — the identicons of a stamped document. The function answers {@code null} for a block
     * with none.
     *
     * @param marginalia the builder by block index, or {@code null} for none
     */
    public void setMarginalia(IntFunction<Node> marginalia) {
        this.marginalia = marginalia;
    }

    /**
     * Turns the widow and orphan rule on: a block that splits leaves at least two lines at the
     * foot and the head of a page, at the given line pitch.
     *
     * @param lineHeight the pitch of a body line in points; 0 turns the rule off
     */
    public void setLineHeight(double lineHeight) {
        this.lineHeight = Math.max(0, lineHeight);
    }

    /** @return the 0-based page each block begins on, parallel to the source's blocks; valid after {@link #layout} */
    public int[] pageOfBlock() {
        return pageOfBlock.clone();
    }

    /** @return each block's top within the column in points, parallel to the source's blocks; valid after {@link #layout} */
    public double[] blockTops() {
        return blockTops.clone();
    }

    /** @return each block's laid-out height in points; valid after {@link #layout} */
    public double[] blockHeights() {
        return blockHeights.clone();
    }

    /** @return the height of one page's content band in points; valid after {@link #layout} */
    public double bandHeight() {
        return bandHeight;
    }

    /**
     * @param source      what to paginate
     * @param viewCalc    the view for resolving concept chips; may be {@code null}
     * @param runningHead the running-head text (the conversation or document name); {@code null} renders blank
     * @param settings    the print settings driving geometry, theme, and furniture
     */
    public PagedDocumentEngine(PrintSource source, ViewCalculator viewCalc,
                               String runningHead, PrintSettings settings) {
        this.source = Objects.requireNonNull(source, "source is null");
        this.viewCalc = viewCalc;
        this.runningHead = runningHead == null ? "" : runningHead;
        this.settings = Objects.requireNonNull(settings, "settings is null");
        java.net.URL css = PagedDocumentEngine.class.getResource("/network/ike/komet/claude/print-theme.css");
        this.cssUrl = css == null ? null : css.toExternalForm();
    }

    /**
     * Lays the document out for a page of the given size (in points), rebuilding and measuring the
     * print column and planning page breaks. Idempotent — call again for a new geometry.
     *
     * @param pageWidthPoints  the page width in points (1/72&nbsp;inch)
     * @param pageHeightPoints the page height in points
     */
    public void layout(double pageWidthPoints, double pageHeightPoints) {
        this.pageWidth = pageWidthPoints;
        this.pageHeight = pageHeightPoints;

        DocumentTheme theme = settings.theme();
        MarginPreset m = settings.margins();
        double furniture = theme.baseFontSize() * FURNITURE_LINES;
        boolean headerBand = settings.runningHead().shown() || settings.pageNumbers().inHeader();
        // The footer's only content is the page number, so a footer band exists iff one is placed there.
        boolean footerBand = settings.pageNumbers().inFooter();
        this.headerBandHeight = headerBand ? furniture : 0;
        this.footerBandHeight = footerBand ? furniture : 0;
        this.contentLeft = m.left();
        this.contentTop = m.top() + headerBandHeight;
        this.contentWidth = Math.max(1, pageWidth - m.left() - m.right());
        this.bandHeight = Math.max(1,
                pageHeight - m.top() - m.bottom() - headerBandHeight - footerBandHeight);

        // Rebuild a fresh print column: theme font size + prose family (never the live surface), and
        // inert figures — a printed page carries no expand affordance.
        List<PrintBlock> sourceBlocks = source.blocks(theme, viewCalc);
        List<DocumentBlock> printBlocks = new ArrayList<>(sourceBlocks.size());
        this.printBlocks = printBlocks;
        this.sections = new String[sourceBlocks.size()];
        for (int i = 0; i < sourceBlocks.size(); i++) {
            printBlocks.add(sourceBlocks.get(i).block());
            sections[i] = sourceBlocks.get(i).section();
        }

        this.column = new VBox();
        column.setFillWidth(true);
        column.setSpacing(0); // separation comes from measured block padding, so page math is exact
        // No pixel snapping: a snapped VBox rounds every child's position and height to whole pixels, so
        // over many blocks the rendered column drifts below the plan's fractional heights and the last
        // line of a page shows through at the top of the next. Unsnapped, the render matches the plan.
        column.setSnapToPixel(false);
        column.getStyleClass().add(theme.styleClass());
        // Unmanaged: the column is positioned/sized explicitly (resizeRelocate + translateY) inside a
        // Pane. A managed child would be autosized by the Pane to its own pref width, overriding the
        // contentWidth the blocks were measured and paginated at — so the render would not match the
        // page plan. Unmanaged keeps our explicit geometry authoritative.
        column.setManaged(false);
        for (DocumentBlock block : printBlocks) {
            column.getChildren().add(block.node());
        }

        // Measure in a throwaway scene so CSS resolves and content-height areas settle their height —
        // or, when the host gave a live parent, inside it, under the stylesheets that will render the pages.
        Group measureRoot = null;
        if (measurementHost != null && measurementHost.getScene() != null) {
            column.setVisible(false);
            measurementHost.getChildren().add(column);
            if (cssUrl != null && !measurementHost.getStylesheets().contains(cssUrl)) {
                measurementHost.getStylesheets().add(cssUrl);
            }
            measurementHost.applyCss();
        } else {
            measureRoot = new Group(column);
            Scene measureScene = new Scene(measureRoot);
            if (cssUrl != null) {
                measureScene.getStylesheets().add(cssUrl);
            }
            measureRoot.applyCss();
        }
        column.applyCss();
        // Lay the column out at its NATURAL height, not one band's height. A VBox constrained to a
        // shorter height shrinks its children toward their min-heights, which would under-measure
        // tables and prose (so pagination under-counts and the tail is clipped off the last page).
        // Content-height areas report their height one layout late, and an area inside a holder
        // one more: resize to the fresh preferred height and lay out again until it settles.
        for (int pass = 0; pass < 4; pass++) {
            invalidateSizes(column);
            column.resize(contentWidth, column.prefHeight(contentWidth));
            column.layout();
        }

        List<Pagination.BlockBox> boxes = new ArrayList<>(printBlocks.size());
        for (int i = 0; i < printBlocks.size(); i++) {
            double h = printBlocks.get(i).node().getLayoutBounds().getHeight();
            // Pin the block to the height it was measured at: a content-height area reports its height a
            // layout late, and a page laid out once would otherwise give it a line too little and let
            // the next block overlap it. The plan and the render then agree to the point.
            if (printBlocks.get(i).node() instanceof Region region) {
                region.setMinHeight(h);
                region.setPrefHeight(h);
                region.setMaxHeight(h);
            }
            PrintBlock print = sourceBlocks.get(i);
            boxes.add(new Pagination.BlockBox(h, print.atomic(), print.keepWithNext()));
        }
        Pagination.PagePlan plan = Pagination.plan(boxes, bandHeight, lineHeight);
        this.pageCount = plan.pageCount();
        this.pageOfBlock = plan.pageOfBlock();
        this.blockHeights = new double[boxes.size()];
        this.blockTops = new double[boxes.size()];
        double top = 0;
        for (int i = 0; i < boxes.size(); i++) {
            top += plan.leadingSpacer()[i];
            blockTops[i] = top;
            blockHeights[i] = boxes.get(i).height();
            top += boxes.get(i).height();
        }

        // Re-assemble the column with keep-together spacers before the blocks that were pushed.
        List<Node> withSpacers = new ArrayList<>();
        for (int i = 0; i < printBlocks.size(); i++) {
            double spacer = plan.leadingSpacer()[i];
            if (spacer > 0) {
                Region gap = new Region();
                gap.setMinHeight(spacer);
                gap.setPrefHeight(spacer);
                gap.setMaxHeight(spacer);
                withSpacers.add(gap);
            }
            withSpacers.add(printBlocks.get(i).node());
        }
        column.getChildren().setAll(withSpacers);
        column.applyCss();
        for (int pass = 0; pass < 3; pass++) {
            invalidateSizes(column);
            this.columnHeight = column.prefHeight(contentWidth);
            column.resize(contentWidth, columnHeight);
            column.layout();
        }

        // Detach so buildPage can reparent the column into each page.
        if (measureRoot != null) {
            measureRoot.getChildren().clear();
        } else {
            measurementHost.getChildren().remove(column);
            column.setVisible(true);
        }
    }

    /**
     * Drops the cached preferred sizes of a subtree. A content-height area reports its height
     * after it has laid out, without invalidating the size its holder cached before, so a holder
     * with padding would keep answering the one-line height it computed first; a fresh request
     * for layout clears the caches up the tree.
     */
    private static void invalidateSizes(Node node) {
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                invalidateSizes(child);
            }
            parent.requestLayout();
        }
    }

    /**
     * The number of pages for the current geometry.
     *
     * @return the page count (at least 1); valid after {@link #layout(double, double)}
     */
    public int pageCount() {
        return pageCount;
    }

    private List<DocumentBlock> printBlocks = List.of();

    /**
     * Builds every page at once, stacked top to bottom with a gap between sheets — one continuous
     * scroll, as a PDF viewer shows a document, rather than one page at a time. Each sheet holds
     * the blocks that begin on it, and, for a block that runs over from the sheet before, a copy
     * of its rich text sharing the same model at the same width, clipped to the band, so the
     * continuation shows exactly the lines the sheet before cut off. The blocks leave the print
     * column: after this, {@link #buildPage(int)} no longer shows them (a print job builds its
     * own engine).
     *
     * @param gap the gap between sheets, in points
     * @return the stack, sized to its sheets
     */
    public Region buildStack(double gap) {
        VBox stack = new VBox(gap);
        stack.setFillWidth(false);
        stack.setAlignment(Pos.TOP_CENTER);
        stack.getStyleClass().add("print-page-stack");
        column.getChildren().clear();
        for (int p = 0; p < pageCount; p++) {
            Pane page = sheet(p);
            Pane clip = band();
            for (int i = 0; i < printBlocks.size(); i++) {
                double top = blockTops[i];
                double bottom = top + blockHeights[i];
                double bandTop = p * bandHeight;
                double bandBottom = bandTop + bandHeight;
                if (bottom <= bandTop + 0.01 || top >= bandBottom - 0.01) {
                    continue;
                }
                Node node = printBlocks.get(i).node();
                if (pageOfBlock[i] == p) {
                    node.setManaged(false);
                    if (node instanceof Region region) {
                        region.resizeRelocate(0, top - bandTop, contentWidth, blockHeights[i]);
                    } else {
                        node.relocate(0, top - bandTop);
                    }
                    clip.getChildren().add(node);
                } else {
                    Node copy = continuation(printBlocks.get(i), top - bandTop, blockHeights[i]);
                    if (copy != null) {
                        clip.getChildren().add(copy);
                    }
                }
            }
            page.getChildren().add(clip);
            marginaliaOn(page, p);
            furnishOn(page, p);
            stack.getChildren().add(page);
        }
        return stack;
    }

    /** A copy of a block's rich text over the same model, for the sheet it runs over to; {@code null} for a block without one. */
    private Node continuation(DocumentBlock block, double top, double height) {
        jfx.incubator.scene.control.richtext.RichTextArea original = block.richText();
        if (original == null) {
            return null;
        }
        jfx.incubator.scene.control.richtext.RichTextArea copy = new jfx.incubator.scene.control.richtext.RichTextArea(original.getModel());
        copy.setEditable(false);
        copy.setWrapText(original.isWrapText());
        copy.setUseContentHeight(true);
        copy.setDisplayCaret(false);
        copy.setHighlightCurrentParagraph(false);
        copy.setContentPadding(original.getContentPadding());
        copy.getStyleClass().setAll(original.getStyleClass());
        copy.setStyle(original.getStyle());
        // Where the rich text sits inside its block: the block's padding and whatever stands above it.
        double dx = 0;
        double dy = 0;
        Node at = original;
        while (at != null && at != block.node()) {
            dx += at.getLayoutX() + at.getTranslateX();
            dy += at.getLayoutY() + at.getTranslateY();
            at = at.getParent();
        }
        copy.setManaged(false);
        copy.resizeRelocate(dx, top + dy, original.getWidth() > 0 ? original.getWidth() : contentWidth, Math.max(1, original.getHeight()));
        return copy;
    }

    private Pane sheet(int pageIndex) {
        DocumentTheme theme = settings.theme();
        Pane page = new Pane();
        page.getStyleClass().add("print-page");
        page.getStyleClass().add(theme.styleClass());
        if (cssUrl != null) {
            page.getStylesheets().add(cssUrl);
        }
        page.setMinSize(pageWidth, pageHeight);
        page.setPrefSize(pageWidth, pageHeight);
        page.setMaxSize(pageWidth, pageHeight);
        page.setId("printPage" + (pageIndex + 1));
        return page;
    }

    private Pane band() {
        Pane clip = new Pane();
        clip.getStyleClass().add("print-content");
        clip.setLayoutX(contentLeft);
        clip.setLayoutY(contentTop);
        clip.setMinSize(contentWidth, bandHeight);
        clip.setPrefSize(contentWidth, bandHeight);
        clip.setMaxSize(contentWidth, bandHeight);
        clip.setClip(new Rectangle(contentWidth, bandHeight));
        return clip;
    }

    /** Marginalia: each block that begins on the page gets its node in the right margin at its top. */
    private void marginaliaOn(Pane page, int pageIndex) {
        if (marginalia == null) {
            return;
        }
        double marginLeft = contentLeft + contentWidth + 6;
        double marginWidth = Math.max(0, pageWidth - marginLeft - 4);
        for (int i = 0; i < pageOfBlock.length; i++) {
            if (pageOfBlock[i] != pageIndex) {
                continue;
            }
            Node node = marginalia.apply(i);
            if (node == null) {
                continue;
            }
            double y = contentTop + blockTops[i] - pageIndex * bandHeight;
            if (y < contentTop - 1 || y > contentTop + bandHeight) {
                continue;
            }
            node.setManaged(false);
            if (node instanceof Region region) {
                region.resizeRelocate(marginLeft, y, marginWidth, Math.max(16, Math.min(blockHeights[i], 40)));
            } else {
                node.relocate(marginLeft, y);
            }
            page.getChildren().add(node);
        }
    }

    /** The running head and the page number, per the settings. */
    private void furnishOn(Pane page, int pageIndex) {
        if (headerBandHeight > 0) {
            boolean headerNumber = settings.pageNumbers().inHeader();
            String head = runningHeadText(pageIndex);
            if (settings.runningHead().shown() && !head.isBlank()) {
                double headWidth = headerNumber ? contentWidth * 0.72 : contentWidth;
                page.getChildren().add(furniture(head, "print-running-head", Pos.CENTER_LEFT, settings.margins().top(), headWidth));
            }
            if (headerNumber) {
                page.getChildren().add(furniture(pageNumberText(pageIndex), "print-page-number", Pos.CENTER_RIGHT, settings.margins().top(), contentWidth));
            }
        }
        if (footerBandHeight > 0 && settings.pageNumbers().inFooter()) {
            Pos pos = settings.pageNumbers() == PageNumberPlacement.FOOTER_CENTER ? Pos.CENTER : Pos.CENTER_RIGHT;
            double y = pageHeight - settings.margins().bottom() - footerBandHeight;
            page.getChildren().add(footerFurniture(pageNumberText(pageIndex), pos, y));
        }
    }

    /**
     * Builds the node for one page: white paper of the full page size, the running head and footer
     * furniture (per the settings), and the content band — the print column clipped to one band and
     * translated so page {@code pageIndex}'s slice shows. The single content column is reparented
     * here, so only one page built by this engine is valid at a time.
     *
     * @param pageIndex the 0-based page to build
     * @return the page node, sized to the full page in points
     */
    public Region buildPage(int pageIndex) {
        DocumentTheme theme = settings.theme();
        Pane page = new Pane();
        page.getStyleClass().add("print-page");
        page.getStyleClass().add(theme.styleClass());
        if (cssUrl != null) {
            page.getStylesheets().add(cssUrl);
        }
        page.setMinSize(pageWidth, pageHeight);
        page.setPrefSize(pageWidth, pageHeight);
        page.setMaxSize(pageWidth, pageHeight);

        // Content band: clip the tall column to one band and translate to this page's slice.
        Pane clip = new Pane();
        clip.getStyleClass().add("print-content");
        clip.setLayoutX(contentLeft);
        clip.setLayoutY(contentTop);
        clip.setMinSize(contentWidth, bandHeight);
        clip.setPrefSize(contentWidth, bandHeight);
        clip.setMaxSize(contentWidth, bandHeight);
        clip.setClip(new Rectangle(contentWidth, bandHeight));
        column.resizeRelocate(0, 0, contentWidth, columnHeight);
        column.setTranslateY(-pageIndex * bandHeight);
        clip.getChildren().setAll(column); // reparents the column (auto-removed from any prior page)
        page.getChildren().add(clip);

        // Marginalia: each block that begins on this page gets its node in the right margin at its top.
        if (marginalia != null) {
            double marginLeft = contentLeft + contentWidth + 6;
            double marginWidth = Math.max(0, pageWidth - marginLeft - 4);
            for (int i = 0; i < pageOfBlock.length; i++) {
                if (pageOfBlock[i] != pageIndex) {
                    continue;
                }
                Node node = marginalia.apply(i);
                if (node == null) {
                    continue;
                }
                double y = contentTop + blockTops[i] - pageIndex * bandHeight;
                if (y < contentTop - 1 || y > contentTop + bandHeight) {
                    continue;
                }
                node.setManaged(false);
                if (node instanceof Region region) {
                    region.resizeRelocate(marginLeft, y, marginWidth, Math.max(16, Math.min(blockHeights[i], 40)));
                } else {
                    node.relocate(marginLeft, y);
                }
                page.getChildren().add(node);
            }
        }

        // Running head + optional header-band page number.
        if (headerBandHeight > 0) {
            boolean headerNumber = settings.pageNumbers().inHeader();
            String head = runningHeadText(pageIndex);
            if (settings.runningHead().shown() && !head.isBlank()) {
                // When a header page number shares the band, cap the running head's width so a long
                // title ellipsizes rather than overlapping the right-aligned number.
                double headWidth = headerNumber ? contentWidth * 0.72 : contentWidth;
                page.getChildren().add(furniture(head, "print-running-head",
                        Pos.CENTER_LEFT, settings.margins().top(), headWidth));
            }
            if (headerNumber) {
                page.getChildren().add(furniture(pageNumberText(pageIndex), "print-page-number",
                        Pos.CENTER_RIGHT, settings.margins().top(), contentWidth));
            }
        }
        // Footer-band page number.
        if (footerBandHeight > 0 && settings.pageNumbers().inFooter()) {
            Pos pos = settings.pageNumbers() == PageNumberPlacement.FOOTER_CENTER
                    ? Pos.CENTER : Pos.CENTER_RIGHT;
            double y = pageHeight - settings.margins().bottom() - footerBandHeight;
            page.getChildren().add(footerFurniture(pageNumberText(pageIndex), pos, y));
        }

        page.applyCss();
        page.layout();
        return page;
    }

    /** A furniture label in the header band, spanning {@code width} from the left content edge. */
    private Label furniture(String text, String styleClass, Pos alignment, double y, double width) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setAlignment(alignment);
        label.setWrapText(false);
        // Unmanaged so the Pane does not autosize it to its text width, which would discard the
        // width cap and re-introduce header/page-number overlap.
        label.setManaged(false);
        label.resizeRelocate(contentLeft, y, width, headerBandHeight);
        return label;
    }

    /** A footer furniture label spanning the content width at the given top. */
    private Label footerFurniture(String text, Pos alignment, double y) {
        Label label = new Label(text);
        label.getStyleClass().add("print-page-number");
        label.setAlignment(alignment);
        label.setWrapText(false);
        label.setManaged(false);
        label.resizeRelocate(contentLeft, y, contentWidth, footerBandHeight);
        return label;
    }

    private String pageNumberText(int pageIndex) {
        return (pageIndex + 1) + " / " + pageCount;
    }

    /**
     * The running head of a page: the document name, and the section at the top of the page when
     * the blocks name one — {@code name · section}.
     *
     * @param pageIndex the 0-based page
     * @return the running-head text, blank when there is neither a name nor a section
     */
    public String runningHeadText(int pageIndex) {
        String section = sectionAt(pageIndex);
        if (section.isBlank() || section.equals(runningHead)) {
            return runningHead;
        }
        return runningHead.isBlank() ? section : runningHead + " · " + section;
    }

    /**
     * The section at the top of a page: that of the first block beginning on it, or, when a block
     * runs over from the page before, that block's.
     *
     * @param pageIndex the 0-based page
     * @return the section, blank when no block names one
     */
    public String sectionAt(int pageIndex) {
        String carried = "";
        for (int i = 0; i < pageOfBlock.length; i++) {
            if (pageOfBlock[i] == pageIndex) {
                return sections[i];
            }
            if (pageOfBlock[i] > pageIndex) {
                break;
            }
            carried = sections[i];
        }
        return carried;
    }

    /**
     * The page dimensions in points for the given settings, applying the orientation swap — the
     * geometry the preview lays out at, and the print job's fallback when the printer reports no
     * printable area.
     *
     * @param settings the print settings
     * @return {@code [width, height]} in points
     */
    public static double[] pageDimensionsPoints(PrintSettings settings) {
        double w = settings.pageSize().widthPoints();
        double h = settings.pageSize().heightPoints();
        return settings.orientation().swapsDimensions()
                ? new double[]{h, w} : new double[]{w, h};
    }
}
