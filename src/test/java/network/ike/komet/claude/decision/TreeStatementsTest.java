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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static network.ike.komet.claude.decision.ScanTreeRollbackTest.T;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ANF statements a tree's nodes hold by naming the document's anf blocks. */
class TreeStatementsTest {

    private static final String TREE = """
            decision: k:ScanNow[Scan now?] @id=rda-scan-now
              alternative: k:Scan[scan] @id=rda-scan statement=anf:req-scan
                money = [700,700] k:Usd[US dollar] @id=rda-usd
                chance: k:MraResult[MRA result] @id=rda-mra-result statement=anf:perf-positive
                  event: k:Positive[positive] @id=rda-positive statement=anf:perf-positive
                    p = [0.2,0.2]
                    outcome: k:Found[found] @id=rda-found statement=anf:req-scan
                  residual: k:Negative[negative] @id=rda-negative statement=anf:perf-negative
                    outcome: k:Clear[clear] @id=rda-clear
              alternative: k:NoScan[no scan] @id=rda-no-scan
                outcome: k:Untreated[untreated] @id=rda-untreated
                  money = [1000,1000] k:Usd[US dollar] @id=rda-usd
            """;

    @Test
    @DisplayName("A node's anf: statements are names in the document, read in the order the text states them")
    void readsTheNames() {
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(TREE, T, BlockIdentities.storeFree());
        assertTrue(parsed.findings().noneSatisfy(f -> f.message().contains("statement")), () -> "" + parsed.findings());
        List<TreeStatements.Held> held = TreeStatements.held(parsed);
        assertEquals(List.of("req-scan", "perf-positive", "perf-positive", "req-scan", "perf-negative"),
                held.stream().map(TreeStatements.Held::name).toList());
    }

    @Test
    @DisplayName("A statement on a chance, of the wrong type, or naming no block is a finding")
    void findings() {
        DecisionTreeText.Parsed parsed = DecisionTreeText.parse(TREE, T, BlockIdentities.storeFree());
        DecisionTree tree = parsed.tree().orElseThrow();
        Map<String, TreeStatements.Kind> kinds = Map.of("req-scan", TreeStatements.Kind.REQUEST,
                "perf-positive", TreeStatements.Kind.PERFORMANCE);
        List<String> findings = TreeStatements.findings(tree, parsed.labelled(BlockIdentities.storeFree()), parsed,
                name -> Optional.ofNullable(kinds.get(name)), true);
        assertEquals(3, findings.size(), () -> "" + findings);
        assertTrue(findings.get(0).startsWith("the chance “MRA result” holds anf:perf-positive, but a decision or chance holds no statement"),
                findings.get(0));
        assertTrue(findings.get(1).startsWith("the outcome “found” holds anf:req-scan, a request statement"), findings.get(1));
        assertTrue(findings.get(2).contains("anf:perf-negative, which no anf block before the tree names"), findings.get(2));
    }

    @Test
    @DisplayName("The assistant hears of a wrong type among the fences of its reply, never of a name another reply may give")
    void feedbackChecksTheReplyFences() {
        String reply = """
                ```anf type=request id=req-scan
                topic:: k:Mra[MRA of head] @id=437731000119100
                ```

                ```anf type=request id=perf-positive
                topic:: candidate[reopening of the sealed aneurysm] disp=PENDING
                result:: [1,1] k:Presence[presence] @id=705057003
                ```

                ```decision-tree
                """ + TREE.replace("  chance: k:MraResult[MRA result] @id=rda-mra-result statement=anf:perf-positive",
                "  chance: k:MraResult[MRA result] @id=rda-mra-result").replace(
                "outcome: k:Found[found] @id=rda-found statement=anf:req-scan", "outcome: k:Found[found] @id=rda-found") + "```\n";
        assertEquals(Map.of("req-scan", TreeStatements.Kind.REQUEST, "perf-positive", TreeStatements.Kind.REQUEST),
                TreeStatements.fenceKinds(reply));
        Optional<String> message = DecisionTreeFeedback.message(reply, T, BlockIdentities.storeFree());
        assertTrue(message.isPresent());
        assertTrue(message.get().contains("the event “positive” holds anf:perf-positive, a request statement;"
                + " an alternative holds a request, an event or outcome a performance"), message.get());
        assertTrue(!message.get().contains("perf-negative"), "a name the reply does not give is passed over");
    }
}
