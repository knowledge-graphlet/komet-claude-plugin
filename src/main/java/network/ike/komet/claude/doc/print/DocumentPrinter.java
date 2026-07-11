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
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;
import network.ike.komet.claude.doc.print.settings.PageSize;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.ui.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Prints a conversation's paged document via a {@link PrinterJob}, using the same
 * {@link PagedDocumentEngine} as the on-screen preview so the printout matches the preview
 * (WYSIWYG). The job gets its own engine instance, so printing never disturbs a live preview's
 * layout.
 *
 * <p>Must be called on the JavaFX application thread.
 */
public final class DocumentPrinter {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentPrinter.class);

    private DocumentPrinter() {
    }

    /**
     * Shows the system print dialog and, if the user proceeds, prints the conversation.
     *
     * @param turns       the conversation's turns, in order
     * @param viewCalc    the view for resolving concept chips; may be {@code null}
     * @param runningHead the running-head text (the conversation name); may be blank
     * @param settings    the print settings
     * @return {@code true} if a job was printed; {@code false} if there is no printer or the user
     *         canceled
     */
    public static boolean print(List<MarkdownRichText.Entry> turns, ViewCalculator viewCalc,
                                String runningHead, PrintSettings settings) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            LOG.warn("No printer available; cannot print the conversation document.");
            return false;
        }
        job.getJobSettings().setJobName("Conversation — "
                + (runningHead == null || runningHead.isBlank() ? "Komet Assistant" : runningHead));

        Printer printer = job.getPrinter();
        PageLayout initialLayout = pageLayoutFor(printer, settings);
        if (initialLayout != null) {
            job.getJobSettings().setPageLayout(initialLayout);
        }

        // Show the print panel with NO owner window: tying it to the journal window makes macOS route
        // cancelOperation: up that window's Cocoa responder chain on dismiss, which JavaFX's Glass view
        // does not handle → NSBeep on Cancel/Print. A null owner presents it standalone.
        if (!job.showPrintDialog(null)) {
            return false; // user canceled
        }
        PageLayout layout = job.getJobSettings().getPageLayout();

        // Lay the document out at the printer-independent full page size (identical to the preview),
        // then scale each page to fit the selected printer's printable area. So the printed layout
        // and page count match the preview regardless of which printer/paper the dialog chose, and
        // no content falls into the printer's unprintable margin.
        double[] paper = PagedDocumentEngine.pageDimensionsPoints(settings);
        PagedDocumentEngine engine = new PagedDocumentEngine(turns, viewCalc, runningHead, settings);
        engine.layout(paper[0], paper[1]);
        double scale = fitScale(layout, paper[0], paper[1]);

        boolean ok = true;
        int pages = engine.pageCount();
        for (int pageIndex = 0; pageIndex < pages && ok; pageIndex++) {
            Region page = engine.buildPage(pageIndex);
            if (scale < 1.0) {
                page.getTransforms().add(new Scale(scale, scale));
            }
            ok = job.printPage(layout, page);
            if (!ok) {
                LOG.warn("printPage failed at page {} of {}", pageIndex + 1, pages);
            }
        }
        if (ok) {
            job.endJob();
            LOG.info("Printed {} page(s) of the conversation document at scale {}.", pages, scale);
        } else {
            job.cancelJob();
        }
        return ok;
    }

    /**
     * The uniform scale that fits a full page of {@code paperW × paperH} points into the layout's
     * printable area, never upscaling.
     */
    private static double fitScale(PageLayout layout, double paperW, double paperH) {
        if (layout == null || paperW <= 0 || paperH <= 0) {
            return 1.0;
        }
        double pw = layout.getPrintableWidth();
        double ph = layout.getPrintableHeight();
        if (pw <= 0 || ph <= 0) {
            return 1.0;
        }
        return Math.min(1.0, Math.min(pw / paperW, ph / paperH));
    }

    /**
     * Builds a page layout for the settings' paper and orientation using the printer's hardware
     * minimum margins — so {@code getPrintable*} reports the true printable area the page is scaled
     * to fit. Falls back to the printer default when the paper is unsupported.
     */
    private static PageLayout pageLayoutFor(Printer printer, PrintSettings settings) {
        if (printer == null) {
            return null;
        }
        Paper paper = paperFor(settings.pageSize());
        PageOrientation orientation = settings.orientation().swapsDimensions()
                ? PageOrientation.LANDSCAPE : PageOrientation.PORTRAIT;
        try {
            PageLayout layout = printer.createPageLayout(paper, orientation,
                    Printer.MarginType.HARDWARE_MINIMUM);
            return layout != null ? layout : printer.getDefaultPageLayout();
        } catch (RuntimeException e) {
            LOG.warn("Falling back to the default page layout ({} {}): {}",
                    paper, orientation, e.toString());
            return printer.getDefaultPageLayout();
        }
    }

    private static Paper paperFor(PageSize pageSize) {
        return switch (pageSize) {
            case LETTER -> Paper.NA_LETTER;
            case A4 -> Paper.A4;
            case LEGAL -> Paper.LEGAL;
        };
    }
}
