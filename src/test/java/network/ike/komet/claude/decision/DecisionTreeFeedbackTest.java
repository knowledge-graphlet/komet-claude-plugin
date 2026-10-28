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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static network.ike.komet.claude.decision.ScanTreeRollbackTest.T;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The findings on a reply's decision-tree blocks, as they go back to the assistant. */
class DecisionTreeFeedbackTest {

    /** The lab's scan tree, fenced as a reply carries it. */
    private static String scanTreeReply() throws Exception {
        String adoc;
        try (InputStream in = DecisionTreeFeedbackTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            adoc = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int open = adoc.indexOf("----\n") + 5;
        int close = adoc.lastIndexOf("----");
        return "Here is the tree.\n\n```decision-tree health-price=100000\n" + adoc.substring(open, close)
                + "```\n\nThe currency and the quantities are local to the block.\n";
    }

    @Test
    @DisplayName("Only the fenced blocks tagged decision-tree are read, by either fence character")
    void findsTheTaggedBlocks() {
        String reply = """
                Some prose.

                ```java
                decision: not a tree
                ```

                ```decision-tree health-price=50000
                decision: k:A[A] @id=a
                ```

                ~~~~property-graph profile=decision-tree
                decision: k:B[B] @id=b
                  alternative: k:C[C] @id=c
                ~~~~
                """;
        List<DecisionTreeFeedback.Block> blocks = DecisionTreeFeedback.blocks(reply);

        assertEquals(2, blocks.size(), () -> "blocks: " + blocks);
        assertEquals("decision-tree health-price=50000", blocks.get(0).info());
        assertEquals("decision: k:A[A] @id=a\n", blocks.get(0).body());
        assertTrue(blocks.get(1).body().endsWith("alternative: k:C[C] @id=c\n"), blocks.get(1).body());
        assertTrue(DecisionTreeFeedback.blocks("No block here, just `inline` ticks.").isEmpty());
    }

    @Test
    @DisplayName("A block that reads clean and rolls back at every node sends nothing back")
    void cleanBlockNeedsNoTurn() throws Exception {
        Optional<String> message = DecisionTreeFeedback.message(scanTreeReply(), T, BlockIdentities.storeFree());

        assertEquals(Optional.empty(), message);
    }

    @Test
    @DisplayName("Findings go back by line, or by the node they are on, and the local symbols do not")
    void findingsGoBackNamed() {
        String reply = """
                ```decision-tree
                decision: k:ScanNow[Scan now?] @id=rda-scan-now
                  alternative: k:Scan[scan] @id=rda-scan
                    cost = [700,700] k:Usd[US dollar] @id=rda-usd
                    outcome: k:Clear[clear] @id=rda-clear
                      money = [100,100] k:Visits[Follow-up visits] @id=rda-visits k:Usd[US dollar] @id=rda-usd
                  alternative: k:NoScan[no scan] @id=rda-no-scan
                    outcome: k:Untreated[rupture risk] @id=rda-untreated
                      money = from k:Product[] of k:RuptureRisk[] @id=rda-rupture-risk k:RuptureCost[] @id=rda-rupture-cost in k:Usd[US dollar] @id=rda-usd
                ```
                """;
        String message = DecisionTreeFeedback.message(reply, T, BlockIdentities.storeFree()).orElseThrow();

        assertTrue(message.startsWith(DecisionTreeFeedback.PREFIX + " block in your reply and found:\n"), message);
        assertTrue(message.contains("- line 3: unknown value of a vertex: cost"), message);
        assertTrue(message.contains("- line 5: ") && message.contains("unexpected"), message);
        assertTrue(message.contains("the money cost under the outcome “rupture risk”: a derivation reads a quantity the tree has no parameter for"), message);
        assertTrue(message.contains("the alternative “no scan”: what follows the branch is not determinable"), message);
        assertFalse(message.contains("local to the block"), message);
        assertTrue(message.endsWith("Load the Decision tree skill if you need the grammar."), message);
        assertTrue(DecisionTreeFeedback.isFeedback(message));
        assertFalse(DecisionTreeFeedback.isFeedback("Komet, read this tree please"));
    }

    @Test
    @DisplayName("A reply with two blocks numbers the one with findings")
    void numbersBlocksWhenSeveral() throws Exception {
        String reply = scanTreeReply() + "\n```decision-tree\ndecision: k:Later[Later?] @id=later\n```\n";
        String message = DecisionTreeFeedback.message(reply, T, BlockIdentities.storeFree()).orElseThrow();

        assertTrue(message.contains("\nBlock 2:\n- the decision “Later?”: a decision has no alternative"), message);
        assertFalse(message.contains("Block 1:"), message);
    }
}
