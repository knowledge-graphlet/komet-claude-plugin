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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.decision.DecisionTreeText;
import network.ike.komet.claude.decision.ModelReview;
import network.ike.komet.claude.tools.GraphTools;
import org.junit.jupiter.api.AfterAll;
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
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review of the site inspection model by itself, with no document behind it
 * ({@code dev-grow-model-review}, the first step of its order of work): the model's values as a
 * table with their sliders and bars, the tree beside it, and every part redrawn when a value
 * moves, while the document's model stands as it was written. At the document's values
 * inspecting costs $250,625 and not inspecting $750,000. With the share of sites that have a
 * material problem moved to 1%, not inspecting is chosen at $50,000.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelReviewPaneIT {
    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final double BASE = 14;
    private static final String MODEL = "bimo-site-model";

    /** An anf block of the corpus: its attribute line and the lines between its delimiters. */
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
        // a sweep runs where it is asked for, so a test reads the bars it has just moved
        sweepsBefore = TornadoBlockRenderer.sweepExecutor;
        TornadoBlockRenderer.sweepExecutor = Runnable::run;

        String corpus;
        try (InputStream in = ModelReviewPaneIT.class.getResourceAsStream("/decision/bimo-site-model.adoc")) {
            corpus = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher anf = ANF_BLOCK.matcher(corpus);
        while (anf.find()) {
            String info = "anf," + anf.group(1);
            String name = AnfBlockRenderer.idOf(info).orElseThrow();
            statements.put(name, AnfAdoc.parse(AnfBlockRenderer.asAdoc(info, anf.group(2) + "\n"),
                    id -> GraphTools.resolveConcept(id, view)));
        }
        assertEquals(6, statements.size(), "the corpus states the six site statements");
        int fence = corpus.indexOf("[graphlet-model");
        modelInfo = corpus.substring(fence + 1, corpus.indexOf("]", fence)).replace(",", "");
        modelBody = corpus.substring(corpus.indexOf("----\n", fence) + 5, corpus.lastIndexOf("----"));
    }

    @AfterAll
    void stopStore() {
        TornadoBlockRenderer.sweepExecutor = sweepsBefore;
        PrimitiveData.stop();
    }

    /** A document's context with the site's statements and its model, written through a writer that records what it is handed. */
    private ProfileBlocks document(Consumer<String> writer) {
        ProfileBlocks document = new ProfileBlocks();
        statements.forEach(document::defineStatement);
        GraphletModelBlockRenderer.publish(modelInfo, modelBody, document, writer);
        return document;
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

    /** Lets what the review asked the FX thread to do later be done: its one redraw, then the bars of its sweep. */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            onFx(() -> null);
        }
    }

    /** A review laid out at a size, as a place it has grown into gives it. */
    private ModelReviewPane review(ProfileBlocks document, double width, double height) throws Exception {
        ModelReviewPane pane = onFx(() -> {
            ModelReviewPane made = new ModelReviewPane(view, null, BASE, document, MODEL, "decision-tree model=" + MODEL + " cohort=100");
            new Scene(made, width, height);
            made.resize(width, height);
            made.applyCss();
            made.layout();
            return made;
        });
        settle();
        return pane;
    }

    private static List<String> labels(Node from) {
        List<String> texts = new ArrayList<>();
        collect(from, texts);
        return texts;
    }

    private static void collect(Node node, List<String> texts) {
        if (node instanceof Label label && label.getText() != null) {
            texts.add(label.getText());
        }
        if (node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, texts));
        }
    }

    /** What the tree's header reads for the expected cost of the alternative chosen. */
    private static String expectedCost(ModelReviewPane pane) throws Exception {
        return onFx(() -> {
            pane.applyCss();
            pane.layout();
            Node tree = pane.lookup("#model-review-tree");
            assertNotNull(tree, "the review has its tree");
            return labels(tree).stream().filter(text -> text.startsWith("expected cost")).findFirst().orElse("no expected cost drawn");
        });
    }

    private int prior(ModelReviewPane pane) {
        return pane.values().get(0).vertex();
    }

    /** How many measures of the tree are drawn on the ground of a selection. */
    private static int markedMeasures(Node from) {
        int marked = from instanceof MeasureChip chip && chip.getStyle().contains("#e6eef8") ? 1 : 0;
        if (from instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                marked += markedMeasures(child);
            }
        }
        return marked;
    }

    private static int markedMeasures(ModelReviewPane pane) throws Exception {
        return onFx(() -> {
            pane.applyCss();
            pane.layout();
            return markedMeasures(pane.lookup("#model-review-tree"));
        });
    }

    @Test
    @DisplayName("The review opens on the document's values: six rows, the tree at $250,625, and bars about the alternative chosen")
    void theReviewOpensOnTheDocumentsValues() throws Exception {
        ModelReviewPane pane = review(document(text -> { }), 1200, 800);
        assertTrue(pane.reviewing());
        assertEquals(6, pane.values().size());
        assertEquals(0, pane.changed());
        assertEquals("No changes. Move a slider to see how the choice changes.", pane.lineText());
        assertEquals("expected cost $250,625", expectedCost(pane));
        assertEquals("Net value of “inspect”", pane.axisTitle());
        int prior = prior(pane);
        assertEquals("15%", pane.fieldText(prior));
        assertEquals(0.15, pane.sliderValue(prior), 1e-9);
        assertFalse(pane.documentMarkShown(prior), "a value at the document's has no mark for the document's");
        assertEquals(1, pane.thresholdsShown(prior), "the prior's slider is marked where the choice changes");
        double[] span = pane.barSpan(prior);
        assertEquals(499_375, span[2], 0.5, "inspecting is ahead by $499,375 at the document's values");
        assertTrue(span[0] < 0 && span[1] > 0, "the prior's bar crosses break-even");
        assertTrue(onFx(pane::wide), "at 1,200 points the tree is beside the values");
    }

    @Test
    @DisplayName("A value moved redraws the tree, the line and the bars, and the document's model stands as it was written")
    void aValueMovedRedrawsEveryPartAndWritesNothing() throws Exception {
        List<String> written = new ArrayList<>();
        ProfileBlocks document = document(written::add);
        ModelReviewPane pane = review(document, 1200, 800);
        int prior = prior(pane);
        onFx(() -> {
            pane.move(prior, 0.01);
            return null;
        });
        settle();
        assertEquals(1, pane.changed());
        assertEquals("1 unsaved change. You will be asked to save when you close.", pane.lineText());
        assertEquals("expected cost $50,000", expectedCost(pane), "the tree redraws at the copy's value, and now chooses not to inspect");
        assertEquals("1%", pane.fieldText(prior));
        assertTrue(pane.documentMarkShown(prior), "an open circle marks the document's value once the value has left it");
        assertEquals("Net value of “inspect”", pane.axisTitle(), "the bars are still about inspecting");
        assertEquals(50_000 - 84_375, pane.barSpan(prior)[2], 0.5, "inspecting is now behind, by $34,375");
        // nothing of the document has moved
        assertTrue(written.isEmpty(), () -> "the review wrote the document: " + written);
        assertEquals(modelBody.stripTrailing(), document.model(MODEL).orElseThrow().body().stripTrailing());
        assertTrue(pane.body().contains("parameter = [0.01,0.01] anf:est-material-problem"), pane.body());
    }

    @Test
    @DisplayName("A value reset is no change, and saving then writes nothing")
    void aValueResetLeavesNothingToSave() throws Exception {
        List<String> written = new ArrayList<>();
        ModelReviewPane pane = review(document(written::add), 1200, 800);
        int prior = prior(pane);
        onFx(() -> {
            pane.move(prior, 0.3);
            return null;
        });
        settle();
        assertEquals("expected cost $428,750", expectedCost(pane));
        onFx(() -> {
            pane.reset(prior);
            return null;
        });
        settle();
        assertEquals(0, pane.changed());
        assertEquals("expected cost $250,625", expectedCost(pane));
        assertFalse(pane.documentMarkShown(prior));
        assertFalse(onFx(pane::save), "nothing differs, so nothing is saved");
        assertEquals("No changes. Move a slider to see how the choice changes.", pane.lineText());
        assertTrue(written.isEmpty());
    }

    @Test
    @DisplayName("Saving several changed values hands the document's model one text, once, and they are then the document's")
    void savingIsOneWrite() throws Exception {
        List<String> written = new ArrayList<>();
        ModelReviewPane pane = review(document(written::add), 1200, 800);
        int prior = prior(pane);
        int cost = pane.values().stream()
                .filter(value -> Math.abs(value.document() - 60_000) < 0.5).findFirst().orElseThrow().vertex();
        onFx(() -> {
            pane.move(prior, 0.01);
            pane.move(cost, 90_000);
            return null;
        });
        settle();
        assertEquals("2 unsaved changes. You will be asked to save when you close.", pane.lineText());
        assertTrue(written.isEmpty(), "moving writes nothing");
        assertFalse(onFx(() -> pane.lookup("#model-review-save").isDisabled()), "Save in document is offered once a value has changed");
        assertTrue(onFx(pane::save));
        settle();
        assertEquals(1, written.size(), "one edit of the document for both values");
        assertTrue(written.get(0).contains("parameter = [0.01,0.01] anf:est-material-problem"), written.get(0));
        assertTrue(written.get(0).contains("parameter = [90000,90000] anf:est-inspection-cost"), written.get(0));
        // what was saved is now the document's: no change, no circle for another value, nothing more to save
        assertEquals(0, pane.changed());
        assertEquals("No changes. Move a slider to see how the choice changes.", pane.lineText());
        assertFalse(pane.documentMarkShown(prior));
        assertEquals("1%", pane.fieldText(prior));
        assertTrue(onFx(() -> pane.lookup("#model-review-save").isDisabled()));
        assertTrue(onFx(() -> pane.lookup("#model-review-reset").isDisabled()));
        assertFalse(onFx(pane::save));
        assertEquals(1, written.size());
    }

    @Test
    @DisplayName("Where the document cannot be edited the review is read-only: values move, and nothing can be saved")
    void aModelThatCannotBeWrittenIsReadOnly() throws Exception {
        ModelReviewPane pane = review(document(null), 1200, 800);
        assertFalse(pane.writable());
        assertEquals("Read-only. This document can't be edited here, so changes can't be saved.", pane.lineText());
        assertTrue(onFx(() -> pane.lookup("#model-review-save").isDisabled()));
        int prior = prior(pane);
        onFx(() -> {
            pane.move(prior, 0.01);
            return null;
        });
        settle();
        assertEquals("1 change. This document can't be edited here, so changes can't be saved.", pane.lineText());
        assertEquals("expected cost $50,000", expectedCost(pane), "the tree still redraws");
        assertTrue(onFx(() -> pane.lookup("#model-review-save").isDisabled()), "Save in document stays off");
        assertFalse(onFx(pane::save), "there is nothing to write to");
    }

    @Test
    @DisplayName("A value typed in its field is taken: a share as a percentage")
    void aTypedShareIsAPercentage() throws Exception {
        ModelReviewPane pane = review(document(text -> { }), 1200, 800);
        int prior = prior(pane);
        onFx(() -> {
            javafx.scene.control.TextField field = (javafx.scene.control.TextField) pane.lookup("#model-review-field-" + prior);
            field.setText("1");
            field.fireEvent(new javafx.event.ActionEvent());
            return null;
        });
        settle();
        ModelReview.Value value = pane.values().get(0);
        assertEquals(0.01, value.now(), 1e-12, "1 typed for a share is 1%");
        assertEquals("expected cost $50,000", expectedCost(pane));
    }

    @Test
    @DisplayName("Selecting a value marks the measures of the tree that read it, and shows the statement it comes from")
    void selectingAValueMarksItsReadersAndShowsItsStatement() throws Exception {
        ModelReviewPane pane = review(document(text -> { }), 1200, 800);
        assertEquals("Click a value, or a part of the tree, to see its statement.", onFx(pane::statementAbout));
        assertEquals(0, markedMeasures(pane));
        int prior = prior(pane);
        onFx(() -> {
            pane.selectValue(prior);
            return null;
        });
        settle();
        assertEquals(prior, pane.selectedValue());
        assertEquals("This value comes from the statement est-material-problem.", onFx(pane::statementAbout));
        assertEquals(1, (int) onFx(pane::statementCards), "the statement, as Komet's own statement card shows it");
        assertEquals(2, markedMeasures(pane), "the share of the unreliable event in each alternative reads the prior");
        // moving the value draws the tree again, and the tree drawn again is marked as the last was
        onFx(() -> {
            pane.move(prior, 0.01);
            return null;
        });
        settle();
        assertEquals("expected cost $50,000", expectedCost(pane));
        assertEquals(2, markedMeasures(pane), "the marks are kept through the redraw");
        assertEquals(prior, pane.selectedValue());
    }

    @Test
    @DisplayName("Selecting a node of the tree marks the rows of the values it reads, and shows its statements")
    void selectingANodeMarksTheValuesItReads() throws Exception {
        ModelReviewPane pane = review(document(text -> { }), 1200, 800);
        ModelReview state = pane.state();
        int sensitivity = pane.values().stream().filter(value -> Math.abs(value.document() - 0.8) < 1e-9).findFirst().orElseThrow().vertex();
        int reanalysis = pane.values().stream().filter(value -> Math.abs(value.document() - 250_000) < 0.5).findFirst().orElseThrow().vertex();
        // the event "found": its share is the inspection's sensitivity, and its cost the re-analysis
        int found = state.read().tree().orElseThrow().parentIndex(state.readers(sensitivity).getFirst()).orElseThrow();
        onFx(() -> {
            pane.selectValue(prior(pane));
            pane.selectNode(found);
            return null;
        });
        settle();
        assertEquals(found, pane.selectedNode());
        assertEquals(null, pane.selectedValue(), "one thing is selected at a time");
        assertEquals(java.util.Set.of(sensitivity, reanalysis), java.util.Set.copyOf(onFx(pane::rowsRead)));
        assertEquals(0, markedMeasures(pane), "the marks of the value selected before are gone");
        assertEquals("“found” has this statement.", onFx(pane::statementAbout));
        assertTrue(onFx(() -> labels(pane)).contains("The document has no statement named perf-problem-found yet."),
                "the site corpus names a statement on this event that none of its anf blocks states");
        // the alternative that is not taken has nothing stated on it
        int notInspecting = state.read().tree().orElseThrow().parentIndex(
                state.read().tree().orElseThrow().parentIndex(state.readers(prior(pane)).getLast()).orElseThrow()).orElseThrow();
        onFx(() -> {
            pane.selectNode(notInspecting);
            return null;
        });
        settle();
        assertTrue(onFx(pane::statementAbout).endsWith("has no statement."), onFx(pane::statementAbout));
        assertTrue(onFx(pane::rowsRead).isEmpty());
        // a value selected again clears the node
        onFx(() -> {
            pane.selectValue(sensitivity);
            return null;
        });
        settle();
        assertEquals(null, pane.selectedNode());
        assertTrue(onFx(pane::rowsRead).isEmpty());
        assertEquals(1, markedMeasures(pane));
    }

    @Test
    @DisplayName("In a narrow place the tree is above the values, and stays in sight")
    void inANarrowPlaceTheTreeIsAboveTheValues() throws Exception {
        ModelReviewPane pane = review(document(text -> { }), 620, 800);
        assertFalse(onFx(pane::wide));
        double[] tops = onFx(() -> new double[] {
                pane.lookup("#model-review-tree").getBoundsInParent().getMinY(),
                pane.lookup("#model-review-values").getBoundsInParent().getMinY()});
        assertTrue(tops[0] < tops[1], () -> "the tree is above the values: tree at " + tops[0] + ", values at " + tops[1]);
        assertEquals("expected cost $250,625", expectedCost(pane));
    }

    @Test
    @DisplayName("A model the document does not have is said so, and there is nothing to review")
    void aMissingModelIsSaidSo() throws Exception {
        ProfileBlocks empty = new ProfileBlocks();
        ModelReviewPane pane = onFx(() -> new ModelReviewPane(view, null, BASE, empty, "no-such-model", null));
        assertFalse(pane.reviewing());
        assertEquals(0, pane.changed());
        assertTrue(labels(pane).get(0).contains("no-such-model"), () -> labels(pane).toString());
        assertTrue(DecisionTreeText.healthPrice("decision-tree").isEmpty(), "a tree block with no price is read at the default");
    }
}
