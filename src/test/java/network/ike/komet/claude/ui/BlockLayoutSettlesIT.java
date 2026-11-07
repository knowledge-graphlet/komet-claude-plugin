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

import dev.ikm.komet.framework.graphics.LayoutWatchdog;
import dev.ikm.komet.markdown.richtext.BlockPiece;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.layout.VBox;
import network.ike.komet.claude.block.LocalConcepts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every kind of block comes to rest at any width a reader may give it. A block that does not lays
 * itself out on every pulse for as long as it shows, and costs a core with nothing on screen to
 * tell: a narrow window squeezed the health price field of a decision tree until its caret had no
 * room, and its skin then moved its text on every layout.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockLayoutSettlesIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final double[] WIDTHS = {1200, 900, 700, 500, 350, 220};
    private static final double HEIGHT = 900;
    private static final double BASE = 13;
    /** How often the FX queue is let run, for the layouts a block posts to fit itself to its width. */
    private static final int ROUNDS = 12;

    /** A tree of consequences with a long name, whose header a link back to the tree below lengthens further. */
    private static final String CONSEQUENCES = """
            chance: k:Consequences[Consequences of an approval on uncertain evidence, as far as a rupture reaches] @id=settle-consequences
              event: k:Death[death] @id=settle-death
                p = [0.35,0.35]
                money = [50000,50000] k:Usd[US dollar] @id=settle-usd
                outcome: k:Dead[death] @id=settle-dead
              residual: k:Survival[survival] @id=settle-survival
                money = [200000,200000] k:Usd[US dollar] @id=settle-usd
                outcome: k:Survived[survival] @id=settle-survived
            """;
    private static final String FOLDING = """
            decision: k:Choice[First-of-kind device submission with no predicate, by the route the sponsor prefers] @id=settle-choice
              alternative: k:DoTreat[treat] @id=settle-do-treat
                money = [10000,10000] k:Usd[US dollar] @id=settle-usd
                outcome: k:Safe[safe] @id=settle-safe
              alternative: k:Wait[wait] @id=settle-wait
                chance: k:Ruptures[Rupture?] @id=settle-ruptures
                  event: k:Rupture[rupture] @id=settle-rupture
                    p = [0.1,0.1]
                    outcome: k:Ruptured[rupture] @id=settle-ruptured tree=settle-consequences
                  residual: k:Intact[intact] @id=settle-intact
                    outcome: k:Fine[fine] @id=settle-fine
            """;
    private static final String CALCULATOR = """
            calculator: k:Simple[A one-input calculator] @id=settle-simple source=paper
              output = k:Risk[Risk] @id=settle-risk in k:Pct[% over 10 years] @id=settle-pct domain [0,100] scale=100
              input: k:Age[Age] @id=settle-age in k:Year[year] @id=settle-year domain [40,79] required
              term = k:LnAge[ln age] @id=settle-ln-age from ln of k:Age[] @id=settle-age
              stratum: k:All[everyone] @id=settle-all
                coefficient = [1,1] k:LnAge[] @id=settle-ln-age basis=cited source=paper
                coefficient = [0.9,0.9] k:S0[baseline survival] @id=settle-s0 basis=cited
                coefficient = [5,5] k:Mean[mean] @id=settle-mean basis=cited
              formula = from CoxSurvival of k:S0[] @id=settle-s0 k:Mean[] @id=settle-mean
              band: k:Low[low] @id=settle-low when [0,10)
              band: k:High[high] @id=settle-high when [10,100]
            """;
    private static final String COMPLEX_CONCEPT = """
            concept: k:Perrla[PERRLA panel] @id=settle-perrla
              parent: k:AssessmentPanel[Clinical assessment panel] @id=settle-assessment-panel
              expansion: uniform and k:Presence[presence] @id=settle-presence
                member: k:PupilEquality[Pupil equality assessment] @id=settle-pupil-equality
                member: k:PupilRoundness[Pupil roundness assessment] @id=settle-pupil-roundness
            """;
    private static final String ANF = """
            topic::   candidate[clinical site inspection] disp=PENDING
            """;

    private ViewCalculator view;
    private Executor sweepsBefore;

    @BeforeAll
    void start() {
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
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        view = Calculators.View.Default();
        sweepsBefore = TornadoBlockRenderer.sweepExecutor;
        TornadoBlockRenderer.sweepExecutor = Runnable::run;
    }

    @AfterAll
    void stop() {
        TornadoBlockRenderer.sweepExecutor = sweepsBefore;
        PrimitiveData.stop();
    }

    @Test
    void aDecisionTreeComesToRest() throws Exception {
        String body;
        try (InputStream in = BlockLayoutSettlesIT.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        settles("decision tree", () -> List.of(only(new DecisionTreeBlockRenderer(view, BASE, new BlockContext())
                .render("decision-tree health-price=100000", body, null))));
    }

    /** The header that was squeezed: a long name and a link leave the price field no room at any ordinary width. */
    @Test
    void treesThatLinkToEachOtherComeToRest() throws Exception {
        settles("linked trees", () -> {
            DecisionTreeBlockRenderer renderer = new DecisionTreeBlockRenderer(view, BASE, new BlockContext());
            return List.of(only(renderer.render("decision-tree", CONSEQUENCES, null)),
                    only(renderer.render("decision-tree", FOLDING, null)));
        });
    }

    @Test
    void aCalculatorComesToRest() throws Exception {
        settles("calculator", () -> List.of(only(new CalculatorBlockRenderer(view, BASE, new BlockContext())
                .render("calculator", CALCULATOR, null))));
    }

    @Test
    void aComplexConceptComesToRest() throws Exception {
        settles("complex concept", () -> List.of(only(new ComplexConceptBlockRenderer(view, BASE, new BlockContext())
                .render("complex-concept", COMPLEX_CONCEPT, null))));
    }

    @Test
    void aComplexConceptBeingEditedComesToRest() throws Exception {
        settles("complex concept in its editor", () -> {
            Node block = only(new ComplexConceptBlockRenderer(view, BASE, new BlockContext())
                    .render("complex-concept", COMPLEX_CONCEPT, null));
            if (block.lookup("#complexConceptEdit") instanceof Button edit) {
                edit.fire();
            }
            assertNotNull(block.lookup(".complex-concept-control"), "the editor is in place of the read-only form");
            return List.of(block);
        });
    }

    /**
     * Drawing a block asks the store nothing it would have to remember: a concept named only in
     * the block takes a nid when the reader chooses to edit. A close would keep such a nid with no
     * record behind it, and every later session would have to answer for it.
     */
    @Test
    void aComplexConceptMintsNothingUntilItIsEdited() throws Exception {
        String block = """
                concept: k:Panel[Unminted panel] @id=unminted-panel
                  expansion: uniform and k:Scale[unminted scale] @id=unminted-scale
                    member: k:Member[unminted member] @id=unminted-member
                """;
        UUID scale = LocalConcepts.uuid("unminted scale");
        UUID member = LocalConcepts.uuid("unminted member");

        Node drawn = onFx(() -> only(new ComplexConceptBlockRenderer(view, BASE, new BlockContext())
                .render("complex-concept", block, null)));

        assertFalse(PrimitiveData.get().hasUuid(scale), "drawing the block minted its scale");
        assertFalse(PrimitiveData.get().hasUuid(member), "drawing the block minted its member");
        assertNull(onFx(() -> drawn.lookup(".complex-concept-control")), "the tree holds nids, so it stays unbuilt");

        onFx(() -> {
            ((Button) drawn.lookup("#complexConceptEdit")).fire();
            return null;
        });

        assertNotNull(onFx(() -> drawn.lookup(".complex-concept-control")));
        assertNull(onFx(() -> drawn.lookup("#complexConceptEdit")), "the editor is open; there is nothing left to choose");
        assertTrue(PrimitiveData.get().hasUuid(scale));
    }

    @Test
    void anAnfStatementComesToRest() throws Exception {
        settles("ANF statement", () -> List.of(only(new AnfBlockRenderer(view, BASE, new BlockContext())
                .render("anf type=request id=settle-request", ANF, null))));
    }

    private static Node only(List<BlockPiece> pieces) {
        assertEquals(1, pieces.size(), "a block renders as one piece");
        return pieces.getFirst().node().get();
    }

    private void settles(String name, Callable<List<Node>> blocks) throws Exception {
        for (double width : WIDTHS) {
            Parent root = onFx(() -> {
                VBox host = new VBox(blocks.call().toArray(Node[]::new));
                new Scene(host, width, HEIGHT);
                return host;
            });
            // every round, not only until one is clean: a block fitting itself rebuilds from the queue
            List<String> unsettled = List.of();
            for (int round = 0; round < ROUNDS; round++) {
                unsettled = onFx(() -> LayoutWatchdog.unsettled(root, width, HEIGHT));
            }
            assertEquals(List.of(), unsettled, name + " is still being laid out at width " + width);
            assertEquals(List.of(), onFx(() -> unstyled(root)), name + " was judged at width " + width + " with controls never styled");
        }
    }

    /** Controls with no skin: they have no size, so a scene holding them would come to rest for nothing. */
    private static List<String> unstyled(Node node) {
        List<String> found = new ArrayList<>();
        if (node instanceof Control control && control.getSkin() == null) {
            found.add(control.toString());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> found.addAll(unstyled(child)));
        }
        return found;
    }

    private static <T> T onFx(Callable<T> body) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(body.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.get(2, TimeUnit.MINUTES);
    }
}
