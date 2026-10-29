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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.decision.DecisionTree;
import network.ike.komet.claude.decision.DecisionTreeSensitivity;
import network.ike.komet.claude.decision.Rollback;

import java.util.Optional;
import java.util.function.IntPredicate;

/**
 * A decision tree and its tornado as Typst code, for the print pipeline, whose final target is
 * Typst: the same figures {@link DecisionTreeSvg} emits, from the same layout and the same
 * estimated label widths, written as a {@code box} of {@code place}d shapes and text in points,
 * so the printed page draws what Komet draws live, natively rather than through an image. Each
 * fragment is markup mode, beginning with {@code #box}, and stands on its own in a page.
 */
public final class DecisionTreeTypst {

    private DecisionTreeTypst() {
    }

    /** The tree as Typst, with nothing folded. */
    public static String tree(DecisionTree tree, Rollback rollback, Identities ids,
                              Optional<DecisionTreeSensitivity> sensitivity) {
        return tree(tree, rollback, ids, v -> false, sensitivity, "");
    }

    /** The tree as Typst; see {@link DecisionTreeSvg#tree(DecisionTree, Rollback, Identities, IntPredicate, Optional, String)}. */
    public static String tree(DecisionTree tree, Rollback rollback, Identities ids, IntPredicate collapsed,
                              Optional<DecisionTreeSensitivity> sensitivity, String symbol) {
        return DecisionFigureTypst.write(DecisionTreeFigures.tree(tree, rollback, ids, collapsed, sensitivity, symbol));
    }

    /** The tornado as Typst, {@link DecisionTreeSvg#TORNADO_WIDTH} points wide. */
    public static String tornado(DecisionTreeSensitivity sensitivity, Identities ids) {
        return tornado(sensitivity, ids, DecisionTreeSvg.TORNADO_WIDTH, "");
    }

    /** The tornado as Typst; see {@link DecisionTreeSvg#tornado(DecisionTreeSensitivity, Identities, double, String)}. */
    public static String tornado(DecisionTreeSensitivity sensitivity, Identities ids, double width, String symbol) {
        return DecisionFigureTypst.write(DecisionFigureTornado.build(DecisionTreeFigures.tornado(sensitivity, ids), width,
                root -> DecisionTreeFigures.money(root, symbol)));
    }
}
