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
 * The measures the document's ANF statements state, by the name an {@code anf} block gives its
 * statement, as a graphlet model's parameter reads one ({@code dev-decision-point-knowledge},
 * settled 2026-11-06): {@code parameter = [0.15,0.15] anf:est-material-problem} names the
 * statement that holds the range someone determined, and the value the tree runs at beside it.
 * The statement's result is the range; its measure semantic is the scale; its topic, with the
 * group it is about, names the parameter. Nothing here is computed: the seam hands the tree what
 * the statement says, and the tree decides what to run at.
 */
@FunctionalInterface
public interface StatementMeasures {

    /**
     * What a statement states, as a parameter reads it.
     *
     * @param range       the statement's result: the range someone determined, a point when they
     *                    stated one
     * @param semanticNid the measure semantic the tree reads the number on: the share at a node
     *                    for a fraction, the statement's own scale otherwise
     * @param label       how the parameter is named: the statement's topic, with the group it is
     *                    about when it names one
     */
    record Stated(Bounds range, long semanticNid, String label) {
    }

    /** The measure the statement under a name states, when the document has that statement. */
    Optional<Stated> measure(String name);

    /** No statements at all, as a block read on its own. */
    static StatementMeasures none() {
        return name -> Optional.empty();
    }
}
