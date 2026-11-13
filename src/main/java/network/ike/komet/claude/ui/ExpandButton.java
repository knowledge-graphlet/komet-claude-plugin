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
package network.ike.komet.claude.ui;

import dev.ikm.komet.layout.expand.ExpansionCorner;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.Region;

/**
 * How a decision block shows that it can grow: one Expand button at the top right corner of the
 * block, drawn always ({@code dev-grow-model-review}, "The grow button has to be seen"). The button
 * is the grow's own icon, placed and shown by what {@code KlExpandable} is told here; a block has no
 * second mark that looks like it.
 */
final class ExpandButton {

    /** The corner of the block the button is in. */
    static final ExpansionCorner CORNER = ExpansionCorner.TOP_RIGHT;

    /** The button is at the corner itself, at the end of the block's first row. */
    static final Insets INSETS = Insets.EMPTY;

    /**
     * The corner of the grown view its contract icon is in: the bottom right. The grown view fills
     * another place than the block, and the journal window draws the assistant's button over its
     * own top right corner.
     */
    static final ExpansionCorner CONTRACT_CORNER = ExpansionCorner.BOTTOM_RIGHT;

    /** The room the button takes at the end of a block's first row, in points: its 24 and a gap. */
    static final double ROOM = 30;

    /** The least height of a block's first row, so that the row beneath it starts under the button. */
    static final double HEIGHT = 26;

    private ExpandButton() {
    }

    /**
     * Keeps a block's first row clear of the button, which is drawn over the row's end: the row
     * ends before the button and is as tall as it.
     *
     * @param firstRow the first row of a block's column
     * @param <R>      the row's type
     * @return the row
     */
    static <R extends Region> R leaveRoom(R firstRow) {
        Insets has = firstRow.getPadding();
        firstRow.setPadding(new Insets(has.getTop(), has.getRight() + ROOM, has.getBottom(), has.getLeft()));
        firstRow.setMinHeight(HEIGHT);
        return firstRow;
    }

    /**
     * The Expand button of a block's node.
     *
     * @param grown the node a block's grow returned for the document
     * @return the button, or null when the node has none
     */
    static Node of(Node grown) {
        return grown == null ? null : grown.lookup(".kl-expand-icon");
    }
}
