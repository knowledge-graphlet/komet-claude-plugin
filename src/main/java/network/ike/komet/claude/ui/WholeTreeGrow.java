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
import dev.ikm.komet.layout.expand.KlExpandable;
import dev.ikm.komet.layout.expand.KlExpansionHost;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import network.ike.komet.document.surface.MarkdownRichText;

import java.util.List;
import java.util.function.Supplier;

/**
 * The grow of a tree that draws no model: the tree whole, at full size and left to right, in the
 * nearest larger place, moved by dragging and enlarged with the shortcut key and the wheel. A tree
 * that draws a model grows into the model's review instead ({@link ModelReviewGrow}).
 *
 * <p>The block shows its Expand button while its drawing is reduced: scaled down to fit, or an
 * outline standing in for the drawing left to right. A tree that is drawn whole where it stands
 * has nothing to grow into ({@code dev-grow-model-review}, "Which figures grow").
 */
final class WholeTreeGrow implements KlExpandable {

    /** The room around the tree in its grown view, in points. */
    private static final double PAD = 8;

    private final VBox column;

    private WholeTreeGrow(VBox column) {
        this.column = column;
    }

    /**
     * A tree's drawing with its grow on it.
     *
     * @param column  the tree block's column, which keeps what draws the tree whole under
     *                {@link DecisionTreeBlockRenderer#WHOLE}
     * @param reduced whether the drawing is reduced where it stands
     * @return the node to place in the document
     */
    static Node grown(VBox column, ObservableBooleanValue reduced) {
        Region host = new WholeTreeGrow(column).withExpandAffordance();
        // the block has its own grow: the surface adds no second icon
        host.getProperties().put(MarkdownRichText.BLOCK_NO_EXPAND, Boolean.TRUE);
        Node button = ExpandButton.of(host);
        if (button != null) {
            button.visibleProperty().bind(reduced);
        }
        return host;
    }

    @Override
    public Region figureNode() {
        return column;
    }

    /** The tree drawn whole, left to right at full size, or null before the block has drawn it. */
    private Region whole() {
        return column.getProperties().get(DecisionTreeBlockRenderer.WHOLE) instanceof Supplier<?> whole
                && whole.get() instanceof Region full ? full : null;
    }

    /**
     * The nearest host when the whole tree fits it, with every host above; the last host alone
     * when it does not, as a model's review opens (KEC, 2026-11-12: "Test the size, if it won't
     * all fit, go to the journal window at once"). A tree is reduced because its document is too
     * narrow for it, so the document's area seldom has the room.
     */
    @Override
    public List<KlExpansionHost.Rung> expansionRungs(List<KlExpansionHost.Rung> hosts) {
        if (hosts.size() <= 1) {
            return hosts;
        }
        Region nearest = hosts.getFirst().fillPane();
        javafx.geometry.Dimension2D full = DecisionTreeBlockRenderer.wholeSize(column, column.getScene());
        boolean fits = full != null
                && full.getWidth() + 2 * PAD <= nearest.getWidth() - 2 * ModelReviewGrow.FRAME
                && full.getHeight() + 2 * PAD <= nearest.getHeight() - 2 * ModelReviewGrow.FRAME - ModelReviewGrow.BAR;
        return fits ? hosts : List.of(hosts.getLast());
    }

    @Override
    public Region expandedView() {
        Region full = whole();
        if (full == null) {
            return new Label("The tree has not been drawn yet.");
        }
        Scale zoom = new Scale(1, 1, 0, 0);
        full.getTransforms().add(zoom);
        ScrollPane pane = new ScrollPane(new Group(full));
        pane.setId("decision-tree-whole");
        pane.setPannable(true);
        pane.setPadding(new Insets(PAD));
        pane.setStyle("-fx-background-color: white;");
        pane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isShortcutDown()) {
                double factor = e.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
                double next = Math.max(0.25, Math.min(4, zoom.getX() * factor));
                zoom.setX(next);
                zoom.setY(next);
                e.consume();
            }
        });
        return pane;
    }

    @Override
    public ExpansionCorner expansionCorner() {
        return ExpandButton.CORNER;
    }

    @Override
    public ExpansionCorner contractionCorner() {
        return ExpandButton.CONTRACT_CORNER;
    }

    @Override
    public boolean expandIconAlwaysShown() {
        return true;
    }

    @Override
    public Insets expandIconInsets() {
        return ExpandButton.INSETS;
    }

    @Override
    public boolean osFullScreenCapable() {
        return true;
    }
}
