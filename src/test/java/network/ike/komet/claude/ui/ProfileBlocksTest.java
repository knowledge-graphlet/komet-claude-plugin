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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The document's tree registry, as the blocks of a conversation share it. */
class ProfileBlocksTest {

    @Test
    void theLatestBlockRestatingATreeHoldsItAndAnEarlierOneRebuildingDoesNotTakeItBack() {
        ProfileBlocks context = new ProfileBlocks();
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

    @Test
    void aModelIsHeardWhenItsTextMovesAndWrittenThroughItsBlock() {
        ProfileBlocks context = new ProfileBlocks();
        AtomicInteger heard = new AtomicInteger();
        Runnable rendering = heard::incrementAndGet;
        context.listen(rendering);
        StringBuilder written = new StringBuilder();
        network.ike.komet.claude.decision.GraphletModel model = new network.ike.komet.claude.decision.GraphletModel(
                "site", "graphlet-model id=site", "decision: k:X[x] @id=x\n  parameter = anf:est-x\n", java.util.Optional.of(written::append));
        context.defineModel(model, 1);
        assertEquals(1, heard.get());
        context.defineModel(model, 1);
        assertEquals(1, heard.get(), "the same text again is silent");
        assertEquals("site", context.model("site").orElseThrow().id());
        // "Use this value" from a rendering: the model's block takes the restated line
        context.model("site").orElseThrow().write(2, network.ike.komet.claude.decision.Bounds.point(0.15));
        assertEquals("decision: k:X[x] @id=x\n  parameter = [0.15,0.15] anf:est-x\n", written.toString());
        java.lang.ref.Reference.reachabilityFence(rendering);
    }

    @Test
    void aPagesCopyOfAModelLeavesTheWritableOneInPlace() {
        ProfileBlocks context = new ProfileBlocks();
        AtomicInteger heard = new AtomicInteger();
        Runnable rendering = heard::incrementAndGet;
        context.listen(rendering);
        StringBuilder written = new StringBuilder();
        String text = "decision: k:X[x] @id=x\n  parameter = [0.1,0.1] k:P[p] @id=p";
        // the block on the surface, which can write the model
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site", text,
                java.util.Optional.of(written::append)), 1);
        // a page prints the document: the same model stated again, later, with its line break and no writer
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model, id=site", text + "\n",
                java.util.Optional.empty()), 7);
        assertTrue(context.model("site").orElseThrow().writable(), "the page's copy does not take the model");
        assertEquals(1, heard.get(), "and nothing moved, so no rendering is told");
        // "Use this value" in a rendering still reaches the block
        assertTrue(context.model("site").orElseThrow().write(2, network.ike.komet.claude.decision.Bounds.point(0.15)));
        assertEquals("decision: k:X[x] @id=x\n  parameter = [0.15,0.15] k:P[p] @id=p", written.toString());
        // and the block, restating its model with the value, is not refused as an earlier block
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site", written.toString(),
                java.util.Optional.of(written::append)), 1);
        assertEquals(written.toString(), context.model("site").orElseThrow().body());
        assertEquals(2, heard.get(), "the renderings hear the value");
        java.lang.ref.Reference.reachabilityFence(rendering);
    }

    @Test
    void aReadOnlyModelOfOtherTextTakesIt() {
        ProfileBlocks context = new ProfileBlocks();
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site",
                "decision: k:X[x] @id=x\n", java.util.Optional.of(text -> { })), 1);
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site",
                "decision: k:Y[y] @id=y\n", java.util.Optional.empty()), 7);
        assertEquals("decision: k:Y[y] @id=y\n", context.model("site").orElseThrow().body(), "the document says something else now");
        assertFalse(context.model("site").orElseThrow().writable());
    }

    @Test
    void aBlockBuiltAfterAPageTakesTheModelFromIt() {
        ProfileBlocks context = new ProfileBlocks();
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site",
                "decision: k:X[x] @id=x\n", java.util.Optional.empty()), 3);
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("site", "graphlet-model id=site",
                "decision: k:X[x] @id=x\n", java.util.Optional.of(text -> { })), 4);
        assertTrue(context.model("site").orElseThrow().writable(), "the surface's block, made later, holds the model and can write it");
    }

    @Test
    void aBlockToldOfItsOwnPublicationIsNotThrownOff() {
        // the context tells every listener at once, the publishing block among them, before its own build is done
        ProfileBlocks context = new ProfileBlocks();
        java.util.concurrent.atomic.AtomicReference<Runnable> self = new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger told = new AtomicInteger();
        Runnable listener = told::incrementAndGet;
        self.set(listener);
        context.listen(listener);
        context.defineModel(new network.ike.komet.claude.decision.GraphletModel("m", "graphlet-model id=m", "decision: k:X[x] @id=x\n", java.util.Optional.empty()), 1);
        assertEquals(1, told.get(), "a block hears its own publication, so it must not act on it before it has read");
        java.lang.ref.Reference.reachabilityFence(self);
    }

    @Test
    void theNewKindsAreNamedByTheirStyles() {
        assertEquals("graphlet-model", new DocumentBlockKinds.GraphletModel().style());
        assertEquals("tornado", new DocumentBlockKinds.Tornado().style());
        assertEquals(java.util.Optional.of("site"),
                network.ike.komet.claude.decision.GraphletModelText.modelReference(DocumentBlockKinds.info("[tornado, model=site]")));
        org.junit.jupiter.api.Assertions.assertTrue(TornadoBlockRenderer.accepts("tornado model=site"));
        org.junit.jupiter.api.Assertions.assertFalse(TornadoBlockRenderer.accepts("tornado"), "a tornado renders a model, so it names one");
        org.junit.jupiter.api.Assertions.assertTrue(DecisionTreeBlockRenderer.accepts("decision-tree model=site cohort=100", ""),
                "a rendering has no body of its own");
        org.junit.jupiter.api.Assertions.assertTrue(GraphletModelBlockRenderer.accepts("graphlet-model id=site", "decision: k:X[x] @id=x\n"));
        org.junit.jupiter.api.Assertions.assertFalse(GraphletModelBlockRenderer.accepts("decision-tree", "decision: k:X[x] @id=x\n"));
    }
}
