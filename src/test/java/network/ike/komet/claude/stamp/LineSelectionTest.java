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
package network.ike.komet.claude.stamp;

import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The selection-to-line-range mapping: whole lines in document order, committed numbers for the trailers. */
class LineSelectionTest {

    private static List<int[]> pairs(List<int[]> ranges) {
        return ranges;
    }

    private static String show(List<int[]> ranges) {
        return ranges.stream().map(r -> r[0] + "-" + r[1]).reduce((a, b) -> a + "," + b).orElse("");
    }

    @Test
    void aSelectionIsAContiguousSpanWhicheverWayItWasDragged() {
        LineSelection forward = new LineSelection(3, 7);
        LineSelection backward = new LineSelection(7, 3);

        assertEquals(3, forward.min());
        assertEquals(7, forward.max());
        assertEquals(3, backward.min());
        assertEquals(7, backward.max());
        assertEquals(5, backward.size());
        assertTrue(forward.contains(5));
        assertFalse(forward.contains(8));
        assertEquals(new LineSelection(3, 10), forward.extendTo(10), "shift-click moves the caret and keeps the anchor");
        assertEquals(new LineSelection(7, 1), backward.extendTo(1));
    }

    @Test
    void committedNumbersFoldIntoRangesAndUncommittedLinesDropOut() {
        // Ordinals 0..6 of a pending document: ordinal 3 is an uncommitted edit (no number), and the
        // committed numbers after it are therefore shifted.
        int[] numbers = {1, 2, 3, 0, 4, 5, 6};
        LineSelection selection = new LineSelection(1, 5);

        assertEquals("2-5", show(pairs(selection.ranges(o -> numbers[o]))), "one contiguous range of committed lines");
        assertEquals(1, selection.uncommitted(o -> numbers[o]));
        assertEquals("", show(new LineSelection(3, 3).ranges(o -> numbers[o])), "only an uncommitted line: nothing to record");
    }

    @Test
    void foldingKeepsGapsApart() {
        assertEquals("1-3,7-7,9-10", show(LineSelection.fold(List.of(1, 2, 3, 7, 9, 10))));
        assertEquals("4-4", show(LineSelection.fold(List.of(4, 4, 4))), "duplicates fold away");
        assertEquals("", show(LineSelection.fold(List.of())));
    }

    @Test
    void aProposalsRangesBecomeOneSelectionOverTheShownLines() {
        // Committed numbers 1..5 shown at ordinals 10..14; number 9 is not shown at all.
        java.util.function.IntUnaryOperator ordinal = n -> n >= 1 && n <= 5 ? n + 9 : -1;

        assertEquals(new LineSelection(10, 12), LineSelection.covering(List.of(new int[] {1, 3}), ordinal));
        assertEquals(new LineSelection(11, 14), LineSelection.covering(List.of(new int[] {2, 2}, new int[] {5, 5}), ordinal),
                "two ranges select the span from the first to the last, as a drag would");
        assertNull(LineSelection.covering(List.of(new int[] {9, 9}), ordinal), "nothing shown, nothing selected");
    }

    @Test
    void ordinalsFollowDocumentOrder() {
        List<StoredLine> lines = List.of(
                new StoredLine(30, "c", null, null, List.of()),
                new StoredLine(10, "a", null, null, List.of()),
                new StoredLine(20, "b", null, null, List.of()));

        assertEquals(Map.of(30L, 0, 10L, 1, 20L, 2), LineSelection.ordinals(lines));
    }
}
