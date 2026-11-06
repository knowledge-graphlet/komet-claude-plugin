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

import dev.ikm.komet.framework.controls.PeekPopover;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.stage.PopupWindow;

/**
 * The popover a tornado shows a {@link MeasureControl} in. A click on a value's name opens it, and
 * it stays until the value is used, Esc is pressed, or a click lands outside it: no pointer
 * movement closes it, so a person can work in it for as long as they need. One chart shows one at a
 * time. A click on another name both closes this one and opens that one, and a click on the name
 * that opened it closes it.
 */
final class MeasurePeek {

    /** How long after a click closed the popover the same click can still arrive at the name. */
    private static final long SAME_CLICK_NANOS = 400_000_000L;
    /** A popover's arrow and corner stand this far above the point it opens at, in pixels. */
    private static final double BELOW_ROW = 26;

    private PeekPopover open;
    private Object row;
    private Object closedRow;
    private long closedAt;

    /**
     * Whether a click on a row's name should open that row's popover. It should not when that same
     * click has just closed the row's popover, or the name could never close what it opened.
     */
    boolean opens(Object clicked) {
        if (clicked == row) {
            return false;
        }
        boolean sameClick = clicked == closedRow && System.nanoTime() - closedAt < SAME_CLICK_NANOS;
        closedRow = null;
        return !sameClick;
    }

    /**
     * Shows a control beneath a row, so the row's own bar stays in view while its value moves.
     *
     * @param clicked  the row, by identity
     * @param control  the control to show
     * @param anchor   the row's name
     * @param width    the popover's width
     * @param onClosed runs when the popover closes by Esc or a click outside it
     */
    void show(Object clicked, MeasureControl control, Node anchor, double width, Runnable onClosed) {
        close();
        Point2D at = anchor.localToScreen(0, anchor.getBoundsInLocal().getHeight());
        if (at == null) {
            return;
        }
        Object[] mine = new Object[1];
        PeekPopover peek = PeekPopover.show(control, width, "Try a value", anchor, at.getX(), at.getY() + BELOW_ROW, null,
                () -> {
                    // a popover fades out, so its end can arrive after the next one has opened
                    if (mine[0] == open) {
                        forget();
                        onClosed.run();
                    }
                });
        mine[0] = peek;
        open = peek;
        row = clicked;
        // a click outside closes the popover and still reaches what it landed on, so one click moves between names
        if (peek.content().getScene() != null && peek.content().getScene().getWindow() instanceof PopupWindow window) {
            window.setConsumeAutoHidingEvents(false);
        }
        control.shown.requestFocus();
    }

    /** Closes the popover without running its {@code onClosed}: the chart is already closing it. */
    void close() {
        PeekPopover closing = open;
        if (closing != null) {
            forget();
            closing.hide();
        }
    }

    private void forget() {
        closed(row);
        open = null;
        row = null;
    }

    /** Remembers which row's popover has just closed, for the click that closed it. */
    void closed(Object closing) {
        closedRow = closing;
        closedAt = System.nanoTime();
    }
}
