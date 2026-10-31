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

import java.util.Optional;

/**
 * Quantities a decision tree may read that another block of the same document computes
 * ({@code dev-calculator-blocks}): a calculator's result, read by {@code p = from k:Risk[] @id=…}
 * where the tree states no parameter for the quantity. The reference points one way: the tree
 * names the quantity, and whoever holds the computed value answers; the tree's text never carries
 * the number. A parse with externals adds the answered quantities as parameters of the tree in
 * memory, so rollback, sweep and drawing see them as any parameter, never as text.
 */
@FunctionalInterface
public interface ExternalQuantities {

    /** A computed value: its bounds, and the measure semantic it is on. */
    record Value(Bounds bounds, long semanticNid) {
    }

    /** The value of a quantity another block computes, empty when none does. */
    Optional<Value> value(long quantityNid);

    /** No externals: a tree reads only what it states. */
    static ExternalQuantities none() {
        return quantityNid -> Optional.empty();
    }
}
