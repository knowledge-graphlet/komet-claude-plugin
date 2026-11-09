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
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** A row that keeps its pieces whole: those that do not fit on a line go to the line beneath. */
class WrappedRowTest {

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

    private static Region box(double width, double height) {
        Region box = new Region();
        box.setPrefSize(width, height);
        return box;
    }

    private static WrappedRow laidOut(WrappedRow row, double width) {
        new Scene(new Pane(row));
        row.resize(width, row.prefHeight(width));
        row.layout();
        return row;
    }

    @Test
    void piecesThatFitStandOnOneLine() throws Exception {
        onFx(() -> {
            Region lead = box(10, 10);
            Region topic = box(100, 20);
            Region result = box(150, 16);
            Region name = box(80, 12);
            WrappedRow row = new WrappedRow(8, 2);
            row.show(lead, List.of(topic, result, name));
            assertEquals(10 + 8 + 100 + 8 + 150 + 8 + 80, row.prefWidth(-1), 1e-9, "on one line: the lead, the pieces, the gaps");
            laidOut(row, 400);
            assertEquals(1, row.lines());
            assertEquals(20, row.getHeight(), 1e-9, "as tall as its tallest piece");
            assertEquals(18, topic.getLayoutX(), 1e-9, "the pieces begin past the lead");
            assertEquals(126, result.getLayoutX(), 1e-9);
            assertEquals(2, result.getLayoutY(), 1e-9, "a shorter piece is centred on the line");
            assertEquals(5, lead.getLayoutY(), 1e-9, "and so is the lead");
        });
    }

    @Test
    void aPieceThatDoesNotFitGoesToTheLineBeneathWhole() throws Exception {
        onFx(() -> {
            Region lead = box(10, 10);
            Region topic = box(100, 20);
            Region result = box(150, 16);
            Region name = box(80, 12);
            WrappedRow row = new WrappedRow(8, 2);
            row.show(lead, List.of(topic, result, name));
            laidOut(row, 300);
            assertEquals(2, row.lines(), "the name does not fit beside the result");
            for (Region piece : List.of(topic, result, name)) {
                assertEquals(piece.getPrefWidth(), piece.getWidth(), 1e-9, "no piece is narrowed to make room");
            }
            assertEquals(18, name.getLayoutX(), 1e-9, "the line beneath begins under the first piece, not under the lead");
            assertEquals(22, name.getLayoutY(), 1e-9);
            assertEquals(20 + 2 + 12, row.getHeight(), 1e-9, "the row grows by the line");
            assertEquals(5, lead.getLayoutY(), 1e-9, "the lead stays on the first line");
        });
    }

    @Test
    void aPieceWiderThanTheRowTakesTheRowsWidthAndWraps() throws Exception {
        onFx(() -> {
            Label sentence = new Label("The scan asked for in year 2 to detect a reopening of the sealed aneurysm");
            sentence.setWrapText(true);
            Region name = box(60, 12);
            WrappedRow row = new WrappedRow(8, 2);
            row.show(box(10, 10), List.of(sentence, name));
            VBox host = new VBox(row);
            new Scene(host, 200, 400);
            host.applyCss();
            host.layout();
            assertEquals(200, row.getWidth(), 1e-9, "the row is never wider than its region");
            assertEquals(200 - 18, sentence.getWidth(), 1e-9, "the sentence takes the row's width");
            assertTrue(sentence.getHeight() > 1.5 * sentence.getFont().getSize(), "and wraps inside it");
            assertTrue(name.getLayoutY() >= sentence.getLayoutY() + sentence.getHeight(), "the next piece stands beneath it");
            assertEquals(row.prefHeight(200), row.getHeight(), 1e-9, "a column gives the row the height its width needs");
        });
    }

    @Test
    void aPieceTakenOutOfTheLayoutLeavesNoGap() throws Exception {
        onFx(() -> {
            Region topic = box(100, 20);
            Region hidden = box(500, 20);
            hidden.setManaged(false);
            Region name = box(80, 12);
            WrappedRow row = new WrappedRow(8, 2);
            row.show(null, List.of(topic, hidden, name));
            laidOut(row, 300);
            assertEquals(1, row.lines());
            assertEquals(0, topic.getLayoutX(), 1e-9, "with no lead the pieces begin at the row's start");
            assertEquals(108, name.getLayoutX(), 1e-9);
        });
    }

    private interface FxBody {
        void run() throws Exception;
    }

    private static void onFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(30, TimeUnit.SECONDS)) {
            fail("the JavaFX thread did not answer");
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
        if (failure.get() instanceof Error e) {
            throw e;
        }
    }
}
