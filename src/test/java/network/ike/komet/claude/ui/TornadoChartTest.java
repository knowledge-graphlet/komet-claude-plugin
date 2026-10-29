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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.BlockSweep;
import network.ike.komet.claude.decision.DecisionElement.MoneyCost;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tornado drawn from the scan tree's sweep, and what it asks of its host. */
class TornadoChartTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static String body;
    static BlockIdentities ids;
    static DecisionTreeSensitivity sweep;
    static DecisionTree tree;

    @BeforeAll
    static void startJavaFxAndSweep() throws Exception {
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
        try (InputStream in = TornadoChartTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        ids = BlockIdentities.storeFree();
        sweep = BlockSweep.sweep(body, T, ids, 100_000, BlockSweep.DEFAULT_STEPS).orElseThrow();
        tree = sweep.tree();
    }

    static int vertexOf(String identifier) {
        long nid = ids.nid(identifier).orElseThrow();
        return tree.nodes().stream().filter(n -> n.concept().orElse(0) == nid).findFirst().orElseThrow().vertexIndex();
    }

    /** A host that names things plainly and records what the chart asks. */
    static final class Recording implements TornadoChart.Host {
        final List<String> lit = new ArrayList<>();
        final List<String> tried = new ArrayList<>();
        final List<String> previews = new ArrayList<>();

        @Override
        public String nameOf(int measureVertex) {
            return measureVertex < 0 ? "price" : "m" + measureVertex;
        }

        @Override
        public String alternativeName(int alternativeVertex) {
            return alternativeVertex < 0 ? "open" : ids.label(tree.node(alternativeVertex).orElseThrow().concept().getAsLong());
        }

        @Override
        public void light(int measureVertex, boolean on) {
            lit.add(measureVertex + (on ? "+" : "-"));
        }

        @Override
        public void tryValue(int measureVertex, double value) {
            tried.add(measureVertex + "=" + Math.round(value));
        }

        @Override
        public void preview(int measureVertex, java.util.OptionalDouble value) {
            previews.add(measureVertex + (value.isPresent() ? "~" + Math.round(value.getAsDouble()) : "~"));
        }
    }

    static TornadoChart drawn(Recording host) {
        TornadoChart chart = new TornadoChart(sweep, 14, host);
        StackPane root = new StackPane(chart);
        new Scene(root, 900, 700);
        root.applyCss();
        root.layout();
        return chart;
    }

    @Test
    @DisplayName("One row per swing that moves the cost, longest first, every bar hollow since the block states points")
    void rowsLongestFirst() throws Exception {
        onFx(() -> {
            TornadoChart chart = drawn(new Recording());
            long moving = sweep.swings().stream().filter(s -> s.width() > 0).count();
            assertEquals(moving, chart.rows().size());
            assertEquals(sweep.swings().get(0).measureVertex(), chart.rows().get(0).swing.measureVertex());
            for (TornadoChart.Row row : chart.rows()) {
                assertTrue(row.hollow());
                assertFalse(row.segments.isEmpty());
                assertEquals(Color.TRANSPARENT, row.segments.get(0).getFill());
                assertTrue(row.segments.get(0).getWidth() >= 1);
                assertTrue(row.band.getWidth() > 800, "the band spans the chart");
            }
            assertTrue(chart.prefHeight(900) > moving * 20);
        });
    }

    @Test
    @DisplayName("The scan cost bar changes colour at its flip and carries a tick saying where")
    void flipColoursAndTicks() throws Exception {
        onFx(() -> {
            TornadoChart chart = drawn(new Recording());
            int scanCost = tree.children(vertexOf("rda-scan"), MoneyCost.class).get(0).vertexIndex();
            TornadoChart.Row row = chart.row(scanCost).orElseThrow();
            assertEquals(2, row.segments.size(), "below the flip and beyond it");
            assertEquals(TornadoChart.BASE_CHOICE, row.segments.get(0).getStroke());
            assertEquals(TornadoChart.OTHER_CHOICE, row.segments.get(1).getStroke());
            assertEquals(1, row.ticks.size());
            assertEquals(TornadoChart.HOT, row.ticks.get(0).getStroke());
            assertEquals(1, row.tickLabels.size());
            assertEquals("flips at 1,008", row.tickLabels.get(0).getText());
            assertTrue(row.highCaption.getLayoutX() > row.tickLabels.get(0).getLayoutX() + row.tickLabels.get(0).getWidth(),
                    "the caption gives way to the flip label");
            double tickX = row.ticks.get(0).getStartX();
            assertEquals(row.segments.get(0).getX() + row.segments.get(0).getWidth(), tickX, 1.5, "the tick sits where the colour changes");
            assertEquals("350", row.lowCaption.getText());
            assertEquals("1,400", row.highCaption.getText());
        });
    }

    @Test
    @DisplayName("A stated interval draws solid")
    void statedIntervalsAreSolid() throws Exception {
        onFx(() -> {
            String widened = body.replace("parameter = [0.9,0.9] k:Sensitivity", "parameter = [0.8,0.95] k:Sensitivity");
            DecisionTreeSensitivity again = BlockSweep.sweep(widened, T, BlockIdentities.storeFree(), 100_000, 4).orElseThrow();
            TornadoChart chart = new TornadoChart(again, 14, new Recording());
            new Scene(new StackPane(chart), 900, 700);
            chart.getParent().applyCss();
            chart.getParent().layout();
            TornadoChart.Row row = chart.row(vertexOf("rda-mra-sensitivity")).orElseThrow();
            assertFalse(row.hollow());
            assertTrue(row.segments.get(0).getFill() instanceof Color c && c.getOpacity() > 0.5);
        });
    }

    @Test
    @DisplayName("Resting on a bar lights the measure on the tree; a click tries the value under the pointer")
    void hoverAndClick() throws Exception {
        onFx(() -> {
            Recording host = new Recording();
            TornadoChart chart = drawn(host);
            int scanCost = tree.children(vertexOf("rda-scan"), MoneyCost.class).get(0).vertexIndex();
            TornadoChart.Row row = chart.row(scanCost).orElseThrow();
            row.band.getOnMouseEntered().handle(null);
            row.band.getOnMouseExited().handle(null);
            assertEquals(List.of(scanCost + "+", scanCost + "-"), host.lit);
            // a point a third of the way along the bar's first segment: the scan dearer than 700 but short of the flip
            double x = row.segments.get(0).getX() + row.segments.get(0).getWidth() / 3;
            double value = chart.valueAt(row, x).orElseThrow();
            assertTrue(value > 350 && value < 1_008, "a value in the range: " + value);
            row.band.getOnMouseClicked().handle(new MouseEvent(MouseEvent.MOUSE_CLICKED, x, row.y + 10, x, row.y + 10,
                    MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, true, false, true, null));
            assertEquals(List.of(scanCost + "=" + Math.round(value)), host.tried, "rounded to the dollar, as the chart prints it");
            assertEquals(0.0235, TornadoChart.rounded(0.02349), 1e-12);
            assertEquals(75_382, TornadoChart.rounded(75_382.1754), 1e-12);
            chart.highlight(scanCost, true);
            assertEquals(TornadoChart.HOT, row.name.getTextFill());
        });
    }

    @Test
    @DisplayName("The price of a healthy year has a row, and its break-even is marked")
    void priceRow() throws Exception {
        onFx(() -> {
            TornadoChart chart = drawn(new Recording());
            TornadoChart.Row row = chart.row(DecisionTreeSensitivity.HEALTH_PRICE).orElseThrow();
            Swing swing = row.swing;
            assertEquals(1, swing.flipsAt(tree.rootIndex()).size());
            assertEquals(1, row.tickLabels.size());
            assertTrue(row.tickLabels.get(0).getText().matches("flips at 8\\d,\\d{3}"), row.tickLabels.get(0).getText());
            assertEquals("0.0090", TornadoChart.value(0.009), "a share keeps four decimals");
            assertEquals("18.01", TornadoChart.value(18.0066));
        });
    }

    @Test
    @DisplayName("Resting on a value's name opens its control: bounds, a slider that previews, and a button that commits")
    void controlPreviewsAndCommits() throws Exception {
        onFx(() -> {
            Recording host = new Recording();
            TornadoChart chart = drawn(host);
            int scanCost = tree.children(vertexOf("rda-scan"), MoneyCost.class).get(0).vertexIndex();
            MeasureControl control = chart.openControl(scanCost);
            assertTrue(chart.control().isPresent());
            assertEquals("350", control.lower.getText());
            assertEquals("1400", control.upper.getText());
            assertEquals(700, control.slider.getValue(), 1e-9);
            assertEquals("700", control.shown.getText());
            control.slider.setValue(1_200);
            assertEquals(List.of(scanCost + "~1200"), host.previews);
            assertEquals("1,200", control.shown.getText());
            control.upper.setText("3000");
            control.upper.fireEvent(new javafx.event.ActionEvent());
            assertEquals(3_000, control.slider.getMax(), 1e-9);
            assertEquals(1_200, control.slider.getValue(), 1e-9, "the value holds when the range widens");
            control.lower.setText("2500");
            control.lower.fireEvent(new javafx.event.ActionEvent());
            assertEquals(2_500, control.slider.getValue(), 1e-9, "and is held within when it narrows");
            control.apply.fire();
            assertEquals(List.of(scanCost + "=2500"), host.tried);
            assertTrue(chart.control().isEmpty(), "committing closes the control");
            MeasureControl again = chart.openControl(scanCost);
            again.slider.setValue(900);
            chart.closeControl();
            assertEquals(scanCost + "~", host.previews.get(host.previews.size() - 1), "closing takes the preview back");
            assertTrue(chart.control().isEmpty());
        });
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
        assertTrue(done.await(60, TimeUnit.SECONDS), "the JavaFX thread answered");
        if (failed.get() instanceof Error error) {
            throw error;
        }
        if (failed.get() != null) {
            throw new AssertionError(failed.get());
        }
    }
}
