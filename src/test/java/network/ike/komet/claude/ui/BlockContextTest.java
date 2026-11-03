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

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The document's tree registry, as the blocks of a conversation share it. */
class BlockContextTest {

    @Test
    void theLatestBlockRestatingATreeHoldsItAndAnEarlierOneRebuildingDoesNotTakeItBack() {
        BlockContext context = new BlockContext();
        AtomicInteger rebuilds = new AtomicInteger();
        // two blocks of one conversation, the first answer and its correction, each defining the tree
        // again whenever the document changes, as a block that folds a tree in rebuilds
        Runnable first = () -> {
            if (rebuilds.incrementAndGet() < 100) {
                context.defineTree("site", "first body", null, 1);
            }
        };
        Runnable second = () -> {
            if (rebuilds.incrementAndGet() < 100) {
                context.defineTree("site", "corrected body", null, 2);
            }
        };
        context.listen(first);
        context.listen(second);
        context.defineTree("site", "first body", null, 1);
        context.defineTree("site", "corrected body", null, 2);
        assertEquals("corrected body", context.trees().get("site"));
        assertEquals(4, rebuilds.get(), "each definition that changed the body is heard once by each block, and no more");
        // the context holds its listeners weakly, as a dropped block is forgotten
        java.lang.ref.Reference.reachabilityFence(first);
        java.lang.ref.Reference.reachabilityFence(second);
    }
}
