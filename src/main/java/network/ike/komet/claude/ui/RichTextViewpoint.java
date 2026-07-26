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

import javafx.application.Platform;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.TextPos;

/**
 * A captured <em>viewpoint</em> on a {@link RichTextArea}: the selection (anchor and caret, when
 * one exists) and the text position at the top of the viewport. One principle drives it
 * (IKE-Network/ike-issues#943): <b>we always return to the equivalent place</b> — a re-render
 * captures before the model swap and restores after, and a window that persists its state stores
 * the same viewpoint in preferences and restores it on reopen. The reader is never thrown to the
 * top or arbitrarily to the end.
 *
 * <p>Paragraph index + character offset is the stable coordinate across a re-render that changes
 * text <em>within</em> paragraphs (a coordinate change renaming chips, #942, or a font-size
 * change); both are clamped to the new document on restore, so a shrunken document degrades to
 * the nearest existing position.
 *
 * <p>All methods must be called on the JavaFX application thread.
 *
 * @param anchorIndex  the selection anchor's paragraph index
 * @param anchorOffset the selection anchor's character offset within its paragraph
 * @param caretIndex   the caret's paragraph index
 * @param caretOffset  the caret's character offset within its paragraph
 * @param hasSelection whether a non-collapsed selection existed at capture
 * @param topIndex     the paragraph index at the top of the viewport
 * @param topOffset    the character offset at the top of the viewport
 */
public record RichTextViewpoint(int anchorIndex, int anchorOffset, int caretIndex, int caretOffset,
                                boolean hasSelection, int topIndex, int topOffset) {

    /**
     * Captures the area's current viewpoint.
     *
     * @param richText the area to capture; must be laid out (skin attached)
     * @return the viewpoint (never null; an empty area captures as position zero)
     */
    public static RichTextViewpoint capture(RichTextArea richText) {
        SelectionSegment selection = richText.getSelection();
        boolean hasSelection = selection != null && !selection.isCollapsed();
        TextPos top = richText.getTextPosition(1, 1);
        if (top == null) {
            top = TextPos.ZERO;
        }
        TextPos anchor = hasSelection ? selection.getAnchor() : top;
        TextPos caret = hasSelection ? selection.getCaret() : top;
        return new RichTextViewpoint(anchor.index(), anchor.offset(), caret.index(), caret.offset(),
                hasSelection, top.index(), top.offset());
    }

    /**
     * Restores this viewpoint after a model swap. Deferred two pulses so the new model has joined
     * layout and reported settled heights before positions are resolved. A selection is restored
     * literally (the caret scrolls into view with it); a scroll-only viewpoint is restored by
     * approaching the captured top position from the document end — a caret moving <em>up</em> to
     * a position above the viewport parks it at the top edge, re-creating the captured screen.
     *
     * @param richText the area to restore onto
     */
    public void restore(RichTextArea richText) {
        restore(richText, null);
    }

    /**
     * As {@link #restore(RichTextArea)}, additionally running {@code afterApplied} once the
     * viewpoint has actually been applied. Restoration is deferred, so a caller holding this
     * viewpoint as authoritative until the reader has been returned to it — keeping a re-render
     * arriving mid-restore from capturing the not-yet-restored area (#943) — keys off this
     * callback. Not run when the area is empty at restore time (nothing was applied, so the
     * held viewpoint stays authoritative).
     *
     * @param richText     the area to restore onto
     * @param afterApplied run on the FX thread after the viewpoint is applied; may be {@code null}
     */
    public void restore(RichTextArea richText, Runnable afterApplied) {
        Platform.runLater(() -> Platform.runLater(() -> {
            if (richText.getParagraphCount() <= 0) {
                return;
            }
            if (hasSelection) {
                richText.select(clamp(richText, anchorIndex, anchorOffset),
                        clamp(richText, caretIndex, caretOffset));
            } else {
                richText.moveDocumentEnd();
                richText.select(clamp(richText, topIndex, topOffset));
            }
            if (afterApplied != null) {
                afterApplied.run();
            }
        }));
    }

    /**
     * Clamps a captured (paragraph, offset) position to the area's current document, so a
     * viewpoint captured against a longer document degrades to the nearest existing position.
     *
     * @param richText the area whose document bounds apply
     * @param index    the captured paragraph index
     * @param offset   the captured character offset
     * @return a valid position in the current document
     */
    public static TextPos clamp(RichTextArea richText, int index, int offset) {
        int count = richText.getParagraphCount();
        if (count <= 0) {
            return TextPos.ZERO;
        }
        int paragraph = Math.clamp(index, 0, count - 1);
        TextPos end = richText.getParagraphEnd(paragraph);
        if (end != null && offset > end.offset()) {
            return end;
        }
        return TextPos.ofLeading(paragraph, Math.max(offset, 0));
    }
}
