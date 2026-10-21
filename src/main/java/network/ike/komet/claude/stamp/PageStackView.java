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

import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import network.ike.komet.claude.doc.print.settings.MarginPreset;
import network.ike.komet.claude.doc.print.settings.PageNumberPlacement;
import network.ike.komet.claude.doc.print.settings.PrintSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Page layout view's pages, stacked in one continuous vertical scroll like a PDF viewer's
 * (KEC, 2026-10-20: a stepper through pages is the HTML pagination approach, a strongly
 * discouraged anti-pattern for Komet). Every page is a white sheet of the print settings' size
 * at a point per pixel, with a gap between sheets and the running head and folio drawn in the
 * margins as Typst draws them; the sheets' content — the surface's line areas at the layout
 * oracle's baselines, and the marginalia beside them — is built only for the sheets near the
 * viewport and dropped again once they are far from it, so a long document costs as much as
 * the few pages in view. The page under the reader's eye is a property the card's footer shows;
 * a jump to a page scrolls.
 */
final class PageStackView extends ScrollPane {

    /** The gap between sheets, in points. */
    static final double GAP = 18;
    /** How many sheets beyond the visible ones are kept built on either side. */
    private static final int KEEP = 2;

    /**
     * One line area placed on a page: the node, its left edge and baseline on the page, its size,
     * and the horizontal scale that fits a line JavaFX lays a hair wider than Typst did into
     * Typst's measure (1 for none).
     */
    record Placed(Node node, double x, double baseline, double ascent, double width, double height, boolean clip, double scaleX) {

        Placed(Node node, double x, double baseline, double ascent, double width, double height, boolean clip) {
            this(node, x, baseline, ascent, width, height, clip, 1);
        }
    }

    /** One marginal node placed on a page beside a block's first line. */
    record Mark(Node node, double baseline, double ascent) { }

    /** Where the reader is: a unit and the line of it at the viewport's top, and that line's offset from the top. */
    record Anchor(int unit, int lineOfUnit, double offset) { }

    /** What the sheets show. */
    interface Content {

        /** @return the page count, at least 1 */
        int pageCount();

        /** @return the line areas on a page, in order */
        List<Placed> lines(int page);

        /** @return the marginalia on a page */
        List<Mark> marginalia(int page);

        /** @return the section the running head names on a page, blank for none */
        String section(int page);

        /** @return the unit and the line of it a placed line stands for, as {@code {unit, line}} */
        int[] identity(Placed placed);
    }

    private final PrintSettings settings;
    private final double pageWidth;
    private final double pageHeight;
    private final String runningHead;
    private final VBox stack = new VBox(GAP);
    private final List<Pane> sheets = new ArrayList<>();
    private final List<Boolean> built = new ArrayList<>();
    private final ReadOnlyIntegerWrapper currentPage = new ReadOnlyIntegerWrapper(0);
    private Content content;

    /**
     * @param settings    the print settings: margins, furniture
     * @param pageWidth   the page width in points
     * @param pageHeight  the page height in points
     * @param runningHead the document's running head
     */
    PageStackView(PrintSettings settings, double pageWidth, double pageHeight, String runningHead) {
        this.settings = settings;
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.runningHead = runningHead == null ? "" : runningHead;
        setId("stampedDocumentPageStack");
        getStyleClass().add("stamped-page-stack");
        stack.setAlignment(Pos.TOP_CENTER);
        stack.setPadding(new Insets(GAP, GAP, GAP, GAP));
        stack.setFillWidth(false);
        setContent(stack);
        setFitToWidth(true);
        setPannable(false);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        vvalueProperty().addListener((obs, was, is) -> viewportMoved());
        viewportBoundsProperty().addListener((obs, was, is) -> viewportMoved());
    }

    /** Shows content: rebuilds the sheets, keeping the scroll position's page where it still exists. */
    void show(Content content) {
        this.content = content;
        sheets.clear();
        built.clear();
        stack.getChildren().clear();
        for (int p = 0; p < content.pageCount(); p++) {
            Pane sheet = new Pane();
            sheet.setId("stampedDocumentPage" + (p + 1));
            sheet.getStyleClass().add("stamped-page");
            sheet.setMinSize(pageWidth, pageHeight);
            sheet.setPrefSize(pageWidth, pageHeight);
            sheet.setMaxSize(pageWidth, pageHeight);
            sheet.setClip(new Rectangle(pageWidth, pageHeight));
            sheets.add(sheet);
            built.add(false);
            stack.getChildren().add(sheet);
        }
        viewportMoved();
    }

