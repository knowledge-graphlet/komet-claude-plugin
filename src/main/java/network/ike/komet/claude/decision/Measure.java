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

import java.util.OptionalLong;

/**
 * An ANF measure as a decision tree carries it: bounds, and the measure semantic that says what
 * the number is, with where the value comes from. The bounds and the semantic sit on the vertex
 * under the kernel's interval-role keys, as an ANF statement's measures do; the basis and the
 * derivation under the decision tree's own keys.
 *
 * @param bounds      the lower and upper bound with their inclusivity
 * @param semanticNid the measure semantic: a share at a node, a money semantic, a health semantic
 * @param basisNid    the basis kind the value rests on, when stated: estimated, cited or derived
 * @param derivedFrom the definition that derives the value, when it is derived
 */
public record Measure(Bounds bounds, long semanticNid, OptionalLong basisNid, OptionalLong derivedFrom) {

    public Measure(Bounds bounds, long semanticNid) {
        this(bounds, semanticNid, OptionalLong.empty(), OptionalLong.empty());
    }

    @Override
    public String toString() {
        return bounds + " on " + semanticNid;
    }
}
