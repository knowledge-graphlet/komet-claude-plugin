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
 * A decision tree and its tornado as self-contained SVG, drawn from the pure model alone: the
 * same {@code DecisionTreeLayout}, rollback and sweep the live JavaFX surface draws from, with
 * label widths estimated rather than measured, so the documentation build and a test draw the
 * picture Komet shows without a toolkit. Squares for decisions, circles for chances, triangles
 * for outcomes; every branch an elbow with its name and probability above the run and its costs
 * below; the rolled-back value at every glyph and on every alternative; the chosen alternative
 * heavy and each rejected one barred. The tornado is one bar per swept measure on the root's
 * expected cost, longest first, solid for a stated interval and hollow for an assumed range,
 * recoloured beyond a flip with a tick carrying the threshold.
 */
public final class DecisionTreeSvg {

    /** The tornado's width when no one says otherwise, in SVG units. */
    public static final double TORNADO_WIDTH = 640;

    private DecisionTreeSvg() {
    }

    /**
     * The tree as SVG, with nothing folded.
     *
     * @param tree        the tree
     * @param rollback    its rollback, which gives every glyph its value and every decision its choice
     * @param ids         what names the concepts
     * @param sensitivity the sweep, when one has run: a stated measure whose range holds a flip of the
     *                    root's choice is marked
     */
    public static String tree(DecisionTree tree, Rollback rollback, Identities ids,
                              Optional<DecisionTreeSensitivity> sensitivity) {
        return tree(tree, rollback, ids, v -> false, sensitivity, "");
    }

    /**
     * The tree as SVG.
     *
     * @param collapsed which nodes are folded: drawn as terminals saying what is beneath them
     * @param symbol    what goes before a rolled-back value, such as a currency sign; empty for none
     */
    public static String tree(DecisionTree tree, Rollback rollback, Identities ids, IntPredicate collapsed,
                              Optional<DecisionTreeSensitivity> sensitivity, String symbol) {
        return DecisionFigureSvg.write(DecisionTreeFigures.tree(tree, rollback, ids, collapsed, sensitivity, symbol));
    }

    /** The tornado as SVG, {@link #TORNADO_WIDTH} wide. */
    public static String tornado(DecisionTreeSensitivity sensitivity, Identities ids) {
        return tornado(sensitivity, ids, TORNADO_WIDTH, "");
    }

    /**
     * The tornado as SVG.
     *
     * @param width  the figure's width, which the bars share after the names and the captions
     * @param symbol what goes before a cost on the axis; empty for none
     */
    public static String tornado(DecisionTreeSensitivity sensitivity, Identities ids, double width, String symbol) {
        return DecisionFigureSvg.write(DecisionFigureTornado.build(DecisionTreeFigures.tornado(sensitivity, ids), width,
                root -> DecisionTreeFigures.money(root, symbol)));
    }
}