    /** @return the 0-based page under the reader's eye: the one at the upper third of the viewport */
    ReadOnlyIntegerProperty currentPageProperty() {
        return currentPage.getReadOnlyProperty();
    }

    /** @return the page count shown */
    int pageCount() {
        return sheets.size();
    }

    /** @return whether a page's content is built (near the viewport) */
    boolean isBuilt(int page) {
        return page >= 0 && page < built.size() && built.get(page);
    }

    /** Scrolls so a page's top edge sits at the viewport's top. */
    void scrollToPage(int page) {
        scrollTo(Math.clamp(page, 0, Math.max(0, sheets.size() - 1)), 0, 0);
    }

    /**
     * Scrolls so a point on a page sits at an offset from the viewport's top.
     *
     * @param page   the 0-based page
     * @param y      the point's distance from the page's top edge
     * @param offset where it lands in the viewport, from the top
     */
    void scrollTo(int page, double y, double offset) {
        if (sheets.isEmpty()) {
            return;
        }
        layout();
        double target = sheetTop(page) + y - offset;
        double scrollable = stack.getHeight() - getViewportBounds().getHeight();
        setVvalue(scrollable <= 0 ? 0 : Math.clamp(target / scrollable, 0, 1));
        viewportMoved();
    }

    /** @return the reader's place: the first line at or below the viewport's top, and its offset from it */
    Optional<Anchor> anchor() {
        if (content == null || sheets.isEmpty()) {
            return Optional.empty();
        }
        double top = viewportTop();
        for (int p = Math.max(0, pageAt(top)); p < sheets.size(); p++) {
            double sheetTop = sheetTop(p);
            for (Placed placed : content.lines(p)) {
                double lineTop = sheetTop + placed.baseline() - placed.ascent();
                if (lineTop >= top - 1) {
                    int[] id = content.identity(placed);
                    return Optional.of(new Anchor(id[0], id[1], lineTop - top));
                }
            }
        }
        return Optional.empty();
    }

    /** Scrolls so the anchored line is where it was; the unit's first line stands in when the line count changed. */
    void restore(Anchor anchor) {
        if (content == null || anchor == null) {
            return;
        }
        Placed fallback = null;
        int fallbackPage = -1;
        for (int p = 0; p < sheets.size(); p++) {
            for (Placed placed : content.lines(p)) {
                int[] id = content.identity(placed);
                if (id[0] != anchor.unit()) {
                    continue;
                }
                if (id[1] == anchor.lineOfUnit()) {
                    scrollTo(p, placed.baseline() - placed.ascent(), anchor.offset());
                    return;
                }
                if (fallback == null) {
                    fallback = placed;
                    fallbackPage = p;
                }
            }
        }
        if (fallback != null) {
            scrollTo(fallbackPage, fallback.baseline() - fallback.ascent(), anchor.offset());
        }
    }

    // ── sheets ────────────────────────────────────────────────────────────────

    private double sheetTop(int page) {
        return stack.getPadding().getTop() + page * (pageHeight + GAP);
    }

    private int pageAt(double y) {
        return (int) Math.floor((y - stack.getPadding().getTop()) / (pageHeight + GAP));
    }

    private double viewportTop() {
        double scrollable = Math.max(0, stack.getHeight() - getViewportBounds().getHeight());
        return getVvalue() * scrollable;
    }

    /** Builds the sheets near the viewport, drops the far ones, and names the page under the eye. */
    private void viewportMoved() {
        if (content == null || sheets.isEmpty()) {
            return;
        }
        double top = viewportTop();
        double height = Math.max(1, getViewportBounds().getHeight());
        int first = Math.clamp(pageAt(top), 0, sheets.size() - 1);
        int last = Math.clamp(pageAt(top + height), 0, sheets.size() - 1);
        for (int p = 0; p < sheets.size(); p++) {
            boolean near = p >= first - KEEP && p <= last + KEEP;
            if (near && !built.get(p)) {
                build(p);
            } else if (!near && built.get(p)) {
                sheets.get(p).getChildren().clear();
                built.set(p, false);
            }
        }
        int eye = Math.clamp(pageAt(top + height / 3), 0, sheets.size() - 1);
        if (eye != currentPage.get()) {
            currentPage.set(eye);
        }
    }

