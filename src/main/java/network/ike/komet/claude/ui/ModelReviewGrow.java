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

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.expand.KlExpandable;
import dev.ikm.komet.layout.expand.KlExpansionHost;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.Region;
import network.ike.komet.document.surface.MarkdownRichText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.IntFunction;

/**
 * The grow of a decision block ({@code dev-grow-model-review}): the model block, the tornado
 * block and the tree block of a decision each grow into the one review of the model they name,
 * a {@link ModelReviewPane}. A decision involves more than one space, which is KEC's rule for a
 * figure that grows (2026-11-12).
 *
 * <p>The review opens where the whole of it fits. The nearest host is tested, and when the
 * review does not fit there it opens in the last, the journal window, at once: the hosts between
 * are not rungs of this figure, so Reduce and Escape from the journal window return to the
 * document.
 *
 * <p>Closing with changes that are not saved asks whether to save them, as a document window
 * does: Save, Don't Save or Cancel. Where the document cannot be edited there is nothing to save
 * and nothing is asked.
 *
 * <p>Save in document does not close the review. A save makes the card draw the model's block
 * again, which removes this figure from the scene, so the expansion is tied to the nearest
 * host's pane and not to the figure. The block drawn in the figure's place adopts the review
 * through the document's {@link ProfileBlocks}, which a redraw of the changed blocks keeps. A
 * figure that is removed and not adopted was removed from outside: the whole document was drawn
 * again, and the review is closed without a question and without a write.
 */
final class ModelReviewGrow implements KlExpandable {

    private static final Logger LOG = LoggerFactory.getLogger(ModelReviewGrow.class);

    /** What a person answers when asked whether to save the changes on closing. */
    enum Answer { SAVE, DONT_SAVE, CANCEL }

    /** The room the overlay takes around the review: its frame on every side, and the bar across the top. */
    /** The room a grown view's frame takes on each side of the place it fills, in points. */
    static final double FRAME = 24;
    /** The height of a grown view's bar, in points. */
    static final double BAR = 44;

    private Region figure;
    private final ViewCalculator viewCalc;
    private final ViewProperties viewProperties;
    private final double base;
    private final ProfileBlocks document;
    private final String modelId;
    private final String treeInfo;
    /** The review while this figure is grown, made when the grow first needs it. */
    private ModelReviewPane pane;
    /** The nearest host's pane, which outlives a block that is drawn again beneath the review. */
    private Node anchor;
    /** Whether the figure has left the scene and no block drawn in its place has adopted the review yet. */
    private boolean orphaned;
    /** The latest drawing of a block of this model made while the review was open: what the review goes on with if its own is removed. */
    private Region candidate;
    /** The expansion's state when the review has carried it to an adopted drawing, to be cleared from that drawing on collapse. */
    private Object carried;
    /** Whether the review is closing: a second Close during the fade asks nothing again. */
    private boolean closing;
    private final ChangeListener<Scene> figureScene = (obs, was, now) -> {
        if (now == null) {
            figureRemoved();
        }
    };
    /** Asks whether to save a number of changes; a test answers in its place. */
    IntFunction<Answer> asker = this::ask;

    private ModelReviewGrow(Region figure, ViewCalculator viewCalc, ViewProperties viewProperties, double base,
                            ProfileBlocks document, String modelId, String treeInfo) {
        this.figure = figure;
        this.viewCalc = viewCalc;
        this.viewProperties = viewProperties;
        this.base = base;
        this.document = document;
        this.modelId = modelId;
        this.treeInfo = treeInfo;
    }

