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
package network.ike.komet.claude.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** A figure that fits its region: scaled down when wider, never scrolled, with the escape offered only when scaled. */
class FittedFigureTest {

    @BeforeAll
    static void startJavaFx() {
        try {
            Class.forName("com.sun.glass.ui.monocle.MonoclePlatformFactory");
            System.setProperty("glass.platform", "Monocle");
            System.setProperty("monocle.platform", "Headless");
            System.setProperty("prism.order", "sw");
            System.setProperty("prism.text", "t2k");
            System.setProperty("java.awt.headless", "true");
        } catch (ClassNotFoundException ignored) {
            // the default toolkit
        }
        try {
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyRunning) {
            // started by another test
        }
    }

    @Test
    void aWideFigureIsScaledToItsRegionAndOffersTheEscape() throws Exception {
        onFx(() -> {
            Region figure = new Region();
            figure.setPrefSize(1000, 400);
            FittedFigure fit = new FittedFigure(figure, 13, width -> { }, () -> { });
            new Scene(new Pane(fit));
            fit.resize(500, fit.prefHeight(500));
            fit.layout();
            assertEquals(0.5, fit.scale(), 1e-9);
            assertEquals(200, fit.prefHeight(500), 1e-9, "the height follows the scale");
            assertTrue(expand(fit).isVisible(), "a scaled figure offers to open whole");

            fit.resize(1200, fit.prefHeight(1200));
            fit.layout();
            assertEquals(1, fit.scale(), 1e-9, "a figure that fits is never enlarged");
            assertEquals(400, fit.prefHeight(1200), 1e-9);
            assertFalse(expand(fit).isVisible());
        });
    }

    @Test
    void aWideFigureStandsIntoTheMarginsOfItsRow() throws Exception {
        onFx(() -> {
            Region figure = new Region();
            figure.setPrefSize(1000, 400);
            FittedFigure fit = new FittedFigure(figure, 13, width -> { }, () -> { });
            GridPane row = new GridPane();
            row.getStyleClass().add("doc-block-row");
            for (double percent : new double[] {100.0 * 2 / 12, 100.0 * 8 / 12, 100.0 * 2 / 12}) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(percent);
                row.getColumnConstraints().add(column);
            }
            row.add(fit, 1, 0);
            new Scene(new Pane(row));
            double body = 400;
            double margin = body / 4 + 8;
            double width = body + 2 * margin;
            fit.resize(body, fit.prefHeight(body));
            fit.layout();
            assertEquals(width / 1000, fit.scale(), 1e-9, "the row's whole width, not the body's");
            assertEquals(-margin, figure.getLayoutX(), 1e-9, "from the left margin's edge");
            assertEquals(400 * width / 1000, fit.prefHeight(body), 1e-9);

            figure.setPrefSize(300, 400);
            fit.layout();
            assertEquals(1, fit.scale(), 1e-9);
            assertEquals(0, figure.getLayoutX(), 1e-9, "a figure that fits stays with the column");
        });
    }

    @Test
    void aFigureThatReflowsIsLaidOutAtItsRegionDownToItsLeastWidthAndOnlyThenScaled() throws Exception {
        onFx(() -> {
            Region figure = new Region();
            figure.setPrefSize(1000, 400);
            FittedFigure fit = new FittedFigure(figure, 13, 600);
            new Scene(new Pane(fit));
            fit.resize(800, fit.prefHeight(800));
            fit.layout();
            assertEquals(800, figure.getWidth(), 1e-9, "the region's width");
            assertEquals(1, fit.scale(), 1e-9);

            fit.resize(300, fit.prefHeight(300));
            fit.layout();
            assertEquals(600, figure.getWidth(), 1e-9, "never narrower than its least");
            assertEquals(0.5, fit.scale(), 1e-9);
            assertFalse(expand(fit).isVisible(), "no escape was given");
        });
    }

    private static Node expand(FittedFigure fit) {
        return fit.getChildrenUnmodifiable().stream().filter(n -> "decision-tree-expand".equals(n.getId())).findFirst().orElseThrow();
    }

    private static void onFx(Runnable body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failed.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            fail("the JavaFX thread did not answer");
        }
        if (failed.get() instanceof Exception e) {
            throw e;
        }
        if (failed.get() instanceof Error e) {
            throw e;
        }
    }
}
