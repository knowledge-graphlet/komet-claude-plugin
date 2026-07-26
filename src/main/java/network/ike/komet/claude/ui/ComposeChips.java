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
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;

/**
 * The compose surface's chip factory (#789): the same {@link KonceptChips} pill the transcript
 * renders — LifeHash identicon, store-resolved name, hover identity, and the concept drag
 * <em>source</em> already installed, so a chip in the input drags back out into any Komet viewer.
 * A thin public bridge, because {@code KonceptChips} stays package-private pending the transcript's
 * convergence on {@code KonceptBadge} ({@code IKE-Network/ike-issues#736}).
 */
public final class ComposeChips {

    private ComposeChips() {
    }

    /**
     * Builds the live chip node for one composed concept. Invoked by the view per render; must be
     * used on the JavaFX application thread.
     *
     * @param pid      the concept's id (resolved and existing)
     * @param viewCalc the view for resolving the chip's name; may be null (icon-only fallback)
     * @param labelPx  the target label size (px) — the compose input's own text size, so a chip and
     *                 the prose around it read at one size
     * @return the chip node
     */
    public static Node chip(PublicId pid, ViewCalculator viewCalc, double labelPx) {
        // KonceptChips renders the small-caps label at base * 0.9 (chips sit a touch under body in
        // the transcript); the compose surface instead wants the label to MATCH its input text, so
        // pass the base that makes 0.9 * base == labelPx.
        return KonceptChips.chip(pid, null, viewCalc, labelPx / 0.9);
    }

    /**
     * Interactive-surface form: the composed chip computes its logical-status cluster and
     * carries the definition popout (ike-issues#941).
     *
     * @param pid            the concept's id (resolved and existing)
     * @param viewProperties the compose surface's view; may be null (icon-only fallback)
     * @param labelPx        the target label size (px), matching the compose input's text
     * @return the chip node
     */
    public static Node chip(PublicId pid, ViewProperties viewProperties, double labelPx) {
        return KonceptChips.chip(pid, null, viewProperties, labelPx / 0.9);
    }

    /** Token-field selection fill (macOS convention): accent-filled pill, white label. */
    private static final String SELECTED_STYLE =
            "-fx-background-color: #3B74C7; -fx-background-radius: 9;"
                    + " -fx-border-color: #2F5FA6; -fx-border-radius: 9; -fx-padding: 0 6 0 6;";
    private static final Object SAVED_STYLE_KEY = new Object();
    private static final Object SAVED_FILL_KEY = new Object();

    /**
     * Paints a chip's selected state — the token-field convention: a selected chip fills with the
     * accent color and its label goes white, since the control's own selection highlight paints
     * behind the opaque pill and would otherwise be invisible over it. Deselecting restores the
     * chip's own styling exactly. Safe on any chip shape (an icon-only fallback simply keeps its
     * look plus the fill on its container when it has one).
     *
     * @param chip     the node {@link #chip} produced (the model hands back the live instance)
     * @param selected whether the chip lies within the selection
     */
    public static void setSelected(Node chip, boolean selected) {
        if (!(chip instanceof HBox pill)) {
            return; // icon-only fallback: nothing safely restylable
        }
        if (selected) {
            pill.getProperties().putIfAbsent(SAVED_STYLE_KEY, pill.getStyle());
            pill.setStyle(SELECTED_STYLE);
            for (Node child : pill.getChildren()) {
                if (child instanceof Text label) {
                    label.getProperties().putIfAbsent(SAVED_FILL_KEY, label.getFill());
                    label.setFill(Color.WHITE);
                }
            }
        } else {
            Object saved = pill.getProperties().remove(SAVED_STYLE_KEY);
            if (saved instanceof String style) {
                pill.setStyle(style);
            }
            for (Node child : pill.getChildren()) {
                if (child instanceof Text label
                        && label.getProperties().remove(SAVED_FILL_KEY) instanceof Paint fill) {
                    label.setFill(fill);
                }
            }
        }
    }
}
