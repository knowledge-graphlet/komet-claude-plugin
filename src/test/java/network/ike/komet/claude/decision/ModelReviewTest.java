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
package network.ike.komet.claude.decision;

import network.ike.komet.claude.block.BlockIdentities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site inspection model under review ({@code dev-grow-model-review}): a copy whose values
 * move while the document's text stands. At the document's values inspecting costs $250,625 and
 * not inspecting $750,000. With the share of sites that have a material problem moved from 15%
 * to 1%, inspecting costs $84,375 and not inspecting $50,000, so the choice changes; it changes
 * at about 1.9%.
 */
class ModelReviewTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    static String model;

    @BeforeAll
    static void readTheModel() throws IOException {
        model = GraphletModelTest.body("/decision/bimo-site-model.adoc");
    }

    private static ModelReview open(BlockIdentities ids) {
        return ModelReview.open(model, T, ids, 100_000, ExternalQuantities.none(), Map.of(),
                GraphletModelTest.statements(ids)).orElseThrow();
    }

    private static int vertex(ModelReview review, BlockIdentities ids, String identifier) {
        return GraphletModelTest.vertexOf(review.rollback().orElseThrow().tree(), ids, identifier);
    }

    /** The vertex of the parameter stated on a line of the model, by what the line says. */
    private static int parameter(BlockIdentities ids, String statement) {
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(model, T, ids, ExternalQuantities.none(),
                GraphletModelTest.statements(ids));
        DecisionTree tree = parsed.tree().orElseThrow();
        for (DecisionElement.Parameter parameter : tree.nodesOfType(DecisionElement.Parameter.class)) {
            if (parsed.parameterStatement(parameter.vertexIndex()).filter(statement::equals).isPresent()) {
                return parameter.vertexIndex();
            }
        }
        throw new AssertionError("no parameter reads anf:" + statement);
    }

    @Test
    @DisplayName("A review opens on the model's six values, the one that moves the choice furthest first, each at the document's")
    void theValuesAreTheTornadosInItsOrder() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        List<ModelReview.Value> values = review.values();
        assertEquals(6, values.size(), "one value for each parameter of the site model");
        int prior = parameter(ids, "est-material-problem");
        assertEquals(prior, values.get(0).vertex(), "the prior moves the net value furthest");
        assertEquals(Bounds.of(0.005, 0.4), values.get(0).range(), "a statement's range is the slider's");
        assertTrue(values.get(0).stated());
        assertEquals(0.15, values.get(0).document(), 1e-12);
        assertEquals(0.15, values.get(0).now(), 1e-12);
        assertTrue(values.stream().noneMatch(ModelReview.Value::changed));
        assertEquals(0, review.changed());
        assertSame(review.documentBody(), review.body(), "an unmoved copy is the document's text itself");
        assertEquals(vertex(review, ids, "bimo-inspect"), review.reference().orElseThrow(),
                "the bars are about the alternative the document's values chose");
    }

    @Test
    @DisplayName("A value moved changes the copy alone: one line restated, the tree rolled back at it, the document's text as it was")
    void aMoveRestatesOneLineOfTheCopy() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        int prior = parameter(ids, "est-material-problem");
        assertTrue(review.move(prior, 0.01));
        assertEquals(1, review.changed());
        assertTrue(review.body().contains("parameter = [0.01,0.01] anf:est-material-problem"), review.body());
        assertTrue(review.documentBody().contains("parameter = [0.15,0.15] anf:est-material-problem"));
        assertEquals(model, review.documentBody(), "the document's text is not touched");
        Rollback rollback = review.rollback().orElseThrow();
        assertEquals(50_000, rollback.rootValue().orElseThrow().midpoint(), 0.5);
        assertEquals(vertex(review, ids, "bimo-no-inspection"), rollback.chosen(rollback.tree().rootIndex()).orElseThrow(),
                "below about 1.9% not inspecting costs less");
        assertEquals(84_375, rollback.value(vertex(review, ids, "bimo-inspect")).orElseThrow().midpoint(), 0.5);
        ModelReview.Value moved = review.value(prior).orElseThrow();
        assertTrue(moved.changed());
        assertEquals(0.15, moved.document(), 1e-12, "the document's value is kept beside the copy's");
        assertEquals(0.01, moved.now(), 1e-12);
    }

    @Test
    @DisplayName("Several values moved are one text, so saving them is one edit of the document")
    void severalMovesAreOneText() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        review.move(parameter(ids, "est-material-problem"), 0.01);
        review.move(parameter(ids, "est-inspection-cost"), 90_000);
        assertEquals(2, review.changed());
        String kept = review.body();
        assertTrue(kept.contains("parameter = [0.01,0.01] anf:est-material-problem"), kept);
        assertTrue(kept.contains("parameter = [90000,90000] anf:est-inspection-cost"), kept);
        assertEquals(model.lines().count(), kept.lines().count(), "a restated line moves no other line");
        assertEquals(114_375, review.rollback().orElseThrow()
                .value(vertex(review, ids, "bimo-inspect")).orElseThrow().midpoint(), 0.5);
    }

    @Test
    @DisplayName("A value reset is no change, and a copy with every value reset is the document's text, character for character")
    void resettingLeavesNothingToSave() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        int prior = parameter(ids, "est-material-problem");
        int cost = parameter(ids, "est-inspection-cost");
        review.move(prior, 0.3);
        review.move(cost, 90_000);
        // dragged back to where the document has it
        assertTrue(review.move(prior, 0.15));
        assertEquals(1, review.changed());
        assertFalse(review.value(prior).orElseThrow().changed());
        assertTrue(review.reset(cost));
        assertEquals(0, review.changed());
        assertEquals(model, review.body());
        assertFalse(review.resetAll(), "nothing left to reset");
        review.move(prior, 0.3);
        review.move(cost, 90_000);
        assertTrue(review.resetAll());
        assertEquals(model, review.body());
    }

    @Test
    @DisplayName("A sweep of the moved copy still draws the net value of inspecting, over the ranges the review opened with")
    void theBarsKeepTheirMeaningWhenTheChoiceChanges() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        int prior = parameter(ids, "est-material-problem");
        int cost = parameter(ids, "est-inspection-cost");
        Bounds costRange = review.value(cost).orElseThrow().range();
        assertEquals(Bounds.of(30_000, 120_000), costRange, "a point is swept from half to double");

        DecisionTreeSensitivity atDocument = review.sweep(review.body(), BlockSweep.DEFAULT_STEPS).orElseThrow();
        assertEquals(499_375, atDocument.baseNet(), 0.5, "inspecting is ahead by $499,375 at the document's values");

        review.move(prior, 0.01);
        review.move(cost, 90_000);
        DecisionTreeSensitivity moved = review.sweep(review.body(), BlockSweep.DEFAULT_STEPS).orElseThrow();
        assertEquals(vertex(review, ids, "bimo-inspect"), moved.reference().orElseThrow(),
                "the bars are still about inspecting, which the copy no longer chooses");
        assertEquals(vertex(review, ids, "bimo-no-inspection"), moved.baseChosen().orElseThrow());
        assertEquals(50_000 - 114_375, moved.baseNet(), 0.5, "inspecting is now behind, and the bar says so by its sign");
        assertEquals(costRange, moved.swing(cost).orElseThrow().range(),
                "the slider's ends do not shift when its value moves");
        assertEquals(Bounds.of(0.005, 0.4), moved.swing(prior).orElseThrow().range());
    }

    @Test
    @DisplayName("The value at which the choice changes is read off a sweep: about 1.9% for the prior")
    void theThresholdIsWhereTheChoiceChanges() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        int prior = parameter(ids, "est-material-problem");
        DecisionTreeSensitivity sweep = review.sweep(review.body(), BlockSweep.DEFAULT_STEPS).orElseThrow();
        List<Double> thresholds = ModelReview.thresholds(sweep, prior);
        assertEquals(1, thresholds.size(), () -> "one change of choice across the prior's range: " + thresholds);
        assertEquals(72_500.0 / 3_812_500.0, thresholds.get(0), 1e-4);
        assertTrue(ModelReview.thresholds(sweep, parameter(ids, "est-inspection-false-alarm")).isEmpty(),
                "the false alarm rate cannot change the choice inside its range");
    }

    @Test
    @DisplayName("Once saved, the copy's values are the document's: nothing differs, and a reset goes back to what was saved")
    void savedValuesAreTheDocuments() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        int prior = parameter(ids, "est-material-problem");
        review.move(prior, 0.01);
        String saved = review.body();
        review.saved();
        assertEquals(0, review.changed());
        assertEquals(saved, review.documentBody(), "the saved text is now the document's");
        assertEquals(0.01, review.value(prior).orElseThrow().document(), 1e-12);
        assertFalse(review.value(prior).orElseThrow().changed());
        review.move(prior, 0.3);
        assertEquals(1, review.changed());
        assertTrue(review.resetAll());
        assertEquals(saved, review.body(), "a reset goes back to what was saved, not to what the review opened with");
        assertEquals(Bounds.of(0.005, 0.4), review.value(prior).orElseThrow().range(), "the slider's range is as it was");
    }

    @Test
    @DisplayName("The prior is read where the tree says an event's share is from it: the unreliable event of each alternative")
    void aValueIsReadByTheMeasuresThatAreFromIt() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        DecisionTree tree = review.rollback().orElseThrow().tree();
        List<Integer> readers = review.readers(parameter(ids, "est-material-problem"));
        assertEquals(2, readers.size(), () -> "one share in each alternative: " + readers);
        List<Integer> events = readers.stream().map(reader -> tree.parentIndex(reader).orElseThrow()).toList();
        assertEquals(List.of(vertex(review, ids, "bimo-unreliable-inspected"), vertex(review, ids, "bimo-unreliable-uninspected")), events);
        // the re-analysis cost is read on two events of the inspected alternative
        List<Integer> cost = review.readers(parameter(ids, "est-reanalysis-cost")).stream()
                .map(reader -> tree.parentIndex(reader).orElseThrow()).toList();
        assertEquals(List.of(vertex(review, ids, "bimo-found"), vertex(review, ids, "bimo-alarm")), cost);
        assertTrue(review.readers(9_999).isEmpty(), "a vertex that is no value of the review has no readers");
    }

    @Test
    @DisplayName("A node reads the values its measures are from: the found event its share and its cost, the root none")
    void aNodeReadsTheValuesItsMeasuresAreFrom() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        List<Integer> found = review.valuesAt(vertex(review, ids, "bimo-found"));
        assertEquals(2, found.size(), found::toString);
        assertTrue(found.contains(parameter(ids, "est-inspection-sensitivity")));
        assertTrue(found.contains(parameter(ids, "est-reanalysis-cost")));
        assertEquals(List.of(parameter(ids, "est-inspection-cost")), review.valuesAt(vertex(review, ids, "bimo-inspect")),
                "the inspected alternative has the inspection's cost");
        assertTrue(review.valuesAt(vertex(review, ids, "bimo-site-decision")).isEmpty(), "the decision itself reads no value");
        assertTrue(review.valuesAt(vertex(review, ids, "bimo-clear")).isEmpty(), "a residual event's share is what the others leave");
    }

    @Test
    @DisplayName("A value the review does not have, or a number that is none, moves nothing")
    void onlyTheModelsValuesMove() {
        BlockIdentities ids = BlockIdentities.storeFree();
        ModelReview review = open(ids);
        assertFalse(review.move(9_999, 0.5));
        assertFalse(review.move(parameter(ids, "est-material-problem"), Double.NaN));
        assertEquals(0, review.changed());
        assertSame(review.documentBody(), review.body());
    }

    @Test
    @DisplayName("A text that does not read as a tree opens no review")
    void aTextThatIsNoTreeOpensNothing() {
        BlockIdentities ids = BlockIdentities.storeFree();
        assertTrue(ModelReview.open("not a model at all\n", T, ids, 100_000, ExternalQuantities.none(), Map.of(),
                StatementMeasures.none()).isEmpty());
    }
}
