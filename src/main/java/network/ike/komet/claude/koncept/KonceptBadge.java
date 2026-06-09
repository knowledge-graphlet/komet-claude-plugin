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
package network.ike.komet.claude.koncept;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Composites the adoc Koncept badge — LifeHash identicon, label, and a rounded
 * pill boundary — into a single PNG (Java2D), so a renderer that can only show
 * images (Zulip) still gets the whole badge as one picture. Mirrors the adoc HTML
 * badge: identicon-left, IKE-blue ({@code #2a5a8a}) label on a soft ({@code #e9eff6})
 * rounded pill.
 *
 * <p>Rendered at {@link #SCALE}× for high-DPI crispness (downscaling a sharp source
 * stays sharp on retina); the identicon is drawn nearest-neighbour to preserve its
 * pixel-art cells. A future {@code koncept-core} module (#623) owns this for the
 * website too; the plugin uploads the result.
 */
public final class KonceptBadge {

    /**
     * Supersampling factor — the pixel density. The badge is laid out from the small
     * <em>logical</em> sizes below (pad/icon/gap/font) multiplied by SCALE, so SCALE
     * raises the density (hi-DPI sharpness) for a fixed design. Zulip downscales a
     * dense source and keeps it crisp on retina (as the 512px identicon showed); the
     * trade-off is that Zulip also sizes the displayed image by its pixels, so higher
     * SCALE can render larger. Tune SCALE for density vs. displayed size.
     */
    public static final int SCALE = 2;

    /**
     * The badge background — painted opaque (NOT left transparent) because Zulip's
     * image pipeline flattens PNG alpha onto grey, which shows as an annoying grey
     * rectangle. Matching Zulip's light-theme message background lets the rounded-pill
     * corners blend into the message instead. (A dark theme would need a different fill.)
     */
    private static final Color MESSAGE_BG = Color.WHITE;
    private static final Color PILL_FILL = new Color(0xE9, 0xEF, 0xF6);
    private static final Color PILL_BORDER = new Color(0xC8, 0xD6, 0xE6);
    private static final Color LABEL_COLOR = new Color(0x2A, 0x5A, 0x8A);

    private KonceptBadge() {
    }

    /**
     * Renders the badge for an identicon + label.
     *
     * @param identiconPng the LifeHash identicon PNG bytes (any size; drawn pixel-crisp)
     * @param label        the concept label (rendered verbatim — never truncated)
     * @return the composited badge PNG bytes
     * @throws UncheckedIOException if decoding the identicon or encoding the badge fails
     */
    public static byte[] png(byte[] identiconPng, String label) {
        final int pad = 4 * SCALE;
        final int icon = 13 * SCALE;
        final int gap = 5 * SCALE;
        final Font font = new Font(Font.SANS_SERIF, Font.BOLD, 11 * SCALE);

        BufferedImage identicon;
        try {
            identicon = ImageIO.read(new ByteArrayInputStream(identiconPng));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode identicon for badge", e);
        }

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        FontMetrics fm = pg.getFontMetrics();
        int textW = fm.stringWidth(label);
        int ascent = fm.getAscent();
        int textH = ascent + fm.getDescent();
        pg.dispose();

        int contentH = Math.max(icon, textH);
        int w = pad + icon + gap + textW + pad;
        int h = pad + contentH + pad;

        BufferedImage badge = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int arc = h;
        // Rounded pill on the message background. NOTE: a full-bleed solid test proved
        // Zulip frames every inline image with its own grey box regardless of the PNG,
        // so we keep the nicer adoc rounded-pill look and accept Zulip's frame (the
        // frameless route is an inline emoji / KaTeX matrix, not the block image).
        g.setColor(MESSAGE_BG);
        g.fillRect(0, 0, w, h);
        g.setColor(PILL_FILL);
        g.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
        g.setColor(PILL_BORDER);
        g.setStroke(new BasicStroke(SCALE));
        g.drawRoundRect(SCALE / 2, SCALE / 2, w - SCALE - 1, h - SCALE - 1, arc, arc);

        // Identicon — at this small size the 32×32 cells are sub-pixel, so bilinear
        // (a smooth colour signature) reads better than nearest-neighbour's dropped cells.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(identicon, pad, (h - icon) / 2, icon, icon, null);

        g.setColor(LABEL_COLOR);
        g.setFont(font);
        g.drawString(label, pad + icon + gap, (h - textH) / 2 + ascent);
        g.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(badge, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode Koncept badge PNG", e);
        }
    }
}
