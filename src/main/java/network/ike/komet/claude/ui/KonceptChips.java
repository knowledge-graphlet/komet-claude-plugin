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

import dev.ikm.komet.framework.Identicon;
import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptKindResolver;
import dev.ikm.komet.framework.graphics.SmallCapsFonts;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.Locale;

/**
 * Factory for the shared <em>Koncept chip</em> node: a component's LifeHash {@link Identicon} on
 * the left and its store-resolved name in small-caps inside a soft rounded pill, with the full
 * identity on hover and a strikethrough name when the component is inactive/retired (#586). The
 * chip is a concept drag source, exactly like every other identicon-bearing chip in Komet (#736).
 *
 * <p>Extracted so both the inline grounding chip ({@link ConceptChipInlineDecorator}, chips that
 * follow an identifier in flowing text) and block renderers ({@code KonceptTreeBlockRenderer},
 * chips as tree-cell graphics) render the <em>identical</em> chip. The name and badge are
 * deterministic functions of the {@link PublicId}, so the chip always faithfully names the
 * concept it stands for — it is never a re-derived picture.
 */
final class KonceptChips {

    /** Adoc Koncept chip palette (koncept.css): soft pill, IKE-blue small-caps label. */
    static final String CHIP_STYLE =
            "-fx-background-color: #e9eff6; -fx-background-radius: 6; -fx-padding: 1 5 1 4;";

    private KonceptChips() {
    }

    /**
     * Builds the chip for an existing component id. Falls back to a bare identicon when the name
     * cannot be resolved, and to an empty {@link Region} if chip construction fails — a chip
     * failure must never break the surface it is embedded in.
     *
     * @param pid      the resolved, existing component id
     * @param sctid    the SCTID for the tooltip, or {@code null} when identified by UUID/nid
     * @param viewCalc the view used to resolve the name and active state; {@code null} → icon only
     * @param base     the ambient body font size (px); the label and identicon scale from it
     * @return the chip node (never null)
     */
    static Node chip(PublicId pid, String sctid, ViewCalculator viewCalc, double base) {
        return chip(pid, sctid, viewCalc, null, base);
    }

    /**
     * Builds the interactive-surface chip: same anatomy, with the view supplied as
     * {@link ViewProperties} so the badge computes its logical-status cluster and carries the
     * definition popout (ike-issues#941). The {@link ViewCalculator} form above stays for
     * non-interactive renderings (paged print — a popout has no place on paper) and for
     * surfaces with no usable view.
     *
     * @param pid            the resolved, existing component id
     * @param sctid          the SCTID for the tooltip, or {@code null}
     * @param viewProperties the interactive surface's view
     * @param base           the ambient body font size (px)
     * @return the chip node (never null)
     */
    static Node chip(PublicId pid, String sctid, ViewProperties viewProperties, double base) {
        return chip(pid, sctid, viewProperties == null ? null : viewProperties.calculator(),
                viewProperties, base);
    }

    private static Node chip(PublicId pid, String sctid, ViewCalculator viewCalc,
                             ViewProperties viewProperties, double base) {
        try {
            int nid = PrimitiveData.nid(pid);
            int iconPx = (int) Math.round(base * 0.92);
            ImageView icon = Identicon.generateIdenticon(pid, iconPx, iconPx);
            icon.setSmooth(false);
            // The name is the view's own answer — the language coordinate's description-type
            // priority (LanguageCoordinate.getDescription), never an FQN-first recipe: the chip
            // shows whatever the user's coordinate says the name is, and the owning surface
            // re-renders when the coordinate changes (ike-issues#942).
            String name = (viewCalc != null)
                    ? viewCalc.getDescriptionText(nid).orElse(null)
                    : null;
            if (name == null || name.isBlank()) {
                return icon;
            }
            // One koncept atom renders the chip (ikmdev/komet#742). The hand-rolled identicon +
            // name this replaces could not carry a kind sigil, so a pattern chip never showed its
            // P. The three things that used to make the atom unusable here are now its own:
            // ambient font scaling (the assistant's font ±), inline styling (this chip's scene is
            // not guaranteed the stylesheet), and a text baseline so the pill seats on a
            // RichTextArea line rather than floating off it.
            KonceptBadge badge;
            if (viewProperties != null) {
                // Self-resolving form: the badge derives name, kind, status cluster, retired
                // state and the definition popout from the view (#941/#942) — nothing
                // caller-resolved to go stale against the coordinate.
                badge = new KonceptBadge(nid, pid, viewProperties);
            } else {
                badge = new KonceptBadge(nid, pid, name);
                badge.setKind(KonceptKindResolver.resolve(nid, viewCalc));
                badge.setInactive(isInactive(viewCalc, nid));
            }
            badge.setAmbientFontSize(base);
            badge.setStandaloneStyling(true);
            badge.setSctid(sctid);
            badge.setMaxWidth(Region.USE_PREF_SIZE);
            // Consume the press so an enclosing RichTextArea doesn't begin a text selection on the
            // first press over the chip — without this it takes a second press (after the chip is
            // already inside a selection) to arm the drag. DRAG_DETECTED still fires, so a single
            // press-and-drag works. This lived only on the tree renderer's chips before, which is
            // why transcript chips needed the extra press; the factory owns it now, one behavior
            // per atom (ikmdev/komet#742).
            badge.setOnMousePressed(Event::consume);
            // No explicit drag install: the badge itself drags the canonical glyph at gesture
            // time, reading its current kind/name/retired state — one drag behavior per atom.
            return badge;
        } catch (RuntimeException e) {
            return new Region();
        }
    }


    /** True if the component's latest version in the current view is inactive (retired). */
    private static boolean isInactive(ViewCalculator viewCalc, int nid) {
        if (viewCalc == null) {
            return false;
        }
        try {
            var latest = viewCalc.stampCalculator().latest(nid);
            return latest.isPresent() && latest.get().inactive();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
