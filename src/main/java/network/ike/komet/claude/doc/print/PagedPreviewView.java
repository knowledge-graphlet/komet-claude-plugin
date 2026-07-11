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
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.control.ToolBar;
import javafx.scene.paint.Color;
import network.ike.komet.claude.doc.print.settings.PrintSettings;
import network.ike.komet.claude.ui.MarkdownRichText;

import java.util.List;

/**
 * The paged print preview as a plain {@link BorderPane} view — the full-surface content shown when
 * the document area is promoted (via {@code KlExpandable}). A page canvas over its own
 * {@link PagedDocumentEngine}, a page-nav bar (◀ n/total ▶), and a Print button whose dialog and
 * output are the same layout — so preview equals print. It carries a snapshot of the turns and
 * settings; the host rebuilds a fresh view when either changes. Must be used on the JavaFX
 * application thread.
 */
public final class PagedPreviewView extends BorderPane {

    private final List<MarkdownRichText.Entry> turns;
    private final ViewCalculator viewCalc;
    private final String runningHead;
    private final PrintSettings settings;

    private final StackPane pageCanvas = new StackPane();
    private final ScrollPane pageScroll = new ScrollPane(pageCanvas);
    private final Label pageLabel = new Label();
    private final Button prevButton = new Button("◀");
    private final Button nextButton = new Button("▶");

    private PagedDocumentEngine engine;
    private int currentPage;

    /**
     * @param turns       the conversation's turns to render (a snapshot)
     * @param viewCalc    the view for resolving concept chips; may be {@code null}
     * @param runningHead the running-head text (the conversation name); may be blank
     * @param settings    the print settings
     */
    public PagedPreviewView(List<MarkdownRichText.Entry> turns, ViewCalculator viewCalc,
                            String runningHead, PrintSettings settings) {
        this.turns = turns == null ? List.of() : turns;
        this.viewCalc = viewCalc;
        this.runningHead = runningHead == null ? "" : runningHead;
        this.settings = settings == null ? PrintSettings.DEFAULT : settings;
        buildUi();
        rebuildEngine();
        showPage(0);
    }

    private void buildUi() {
        Button print = new Button("Print…");
        print.setOnAction(e -> DocumentPrinter.print(turns, viewCalc, runningHead, settings));
        prevButton.setOnAction(e -> showPage(currentPage - 1));
        nextButton.setOnAction(e -> showPage(currentPage + 1));

        Region spring = new Region();
        HBox.setHgrow(spring, Priority.ALWAYS);
        ToolBar bar = new ToolBar(print, spring, prevButton, pageLabel, nextButton);
        setTop(bar);

        pageCanvas.setPadding(new Insets(24));
        pageCanvas.setStyle("-fx-background-color: #6f6f6f;");
        pageScroll.setFitToWidth(true);
        pageScroll.setFitToHeight(true);
        pageScroll.setStyle("-fx-background: #6f6f6f;");
        setCenter(pageScroll);
    }

    private void rebuildEngine() {
        double[] dims = PagedDocumentEngine.pageDimensionsPoints(settings);
        engine = new PagedDocumentEngine(turns, viewCalc, runningHead, settings);
        engine.layout(dims[0], dims[1]);
    }

    private void showPage(int index) {
        if (engine == null) {
            return;
        }
        int last = Math.max(0, engine.pageCount() - 1);
        currentPage = Math.clamp(index, 0, last);
        Region page = engine.buildPage(currentPage);
        page.setEffect(new DropShadow(18, 0, 4, Color.rgb(0, 0, 0, 0.4)));
        pageCanvas.getChildren().setAll(page);
        pageLabel.setText((currentPage + 1) + " / " + engine.pageCount());
        prevButton.setDisable(currentPage <= 0);
        nextButton.setDisable(currentPage >= last);
    }
}
