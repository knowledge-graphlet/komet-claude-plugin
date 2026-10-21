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

    private static Pagination.BlockBox heading(double h) {
        return new Pagination.BlockBox(h, true, true);
    }

    @Test
    void headingThatWouldEndAPageMovesWithItsText() {
        // prose fills 0..70; the 20pt heading fits (70..90) but its companion (min(60, 2×20)=40pt) does
        // not, so the heading is pushed to the next band and the text starts there with it.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(70), heading(20), prose(60)), 100);
        assertEquals(30.0, plan.leadingSpacer()[1], 0.001);
        assertArrayEquals(new int[]{0, 1, 1}, plan.pageOfBlock());
        assertEquals(2, plan.pageCount());
    }

    @Test
    void headingWithRoomForItsCompanionStays() {
        // prose fills 0..30; heading 30..50; companion 40pt fits to 90 → no push.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(30), heading(20), prose(60)), 100);
        assertEquals(0.0, plan.leadingSpacer()[1]);
        assertArrayEquals(new int[]{0, 0, 0}, plan.pageOfBlock());
    }

    @Test
    void lastHeadingHasNothingToKeepWith() {
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(70), heading(20)), 100);
        assertEquals(0.0, plan.leadingSpacer()[1]);
        assertEquals(1, plan.pageCount());
    }

    // ── widows and orphans (dev-document-surface-pages: Typst keeps two lines at each edge) ──

    @Test
    void anOrphanedFootIsPushedWholeToTheNextPage() {
        // A 100pt band, a 12pt line; 92pt used, then a 60pt paragraph: 8pt (under a line) would stand at the foot.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(92), prose(60)), 100, 12);
        assertEquals(8.0, plan.leadingSpacer()[1], 0.001, "the paragraph starts on the next page instead");
        assertEquals(1, plan.pageOfBlock()[1]);
        assertEquals(2, plan.pageCount());
    }

    @Test
    void aWidowedHeadIsAvoidedByMovingTheParagraphDown() {
        // 50pt used, then a 60pt paragraph: 50pt fits, 10pt (under two 12pt lines) would spill. Shift by the lines lacking.
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(50), prose(60)), 100, 12);
        assertEquals(24.0, plan.leadingSpacer()[1], 0.001, "two 12pt lines of shift: the page runs short so two lines spill");
        assertEquals(0, plan.pageOfBlock()[1], "the paragraph still starts on the first page");
        assertEquals(2, plan.pageCount());
    }

    @Test
    void shortParagraphsAndTheOldPlanAreUntouchedByTheRule() {
        // Under four lines tall: no rule. And lineHeight 0 is the plain plan.
        Pagination.PagePlan small = Pagination.plan(List.of(prose(92), prose(40)), 100, 12);
        assertEquals(0.0, small.leadingSpacer()[1], 0.001);
        Pagination.PagePlan plain = Pagination.plan(List.of(prose(92), prose(60)), 100, 0);
        assertEquals(0.0, plain.leadingSpacer()[1], 0.001);
        assertEquals(Pagination.plan(List.of(prose(92), prose(60)), 100).pageCount(), plain.pageCount());
    }

    @Test
    void aParagraphThatFitsExactlyIsNotShifted() {
        Pagination.PagePlan plan = Pagination.plan(List.of(prose(40), prose(60)), 100, 12);
        assertEquals(0.0, plan.leadingSpacer()[1], 0.001);
        assertEquals(1, plan.pageCount());
    }
}