    /**
     * A decision block's drawing with the grow on it.
     *
     * @param figure   the block's drawing
     * @param document the document's context
     * @param modelId  the model the block is, or draws
     * @param treeInfo a tree block's info string, for the number it is counted out for and its price
     *                 of a healthy year; null when the block is not a tree
     * @return the node to place in the document: the drawing with its Expand button
     */
    static Node grown(Region figure, ViewCalculator viewCalc, ViewProperties viewProperties, double base,
                      ProfileBlocks document, String modelId, String treeInfo) {
        ModelReviewGrow grow = new ModelReviewGrow(figure, viewCalc, viewProperties, base, document, modelId, treeInfo);
        Region host = grow.withExpandAffordance();
        // the block has its own grow: the surface adds no second icon that would grow a copy of the block alone
        host.getProperties().put(MarkdownRichText.BLOCK_NO_EXPAND, Boolean.TRUE);
        host.getProperties().put(ModelReviewGrow.class, grow);
        // a review of this model left open when its block was drawn again goes on with this drawing
        document.openReview(modelId).ifPresent(open -> open.drawnAgain(figure));
        return host;
    }

    /** The grow of a decision block's node, as {@link #grown} returned it; for a test and the driver. */
    static ModelReviewGrow of(Node grown) {
        return grown.getProperties().get(ModelReviewGrow.class) instanceof ModelReviewGrow grow ? grow : null;
    }

    /** The review while a block of this model is grown: this block's, or the one open from another block of the model. */
    ModelReviewPane review() {
        ModelReviewGrow open = openElsewhere();
        return open != null ? open.pane : pane;
    }

    /**
     * The review of this model that is open from another drawing, when there is one: another of
     * the model's three blocks, or the drawing this one replaced when the card drew its block again.
     */
    private ModelReviewGrow openElsewhere() {
        return document.openReview(modelId).filter(open -> open != this && open.pane != null).orElse(null);
    }

    private ModelReviewPane pane() {
        if (pane == null) {
            pane = new ModelReviewPane(viewCalc, viewProperties, base, document, modelId, treeInfo);
            // the model was changed in the document by someone else: this review is of a model that is no more
            pane.onDocumentChanged(() -> endedFromOutside("its model was changed in the document"));
        }
        return pane;
    }

    @Override
    public Region figureNode() {
        return figure;
    }

    @Override
    public Region expandedView() {
        return pane();
    }

    /** A review is worth the whole screen. */
    @Override
    public dev.ikm.komet.layout.expand.ExpansionCorner expansionCorner() {
        return ExpandButton.CORNER;
    }

    @Override
    public dev.ikm.komet.layout.expand.ExpansionCorner contractionCorner() {
        return ExpandButton.CONTRACT_CORNER;
    }

    /** A decision block's Expand button is drawn always: a reader must see that the block grows. */
    @Override
    public boolean expandIconAlwaysShown() {
        return true;
    }

    @Override
    public javafx.geometry.Insets expandIconInsets() {
        return ExpandButton.INSETS;
    }

    @Override
    public boolean osFullScreenCapable() {
        return true;
    }

    /**
     * The nearest host when the whole review fits it, with every host above; the last host alone
     * when it does not (KEC, 2026-11-12: "Test the size, if it won't all fit, go to the journal
     * window at once").
     */
    @Override
    public List<KlExpansionHost.Rung> expansionRungs(List<KlExpansionHost.Rung> hosts) {
        if (hosts.isEmpty()) {
            return hosts;
        }
        Region nearest = hosts.getFirst().fillPane();
        anchor = nearest;
        if (hosts.size() == 1
                || pane().fitsIn(nearest.getWidth() - 2 * FRAME, nearest.getHeight() - 2 * FRAME - BAR, figure.getScene())) {
            return hosts;
        }
        return List.of(hosts.getLast());
    }

    @Override
    public Node expansionAnchor() {
        return anchor != null ? anchor : figure;
    }

    @Override
    public void expand() {
        // one review of a model at a time, whichever of its blocks it was grown from
        if (isExpanded() || openElsewhere() != null) {
            return;
        }
        closing = false;
        KlExpandable.super.expand();
        if (isExpanded()) {
            orphaned = false;
            document.reviewOpened(modelId, this);
            figure.sceneProperty().addListener(figureScene);
        } else {
            pane = null;
        }
    }

