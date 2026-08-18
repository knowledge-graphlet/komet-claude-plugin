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
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.scene.control.TextArea;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalInt;
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
     * tokens at the caret, one per dropped component, space-joined.
     *
     * @param area     the raw editor
     * @param viewCalc supplies the view for name resolution; a {@code null} supplier result
     *                 falls back to the store's default text
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
            int[] nids = KometClipboard.conceptNidsFrom(e.getDragboard());
            if (nids.length == 0) {
                OptionalInt nid = KometClipboard.conceptNid(e.getDragboard());
                if (nid.isEmpty()) {
                    nid = KometClipboard.entityNidFrom(e.getDragboard());
                }
                if (nid.isPresent()) {
                    nids = new int[] {nid.getAsInt()};
                }
            }
            if (nids.length > 0) {
                StringBuilder tokens = new StringBuilder();
                for (int nid : nids) {
                    if (!tokens.isEmpty()) {
                        tokens.append(' ');
                    }
                    tokens.append(tokenFor(nid, viewCalc));
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

    /** The dropped component's {@code k:} token: primordial UUID plus the view's name. */
    private static String tokenFor(int nid, Supplier<ViewCalculator> viewCalc) {
        PublicId pid = PrimitiveData.publicId(nid);
        String name = null;
        try {
            ViewCalculator calculator = viewCalc == null ? null : viewCalc.get();
            if (calculator != null) {
                name = calculator.getDescriptionText(nid).orElse(null);
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not resolve a name for dropped nid {}", nid, e);
        }
        if (name == null || name.isBlank()) {
            name = PrimitiveData.text(nid);
        }
        return KonceptTokens.token("uuid", pid.asUuidArray()[0].toString(), name);
    }

    private static boolean accepts(Dragboard dragboard) {
        return dragboard != null && dragboard.getContentTypes() != null
                && dragboard.getContentTypes().stream().anyMatch(DROPPABLE_FORMATS::contains);
    }
}
