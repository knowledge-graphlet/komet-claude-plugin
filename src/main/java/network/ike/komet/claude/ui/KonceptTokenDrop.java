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

import dev.ikm.komet.framework.dnd.KometClipboard;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.control.TextArea;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import network.ike.komet.claude.koncept.ComponentText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Concept drop-in for a <em>raw Markdown editor</em> (a plain {@code TextArea}): a Koncept
 * dragged from anywhere in Komet — navigator, search, a transcript chip — drops as its
 * id-bearing {@code k:} interchange token at the caret, so instruction authoring binds to
 * identified components the same way the assistant's compose surface does
 * ({@code IKE-Network/ike-issues#1042}: knowledge-referencing instructions). The rendered view
 * of the same editor then shows the token as a live badge.
 */
public final class KonceptTokenDrop {

    private static final Logger LOG = LoggerFactory.getLogger(KonceptTokenDrop.class);

    /** Concept + pattern drag formats accepted, mirroring the assistant compose surface. */
    private static final Set<DataFormat> DROPPABLE_FORMATS = Stream.concat(
                    Stream.of(KometClipboard.KOMET_CONCEPT_LIST),
                    Stream.concat(KometClipboard.CONCEPT_TYPES.stream(),
                            KometClipboard.PATTERN_TYPES.stream()))
            .collect(Collectors.toUnmodifiableSet());

    private KonceptTokenDrop() {
    }

    /**
     * Installs the drop handlers: droppable komet content inserts {@code k:uuid=<id>[Name]}
     * tokens at the caret, one per dropped component, space-joined. A component the store has
     * no public id for inserts nothing.
     *
     * @param area     the raw editor
     * @param viewCalc supplies the view for name resolution; a {@code null} supplier result
     *                 yields tokens without a label
     */
    public static void install(TextArea area, Supplier<ViewCalculator> viewCalc) {
        area.addEventFilter(DragEvent.DRAG_OVER, e -> {
            if (accepts(e.getDragboard())) {
                e.acceptTransferModes(TransferMode.COPY);
                e.consume();
            }
        });
        area.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            if (!accepts(e.getDragboard())) {
                return;
            }
            long[] nids = KometClipboard.conceptNidsFrom(e.getDragboard());
            if (nids.length == 0) {
                OptionalLong nid = KometClipboard.conceptNid(e.getDragboard());
                if (nid.isEmpty()) {
                    nid = KometClipboard.entityNidFrom(e.getDragboard());
                }
                if (nid.isPresent()) {
                    nids = new long[] {nid.getAsLong()};
                }
            }
            if (nids.length > 0) {
                StringBuilder tokens = new StringBuilder();
                for (long nid : nids) {
                    Optional<String> token = tokenFor(nid, viewCalc);
                    if (token.isEmpty()) {
                        continue;
                    }
                    if (!tokens.isEmpty()) {
                        tokens.append(' ');
                    }
                    tokens.append(token.get());
                }
                // The tokens land where they were DROPPED, not at whatever position the caret
                // last held (which, on an unfocused editor, is the start).
                int at = area.getCaretPosition();
                if (area.getSkin() instanceof javafx.scene.control.skin.TextAreaSkin skin) {
                    at = skin.getIndex(e.getX(), e.getY()).getInsertionIndex();
                }
                // A drop below the last line can map past the document end (JavaFX 27-ea
                // returns an out-of-range insertion index there) — clamp before inserting.
                area.insertText(Math.clamp(at, 0, area.getLength()), tokens.toString());
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    /**
     * The dropped component's badge token: its least UUID plus the view's name, or empty when the
     * store has no public id for it. The token never holds a nid
     * ({@code IKE-Network/ike-issues#1170}).
     */
    private static Optional<String> tokenFor(long nid, Supplier<ViewCalculator> viewCalc) {
        ViewCalculator calculator = null;
        try {
            calculator = viewCalc == null ? null : viewCalc.get();
        } catch (RuntimeException e) {
            LOG.warn("No view to name the dropped component; its token has no label", e);
        }
        return ComponentText.badge(calculator, nid);
    }

    private static boolean accepts(Dragboard dragboard) {
        return dragboard != null && dragboard.getContentTypes() != null
                && dragboard.getContentTypes().stream().anyMatch(DROPPABLE_FORMATS::contains);
    }
}
