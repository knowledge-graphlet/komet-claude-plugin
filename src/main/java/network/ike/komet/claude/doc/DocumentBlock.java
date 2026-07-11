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
 */
public final class DocumentBlock {

    private final Node node;
    private final String plainText;
    private final RichTextArea richText;
    private final StyledTextModel model;

    private DocumentBlock(Node node, String plainText, RichTextArea richText, StyledTextModel model) {
        this.node = node;
        this.plainText = plainText;
        this.richText = richText;
        this.model = model;
    }

    /**
     * A prose block: a view-only {@code RichTextArea} sized to its content.
     *
     * @param richText  the area (already configured and modelled)
     * @param model     the area's model (searchable)
     * @param plainText the block's plain-text projection
     * @return the block
     */
    static DocumentBlock prose(RichTextArea richText, StyledTextModel model, String plainText) {
        return new DocumentBlock(richText, plainText, richText, model);
    }

    /**
     * A non-prose block: a turn header or a native node (tree, table).
     *
     * @param node      the block's node
     * @param plainText the block's plain-text projection (searched by contains-scan)
     * @return the block
     */
    static DocumentBlock of(Node node, String plainText) {
        return new DocumentBlock(node, plainText, null, null);
    }

    /**
     * The stack child this block contributes.
     *
     * @return the block's node
     */
    public Node node() {
        return node;
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
