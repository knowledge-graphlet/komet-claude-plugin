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

import dev.ikm.komet.layout.expand.KlExpansionHost;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.tools.GraphTools;
import network.ike.komet.document.surface.MarkdownRichText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grow of the three decision blocks ({@code dev-grow-model-review}, the second step of its
 * order of work), in a document's area inside a card inside a journal window, with the site
 * inspection model. The review opens where it fits, closing with unsaved changes asks whether to
 * save them, a save leaves the review open while the card draws the model's block again, and a
 * review whose document is drawn again from outside closes without a question or a write.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelReviewGrowIT {
    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final double BASE = 14;
    private static final String MODEL = "bimo-site-model";
    private static final Pattern ANF_BLOCK = Pattern.compile("(?m)^\\[anf,([^\\]]*)]\\n--\\n(.*?)\\n--\\n", Pattern.DOTALL);

    private ViewCalculator view;
    private Executor sweepsBefore;
    private String modelInfo;
    private String modelBody;
    private final Map<String, AnfStatement> statements = new LinkedHashMap<>();

    @BeforeAll
    void startStore() throws IOException {
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // the toolkit outlives a test class
        }
        new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute();
        view = Calculators.View.Default();
        sweepsBefore = TornadoBlockRenderer.sweepExecutor;
        TornadoBlockRenderer.sweepExecutor = Runnable::run;
        String corpus;
        try (InputStream in = ModelReviewGrowIT.class.getResourceAsStream("/decision/bimo-site-model.adoc")) {
            corpus = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher anf = ANF_BLOCK.matcher(corpus);
        while (anf.find()) {
            String info = "anf," + anf.group(1);
            statements.put(AnfBlockRenderer.idOf(info).orElseThrow(), AnfAdoc.parse(
                    AnfBlockRenderer.asAdoc(info, anf.group(2) + "\n"), id -> GraphTools.resolveConcept(id, view)));
        }
        int fence = corpus.indexOf("[graphlet-model");
        modelInfo = corpus.substring(fence + 1, corpus.indexOf("]", fence)).replace(",", "");
        modelBody = corpus.substring(corpus.indexOf("----\n", fence) + 5, corpus.lastIndexOf("----"));
    }

    @AfterAll
    void stopStore() {
        TornadoBlockRenderer.sweepExecutor = sweepsBefore;
        PrimitiveData.stop();
    }

    private static <T> T onFx(Callable<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "the FX thread answered");
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        return result.get();
    }

    private static void onFxRun(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    /** Lets what was asked of the FX thread for later be done, and a closing review finish its fade. */
    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            onFx(() -> null);
        }
        Thread.sleep(350);
        onFx(() -> null);
    }

    /**
     * A document with the model's three blocks, in a card, in a journal window, as the grow finds
     * them: each of the three panes a host. The model's block is written through a writer that
     * records each text and then, as the card does after an edit, draws the model's block again
     * in the same context and leaves the other blocks as they are.
     */
    private final class Place {
        final ProfileBlocks document = new ProfileBlocks();
        final List<String> written = new ArrayList<>();
        final List<Integer> asked = new ArrayList<>();
        final VBox blocks = new VBox();
        final BorderPane documentPane = new BorderPane(blocks);
        final BorderPane card = new BorderPane(documentPane);
        final BorderPane window = new BorderPane(card);
        final GraphletModelBlockRenderer models;
        Node model;
        Node tree;
        Node tornado;
        ModelReviewGrow.Answer answer = ModelReviewGrow.Answer.CANCEL;

        Place(boolean writable, double windowWidth, double windowHeight, double cardWidth) {
            statements.forEach(document::defineStatement);
            models = new GraphletModelBlockRenderer(view, BASE, document);
            if (writable) {
                models.setRewriter((info, was, newInfo, body) -> {
                    written.add(body);
                    Platform.runLater(() -> drawModelAgain(body));
                });
            }
            model = models.render(modelInfo, modelBody, null).getFirst().node().get();
            tornado = new TornadoBlockRenderer(view, BASE, document).render("tornado model=" + MODEL, "", null).getFirst().node().get();
            tree = new DecisionTreeBlockRenderer(view, BASE, document)
                    .render("decision-tree model=" + MODEL + " cohort=100", "", null).getFirst().node().get();
            blocks.getChildren().addAll(model, tornado, tree);
            KlExpansionHost.mark(documentPane, () -> "Document", false);
            KlExpansionHost.mark(card, () -> "Site inspection", false);
            KlExpansionHost.mark(window, () -> "Journal window", true);
            card.setMaxWidth(cardWidth);
            Stage stage = new Stage();
            stage.setScene(new Scene(window, windowWidth, windowHeight));
            stage.show();
            window.applyCss();
            window.layout();
            for (Node grown : List.of(model, tornado, tree)) {
                ModelReviewGrow.of(grown).asker = changes -> {
                    asked.add(changes);
                    return answer;
                };
            }
        }

        /** What the card does after a saved edit of the model's block: that block is drawn again, the others are kept. */
        void drawModelAgain(String body) {
            int at = blocks.getChildren().indexOf(model);
            Node again = models.render(modelInfo, body, null).getFirst().node().get();
            blocks.getChildren().set(at, again);
            model = again;
        }

        /** The pane the review's overlay is in, or null when no review is open. */
        BorderPane filled() {
            for (BorderPane pane : List.of(documentPane, card, window)) {
                if (pane.getChildren().stream().anyMatch(child -> child.getStyleClass().contains("kl-expansion-overlay"))) {
                    return pane;
                }
            }
            return null;
        }

        ModelReviewPane review() {
            return window.lookup("#model-review") instanceof ModelReviewPane pane ? pane : null;
        }

        String reduce() {
            window.applyCss();
            window.layout();
            return window.lookup(".kl-expansion-reduce") instanceof Button button ? button.getText() : null;
        }
    }

    /**
     * Whether the review has closed: a closing review fades for a moment, longer on a busy
     * machine, so this waits for the fade rather than for a fixed time.
     */
    private static boolean closed(Place place) throws Exception {
        for (int i = 0; i < 100; i++) {
            if (onFx(place::filled) == null) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    private Place place(boolean writable, double windowWidth, double windowHeight, double cardWidth) throws Exception {
        Place place = onFx(() -> new Place(writable, windowWidth, windowHeight, cardWidth));
        settle();
        return place;
    }

    private static int prior(ModelReviewPane review) {
        return review.values().get(0).vertex();
    }

    @Test
    @DisplayName("Each of the three blocks has one Expand button, drawn always at its top right, and no second mark")
    void theThreeBlocksHaveTheExpandButton() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> {
            for (Node grown : List.of(place.model, place.tornado, place.tree)) {
                assertNotNull(ModelReviewGrow.of(grown), "the block grows into its model's review");
                assertTrue(grown.getProperties().containsKey(MarkdownRichText.BLOCK_NO_EXPAND), "the surface is told to add no second icon");
                assertEquals(1, grown.lookupAll(".kl-expand-icon").size(), "one button on the block");
                Button button = (Button) ExpandButton.of(grown);
                assertTrue(button.isVisible() && button.getOpacity() == 1 && !button.isMouseTransparent(),
                        "the button is drawn always, and not only while the pointer is near it");
                assertTrue(button.getWidth() >= 24 && button.getHeight() >= 24,
                        "large enough to click: " + button.getWidth() + " by " + button.getHeight());
                assertEquals("Expand", button.getTooltip().getText());
                Bounds block = grown.localToScene(grown.getLayoutBounds());
                Bounds at = button.localToScene(button.getBoundsInLocal());
                assertEquals(block.getMaxX(), at.getMaxX(), 1, "at the right of the block");
                assertEquals(block.getMinY(), at.getMinY(), 1, "at the top of the block");
            }
            assertNull(place.window.lookup("#decision-tree-expand"), "the tree has no mark of its own beside the button");
            // the first row of each block ends before the button, so nothing of it is drawn under the button
            for (String firstRow : List.of("Graphlet model", "Sensitivity of the choice, one value at a time", "Decision tree")) {
                Node title = place.window.lookupAll(".label").stream()
                        .filter(label -> label instanceof javafx.scene.control.Label named && firstRow.equals(named.getText()))
                        .findFirst().orElseThrow(() -> new AssertionError("no row " + firstRow));
                Node block = title;
                while (ExpandButton.of(block) == null) {
                    block = block.getParent();
                }
                Bounds words = title.localToScene(title.getBoundsInLocal());
                Bounds button = ExpandButton.of(block).localToScene(ExpandButton.of(block).getBoundsInLocal());
                assertTrue(words.getMaxX() <= button.getMinX(), firstRow + " ends before the button");
            }
        });
    }

    @Test
    @DisplayName("A click on a block's Expand button, as a pointer makes it, opens the review")
    void aPointerClickOnTheExpandButtonOpensTheReview() throws Exception {
        // a robot on a real screen would move the person's pointer
        Assumptions.assumeTrue("Monocle".equals(System.getProperty("glass.platform")), "a pointer of the test's own");
        Place place = place(true, 1300, 850, 620);
        Point2D at = onFx(() -> {
            Node button = ExpandButton.of(place.model);
            Bounds on = button.localToScreen(button.getBoundsInLocal());
            return new Point2D(on.getCenterX(), on.getCenterY());
        });
        onFxRun(() -> {
            javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
            robot.mouseMove(at);
            robot.mousePress(javafx.scene.input.MouseButton.PRIMARY);
            robot.mouseRelease(javafx.scene.input.MouseButton.PRIMARY);
        });
        settle();
        assertNotNull(onFx(place::review), "nothing is drawn over the button: the pointer's click reaches it");
        onFxRun(() -> ModelReviewGrow.of(place.model).stepDown());
        settle();
        assertTrue(closed(place));
    }

    @Test
    @DisplayName("A tree with its own text has the Expand button while it is drawn reduced, and grows whole, with no window of its own")
    void aTreeWithNoModelGrowsWhole() throws Exception {
        String body;
        try (InputStream in = ModelReviewGrowIT.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
        // a document too narrow for the tree, and one with room for it at full size
        Lone narrow = onFx(() -> new Lone(body, 420));
        Lone wide = onFx(() -> new Lone(body, 6000));
        settle();
        settle();
        onFxRun(() -> {
            assertTrue(narrow.tree.getProperties().containsKey(MarkdownRichText.BLOCK_NO_EXPAND), "the surface is told to add no second icon");
            assertNull(ModelReviewGrow.of(narrow.tree), "a tree that draws no model has no review to grow into");
            Button button = (Button) ExpandButton.of(narrow.tree);
            assertTrue(button.isVisible(), "a tree drawn smaller than full size, or as an outline, can be expanded");
            assertFalse(ExpandButton.of(wide.tree).isVisible(), "a tree drawn whole where it stands has nothing to grow into");
            assertNull(narrow.documentPane.lookup("#decision-tree-expand"), "and no mark of its own");
            button.fire();
        });
        settle();
        onFxRun(() -> {
            assertNotNull(narrow.window.lookup("#decision-tree-whole"), "the tree whole, in the place the grow fills");
            assertTrue(narrow.window.getChildren().stream().anyMatch(child -> child.getStyleClass().contains("kl-expansion-overlay")),
                    "the document's area is too narrow for the whole tree, so it opens in the journal window at once");
            assertEquals(javafx.geometry.Pos.BOTTOM_RIGHT, javafx.scene.layout.StackPane.getAlignment(narrow.window.lookup(".kl-contract-icon")));
            javafx.stage.Window documents = narrow.documentPane.getScene().getWindow();
            assertTrue(javafx.stage.Window.getWindows().stream().noneMatch(w -> w instanceof Stage stage && stage.getOwner() == documents),
                    "no window of the tree's own is opened over the document's");
        });
    }

    /** A tree with its own text, alone in a document's area of a given width, in a journal window of its own. */
    private final class Lone {
        final Node tree;
        final BorderPane documentPane;
        final BorderPane window;

        Lone(String body, double width) {
            tree = new DecisionTreeBlockRenderer(view, BASE, new ProfileBlocks())
                    .render("decision-tree health-price=100000", body, null).getFirst().node().get();
            // the pane a block stands in is its home; the document's area around it is the nearest place it grows into
            documentPane = new BorderPane(new VBox(tree));
            documentPane.setMaxWidth(width);
            window = new BorderPane(documentPane);
            KlExpansionHost.mark(documentPane, () -> "Document", false);
            KlExpansionHost.mark(window, () -> "Journal window", true);
            Stage stage = new Stage();
            stage.setScene(new Scene(window, Math.max(1800, width), 900));
            stage.show();
            window.applyCss();
            window.layout();
        }
    }

    @Test
    @DisplayName("In a card too narrow for the whole review, it opens in the journal window at once, and Reduce returns to the document")
    void theReviewOpensInTheJournalWindowWhenItDoesNotFit() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        assertSame(place.window, onFx(place::filled), "the document's area and the card are too small, so they are not rungs");
        assertEquals("↙ Collapse", onFx(place::reduce));
        assertEquals(javafx.geometry.Pos.BOTTOM_RIGHT,
                onFx(() -> javafx.scene.layout.StackPane.getAlignment(place.window.lookup(".kl-contract-icon"))),
                "the review's contract icon is at its bottom right, clear of what the journal window draws at its own top right");
        assertNotNull(onFx(place::review));
        assertEquals("No changes. Move a slider to see how the choice changes.", onFx(() -> place.review().lineText()));
        // the same review from another of the three blocks is not opened over this one
        onFxRun(() -> ModelReviewGrow.of(place.tree).stepDown());
        settle();
        assertTrue(closed(place), "with nothing changed, closing asks nothing");
        assertTrue(place.asked.isEmpty());
    }

    @Test
    @DisplayName("A card with room for the values and for most of the tree is too small: the tree is measured at full size, with its names")
    void aPlaceTooNarrowForTheWholeTreeIsNotARung() throws Exception {
        Place probe = place(true, 1300, 850, 620);
        double treeWidth = onFx(() -> DecisionTreeBlockRenderer.wholeSize(
                ModelReviewGrow.of(probe.tree).figureNode(), probe.window.getScene()).getWidth());
        assertTrue(treeWidth > 600, "the whole tree is measured with its names, not as a drawing with no skins: " + treeWidth);
        // room for the table of values, 32 ems wide, and for four fifths of the tree beside it
        double cardWidth = BASE * 32 + treeWidth * 0.8;
        Place place = place(true, cardWidth + 1500, 3000, cardWidth);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        assertSame(place.window, onFx(place::filled), "the tree would not be at full size in the card, so the card is not a rung");
        onFxRun(() -> ModelReviewGrow.of(place.tree).stepDown());
        settle();
        assertTrue(closed(place));
    }

    @Test
    @DisplayName("In a place with room for the whole review, it opens in the nearest, the document's area")
    void theReviewOpensInTheNearestPlaceWhenItFits() throws Exception {
        Place place = place(true, 6000, 3000, 6000);
        onFxRun(() -> ModelReviewGrow.of(place.tornado).expand());
        settle();
        assertSame(place.documentPane, onFx(place::filled));
        assertTrue(onFx(() -> place.review().wide()));
        onFxRun(() -> ModelReviewGrow.of(place.tornado).collapse());
        settle();
    }

    @Test
    @DisplayName("Closing with an unsaved change asks: Cancel stays, Don't Save closes and writes nothing")
    void closingAsksWhetherToSave() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        onFxRun(() -> place.review().move(prior(place.review()), 0.01));
        settle();
        place.answer = ModelReviewGrow.Answer.CANCEL;
        onFxRun(() -> ModelReviewGrow.of(place.tree).collapse());
        settle();
        assertEquals(List.of(1), place.asked, "asked once, about one change");
        assertNotNull(onFx(place::filled), "Cancel leaves the review open");
        assertEquals(1, onFx(() -> place.review().changed()), "with its change");

        place.answer = ModelReviewGrow.Answer.DONT_SAVE;
        onFxRun(() -> ModelReviewGrow.of(place.tree).collapse());
        settle();
        assertEquals(List.of(1, 1), place.asked);
        assertTrue(closed(place), "Don't Save closes the review");
        assertTrue(place.written.isEmpty(), "and writes nothing");
    }

    @Test
    @DisplayName("Answering Save on closing writes the changes as one edit and closes")
    void savingOnClosingWritesOnce() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tornado).expand());
        settle();
        onFxRun(() -> place.review().move(prior(place.review()), 0.01));
        settle();
        place.answer = ModelReviewGrow.Answer.SAVE;
        onFxRun(() -> ModelReviewGrow.of(place.tornado).collapse());
        settle();
        assertEquals(List.of(1), place.asked);
        assertEquals(1, place.written.size(), "one edit");
        assertTrue(place.written.get(0).contains("parameter = [0.01,0.01] anf:est-material-problem"), place.written.get(0));
        assertTrue(closed(place), "the review closed");
    }

    @Test
    @DisplayName("Save in document leaves the review open, though the card draws the model's block again beneath it")
    void savingLeavesTheReviewOpen() throws Exception {
        Place place = place(true, 1300, 850, 620);
        // grown from the model's own block, the one a save draws again
        Node first = place.model;
        onFxRun(() -> ModelReviewGrow.of(place.model).expand());
        settle();
        ModelReviewPane review = onFx(place::review);
        onFxRun(() -> review.move(prior(review), 0.01));
        settle();
        assertTrue(onFx(review::save));
        settle();
        assertEquals(1, place.written.size());
        assertTrue(place.model != first, "the card drew the model's block again");
        assertSame(place.window, onFx(place::filled), "the review is still open");
        assertSame(review, onFx(place::review), "and is the same review");
        assertEquals("No changes. Move a slider to see how the choice changes.", onFx(review::lineText));
        assertEquals(0.01, review.values().get(0).document(), 1e-12, "the saved value is now the document's");

        // it goes on working: another value saved is another edit, from the text the first save left
        onFxRun(() -> review.move(prior(review), 0.3));
        settle();
        assertEquals("1 unsaved change. You will be asked to save when you close.", onFx(review::lineText));
        assertTrue(onFx(review::save));
        settle();
        assertEquals(2, place.written.size());
        assertTrue(place.written.get(1).contains("parameter = [0.3,0.3] anf:est-material-problem"), place.written.get(1));
        assertSame(place.window, onFx(place::filled), "still open after a second save");

        // and it closes as any review does, with nothing to ask
        onFxRun(() -> ModelReviewGrow.of(place.model).collapse());
        settle();
        assertTrue(closed(place), "the review closed");
        assertTrue(place.asked.isEmpty(), "every change was saved, so closing asked nothing");
        // the block drawn in its place can be grown again, and closed again
        onFxRun(() -> ModelReviewGrow.of(place.model).expand());
        settle();
        assertSame(place.window, onFx(place::filled));
        onFxRun(() -> ModelReviewGrow.of(place.model).collapse());
        assertTrue(closed(place), "the review grown from the redrawn block closes");
    }

    @Test
    @DisplayName("There is one review of a model at a time, whichever of its three blocks is asked to grow")
    void oneReviewOfAModelAtATime() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        ModelReviewPane review = onFx(place::review);
        onFxRun(() -> ModelReviewGrow.of(place.tornado).expand());
        settle();
        assertSame(review, onFx(place::review), "the tornado does not open a second review over the tree's");
        assertSame(review, onFx(() -> ModelReviewGrow.of(place.tornado).review()), "any block of the model answers with the review that is open");
        onFxRun(() -> ModelReviewGrow.of(place.model).collapse());
        assertTrue(closed(place), "and any of them closes it");
    }

    @Test
    @DisplayName("A review whose document is drawn again from outside closes, asks nothing and writes nothing")
    void aReviewEndedFromOutsideAsksNothing() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        onFxRun(() -> place.review().move(prior(place.review()), 0.01));
        settle();
        // the card reads the whole document again: every block's drawing goes, and none is drawn in this context
        onFxRun(() -> place.blocks.getChildren().clear());
        settle();
        assertTrue(closed(place), "the review closed");
        assertTrue(place.asked.isEmpty(), "without a question");
        assertTrue(place.written.isEmpty(), "and without a write");
    }

    @Test
    @DisplayName("A review whose model is changed in the document by someone else closes, asks nothing and writes nothing")
    void aModelChangedFromOutsideEndsTheReview() throws Exception {
        Place place = place(true, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tornado).expand());
        settle();
        onFxRun(() -> place.review().move(prior(place.review()), 0.01));
        settle();
        // the paired assistant restates the model: its block is drawn again with another text
        String other = modelBody.replace("parameter = [60000,60000] anf:est-inspection-cost", "parameter = [75000,75000] anf:est-inspection-cost");
        onFxRun(() -> place.drawModelAgain(other));
        settle();
        assertTrue(closed(place), "the review closed");
        assertTrue(place.asked.isEmpty());
        assertTrue(place.written.isEmpty());
    }

    @Test
    @DisplayName("Where the document cannot be edited, closing with changes asks nothing")
    void aReadOnlyReviewClosesWithoutAsking() throws Exception {
        Place place = place(false, 1300, 850, 620);
        onFxRun(() -> ModelReviewGrow.of(place.tree).expand());
        settle();
        onFxRun(() -> place.review().move(prior(place.review()), 0.01));
        settle();
        assertEquals("1 change. This document can't be edited here, so changes can't be saved.", onFx(() -> place.review().lineText()));
        onFxRun(() -> ModelReviewGrow.of(place.tree).collapse());
        settle();
        assertTrue(closed(place), "the review closed");
        assertTrue(place.asked.isEmpty(), "there is nothing to save, so nothing is asked");
    }
}
