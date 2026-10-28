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

import org.eclipse.collections.api.factory.primitive.LongLists;
import org.eclipse.collections.api.list.primitive.ImmutableLongList;

import java.util.OptionalLong;

/**
 * An ANF measure as a decision tree carries it: bounds, and the measure semantic that says what
 * the number is, with where the value comes from. The bounds and the semantic sit on the vertex
 * under the kernel's interval-role keys, as an ANF statement's measures do; the basis, the
 * derivation and its operands under the decision tree's own keys. A derived measure states no
 * bounds of its own: {@link Derivations} computes them from the tree's parameters.
 *
 * @param bounds      the lower and upper bound with their inclusivity; NaN bounds when the
 *                    measure is derived and states none
 * @param semanticNid the measure semantic: a share at a node, a money semantic, a health semantic
 * @param basisNid    the basis kind the value rests on, when stated: estimated, cited or derived
 * @param derivedFrom the derivation kind or the definition that derives the value, when it is derived
 * @param operands    the quantities a derivation reads, in its order, as the concepts naming them
 */
public record Measure(Bounds bounds, long semanticNid, OptionalLong basisNid, OptionalLong derivedFrom,
                      ImmutableLongList operands) {

    public Measure(Bounds bounds, long semanticNid) {
        this(bounds, semanticNid, OptionalLong.empty(), OptionalLong.empty(), LongLists.immutable.empty());
    }

    /** Whether the measure states bounds of its own. */
    public boolean stated() {
        return !Double.isNaN(bounds.lower());
    }

    /** Whether the measure's value is computed from other measures. */
    public boolean derived() {
        return derivedFrom.isPresent();
    }

    @Override
    public String toString() {
        return (stated() ? bounds.toString() : "derived") + " on " + semanticNid;
    }
}