    /**
     * Closes the review. With changes that are not saved, and a document that can be edited, a
     * person is asked first: Save saves them and closes, Don't Save closes, Cancel stays.
     */
    @Override
    public void collapse() {
        ModelReviewGrow open = openElsewhere();
        if (open != null) {
            open.collapse();                                     // the review belongs to the drawing it was grown from
            return;
        }
        if (!isExpanded() || closing) {
            return;
        }
        if (pane != null && pane.changed() > 0 && pane.writable()) {
            Answer answer = asker.apply(pane.changed());
            if (answer == Answer.CANCEL) {
                return;
            }
            if (answer == Answer.SAVE && !pane.save()) {
                return;                                          // not saved: the review stays, with what it has
            }
        }
        closing = true;
        KlExpandable.super.collapse();
    }

    @Override
    public void onCollapsed() {
        figure.sceneProperty().removeListener(figureScene);
        // an adopted drawing has the expansion's state, which the overlay looked for on the first drawing
        if (carried != null && figure.getProperties().get(PropertyKeys.EXPANSION) == carried) {
            figure.getProperties().remove(PropertyKeys.EXPANSION);
        }
        document.reviewClosed(modelId, this);
        pane = null;
        anchor = null;
        candidate = null;
        carried = null;
        orphaned = false;
        closing = false;
    }

    /**
     * A block of this model was drawn again while the review is open. The review goes on with
     * that drawing when its own is gone, whichever of the two the card does first.
     */
    private void drawnAgain(Region drawing) {
        if (!isExpanded() || drawing == figure) {
            return;
        }
        candidate = drawing;
        if (orphaned) {
            adopt(drawing);
        }
    }

    /** The review goes on with another drawing, and ends with it as it would have with the first. */
    private void adopt(Region drawing) {
        figure.sceneProperty().removeListener(figureScene);
        Object expansion = figure.getProperties().remove(PropertyKeys.EXPANSION);
        figure = drawing;
        if (expansion != null) {
            figure.getProperties().put(PropertyKeys.EXPANSION, expansion);
            carried = expansion;
        }
        figure.sceneProperty().addListener(figureScene);
        candidate = null;
        orphaned = false;
    }

    /** The figure left the scene: its block is being drawn again, or the whole document is. */
    private void figureRemoved() {
        if (!isExpanded()) {
            return;
        }
        orphaned = true;
        if (candidate != null && candidate != figure && candidate.getScene() != null) {
            adopt(candidate);                                    // the card drew the block again before it removed this drawing
            return;
        }
        // a redraw of the changed blocks draws this block again in the same pass: its new drawing is in the scene by
        // the time the pass is over, and the review goes on with it. A document drawn whole has no such drawing.
        Platform.runLater(() -> Platform.runLater(() -> {
            if (!orphaned || !isExpanded()) {
                return;
            }
            if (candidate != null && candidate != figure && candidate.getScene() != null) {
                adopt(candidate);
            } else {
                endedFromOutside("its document was read again");
            }
        }));
    }

    /** The review is over for a reason that is not the person's: nothing is asked, nothing is written. */
    private void endedFromOutside(String why) {
        if (!isExpanded()) {
            return;
        }
        int unsaved = pane == null ? 0 : pane.changed();
        LOG.info("The review of {} closed because {}; {} change(s) were not saved", modelId, why, unsaved);
        closing = true;
        KlExpandable.super.collapse();
    }

    /** Asks whether to save the changes, as a document window asks when it is closed with unsaved changes. */
    private Answer ask(int changes) {
        ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
        ButtonType dontSave = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert question = new Alert(Alert.AlertType.CONFIRMATION,
                "If you don't save, the document stays as it is.", save, dontSave, cancel);
        // wide enough for the sentence: a dialog sized by its three buttons alone cuts it short
        question.getDialogPane().setMinWidth(base * 36);
        question.setTitle("Save Changes");
        question.setHeaderText(changes == 1 ? "Save the change in the document?" : "Save the " + changes + " changes in the document?");
        question.getDialogPane().setId("modelReviewSaveDialog");
        if (pane != null && pane.getScene() != null) {
            question.initOwner(pane.getScene().getWindow());
        }
        ButtonType answered = question.showAndWait().orElse(cancel);
        return answered == save ? Answer.SAVE : answered == dontSave ? Answer.DONT_SAVE : Answer.CANCEL;
    }
}
