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
import dev.ikm.komet.framework.dnd.KonceptDragSource;
import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.InlinePiece;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import javafx.geometry.Pos;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant's {@link InlineDecorator}: in every rendered text run it detects component
 * identifiers — an SCTID, a UUID, or a {@code nid=…} — and follows each existence-gated one
 * with a <em>concept chip</em> styled like the AsciiDoc {@code k:} Koncept chip: the
 * component's LifeHash {@link Identicon} on the left and its store-resolved name in small-caps
 * inside a soft rounded pill, with the full identity (name, SCTID, UUID, nid) on hover.
 *
 * <p>Detection is format-agnostic (the model routinely drops brackets and reshapes ids into
 * tables / inline code) and existence-gated against the live store, so only real components
 * get a chip. When the component's latest version is inactive/retired in the view, the chip's
 * name is struck through — the dedicated "inactive" signal (#586). The badge and name are
 * deterministic functions of the {@link PublicId}, so a fabricated id would not match what
 * Komet displays.
 */
final class ConceptChipInlineDecorator implements InlineDecorator {

    /**
     * Matches component identifiers in whatever shape the model reproduces them — brackets
     * optional. Group 1 = UUID, group 2 = nid, group 3 = a bare SCTID-like number (6-18
     * digits). Permissive matching is safe because every candidate is existence-gated in
     * {@link #resolve}.
     */
    private static final Pattern TOKEN = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
                    + "|nid=(-?\\d+)"
                    + "|\\b(\\d{6,18})\\b");

    /** Adoc Koncept chip palette (koncept.css): soft pill, IKE-blue small-caps label. */
    private static final String CHIP_STYLE =
            "-fx-background-color: #e9eff6; -fx-background-radius: 6; -fx-padding: 1 5 1 4;";

    /** The view used to resolve concept names for chips; may be null (icon-only fallback). */
    private final ViewCalculator viewCalc;
    /** Base body font size (px); the chip label and identicon scale from it. */
    private final double base;

    ConceptChipInlineDecorator(ViewCalculator viewCalc, double base) {
        this.viewCalc = viewCalc;
        this.base = base;
    }

    /**
     * Decomposes {@code text} into pieces, keeping each matched identifier inline (so the digits
     * the user asked for stay visible) as a text piece and following each resolved identifier with
     * its concept chip as a node piece. Returning pieces (rather than writing into a paragraph
     * builder) is what lets the chips render inside table cells as well as in flowing text.
     */
    @Override
    public List<InlinePiece> decorate(String text, StyleAttributeMap style) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<InlinePiece> pieces = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            // Text up to and including the identifier (the id stays visible).
            addText(pieces, text.substring(last, m.end()), style);
            PublicId id = resolve(m);
            if (id != null) {
                String sctid = m.group(3);
                // The id itself is already in the preceding text piece, so the chip's plain-text
                // projection is empty to avoid duplicating it on copy.
                pieces.add(new InlinePiece.NodeRun(() -> conceptChip(id, sctid), ""));
            }
            last = m.end();
        }
        if (last < text.length()) {
            addText(pieces, text.substring(last), style);
        }
        return pieces;
    }

    private static void addText(List<InlinePiece> pieces, String text, StyleAttributeMap style) {
        if (!text.isEmpty()) {
            pieces.add(new InlinePiece.TextRun(text, style));
        }
    }

    /** True if the component's latest version in the current view is inactive (retired). */
    private boolean isInactive(int nid) {
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

    /**
     * Resolves a matched token to a {@link PublicId} that actually exists in the store, or
     * null. The {@code hasPublicId} gate is what makes bare-number matching safe.
     */
    private static PublicId resolve(Matcher m) {
        try {
            PublicId pid = null;
            if (m.group(1) != null) {
                pid = PublicIds.of(UUID.fromString(m.group(1)));
            } else if (m.group(2) != null) {
                pid = PrimitiveData.publicId(Integer.parseInt(m.group(2)));
            } else if (m.group(3) != null) {
                pid = PublicIds.of(UuidUtil.fromSNOMED(m.group(3)));
            }
            if (pid != null && PrimitiveData.get().hasPublicId(pid)) {
                return pid;
            }
        } catch (RuntimeException ignored) {
            // Unresolvable / non-existent id — render text without a chip.
        }
        return null;
    }

    /**
     * Builds the inline concept chip: identicon (left) + store-resolved name in small-caps,
     * in a soft rounded pill, with the full grounded identity on hover; the name is struck
     * through when the component is inactive. Falls back to a bare identicon when the name
     * cannot be resolved.
     *
     * @param pid   the resolved, existing component id
     * @param sctid the matched SCTID for the tooltip, or null if matched by UUID/nid
     */
    private javafx.scene.Node conceptChip(PublicId pid, String sctid) {
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
            boolean inactive = isInactive(nid);
            // Strikethrough on the name is the dedicated "inactive / retired" signal
            // (reusable; leaves ⚠ free for other meanings).
            javafx.scene.text.Text nameText = new javafx.scene.text.Text(name.toUpperCase(Locale.ROOT));
            nameText.setFont(javafx.scene.text.Font.font(base * 0.8));
            nameText.setFill(Color.web(inactive ? "#b00020" : "#2a5a8a"));
            nameText.setStrikethrough(inactive);
            final javafx.scene.text.Text chipText = nameText;
            // CENTER_LEFT centres the identicon on the name's midline; getBaselineOffset
            // reports the text baseline so RTA seats the chip on the surrounding line.
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
            chip.setStyle(CHIP_STYLE);

            // A transcript chip is a concept drag source, like every other identicon-bearing chip:
            // the cursor sits just right of the identicon on the drag image's bottom border (#736).
            KonceptDragSource.install(chip, nid);

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
            return chip;
        } catch (RuntimeException e) {
            // Never let a chip failure break the transcript.
            return new javafx.scene.layout.Region();
        }
    }
}
