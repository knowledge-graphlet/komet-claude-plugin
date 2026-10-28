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
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Polyline;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.decision.Bounds;
import network.ike.komet.claude.decision.DecisionElement.Alternative;
import network.ike.komet.claude.decision.DecisionTerms;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.Rollback;
import network.ike.komet.claude.decision.ValuationCoordinate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The drawn scan tree: a glyph per node, an elbow per branch, the chosen alternative heavy and
 * the rejected one barred, a stated cost a chip that opens a field and hands typed bounds to
 * the host, a folded chance drawn as a terminal. Hermetic: synthetic terms, store-free
 * identities, plain labels for chips.
 */
class DecisionTreeDiagramTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static DecisionTree tree;
    static BlockIdentities ids;
    static Rollback rollback;

    @BeforeAll
    static void startJavaFx() throws Exception {
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
        try (InputStream in = DecisionTreeDiagramTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            ids = BlockIdentities.storeFree();
            tree = DecisionTreeText.parse(text, T, ids).tree().orElseThrow();
        }
        rollback = Rollback.of(tree, ValuationCoordinate.pricingHealthAt(Bounds.point(100_000),
                ids.nid("rda-usd").orElseThrow(), ids.nid("rda-qaly").orElseThrow()));
        assertTrue(rollback.determinable(), () -> rollback.findings().toString());
    }

    /** A host that labels concepts plainly and records what the diagram asks of it. */
    static final class Recording implements DecisionTreeDiagram.Host {
        final List<String> edits = new ArrayList<>();
        final List<Integer> toggles = new ArrayList<>();

        @Override
        public Optional<Node> concept(OptionalLong nid) {
            return nid.isEmpty() ? Optional.empty() : Optional.of(new Label(ids.label(nid.getAsLong())));
        }

        @Override
        public boolean editable(int measureVertex) {
            return tree.measure(measureVertex).stated();
        }

        @Override
        public void edit(int measureVertex, Bounds bounds) {
            edits.add(measureVertex + "=" + bounds);
        }

        @Override
        public void toggle(int nodeVertex) {
            toggles.add(nodeVertex);
        }
    }

    static DecisionTreeDiagram drawn(Recording host, Set<Integer> collapsed) {
        DecisionTreeDiagram diagram = new DecisionTreeDiagram(tree, rollback, ids, List.of(), collapsed, 14, host);
        StackPane root = new StackPane(diagram);
        new Scene(root, 1200, 600);
        root.applyCss();
        root.layout();
        return diagram;
    }

    @Test
    @DisplayName("Every node has a glyph, every branch an elbow of four points, and the drawing has a size")
    void drawsEveryPart() throws Exception {
        onFx(() -> {
            DecisionTreeDiagram diagram = drawn(new Recording(), new HashSet<>());
            assertEquals(7, diagram.geometry().rows());
            assertTrue(diagram.glyph(tree.rootIndex()).isPresent());
            for (var run : diagram.geometry().runs()) {
                Polyline line = diagram.run(run.vertexIndex()).orElseThrow();
                assertEquals(8, line.getPoints().size());
                assertEquals(run.x0(), line.getPoints().get(0), 1e-9);
                assertEquals(run.x1(), line.getPoints().get(6), 1e-9);
            }
            assertTrue(diagram.drawnWidthProperty().get() > 400, "wide enough for five columns of labels");
            assertTrue(diagram.drawnHeightProperty().get() > 7 * 30);
        });
    }

    @Test
    @DisplayName("The chosen alternative is drawn heavy and marked; the other is barred")
    void chosenAndRejected() throws Exception {
        onFx(() -> {
            DecisionTreeDiagram diagram = drawn(new Recording(), new HashSet<>());
            int chosen = rollback.chosen(tree.rootIndex()).orElseThrow();
            List<Alternative> top = tree.children(tree.rootIndex(), Alternative.class).castToList();
            int rejected = top.stream().mapToInt(Alternative::vertexIndex).filter(i -> i != chosen).findFirst().orElseThrow();
            assertEquals(2.6, diagram.run(chosen).orElseThrow().getStrokeWidth(), 1e-9);
            assertFalse(diagram.barred(chosen));
            assertTrue(diagram.barred(rejected));
            assertEquals(1.2, diagram.run(rejected).orElseThrow().getStrokeWidth(), 1e-9);
            String above = labels(diagram.labelOf(chosen, true).orElseThrow());
            assertTrue(above.contains("✓ chosen"), above);
        });
    }

    @Test
    @DisplayName("A stated cost is a chip that opens a field and hands the typed bounds to the host")
    void statedCostsAreEditable() throws Exception {
        onFx(() -> {
            Recording host = new Recording();
            DecisionTreeDiagram diagram = drawn(host, new HashSet<>());
            Alternative scan = tree.nodesOfType(Alternative.class).get(0);
            Node below = diagram.labelOf(scan.vertexIndex(), false).orElseThrow();
            MeasureChip money = (MeasureChip) ((javafx.scene.Parent) below).getChildrenUnmodifiable().get(0);
            assertEquals("money 700", money.shown());
            int costVertex = scan.costs().get(0).vertexIndex();
            money.open(Bounds.point(700), 14, bounds -> host.edit(costVertex, bounds));
            TextField field = money.field().orElseThrow();
            field.setText("650");
            field.fireEvent(new javafx.event.ActionEvent());
            assertEquals(List.of(costVertex + "=650"), host.edits);
            assertTrue(money.field().isEmpty(), "the field closes once the value is handed over");
        });
    }

    @Test
    @DisplayName("A folded chance is drawn as a terminal saying what is folded, and a click asks to open it")
    void foldedChance() throws Exception {
        onFx(() -> {
            Recording host = new Recording();
            int mra = tree.nodesOfType(network.ike.komet.claude.decision.DecisionElement.Chance.class).get(0).vertexIndex();
            DecisionTreeDiagram diagram = drawn(host, new HashSet<>(Set.of(mra)));
            assertEquals(3, diagram.geometry().rows());
            assertTrue(diagram.run(mra).isEmpty());
            diagram.glyph(mra).orElseThrow().getOnMouseClicked().handle(null);
            assertEquals(List.of(mra), host.toggles);
        });
    }

    private static String labels(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Label label) {
            sb.append(label.getText()).append(' ');
        }
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, sb));
        }
    }

    /** Runs on the JavaFX thread and waits, rethrowing what failed there. */
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
