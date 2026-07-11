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
import dev.ikm.komet.framework.dnd.KonceptDragGlyph;
import dev.ikm.komet.framework.graphics.SmallCapsFonts;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
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
        try {
            int nid = PrimitiveData.nid(pid);
            int iconPx = (int) Math.round(base * 0.92);
            ImageView icon = Identicon.generateIdenticon(pid, iconPx, iconPx);
            icon.setSmooth(false);
            String name = (viewCalc != null)
                    ? viewCalc.getFullyQualifiedNameText(nid)
                            .orElseGet(() -> viewCalc.getPreferredDescriptionTextWithFallbackOrNid(nid))
                    : null;
            if (name == null || name.isBlank()) {
                return icon;
            }
            boolean inactive = isInactive(viewCalc, nid);
            // Strikethrough on the name is the dedicated "inactive / retired" signal
            // (reusable; leaves ⚠ free for other meanings).
            // Small caps is the koncept chip's cross-medium signature (real in the CSS renderers).
            // With the bundled dedicated small-caps family (LoadFonts) the name renders in its
            // NATURAL case — capitals stay full height, lowercase becomes small capitals — which
            // both looks like true small caps and is more faithful than force-uppercasing
            // (koncept-label-fidelity). Absent the font, fall back to the prior shrunken all-caps.
            String scFamily = SmallCapsFonts.family();
            Text nameText = new Text(scFamily != null ? name : name.toUpperCase(Locale.ROOT));
            nameText.setFont(scFamily != null
                    ? Font.font(scFamily, base * 0.9)
                    : Font.font(base * 0.8));
            nameText.setFill(Color.web(inactive ? "#b00020" : "#2a5a8a"));
            nameText.setStrikethrough(inactive);
            final Text chipText = nameText;
            // CENTER_LEFT centres the identicon on the name's midline; getBaselineOffset
            // reports the text baseline so a host (RTA line, tree cell) seats the chip on its line.
            HBox chip = new HBox(icon, nameText) {
                @Override
                public double getBaselineOffset() {
                    javafx.geometry.Insets in = getInsets();
                    double contentH = prefHeight(-1) - in.getTop() - in.getBottom();
                    double textTop = in.getTop()
                            + Math.max(0, (contentH - chipText.getLayoutBounds().getHeight()) / 2);
                    return textTop + chipText.getBaselineOffset();
                }
            };
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.setSpacing(3);
            // Hug the content: an HBox's default max width resolves to MAX_VALUE, so in a cell that
            // stretches its graphic the pill would fill the whole row. Cap it at its preferred width
            // so the blue pill wraps just the identicon + name, in a cell exactly as in flowing text.
            chip.setMaxWidth(Region.USE_PREF_SIZE);
            chip.setStyle(CHIP_STYLE);

            StringBuilder tip = new StringBuilder();
            if (inactive) {
                tip.append("INACTIVE — retired in this view\n");
            }
            tip.append(name);
            if (sctid != null) {
                tip.append("\nSCTID: ").append(sctid);
            }
            tip.append("\nUUID: ").append(pid.idString());
            tip.append("\nnid: ").append(nid);
            Tooltip.install(chip, new Tooltip(tip.toString()));

            // The chip is a concept drag source. Its drag image is a dedicated, width-bounded glyph
            // (a long name ellipsises there) rendered headless — not a snapshot of this full-width
            // chip — so the displayed chip and the drag glyph are each right on their own terms.
            KonceptDragGlyph.install(chip, nid, pid, name, inactive);
            return chip;
        } catch (RuntimeException e) {
            // Never let a chip failure break the surface it is embedded in.
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
