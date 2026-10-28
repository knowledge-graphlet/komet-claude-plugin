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
package network.ike.komet.claude.block;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fenced blocks are found with their place, and one is restated without touching the rest. */
class FencedBlocksTest {

    static final String TEXT = """
            Before.

            ```decision-tree health-price=100000
            decision: k:D[D?] @id=d
              alternative: k:A[a] @id=a
            ```

            Between, with `inline code`.

            ~~~~json
            {"a": 1}
            ~~~~
            After.""";

    @Test
    @DisplayName("Both fences are found, with their info, body, fence and offsets")
    void scansBoth() {
        List<FencedBlocks.Fence> fences = FencedBlocks.scan(TEXT);
        assertEquals(2, fences.size());
        FencedBlocks.Fence tree = fences.get(0);
        assertEquals("decision-tree health-price=100000", tree.info());
        assertEquals("decision: k:D[D?] @id=d\n  alternative: k:A[a] @id=a\n", tree.body());
        assertEquals("```", tree.fence());
        assertEquals(TEXT.indexOf("```decision-tree"), tree.start());
        assertTrue(TEXT.substring(tree.start(), tree.end()).endsWith("```\n"));
        FencedBlocks.Fence json = fences.get(1);
        assertEquals("json", json.info());
        assertEquals("~~~~", json.fence());
        assertEquals("{\"a\": 1}\n", json.body());
    }

    @Test
    @DisplayName("Restating the tree keeps the prose and the other block byte for byte")
    void replacesOne() {
        String restated = FencedBlocks.replace(TEXT, "decision-tree health-price=100000",
                "decision: k:D[D?] @id=d\n  alternative: k:A[a] @id=a\n",
                "decision-tree health-price=50000",
                "decision: k:D[D?] @id=d\n  alternative: k:B[b] @id=b\n").orElseThrow();
        assertEquals(TEXT.replace("health-price=100000", "health-price=50000").replace("k:A[a] @id=a", "k:B[b] @id=b"),
                restated);
    }

    @Test
    @DisplayName("No block with that info and body means nothing to restate")
    void nothingToRestate() {
        assertEquals(Optional.empty(), FencedBlocks.replace(TEXT, "decision-tree", "x\n", "decision-tree", "y\n"));
        assertEquals(Optional.empty(), FencedBlocks.replace("no fences", "a", "b\n", "a", "c\n"));
    }

    @Test
    @DisplayName("A fence closing the text without a final newline is restated without adding one")
    void keepsTheEnding() {
        String text = "Lead.\n```x\nold\n```";
        assertEquals("Lead.\n```x\nnew\n```", FencedBlocks.replace(text, "x", "old\n", "x", "new\n").orElseThrow());
        String open = "Lead.\n```x\nold\n";
        assertEquals("Lead.\n```x\nnew\n```\n", FencedBlocks.replace(open, "x", "old\n", "x", "new").orElseThrow());
    }
}
