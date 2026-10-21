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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A selection of a stamped document: a contiguous range of its lines in document order,
 * anchored where the gesture began and ending where the caret is (ike-lab-documents topic
 * {@code dev-stamped-document-surface}). Selection works at the level annotation works at,
 * whole lines, because an annotation names a line range in its trailer; what is selected is
 * exactly what will be recorded.
 *
 * <p>Ordinals are indexes into the document's lines in manifest order, so a selection may
 * cross blocks. {@link #ranges} turns it into the 1-based line numbers of the committed file
 * for the trailers, skipping lines the committed file does not hold.
 *
 * @param anchor the ordinal of the line the selection started on
 * @param caret  the ordinal of the line it ends on
 */
public record LineSelection(int anchor, int caret) {

    /** @return the smaller ordinal */
    public int min() {
        return Math.min(anchor, caret);
    }

    /** @return the larger ordinal */
    public int max() {
        return Math.max(anchor, caret);
    }

    /** @return how many lines are selected */
    public int size() {
        return max() - min() + 1;
    }

    /** @return whether the line with that ordinal is selected */
    public boolean contains(int ordinal) {
        return ordinal >= min() && ordinal <= max();
    }

    /** @return this selection with the caret moved, the anchor kept */
    public LineSelection extendTo(int newCaret) {
        return new LineSelection(anchor, newCaret);
    }

    /**
     * The committed line numbers of the selected lines, folded into contiguous 1-based
     * inclusive ranges in the order the trailers want them.
     *
     * @param number the committed line number (1-based) of a line by ordinal, or {@code 0} when
     *               the committed file does not hold it
     * @return the ranges, empty when no selected line is committed
     */
    public List<int[]> ranges(java.util.function.IntUnaryOperator number) {
        List<Integer> numbers = new ArrayList<>();
        for (int ordinal = min(); ordinal <= max(); ordinal++) {
            int n = number.applyAsInt(ordinal);
            if (n > 0) {
                numbers.add(n);
            }
        }
        return fold(numbers);
    }

    /** @return how many selected lines have no committed number */
    public int uncommitted(java.util.function.IntUnaryOperator number) {
        int count = 0;
        for (int ordinal = min(); ordinal <= max(); ordinal++) {
            if (number.applyAsInt(ordinal) <= 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * Folds sorted line numbers into contiguous inclusive ranges.
     *
     * @param numbers 1-based line numbers; sorted ascending, duplicates allowed
     * @return the ranges as {@code {from, to}} pairs
     */
    public static List<int[]> fold(List<Integer> numbers) {
        List<int[]> ranges = new ArrayList<>();
        int[] current = null;
        for (int n : numbers.stream().sorted().distinct().toList()) {
            if (current != null && current[1] == n - 1) {
                current[1] = n;
            } else {
                current = new int[] {n, n};
                ranges.add(current);
            }
        }
        return ranges;
    }

    /**
     * The selection covering the given 1-based committed line ranges, as one contiguous span
     * from the first line of the first range to the last of the last — a proposal from the
     * assistant names ranges, and the surface shows them as one selection.
     *
     * @param ranges  1-based inclusive ranges
     * @param ordinal the ordinal of a committed line number, or {@code -1} when the shown document
     *                does not hold it
     * @return the selection, or {@code null} when none of the lines is shown
     */
    public static LineSelection covering(List<int[]> ranges, java.util.function.IntUnaryOperator ordinal) {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (int[] r : ranges) {
            for (int n = r[0]; n <= r[1]; n++) {
                int o = ordinal.applyAsInt(n);
                if (o >= 0) {
                    first = Math.min(first, o);
                    last = Math.max(last, o);
                }
            }
        }
        return last < 0 ? null : new LineSelection(first, last);
    }

    /** @return each line's ordinal by nid, in the given order */
    public static Map<Long, Integer> ordinals(List<StoredLine> lines) {
        Map<Long, Integer> ordinals = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            ordinals.putIfAbsent(lines.get(i).nid(), i);
        }
        return ordinals;
    }
}
