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
import network.ike.komet.claude.decision.DecisionTreeSensitivity.Swing;
import network.ike.komet.claude.decision.render.DecisionTreeFigures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site inspection tree as a graphlet model ({@code dev-decision-point-knowledge}, settled
 * 2026-11-06): its numbers are the document's statements, each parameter reads one by name and
 * holds the value the tree runs at. With every value at today's base the rollback is what the
 * bare-parameter block gave, $250,625 for inspecting against $750,000 for not, and the ledger is
 * unchanged. The prior's statement determines 0.5% to 40%, midpoint 20.25%, while the model runs
 * at 15%: a value not honoured moves the numbers, and the sweep swings the prior over the
 * statement's range, where the choice turns at about 1.9%.
 */
class GraphletModelTest {

    static final DecisionTerms T = DecisionTerms.synthetic();
    /** The US dollar as the statements' candidate scale takes its identity: one per label, local to the document. */
    static final String USD = DecisionTreeText.CANDIDATE_SCALE + "US dollar";
    static String model;
    static String bare;

    /** What the site document's six statements state. */
    static StatementMeasures statements(BlockIdentities ids) {
        long usd = ids.local(USD, "US dollar").orElseThrow();
        Map<String, StatementMeasures.Stated> stated = Map.of(
                "est-material-problem", new StatementMeasures.Stated(Bounds.of(0.005, 0.4), T.shareAtNode(),
                        "material problem in the site's clinical data among submissions like this one"),
                "est-inspection-sensitivity", new StatementMeasures.Stated(Bounds.point(0.8), T.shareAtNode(),
                        "clinical site inspection among sites with a material problem"),
                "est-inspection-false-alarm", new StatementMeasures.Stated(Bounds.point(0.05), T.shareAtNode(),
                        "clinical site inspection among sites with sound data"),
                "est-inspection-cost", new StatementMeasures.Stated(Bounds.point(60_000), usd, "clinical site inspection (US dollar)"),
                "est-reanalysis-cost", new StatementMeasures.Stated(Bounds.point(250_000), usd, "exclusion of the site's data and re-analysis (US dollar)"),
                "est-bad-approval-cost", new StatementMeasures.Stated(Bounds.point(5_000_000), usd, "approval on unreliable data (US dollar)"));
        return name -> Optional.ofNullable(stated.get(name));
    }

    @BeforeAll
    static void readTheBlocks() throws IOException {
        model = body("/decision/bimo-site-model.adoc");
        bare = body("/decision/bimo-site-tree.adoc");
    }

    static String body(String resource) throws IOException {
        try (InputStream in = GraphletModelTest.class.getResourceAsStream(resource)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        }
    }

    static Rollback rollback(String body, BlockIdentities ids, StatementMeasures statements) {
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(body, T, ids, ExternalQuantities.none(), statements);
        assertTrue(parsed.findings().stream().allMatch(DecisionTreeFeedback::isLocalSymbolsNote), () -> "findings: " + parsed.findings());
        DecisionTree tree = parsed.tree().orElseThrow();
        assertTrue(tree.check().isEmpty(), () -> "check: " + tree.check());
        return Rollback.of(tree, BlockSweep.coordinate(tree, 100_000), LinkedTrees.none());
    }

    static int vertexOf(DecisionTree tree, BlockIdentities ids, String identifier) {
        long nid = ids.nid(identifier).orElseThrow();
        return tree.nodes().stream().filter(n -> n.concept().orElse(0) == nid).findFirst().orElseThrow().vertexIndex();
    }

    @Test
    @DisplayName("With every value at its base, the model rolls back as the bare block did: $250,625 against $750,000")
    void theModelRollsBackToTheWorkedNumbers() {
        BlockIdentities ids = BlockIdentities.storeFree();
        Rollback rollback = rollback(model, ids, statements(ids));
        DecisionTree tree = rollback.tree();
        assertEquals(250_625, rollback.rootValue().orElseThrow().midpoint(), 0.5);
        assertEquals(750_000, rollback.value(vertexOf(tree, ids, "bimo-no-inspection")).orElseThrow().midpoint(), 0.5);
        assertEquals(vertexOf(tree, ids, "bimo-inspect"), rollback.chosen(tree.rootIndex()).orElseThrow());
    }

