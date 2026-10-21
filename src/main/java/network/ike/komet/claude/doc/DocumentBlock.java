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
package network.ike.komet.claude.doc;

import javafx.scene.Node;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.model.StyledTextModel;

/**
 * One child of the {@link DocumentSurface} stack: a header, a run of prose, or a native block
 * (a koncept-tree, a table). Prose blocks carry their {@code RichTextArea} and model so search can
 * run against the model and select in the area; header and node blocks carry only their node and
 * plain-text projection.
 *
 * <p>A block may carry <em>marginalia</em> ({@code dev-interaction-surface-family}): a node for
 * the left margin and one for the right, which share the block's row and scroll with it. The
 * surface lays them beside the block's body when its margin regions are on; a surface with the
 * regions off ignores them.
 */
public final class DocumentBlock {

    private final Node node;
    private final String plainText;
    private final RichTextArea richText;
    private final StyledTextModel model;
    private final Node leftMarginalia;
    private final Node rightMarginalia;
    /** The stack child the surface built for this block: the body itself, or its row with the margins. */
    private Node row;

    private DocumentBlock(Node node, String plainText, RichTextArea richText, StyledTextModel model, Node leftMarginalia, Node rightMarginalia) {
        this.node = node;
        this.plainText = plainText;
        this.richText = richText;
        this.model = model;
        this.leftMarginalia = leftMarginalia;
        this.rightMarginalia = rightMarginalia;
    }

    /**
     * A prose block: a view-only {@code RichTextArea} sized to its content.
     *
     * @param richText  the area (already configured and modelled)
     * @param model     the area's model (searchable)
     * @param plainText the block's plain-text projection
     * @return the block
     */
    public static DocumentBlock prose(RichTextArea richText, StyledTextModel model, String plainText) {
        return new DocumentBlock(richText, plainText, richText, model, null, null);
    }

    /**
     * A prose block whose body holds more than the area — references beneath it, an edit strip —
     * while search and selection still run against the area and its model.
     *
     * @param body      the block's node, containing the area
     * @param richText  the area (already configured and modelled)
     * @param model     the area's model (searchable)
     * @param plainText the block's plain-text projection
     * @return the block
     */
    public static DocumentBlock prose(Node body, RichTextArea richText, StyledTextModel model, String plainText) {
        return new DocumentBlock(body, plainText, richText, model, null, null);
    }

    /**
     * A non-prose block: a turn header or a native node (tree, table).
     *
     * @param node      the block's node
     * @param plainText the block's plain-text projection (searched by contains-scan)
     * @return the block
     */
    public static DocumentBlock of(Node node, String plainText) {
        return new DocumentBlock(node, plainText, null, null, null, null);
    }

    /**
     * This block with marginalia beside it: the nodes the surface lays in the left and right
     * margin of the block's row.
     *
     * @param left  the left-margin node, or {@code null}
     * @param right the right-margin node, or {@code null}
     * @return a block with the marginalia
     */
    public DocumentBlock withMarginalia(Node left, Node right) {
        return new DocumentBlock(node, plainText, richText, model, left, right);
    }

    /**
     * The block's body: the stack child this block contributes when the surface has no margin
     * regions, or the body column of its row when it has.
     *
     * @return the block's node
     */
    public Node node() {
        return node;
    }

    /** @return the left-margin node, or {@code null} */
    public Node leftMarginalia() {
        return leftMarginalia;
    }

    /** @return the right-margin node, or {@code null} */
    public Node rightMarginalia() {
        return rightMarginalia;
    }

    /**
     * The node the surface stacked for this block: the row holding the body and its marginalia
     * when the margin regions are on, else the body itself. This is the node whose bounds place
     * the block on the surface.
     *
     * @return the stacked node; the body before the surface has stacked the block
     */
    public Node stackNode() {
        return row == null ? node : row;
    }

    void setRow(Node row) {
        this.row = row;
    }

    /**
     * The block's plain-text projection, for search and copy parity.
     *
     * @return the plain text; never {@code null}
     */
    public String plainText() {
        return plainText == null ? "" : plainText;
    }

    /**
     * The prose block's text area, when this is a prose block.
     *
     * @return the area, or {@code null} for header/node blocks
     */
    public RichTextArea richText() {
        return richText;
    }

    /**
     * The prose block's searchable model, when this is a prose block.
     *
     * @return the model, or {@code null} for header/node blocks
     */
    public StyledTextModel model() {
        return model;
    }
}