    private void build(int page) {
        Pane sheet = sheets.get(page);
        sheet.getChildren().clear();
        furnish(sheet, page);
        MarginPreset m = settings.margins();
        for (Placed placed : content.lines(page)) {
            Node node = placed.node();
            node.setManaged(false);
            double top = placed.baseline() - placed.ascent();
            if (node instanceof Region region) {
                if (placed.clip()) {
                    // Tall enough for the line and the justify filler's line beneath it — a region shorter than its
                    // content grows a scroll bar, which narrows the measure and wraps the line's last word away —
                    // and clipped to the line alone.
                    region.resizeRelocate(placed.x(), top, placed.width(), placed.height() * 2);
                    region.setClip(new Rectangle(0, 0, placed.width(), placed.height()));
                } else {
                    region.resizeRelocate(placed.x(), top, placed.width(), placed.height());
                }
            } else {
                node.relocate(placed.x(), top);
            }
            if (placed.scaleX() < 1) {
                // Scaled about its left edge: the line keeps Typst's left and right edges, its glyphs a hair narrower.
                node.setScaleX(placed.scaleX());
                node.setTranslateX(-(1 - placed.scaleX()) * placed.width() / 2);
            }
            sheet.getChildren().add(node);
        }
        double marginLeft = pageWidth - m.right() + 6;
        double marginWidth = Math.max(0, m.right() - 10);
        for (Mark mark : content.marginalia(page)) {
            Node node = mark.node();
            node.setManaged(false);
            if (node instanceof Region region) {
                region.resizeRelocate(marginLeft, mark.baseline() - mark.ascent(), marginWidth, 16);
            } else {
                node.relocate(marginLeft, mark.baseline() - mark.ascent());
            }
            sheet.getChildren().add(node);
        }
        built.set(page, true);
        sheet.applyCss();
        sheet.layout();
    }

    /** The running head and folio in the margins, where Typst puts them: the head 30% of the top margin above the content, the folio 30% of the bottom margin below. */
    private void furnish(Pane sheet, int page) {
        MarginPreset m = settings.margins();
        double size = settings.theme().baseFontSize() * 0.8;
        double left = m.left();
        double width = pageWidth - m.left() - m.right();
        PageNumberPlacement numbers = settings.pageNumbers();
        boolean head = settings.runningHead().shown();
        String folio = Integer.toString(page + 1);
        if (head || numbers.inHeader()) {
            double bottom = m.top() * 0.7;
            double height = size * 1.4;
            if (head) {
                String section = content.section(page);
                String text = runningHead + (section.isBlank() || section.equals(runningHead) ? "" : "    " + section);
                Label label = furniture(text, size, Pos.CENTER_LEFT, left, bottom - height, numbers.inHeader() ? width * 0.8 : width, height);
                label.getStyleClass().add("stamped-page-running-head");
                sheet.getChildren().add(label);
                Line rule = new Line(left, bottom + 1, left + width, bottom + 1);
                rule.setStroke(javafx.scene.paint.Color.rgb(170, 170, 170));
                rule.setStrokeWidth(0.4);
                sheet.getChildren().add(rule);
            }
            if (numbers.inHeader()) {
                Label label = furniture(folio, size, Pos.CENTER_RIGHT, left, bottom - height, width, height);
                label.getStyleClass().add("stamped-page-folio");
                sheet.getChildren().add(label);
            }
        }
        if (numbers.inFooter()) {
            double top = pageHeight - m.bottom() + m.bottom() * 0.3;
            Label label = furniture(folio, size, numbers == PageNumberPlacement.FOOTER_CENTER ? Pos.CENTER : Pos.CENTER_RIGHT, left, top, width, size * 1.4);
            label.getStyleClass().add("stamped-page-folio");
            sheet.getChildren().add(label);
        }
    }

    private static Label furniture(String text, double size, Pos alignment, double x, double y, double width, double height) {
        Label label = new Label(text);
        label.setStyle("-fx-text-fill: #464646; -fx-font-size: " + size + "; -fx-font-family: '" + network.ike.komet.claude.stamp.print.DocumentFonts.SERIF + "';");
        label.setAlignment(alignment);
        label.setWrapText(false);
        label.setManaged(false);
        label.resizeRelocate(x, y, width, height);
        return label;
    }
}
