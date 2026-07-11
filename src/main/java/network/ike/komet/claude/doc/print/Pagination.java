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

import java.util.List;
import java.util.Objects;

/**
 * The pure, off-JavaFX pagination arithmetic for the paged document print: given each block's
 * measured height and whether it is atomic, and the printable content-band height of one page,
 * decide where page breaks fall and how much leading spacing to insert before each block so that
 * atomic blocks are kept together.
 *
 * <p>The print engine renders all blocks into one tall content column and shows page&nbsp;{@code k}
 * by translating that column up by {@code k × bandHeight} and clipping to one band. This class only
 * computes the spacers and the page count for that column — it never touches the scene graph, so it
 * is deterministic and unit-testable.
 *
 * <p><b>Keep-together rule.</b> A block whose content must not be split across a page boundary
 * (a role header, a concept tree, a table) is {@linkplain BlockBox#atomic() atomic}. When an atomic
 * block would straddle a band boundary, a spacer is inserted before it to push it to the top of the
 * next band. Prose blocks are not atomic and break naturally at the band boundary (clean
 * prose-splitting is a deferred follow-on). An atomic block taller than a whole band is pushed to a
 * clean band top and then allowed to straddle — there is no other option, and this never loops.
 */
public final class Pagination {

    /** Sub-point slack so a block that fills a band to the pixel is not treated as straddling. */
    private static final double EPSILON = 0.5;

    private Pagination() {
    }

    /**
     * A measured block awaiting placement.
     *
     * @param height the block's laid-out height, in the same units as the band height (points)
     * @param atomic whether the block must not be split across a page boundary
     */
    public record BlockBox(double height, boolean atomic) {
    }

    /**
     * The computed page layout.
     *
     * @param pageCount     the number of pages (always at least 1, so an empty document still
     *                      prints one blank page)
     * @param leadingSpacer the spacer height to insert before each block, parallel to the input
     *                      list (0 where no keep-together push was needed)
     * @param pageOfBlock   the 0-based page index on which each block begins, parallel to the input
     *                      list (a block taller than a band begins on this page and continues onto
     *                      the next)
     */
    public record PagePlan(int pageCount, double[] leadingSpacer, int[] pageOfBlock) {
    }

    /**
     * Plans page breaks and keep-together spacers for a column of blocks.
     *
     * @param blocks     the blocks in stacking order; must not be {@code null} (may be empty)
     * @param bandHeight the printable content-band height of one page, in points; must be positive
     * @return the page plan (spacers, per-block start page, and total page count)
     * @throws IllegalArgumentException if {@code bandHeight} is not positive
     * @throws NullPointerException     if {@code blocks} is {@code null}
     */
    public static PagePlan plan(List<BlockBox> blocks, double bandHeight) {
        Objects.requireNonNull(blocks, "blocks is null");
        if (bandHeight <= 0) {
            throw new IllegalArgumentException("bandHeight must be positive: " + bandHeight);
        }
        int n = blocks.size();
        double[] leadingSpacer = new double[n];
        int[] pageOfBlock = new int[n];
        double y = 0; // running top offset of the next block within the spacer-padded column

        for (int i = 0; i < n; i++) {
            BlockBox block = blocks.get(i);
            int band = (int) (y / bandHeight);
            double bandTop = band * bandHeight;
            double bandBottom = bandTop + bandHeight;
            boolean straddles = y + block.height() > bandBottom + EPSILON;
            boolean midBand = y > bandTop + EPSILON;

            if (block.atomic() && straddles && midBand) {
                // Push the atomic block to the top of the next band so it starts clean.
                leadingSpacer[i] = bandBottom - y;
                y = bandBottom;
            }
            pageOfBlock[i] = (int) (y / bandHeight);
            y += block.height();
        }

        // ceil(totalHeight / bandHeight), with slack so an exact multiple does not add a blank page,
        // and a floor of one page so an empty document still yields a single (blank) page.
        int pageCount = (int) Math.ceil((y - EPSILON) / bandHeight);
        if (pageCount < 1) {
            pageCount = 1;
        }
        return new PagePlan(pageCount, leadingSpacer, pageOfBlock);
    }
}