    @Test
    @DisplayName("The ledger per hundred is the bare block's, column for column")
    void theLedgerIsUnchanged() {
        BlockIdentities modelIds = BlockIdentities.storeFree();
        Rollback fromModel = rollback(model, modelIds, statements(modelIds));
        BlockIdentities bareIds = BlockIdentities.storeFree();
        Rollback fromBare = rollback(bare, bareIds, StatementMeasures.none());
        List<List<String>> modelLedger = DecisionTreeFigures.ledgerTable(fromModel.tree(), fromModel, modelIds, "$", "",
                Optional.of(Cohort.of(fromModel.tree(), fromModel, 100)));
        List<List<String>> bareLedger = DecisionTreeFigures.ledgerTable(fromBare.tree(), fromBare, bareIds, "$", "",
                Optional.of(Cohort.of(fromBare.tree(), fromBare, 100)));
        assertEquals(bareLedger, modelLedger);
        assertTrue(modelLedger.size() >= 3, () -> "a ledger with a row per alternative: " + modelLedger);
    }

    @Test
    @DisplayName("A parameter with no value runs at its statement's midpoint, and the prior's midpoint moves the numbers")
    void anUnheldValueIsTheMidpoint() {
        BlockIdentities ids = BlockIdentities.storeFree();
        String atMidpoint = model.replace("parameter = [0.15,0.15] anf:est-material-problem", "parameter = anf:est-material-problem");
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(atMidpoint, T, ids, ExternalQuantities.none(), statements(ids));
        DecisionTree tree = parsed.tree().orElseThrow();
        DecisionElement.Parameter prior = tree.nodesOfType(DecisionElement.Parameter.class).get(0);
        assertEquals(Optional.of("est-material-problem"), parsed.parameterStatement(prior.vertexIndex()));
        assertEquals(Bounds.of(0.005, 0.4), parsed.range(prior.vertexIndex()).orElseThrow());
        assertEquals(0.2025, prior.measure().bounds().midpoint(), 1e-9, "the midpoint, computed and never written");
        Rollback rollback = Rollback.of(tree, BlockSweep.coordinate(tree, 100_000), LinkedTrees.none());
        assertEquals(312_968.75, rollback.rootValue().orElseThrow().midpoint(), 0.5);
        assertNotEquals(250_625, Math.round(rollback.rootValue().orElseThrow().midpoint()), "a stored value not honoured moves the numbers");
    }

    @Test
    @DisplayName("The emitter writes a statement's parameter back as anf:<name>, and a read as from anf:<name>")
    void theModelWritesItsStatementsBack() {
        BlockIdentities ids = BlockIdentities.storeFree();
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(model, T, ids, ExternalQuantities.none(), statements(ids));
        String written = DecisionTreeText.body(parsed.tree().orElseThrow(), ids);
        assertTrue(written.contains("parameter = [0.15,0.15] anf:est-material-problem"), written);
        assertTrue(written.contains("p = from anf:est-material-problem") || written.contains("probability = from anf:est-material-problem"), written);
        assertTrue(written.contains("money = from anf:est-inspection-cost"), written);
        assertFalse(written.contains("candidate:"), "a statement's scale is the statement's, never written by the model");
        DecisionTreeText.Parsed again = DecisionTreeText.parse(written, T, ids, ExternalQuantities.none(), statements(ids));
        assertEquals(written, DecisionTreeText.body(again.tree().orElseThrow(), ids), "the second emission is the first");
    }

    @Test
    @DisplayName("A statement the document has not stated yet leaves its parameter without a value, and the tree waits")
    void aMissingStatementIsWaitedFor() {
        BlockIdentities ids = BlockIdentities.storeFree();
        String without = model.replace("parameter = [0.8,0.8] anf:est-inspection-sensitivity", "parameter = anf:est-inspection-sensitivity");
        StatementMeasures all = statements(ids);
        StatementMeasures missingOne = name -> "est-inspection-sensitivity".equals(name) ? Optional.empty() : all.measure(name);
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(without, T, ids, ExternalQuantities.none(), missingOne);
        assertEquals(List.of("est-inspection-sensitivity"), parsed.missingStatements());
        DecisionTree tree = parsed.tree().orElseThrow();
        Rollback rollback = Rollback.of(tree, BlockSweep.coordinate(tree, 100_000), LinkedTrees.none());
        assertFalse(rollback.determinable(), "no guess stands in for a statement");
        // with the value held in the model, the tree runs even before the statement lands
        DecisionTreeText.Parsed held = DecisionTreeText.parse(model, T, ids, ExternalQuantities.none(), missingOne);
        assertEquals(List.of("est-inspection-sensitivity"), held.missingStatements());
        Rollback heldRollback = Rollback.of(held.tree().orElseThrow(), BlockSweep.coordinate(held.tree().orElseThrow(), 100_000), LinkedTrees.none());
        assertEquals(250_625, heldRollback.rootValue().orElseThrow().midpoint(), 0.5);
    }

