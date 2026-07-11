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
package network.ike.komet.claude.doc.print;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The pure pagination arithmetic ({@link Pagination}): page counting and the keep-together
 * spacer-push for atomic blocks. No JavaFX — the block heights are given directly.
 */
class PaginationTest {

    private static Pagination.BlockBox prose(double h) {
        return new Pagination.BlockBox(h, false);
    }

    private static Pagination.BlockBox atom(double h) {
        return new Pagination.BlockBox(h, true);
    }

    @Test
    void emptyDocumentIsOneBlankPage() {
        Pagination.PagePlan plan = Pagination.plan(List.of(), 100);
        assertEquals(1, plan.pageCount());
        assertEquals(0, plan.leadingSpacer().length);
        assertEquals(0, plan.pageOfBlock().length);
    }

    @Test
    void proseFlowsAcrossBandsWithoutSpacers() {
        // Three 60pt prose blocks in a 100pt band → 180pt → 2 pages, no keep-together push.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(60), prose(60), prose(60)), 100);
        assertEquals(2, plan.pageCount());
        for (double spacer : plan.leadingSpacer()) {
            assertEquals(0.0, spacer);
        }
        assertArrayEquals(new int[]{0, 0, 1}, plan.pageOfBlock());
    }

    @Test
    void atomicBlockStraddlingIsPushedToTheNextBand() {
        // prose fills 0..60; the atomic block would straddle 60..120, so it is pushed to 100..160.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(60), atom(60)), 100);
        assertEquals(40.0, plan.leadingSpacer()[1], 0.001);
        assertArrayEquals(new int[]{0, 1}, plan.pageOfBlock());
        assertEquals(2, plan.pageCount());
    }

    @Test
    void proseBlockStraddlingIsNotPushed() {
        // Same geometry, but prose is not atomic: no spacer, it breaks naturally at the boundary.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(60), prose(60)), 100);
        assertEquals(0.0, plan.leadingSpacer()[1]);
        assertEquals(0, plan.pageOfBlock()[1]); // starts on page 0, spans into page 1
    }

    @Test
    void atomicTallerThanABandStraddlesAfterASinglePush() {
        // The atomic block (150pt) is taller than the 100pt band; it is pushed once to a clean band
        // top, then allowed to straddle — exactly one spacer, never a loop.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(60), atom(150)), 100);
        assertEquals(40.0, plan.leadingSpacer()[1], 0.001);
        assertEquals(1, plan.pageOfBlock()[1]);
        assertEquals(3, plan.pageCount()); // 60 + 40 spacer + 150 = 250 → 3 bands
    }

    @Test
    void exactBandFillAddsNoBlankPageOrSpacer() {
        Pagination.PagePlan plan = Pagination.plan(List.of(atom(100), atom(100)), 100);
        assertEquals(0.0, plan.leadingSpacer()[0]);
        assertEquals(0.0, plan.leadingSpacer()[1]);
        assertArrayEquals(new int[]{0, 1}, plan.pageOfBlock());
        assertEquals(2, plan.pageCount());
    }

    @Test
    void atomicAlreadyAtABandTopIsNotPushed() {
        // The first atomic block fills band 0 exactly; the second starts at the band-1 top, so it
        // does not straddle and needs no push.
        Pagination.PagePlan plan = Pagination.plan(List.of(atom(100), atom(50)), 100);
        assertEquals(0.0, plan.leadingSpacer()[1]);
        assertEquals(1, plan.pageOfBlock()[1]);
    }

    @Test
    void nonPositiveBandIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Pagination.plan(List.of(prose(10)), 0));
    }
}
