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
import network.ike.komet.claude.doc.BlockFactory;
import network.ike.komet.claude.doc.DocumentBlock;
import network.ike.komet.claude.doc.print.settings.DocumentTheme;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PageNumberPlacement;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a conversation's turns into paged, printable page nodes for a given page geometry — the
 * shared engine behind both the print preview and the {@link javafx.print.PrinterJob} loop, so the
 * two are the same layout (WYSIWYG).
 *
 * <p>It rebuilds a fresh print content column via a print-configured {@link BlockFactory} (the
 * theme's serif body and font size) rather than reparenting the live on-screen surface, measures
 * every block in a throwaway CSS-loaded {@link Scene}, asks {@link Pagination} where the page breaks
 * fall (keeping headers/trees/tables together), then builds each page as margins + optional running
 * head and footer furniture + a clipped band of the column translated into view. Because printing
 * renders one page at a time, the single content column is reparented into each page as it is built;
 * give a print job its own engine instance so it never disturbs a live preview's engine.
 *
 * <p>Must be used on the JavaFX application thread. Call {@link #layout(double, double)} once for a
 * page geometry before {@link #pageCount()} or {@link #buildPage(int)}.
 */
public final class PagedDocumentEngine {

    /** Height reserved for a running-head or footer band, as a multiple of the base font size. */
    private static final double FURNITURE_LINES = 2.2;

    private final List<MarkdownRichText.Entry> turns;
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

    /**
     * @param turns       the conversation's turns, in order
     * @param viewCalc    the view for resolving concept chips; may be {@code null}
     * @param runningHead the running-head text (the conversation name); {@code null} renders blank
     * @param settings    the print settings driving geometry, theme, and furniture
     */
    public PagedDocumentEngine(List<MarkdownRichText.Entry> turns, ViewCalculator viewCalc,
                               String runningHead, PrintSettings settings) {
        this.turns = List.copyOf(Objects.requireNonNull(turns, "turns is null"));
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
        BlockFactory factory = BlockFactory.forPrint(viewCalc, theme.baseFontSize(), theme.fontFamily());
        List<DocumentBlock> printBlocks = new ArrayList<>();
        for (MarkdownRichText.Entry turn : turns) {
            printBlocks.addAll(factory.turnBlocks(turn));
        }

        this.column = new VBox();
        column.setFillWidth(true);
        column.setSpacing(0); // separation comes from measured block padding, so page math is exact
        column.getStyleClass().add(theme.styleClass());
        // Unmanaged: the column is positioned/sized explicitly (resizeRelocate + translateY) inside a
        // Pane. A managed child would be autosized by the Pane to its own pref width, overriding the
        // contentWidth the blocks were measured and paginated at — so the render would not match the
        // page plan. Unmanaged keeps our explicit geometry authoritative.
        column.setManaged(false);
        for (DocumentBlock block : printBlocks) {
            column.getChildren().add(block.node());
        }

        // Measure in a throwaway scene so CSS resolves and content-height areas settle their height.
        Group measureRoot = new Group(column);
        Scene measureScene = new Scene(measureRoot);
        if (cssUrl != null) {
            measureScene.getStylesheets().add(cssUrl);
        }
        measureRoot.applyCss();
        column.applyCss();
        // Lay the column out at its NATURAL height, not one band's height. A VBox constrained to a
        // shorter height shrinks its children toward their min-heights, which would under-measure
        // tables and prose (so pagination under-counts and the tail is clipped off the last page).
        column.resize(contentWidth, column.prefHeight(contentWidth));
        column.layout();
        column.layout(); // second pass: useContentHeight areas report height one layout late

        List<Pagination.BlockBox> boxes = new ArrayList<>(printBlocks.size());
        for (DocumentBlock block : printBlocks) {
            double h = block.node().getLayoutBounds().getHeight();
            boolean atomic = block.richText() == null; // headers, trees, tables keep together
            boxes.add(new Pagination.BlockBox(h, atomic));
        }
        Pagination.PagePlan plan = Pagination.plan(boxes, bandHeight);
        this.pageCount = plan.pageCount();

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
        this.columnHeight = column.prefHeight(contentWidth);
        column.resize(contentWidth, columnHeight);
        column.applyCss();
        column.layout();

        // Detach so buildPage can reparent the column into each page.
        measureRoot.getChildren().clear();
    }

    /**
     * The number of pages for the current geometry.
     *
     * @return the page count (at least 1); valid after {@link #layout(double, double)}
     */
    public int pageCount() {
        return pageCount;
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

        // Running head + optional header-band page number.
        if (headerBandHeight > 0) {
            boolean headerNumber = settings.pageNumbers().inHeader();
            if (settings.runningHead().shown() && !runningHead.isBlank()) {
                // When a header page number shares the band, cap the running head's width so a long
                // title ellipsizes rather than overlapping the right-aligned number.
                double headWidth = headerNumber ? contentWidth * 0.72 : contentWidth;
                page.getChildren().add(furniture(runningHead, "print-running-head",
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