    @Test
    @DisplayName("The sweep swings the prior over the statement's range, as stated, and the choice turns at about 1.9%")
    void theSweepTakesTheStatementsRange() {
        BlockIdentities ids = BlockIdentities.storeFree();
        StatementMeasures statements = statements(ids);
        DecisionTreeSensitivity sweep = BlockSweep.sweep(model, T, ids, 100_000, BlockSweep.DEFAULT_STEPS,
                ExternalQuantities.none(), Map.of(), statements).orElseThrow();
        DecisionTree tree = sweep.tree();
        DecisionElement.Parameter prior = tree.nodesOfType(DecisionElement.Parameter.class).get(0);
        Swing swing = sweep.swing(prior.vertexIndex()).orElseThrow();
        assertTrue(swing.stated(), "the statement determined the range");
        assertEquals(Bounds.of(0.005, 0.4), swing.range());
        assertEquals(0.15, swing.base(), 1e-9, "the tree runs at the model's value, not the range's midpoint");
        List<DecisionTreeSensitivity.Flip> flips = swing.flipsAt(tree.rootIndex()).castToList();
        assertEquals(1, flips.size(), () -> "one flip: " + flips);
        assertEquals(0.019, flips.get(0).at(), 0.001);
        // a parameter whose statement states a point swings over an assumed range, hollow, as a bare point does
        DecisionElement.Parameter sensitivity = tree.nodesOfType(DecisionElement.Parameter.class).get(1);
        assertFalse(sweep.swing(sensitivity.vertexIndex()).map(Swing::stated).orElse(true));
    }

    @Test
    @DisplayName("A value written into the model restates the one line, adding a value to a line that had none")
    void aWrittenValueRestatesOneLine() {
        String atMidpoint = model.replace("parameter = [0.15,0.15] anf:est-material-problem", "parameter = anf:est-material-problem");
        int line = 2;
        assertEquals(model, DecisionTreeEdits.restate(atMidpoint, line, Bounds.point(0.15)).orElseThrow());
        assertEquals(model.replace("[0.15,0.15] anf:est-material-problem", "[0.2,0.2] anf:est-material-problem"),
                DecisionTreeEdits.restate(model, line, Bounds.point(0.2)).orElseThrow());
        StringBuilder taken = new StringBuilder();
        GraphletModel writable = new GraphletModel("bimo-site-model", "graphlet-model id=bimo-site-model", atMidpoint,
                Optional.of(taken::append));
        assertTrue(writable.write(line, Bounds.point(0.15)));
        assertEquals(model, taken.toString());
        GraphletModel readOnly = new GraphletModel("bimo-site-model", "graphlet-model id=bimo-site-model", atMidpoint, Optional.empty());
        assertFalse(readOnly.write(line, Bounds.point(0.15)));
    }

    @Test
    @DisplayName("A model names itself with id=, or by its root's @id; a rendering names its model with model=")
    void namesAreRead() {
        assertEquals(Optional.of("bimo-site-model"), GraphletModelText.modelId("graphlet-model id=bimo-site-model", model));
        assertEquals(Optional.of("bimo-site-decision"), GraphletModelText.modelId("graphlet-model", model));
        assertEquals(Optional.of("bimo-site-model"), GraphletModelText.modelReference("decision-tree model=bimo-site-model cohort=100"));
        assertEquals(Optional.of("bimo-site-model"), GraphletModelText.modelReference("tornado model=\"bimo-site-model\""));
        assertEquals(Optional.empty(), GraphletModelText.modelReference("decision-tree cohort=100"));
        assertTrue(GraphletModelText.isTagged("graphlet-model id=x"));
        assertFalse(GraphletModelText.isTagged("decision-tree model=x"));
        assertEquals(OptionalLong.empty(), BlockIdentities.storeFree().nid("anf:est-material-problem"), "a statement is never a concept of the store");
    }
}
