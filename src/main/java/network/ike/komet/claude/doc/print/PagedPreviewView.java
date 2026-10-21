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
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import network.ike.komet.claude.doc.print.settings.PrintSettings;

/**
 * The paged print preview as a plain {@link BorderPane} view — the full-surface content shown when
 * the document area is promoted (via {@code KlExpandable}). Every page of its own
 * {@link PagedDocumentEngine} stacked in one continuous vertical scroll, as a PDF viewer shows a
 * document (KEC, 2026-10-20: a page stepper is the HTML pagination approach, a strongly
 * discouraged anti-pattern for Komet), a footer that names the page under the eye, and a Print
 * button whose dialog and output are the same layout — so preview equals print. It carries the
 * print source and a snapshot of the settings; the host rebuilds a fresh view when either changes.
 * Must be used on the JavaFX application thread.
 */
public final class PagedPreviewView extends BorderPane {

    /** The gap between sheets, in points. */
    static final double GAP = 18;

    private final PrintSource source;
    private final ViewCalculator viewCalc;
    private final String runningHead;
    private final PrintSettings settings;

    private final StackPane pageCanvas = new StackPane();
    private final ScrollPane pageScroll = new ScrollPane(pageCanvas);
    private final Label pageLabel = new Label();

    private PagedDocumentEngine engine;
    private double pageHeight;

    /**
     * @param source      what to render
     * @param viewCalc    the view for resolving concept chips; may be {@code null}
     * @param runningHead the running-head text (the conversation or document name); may be blank
     * @param settings    the print settings
     */
    public PagedPreviewView(PrintSource source, ViewCalculator viewCalc,
                            String runningHead, PrintSettings settings) {
        this.source = source == null ? PrintSource.EMPTY : source;
        this.viewCalc = viewCalc;
        this.runningHead = runningHead == null ? "" : runningHead;
        this.settings = settings == null ? PrintSettings.DEFAULT : settings;
        buildUi();
        rebuildEngine();
    }

    private void buildUi() {
        Button print = new Button("Print…");
        print.setOnAction(e -> DocumentPrinter.print(source, viewCalc, runningHead, settings));
        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        pageLabel.setId("printPreviewPageLabel");
        ToolBar bar = new ToolBar(print, spring, pageLabel);
        setTop(bar);

        pageCanvas.setPadding(new Insets(24));
        pageCanvas.setStyle("-fx-background-color: #6f6f6f;");
        pageScroll.setFitToWidth(true);
        pageScroll.setStyle("-fx-background: #6f6f6f;");
        pageScroll.setPannable(false);
        pageScroll.vvalueProperty().addListener((obs, was, is) -> namePage());
        pageScroll.viewportBoundsProperty().addListener((obs, was, is) -> namePage());
        setCenter(pageScroll);
    }

    private void rebuildEngine() {
        double[] dims = PagedDocumentEngine.pageDimensionsPoints(settings);
        engine = new PagedDocumentEngine(source, viewCalc, runningHead, settings);
        engine.layout(dims[0], dims[1]);
        pageHeight = dims[1];
        pageCanvas.getChildren().setAll(engine.buildStack(GAP));
        namePage();
    }

    /** @return the number of pages */
    public int pageCount() {
        return engine == null ? 0 : engine.pageCount();
    }

    /** @return the 0-based page under the eye: the one at the upper third of the viewport */
    public int currentPage() {
        if (engine == null || engine.pageCount() == 0) {
            return 0;
        }
        double scrollable = Math.max(0, pageCanvas.getHeight() - pageScroll.getViewportBounds().getHeight());
        double top = pageScroll.getVvalue() * scrollable + pageScroll.getViewportBounds().getHeight() / 3 - pageCanvas.getPadding().getTop();
        return Math.clamp((int) Math.floor(top / (pageHeight + GAP)), 0, engine.pageCount() - 1);
    }

    /** Scrolls so a page's top edge sits at the viewport's top. */
    public void scrollToPage(int page) {
        if (engine == null) {
            return;
        }
        layout();
        double target = pageCanvas.getPadding().getTop() + Math.clamp(page, 0, engine.pageCount() - 1) * (pageHeight + GAP);
        double scrollable = pageCanvas.getHeight() - pageScroll.getViewportBounds().getHeight();
        pageScroll.setVvalue(scrollable <= 0 ? 0 : Math.clamp(target / scrollable, 0, 1));
    }

    private void namePage() {
        pageLabel.setText(engine == null ? "" : "Page " + (currentPage() + 1) + " of " + engine.pageCount());
    }
}
