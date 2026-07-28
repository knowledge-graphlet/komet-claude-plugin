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

import dev.ikm.komet.markdown.richtext.RichTextSearch;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import jfx.incubator.scene.control.richtext.RichTextArea;
import jfx.incubator.scene.control.richtext.SelectionSegment;
import jfx.incubator.scene.control.richtext.TextPos;
import network.ike.komet.claude.ui.MarkdownRichText;
import network.ike.komet.claude.ui.RichTextViewpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The v1 Document surface ({@code IKE-Network/ike-issues#808}): a single-column, read-only
 * <em>block stack</em> — the spatial half of the rich interaction surface
 * ({@code dev-interaction-surface-family}). Turns append as blocks: prose runs are per-block
 * view-only {@code RichTextArea}s sized to their content; structured blocks (koncept-trees,
 * tables) are direct children of the stack rather than nodes smuggled into a text model. The
 * surface owns the document's <b>one vertical scroll axis</b> — no block scrolls internally —
 * which is the scroll discipline that retires the single-transcript-area virtualization fight.
 *
 * <p>Deliberately host-independent: it consumes only {@link MarkdownRichText.Entry} turns through
 * a {@link BlockFactory}, so the Scriptorium window ({@code #814}/{@code #815}) hosts it
 * unchanged. Non-virtualized by design for conversation-length documents (the {@code DynamicCard}
 * stack discipline); appends are incremental, and heavy nodes build lazily via their suppliers.
 *
 * <p><b>Accepted trade-off</b>: text selection is per-block — a drag cannot cross block
 * boundaries the way it crossed paragraphs in the single-area transcript. Whole-conversation
 * export lives in the host's Save action; a table carries its own copy affordance (the {@code
 * #596} interchange context menu) and a koncept-tree's chips drag. A surface-level
 * copy-turn/copy-document affordance is future work on the projection-flip slice.
 *
 * <p>All methods must be called on the JavaFX application thread.
 */
public final class DocumentSurface extends ScrollPane {

    /** Vertical gap between stacked blocks, px. */
    private static final double BLOCK_SPACING = 6;
    /** Extra gap above a turn header, px (separates turns more strongly than blocks). */
    private static final double TURN_SPACING = 10;

    private final VBox stack = new VBox(BLOCK_SPACING);
    private final List<MarkdownRichText.Entry> turns = new ArrayList<>();
    /** Per-turn: index (into {@link #blocks}) of the turn's first block (its header). */
    private final List<Integer> turnStarts = new ArrayList<>();
    private final List<DocumentBlock> blocks = new ArrayList<>();

    private BlockFactory blockFactory;
    /** The prose area holding the current find selection, for {@link #clearSelection()}. */
    private RichTextArea selectedArea;

    /**
     * Creates an empty surface.
     *
     * @param blockFactory builds each turn's blocks (view + font wiring)
     */
    public DocumentSurface(BlockFactory blockFactory) {
        this.blockFactory = blockFactory;
        stack.getStyleClass().add("claude-doc-stack");
        stack.setPadding(new Insets(8, 10, 8, 10));
        stack.setFillWidth(true);
        setContent(stack);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("claude-doc-surface");
    }

    /**
     * Re-wires block production (a new view coordinate or font size). Takes effect on the next
     * {@link #setTurns} or {@link #appendTurn} — callers refreshing an existing document follow
     * with {@code setTurns(...)}, which rebuilds every block through the new factory.
     *
     * @param blockFactory the new factory
     */
    public void setBlockFactory(BlockFactory blockFactory) {
        this.blockFactory = blockFactory;
    }

    /**
     * Replaces the document with the given turns, in order.
     *
     * @param entries the conversation's turns
     */
    public void setTurns(List<MarkdownRichText.Entry> entries) {
        turns.clear();
        turns.addAll(entries);
        rebuild();
    }

    /**
     * Appends one turn's blocks to the stack.
     *
     * @param turn the turn to append
     */
    public void appendTurn(MarkdownRichText.Entry turn) {
        turns.add(turn);
        appendBlocksFor(turn);
    }

    /**
     * Pops turns from the end until {@code turnCount} remain — the failed-send rollback.
     *
     * @param turnCount the number of turns to keep
     */
    public void truncateTurns(int turnCount) {
        if (turnCount < 0 || turnCount >= turns.size()) {
            return;
        }
        clearSelection();
        int firstRemovedBlock = turnStarts.get(turnCount);
        stack.getChildren().remove(firstRemovedBlock, stack.getChildren().size());
        blocks.subList(firstRemovedBlock, blocks.size()).clear();
        turnStarts.subList(turnCount, turnStarts.size()).clear();
        turns.subList(turnCount, turns.size()).clear();
    }

    /**
     * The number of turns on the surface.
     *
     * @return the turn count
     */
    public int turnCount() {
        return turns.size();
    }

    /**
     * The surface's turns, in order — the source a paged print rendering rebuilds from (it renders
     * its own print column at page width, rather than reparenting these live blocks).
     *
     * @return an unmodifiable snapshot of the turns
     */
    public List<MarkdownRichText.Entry> turns() {
        return List.copyOf(turns);
    }

    /**
     * Scrolls so the turn's header block sits at the top of the viewport. Deferred two pulses so
     * freshly appended content-height blocks have settled their heights before the offset is
     * computed (the successor of the old transcript's paragraph-anchor scroll).
     *
     * @param turnIndex the turn to reveal
     */
    public void scrollToTurn(int turnIndex) {
        if (turnIndex < 0 || turnIndex >= turnStarts.size()) {
            return;
        }
        int blockIndex = turnStarts.get(turnIndex);
        // Two chained pulses: the first lets the new blocks join layout, the second reads settled
        // bounds (useContentHeight areas report their height one pulse after their model lands).
        Platform.runLater(() -> Platform.runLater(() -> scrollToBlock(blockIndex)));
    }

    // ── Viewpoint (ike-issues#943) ───────────────────────────────────────

    /**
     * A text selection within one block's prose area, in the block-stack's stable coordinates.
     *
     * @param blockIndex   the block holding the selection
     * @param anchorIndex  the anchor's paragraph index within the block's model
     * @param anchorOffset the anchor's character offset within its paragraph
     * @param caretIndex   the caret's paragraph index within the block's model
     * @param caretOffset  the caret's character offset within its paragraph
     */
    public record TextSelection(int blockIndex, int anchorIndex, int anchorOffset,
                                int caretIndex, int caretOffset) {
    }

    /**
     * The surface's <em>equivalent place</em> (IKE-Network/ike-issues#943): the viewport top sits
     * {@code fraction} of the way down block {@code blockIndex}, plus the text selection if there
     * is one. Block index is the stable coordinate across a re-render that rebuilds the same
     * turns (a coordinate change, #942, or a font-size change) and across a reopen of the same
     * conversation; the fraction degrades gracefully when block heights shift. One value serves
     * both journeys — held across a rebuild in memory, or {@linkplain #encode() encoded} into
     * preferences when leaving and {@linkplain #decode(String) decoded} on return.
     *
     * @param blockIndex the block at the viewport top
     * @param fraction   how far down that block the viewport top sits (0 = its top edge)
     * @param selection  the text selection, or {@code null} when there is none
     */
    public record Viewpoint(int blockIndex, double fraction, TextSelection selection) {

        /**
         * Encodes this viewpoint as a compact string for a preferences value.
         *
         * @return the encoded form
         */
        public String encode() {
            StringBuilder sb = new StringBuilder()
                    .append(blockIndex).append(':').append(fraction);
            if (selection != null) {
                sb.append(':').append(selection.blockIndex())
                        .append(':').append(selection.anchorIndex())
                        .append(':').append(selection.anchorOffset())
                        .append(':').append(selection.caretIndex())
                        .append(':').append(selection.caretOffset());
            }
            return sb.toString();
        }

        /**
         * Decodes a viewpoint previously written by {@link #encode()}.
         *
         * @param encoded the encoded form; blank or malformed input yields {@code null}
         * @return the viewpoint, or {@code null}
         */
        public static Viewpoint decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String[] parts = encoded.split(":");
                int block = Integer.parseInt(parts[0]);
                double fraction = Double.parseDouble(parts[1]);
                TextSelection selection = (parts.length >= 7)
                        ? new TextSelection(Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                                Integer.parseInt(parts[4]), Integer.parseInt(parts[5]),
                                Integer.parseInt(parts[6]))
                        : null;
                return new Viewpoint(block, fraction, selection);
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    /**
     * Captures the surface's current viewpoint.
     *
     * @return the viewpoint, or {@code null} for an empty surface
     */
    public Viewpoint viewpoint() {
        if (blocks.isEmpty()) {
            return null;
        }
        double contentHeight = stack.getBoundsInLocal().getHeight();
        double range = Math.max(0, contentHeight - getViewportBounds().getHeight());
        double y = getVvalue() * range;
        int blockIndex = 0;
        double fraction = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Bounds bounds = blocks.get(i).node().getBoundsInParent();
            if (bounds.getMinY() > y) {
                break;
            }
            blockIndex = i;
            fraction = bounds.getHeight() <= 0 ? 0
                    : Math.clamp((y - bounds.getMinY()) / bounds.getHeight(), 0, 1);
        }
        return new Viewpoint(blockIndex, fraction, currentTextSelection());
    }

    /** Pulse cap for {@link #restoreViewpoint}'s settle wait (~1 s at 60 fps). */
    private static final int MAX_SETTLE_PULSES = 60;

    /**
     * Restores a viewpoint after a rebuild (or on reopen). Waits for the stack's layout to
     * settle before computing offsets: a fixed two-pulse defer is enough after an in-place
     * rebuild, but on a fresh reopen the content-height prose areas settle their heights over
     * several pulses (fonts, first layout of a whole conversation) — scrolling before that
     * computes offsets against a half-built stack, which is exactly the "selection restored but
     * not the viewport" failure (#943). The block index clamps to the current stack and the
     * selection clamps to its block's document, so a viewpoint captured against a longer
     * document degrades to the nearest existing place.
     *
     * @param viewpoint the viewpoint to restore; {@code null} is a no-op
     */
    public void restoreViewpoint(Viewpoint viewpoint) {
        restoreViewpoint(viewpoint, null);
    }

    /**
     * As {@link #restoreViewpoint(Viewpoint)}, additionally running {@code afterApplied} once the
     * viewpoint has actually been applied. Restoration is asynchronous (it waits for layout to
     * settle), so a host that must not treat the surface's current position as the reader's
     * place until the return has happened — e.g. to keep captures from overwriting a
     * not-yet-applied restore (#943) — keys that off this callback.
     *
     * @param viewpoint    the viewpoint to restore; {@code null} is a no-op
     * @param afterApplied run on the FX thread after the viewpoint is applied; may be {@code null}
     */
    public void restoreViewpoint(Viewpoint viewpoint, Runnable afterApplied) {
        if (viewpoint == null) {
            return;
        }
        awaitSettledLayout(0, -1, () -> {
            if (blocks.isEmpty()) {
                return;
            }
            scrollToBlockOffset(Math.min(viewpoint.blockIndex(), blocks.size() - 1),
                    viewpoint.fraction());
            TextSelection selection = viewpoint.selection();
            if (selection != null && selection.blockIndex() >= 0
                    && selection.blockIndex() < blocks.size()) {
                RichTextArea area = blocks.get(selection.blockIndex()).richText();
                if (area != null && area.getParagraphCount() > 0) {
                    // Blocks are content-height (no internal scroll), so selecting here cannot
                    // fight the surface scroll restored above.
                    area.select(
                            RichTextViewpoint.clamp(area, selection.anchorIndex(),
                                    selection.anchorOffset()),
                            RichTextViewpoint.clamp(area, selection.caretIndex(),
                                    selection.caretOffset()));
                    selectedArea = area;
                }
            }
            if (afterApplied != null) {
                afterApplied.run();
            }
        });
    }

    /**
     * Runs {@code action} once the stack's content height is nonzero and unchanged across
     * consecutive pulses and the viewport has bounds — the earliest moment scroll offsets are
     * meaningful. Each poll forces a layout pass first: content-height prose areas report their
     * height only after real layout, and bounds can sit stale-but-equal across pulses before the
     * first pass — "stable" must mean genuinely settled, not not-yet-started (#943). After the
     * action runs, one more pulse verifies the stack did not move (late font/wrap growth); if it
     * did, the offsets were computed against a half-built stack and the action re-runs (it is
     * idempotent). Capped at {@link #MAX_SETTLE_PULSES} so a pathological layout cannot stall
     * the restore forever (the action then runs against the best available geometry).
     */
    private void awaitSettledLayout(int attempt, double lastHeight, Runnable action) {
        Platform.runLater(() -> {
            stack.applyCss();
            stack.layout();
            double height = stack.getBoundsInLocal().getHeight();
            boolean settled = attempt >= 2 && height > 0 && height == lastHeight
                    && getViewportBounds().getHeight() > 0;
            if (settled || attempt >= MAX_SETTLE_PULSES) {
                action.run();
                if (attempt < MAX_SETTLE_PULSES) {
                    final double appliedHeight = height;
                    Platform.runLater(() -> {
                        stack.applyCss();
                        stack.layout();
                        double verifyHeight = stack.getBoundsInLocal().getHeight();
                        if (verifyHeight != appliedHeight) {
                            awaitSettledLayout(attempt + 1, verifyHeight, action);
                        }
                    });
                }
            } else {
                awaitSettledLayout(attempt + 1, height, action);
            }
        });
    }

    /** The first block-level text selection on the surface, or {@code null} when there is none. */
    private TextSelection currentTextSelection() {
        for (int i = 0; i < blocks.size(); i++) {
            RichTextArea area = blocks.get(i).richText();
            if (area != null && area.hasNonEmptySelection()) {
                SelectionSegment segment = area.getSelection();
                return new TextSelection(i, segment.getAnchor().index(),
                        segment.getAnchor().offset(),
                        segment.getCaret().index(), segment.getCaret().offset());
            }
        }
        return null;
    }

    // ── Find ─────────────────────────────────────────────────────────────

    /**
     * One find match. Prose matches are selectable in their block's text area; header and node
     * blocks match on their plain-text projection and are revealed by scrolling only.
     *
     * @param blockIndex     the block holding the match
     * @param paragraphIndex the paragraph within a prose block's model, or {@code -1}
     * @param start          the match's start offset within the paragraph, or {@code -1}
     * @param end            the match's end offset within the paragraph, or {@code -1}
     * @param selectable     whether {@link #select} can place a text selection on this match
     */
    public record DocumentMatch(int blockIndex, int paragraphIndex, int start, int end,
                                boolean selectable) {
    }

    /**
     * Finds every case-insensitive occurrence of {@code query} across the document's blocks, in
     * document order.
     *
     * @param query the text to find; blank finds nothing
     * @return the matches, in document order
     */
    public List<DocumentMatch> findAll(String query) {
        List<DocumentMatch> matches = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return matches;
        }
        for (int i = 0; i < blocks.size(); i++) {
            DocumentBlock block = blocks.get(i);
            if (block.model() != null) {
                for (RichTextSearch.Match m : RichTextSearch.findAll(block.model(), query)) {
                    matches.add(new DocumentMatch(i, m.paragraphIndex(), m.start(), m.end(), true));
                }
            } else {
                String hay = block.plainText().toLowerCase(Locale.ROOT);
                String needle = query.toLowerCase(Locale.ROOT);
                int from = 0;
                int at;
                while ((at = hay.indexOf(needle, from)) >= 0) {
                    matches.add(new DocumentMatch(i, -1, -1, -1, false));
                    from = at + needle.length();
                }
            }
        }
        return matches;
    }

    /**
     * Reveals a match: scrolls its block into view and, for a prose match, selects the matched
     * range in the block's text area.
     *
     * @param match the match to reveal (from {@link #findAll})
     */
    public void select(DocumentMatch match) {
        if (match == null || match.blockIndex() < 0 || match.blockIndex() >= blocks.size()) {
            return;
        }
        clearSelection();
        DocumentBlock block = blocks.get(match.blockIndex());
        if (match.selectable() && block.richText() != null) {
            RichTextArea area = block.richText();
            area.select(TextPos.ofLeading(match.paragraphIndex(), match.start()),
                    TextPos.ofLeading(match.paragraphIndex(), match.end()));
            selectedArea = area;
            // Reveal the match itself, not merely the block's top edge: a match deep inside a
            // tall content-height block would otherwise select off-screen. The paragraph's
            // fraction of the model approximates its fraction of the block's height.
            int paragraphCount = block.model() == null ? 0 : block.model().size();
            double fraction = paragraphCount <= 1 ? 0
                    : (double) match.paragraphIndex() / (paragraphCount - 1);
            scrollToBlockOffset(match.blockIndex(), fraction);
        } else {
            scrollToBlock(match.blockIndex());
        }
    }

    /** Collapses the current find selection, if any. */
    public void clearSelection() {
        if (selectedArea != null) {
            selectedArea.select(TextPos.ZERO);
            selectedArea = null;
        }
    }

    // ── Internals ────────────────────────────────────────────────────────

    /** Rebuilds every block from {@link #turns} through the current factory. */
    private void rebuild() {
        clearSelection();
        stack.getChildren().clear();
        blocks.clear();
        turnStarts.clear();
        List<MarkdownRichText.Entry> snapshot = new ArrayList<>(turns);
        turns.clear();
        for (MarkdownRichText.Entry turn : snapshot) {
            appendTurn(turn);
        }
    }

    /** Builds and stacks one turn's blocks, recording the turn's start block. */
    private void appendBlocksFor(MarkdownRichText.Entry turn) {
        turnStarts.add(blocks.size());
        boolean first = true;
        for (DocumentBlock block : blockFactory.turnBlocks(turn)) {
            Node node = block.node();
            if (first && !blocks.isEmpty()) {
                // A little extra air above each turn header, so turns read as units.
                VBox.setMargin(node, new Insets(TURN_SPACING, 0, 0, 0));
            }
            blocks.add(block);
            stack.getChildren().add(node);
            first = false;
        }
    }

    /** Scrolls so the block sits at the viewport top (clamped to the scroll range). */
    private void scrollToBlock(int blockIndex) {
        scrollToBlockOffset(blockIndex, 0);
    }

    /**
     * Scrolls so the point {@code fraction} of the way down the block sits at the viewport top.
     *
     * @param blockIndex the block to reveal
     * @param fraction   0 = the block's top edge, 1 = its bottom edge
     */
    private void scrollToBlockOffset(int blockIndex, double fraction) {
        if (blockIndex < 0 || blockIndex >= blocks.size()) {
            return;
        }
        stack.applyCss();
        stack.layout();
        Node node = blocks.get(blockIndex).node();
        double y = node.getBoundsInParent().getMinY()
                + fraction * node.getBoundsInParent().getHeight();
        double contentHeight = stack.getBoundsInLocal().getHeight();
        double viewportHeight = getViewportBounds().getHeight();
        double range = contentHeight - viewportHeight;
        setVvalue(range <= 0 ? 0 : Math.clamp(y / range, 0, 1));
    }
}
