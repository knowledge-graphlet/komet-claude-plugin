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

import dev.ikm.komet.framework.controls.KonceptBadge;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import jfx.incubator.scene.control.richtext.RichTextArea;

/**
 * Owns the drag gesture for koncept chips embedded in a {@link RichTextArea}
 * (knowledge-graphlet/komet-claude-plugin#5).
 *
 * <p>The incubator {@code RichTextAreaBehavior} registers its mouse handling as event
 * <em>filters on its content pane</em> — the capture phase — and never consults
 * {@code isConsumed()}. Nothing a chip does in the bubbling phase can preempt that: the first
 * press over a chip moved the caret into the chip's paragraph, the virtualized flow rebuilt the
 * paragraph and <em>replaced the chip node mid-gesture</em>, so the {@code DRAG_DETECTED}
 * synthesized for the gesture landed on the detached original and the drag never started — while
 * the drag events visibly text-selected around the badge. Only a second gesture, with the caret
 * already in place and the chip stable, could drag.
 *
 * <p>The one place that runs before the content pane's filters is a capture filter on an
 * <em>ancestor</em> — the {@code RichTextArea} control itself. These filters claim the whole
 * gesture when the press lands inside a {@link KonceptBadge}: press, drag, and release are
 * consumed, so there is no caret move, no paragraph rebuild, and no selection. That is ALL that
 * is needed: the scene synthesizes {@code DRAG_DETECTED} at the press target regardless of the
 * consumption — consumption silences the RichTextArea, not the scene — and with the chip no
 * longer replaced mid-gesture, the badge's own handler receives it and starts the drag. Firing a
 * {@code DRAG_DETECTED} manually can never work instead: {@code Scene.startDragAndDrop} is legal
 * only inside the scene's own synthesized dispatch (its drag gesture exists only there), and
 * throws {@code IllegalStateException} from any hand-built event. Presses on text are untouched:
 * caret, selection, and every other RichTextArea behavior work exactly as before.
 */
public final class KonceptChipGestures {

    private KonceptChipGestures() {
    }

    /**
     * Installs chip-gesture ownership on {@code area}. Call once per chip-bearing
     * {@link RichTextArea}; areas without chips are unaffected (the press check finds no badge and
     * every event passes through untouched).
     *
     * @param area the rich text area whose embedded koncept chips should drag on a single gesture
     */
    public static void install(RichTextArea area) {
        // Per-area gesture state; a single pointer means a single active gesture.
        final class GestureState {
            KonceptBadge chip;
        }
        GestureState state = new GestureState();

        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            state.chip = (e.getButton() == MouseButton.PRIMARY)
                    ? chipAt(e.getTarget(), area)
                    : null;
            if (state.chip != null) {
                // Consumed BEFORE the content pane's filters: no caret move, no paragraph
                // rebuild, no chip replacement — the gesture target survives, and the scene's
                // own DRAG_DETECTED synthesis reaches it.
                e.consume();
            }
        });

        area.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (state.chip != null) {
                e.consume(); // no selection painting around the badge
            }
        });

        area.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (state.chip != null) {
                e.consume();
                state.chip = null;
            }
        });
    }

    /**
     * The {@link KonceptBadge} enclosing {@code target}, walking parents up to (exclusive)
     * {@code stopAt}; {@code null} when the press was not on a chip.
     *
     * @param target the event target of the press
     * @param stopAt the ancestor to stop at (the rich text area)
     * @return the enclosing badge, or {@code null}
     */
    static KonceptBadge chipAt(EventTarget target, Node stopAt) {
        Node node = (target instanceof Node n) ? n : null;
        while (node != null && node != stopAt) {
            if (node instanceof KonceptBadge badge) {
                return badge;
            }
            node = node.getParent();
        }
        return null;
    }
}
