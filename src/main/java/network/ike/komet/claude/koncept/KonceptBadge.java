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

import network.ike.docs.konceptcore.KonceptKind;
import network.ike.docs.konceptcore.StampSigilGeometry;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Composites the Koncept badge — LifeHash identicon, label, and a rounded pill — into a single PNG
 * (Java2D), so a renderer that can only show images (Zulip / email) still gets the whole badge as
 * one picture. Honest about the component {@link KonceptKind} (ike-issues#638): a concept is the
 * bare identicon + label; every other kind prepends its coloured letter sigil; a stamp is the gray
 * {@link StampSigilGeometry} pentagon plus its compact provenance text (no identicon, no name-pill).
 *
 * <p>Appearance matches the JavaFX / adoc spec (ike-issues#742): normal-weight label, subtle pill
 * radius, no border. Rendered at {@link #SCALE}× for high-DPI crispness. A future {@code koncept-core}
 * module (#623) owns the shared spec; the plugin uploads the result.
 */
public final class KonceptBadge {

    /**
     * Supersampling factor — the pixel density. The badge is laid out from the small <em>logical</em>
     * sizes below (pad/icon/gap/font) multiplied by SCALE, so SCALE raises the density (hi-DPI
     * sharpness) for a fixed design. Zulip downscales a dense source and keeps it crisp on retina.
     */
    public static final int SCALE = 2;

    /**
     * The badge background — painted opaque (NOT left transparent) because Zulip's image pipeline
     * flattens PNG alpha onto grey. Matching Zulip's light-theme message background lets the
     * rounded-pill corners blend into the message. (A dark theme would need a different fill.)
     */
    private static final Color MESSAGE_BG = Color.WHITE;
    private static final Color PILL_FILL = new Color(0xE9, 0xEF, 0xF6);
    private static final Color LABEL_COLOR = new Color(0x2A, 0x5A, 0x8A);

    /** Gray metadata/provenance chip for the STAMP kind — never the blue name-pill (ike-issues#638). */
    private static final Color STAMP_CHIP_FILL = new Color(0xEC, 0xEB, 0xE8);

    /** Dark-gray text for the stamp's compact provenance (status · date-time · author). */
    private static final Color STAMP_TEXT_COLOR = new Color(0x5A, 0x57, 0x50);

    /** Subtle pill corner radius (px, pre-scale), matching the adoc {@code border-radius:0.5em} (ike-issues#742). */
    private static final int PILL_ARC = 8;

    /** Pentagon unit-radius as a fraction of the half-box — kept identical to the JavaFX {@code StampSigil}. */
    private static final double STAMP_RADIUS_FRACTION = 0.92;

    private KonceptBadge() {
    }

    /**
     * Renders the badge for an identicon + label, honest about the component {@code kind}
     * (ike-issues#638): a {@link KonceptKind#CONCEPT} is the bare identicon + label pill; a
     * {@link KonceptKind#STAMP} is the gray {@link #stampPng(String) pentagon chip} carrying its
     * compact provenance text; every other kind prepends its coloured letter sigil
     * ({@code D}/{@code S}/{@code P}/{@code ?}).
     *
     * <p>Appearance reconciled to the JavaFX/adoc spec (ike-issues#742): normal-weight label, subtle
     * pill radius, no border. (Java2D has no small-caps, so the label is drawn verbatim.)
     *
     * @param identiconPng the LifeHash identicon PNG bytes (any size; drawn pixel-crisp)
     * @param label        the concept label, or — for a stamp — its compact provenance text
     * @param kind         the component kind; {@code null} is treated as {@link KonceptKind#CONCEPT}
     * @return the composited badge PNG bytes
     * @throws UncheckedIOException if decoding the identicon or encoding the badge fails
     */
    public static byte[] png(byte[] identiconPng, String label, KonceptKind kind) {
        KonceptKind resolved = (kind == null) ? KonceptKind.CONCEPT : kind;
        if (resolved.isStamp()) {
            return stampPng(label);
        }
        final int pad = 4 * SCALE;
        final int icon = 13 * SCALE;
        final int gap = 5 * SCALE;
        // #742: the label is normal weight (its small-caps in CSS is not bold; Java2D can't render
        // small-caps, so the label is drawn verbatim). The kind sigil is a distinct bold mark.
        final Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11 * SCALE);
        final Font sigilFont = new Font(Font.SANS_SERIF, Font.BOLD, 10 * SCALE);
        final String sigilGlyph = resolved.hasLetterGlyph() ? resolved.glyph() : null;

        BufferedImage identicon;
        try {
            identicon = ImageIO.read(new ByteArrayInputStream(identiconPng));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode identicon for badge", e);
        }

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(labelFont);
        FontMetrics fm = pg.getFontMetrics();
        int textW = fm.stringWidth(label);
        int ascent = fm.getAscent();
        int textH = ascent + fm.getDescent();
        int sigilW = 0;
        if (sigilGlyph != null) {
            pg.setFont(sigilFont);
            sigilW = pg.getFontMetrics().stringWidth(sigilGlyph) + gap;
        }
        pg.dispose();

        int contentH = Math.max(icon, textH);
        int w = pad + sigilW + icon + gap + textW + pad;
        int h = pad + contentH + pad;

        BufferedImage badge = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // #742: opaque message background (Zulip flattens alpha) + soft pill at a subtle radius, no
        // border. (Zulip frames every inline image with its own grey box regardless.)
        g.setColor(MESSAGE_BG);
        g.fillRect(0, 0, w, h);
        g.setColor(PILL_FILL);
        g.fillRoundRect(0, 0, w - 1, h - 1, PILL_ARC * SCALE, PILL_ARC * SCALE);

        int x = pad;
        if (sigilGlyph != null) {
            g.setColor(Color.decode(resolved.colorHex()));
            g.setFont(sigilFont);
            FontMetrics sfm = g.getFontMetrics();
            g.drawString(sigilGlyph, x, (h - (sfm.getAscent() + sfm.getDescent())) / 2 + sfm.getAscent());
            x += sigilW;
        }

        // Identicon — at this small size the 32×32 cells are sub-pixel, so bilinear (a smooth colour
        // signature) reads better than nearest-neighbour's dropped cells.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(identicon, x, (h - icon) / 2, icon, icon, null);
        x += icon + gap;

        g.setColor(LABEL_COLOR);
        g.setFont(labelFont);
        g.drawString(label, x, (h - textH) / 2 + ascent);
        g.dispose();

        return encode(badge);
    }

    /**
     * The STAMP kind badge: the locked gray pentagon (from the shared {@link StampSigilGeometry}) in
     * a gray metadata chip, followed by the compact provenance text — no identicon, no name. A stamp
     * is provenance, never a name-pill (ike-issues#638). The pentagon geometry is the byte-identical
     * port of the JavaFX {@code StampSigil}, so the same pentagon renders in every medium.
     *
     * @param label the compact stamp text ({@code status · date-time · author})
     * @return the composited stamp-badge PNG bytes
     */
    private static byte[] stampPng(String label) {
        final int box = 16 * SCALE;
        final int pad = 4 * SCALE;
        final int gap = 5 * SCALE;
        final Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11 * SCALE);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(labelFont);
        FontMetrics fm = pg.getFontMetrics();
        int textW = fm.stringWidth(label);
        int ascent = fm.getAscent();
        int textH = ascent + fm.getDescent();
        pg.dispose();

        int contentH = Math.max(box, textH);
        int w = pad + box + gap + textW + pad;
        int h = pad + contentH + pad;

        BufferedImage badge = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(MESSAGE_BG);
        g.fillRect(0, 0, w, h);
        g.setColor(STAMP_CHIP_FILL);
        g.fillRoundRect(0, 0, w - 1, h - 1, PILL_ARC * SCALE, PILL_ARC * SCALE);

        // The pentagon leads (in place of an identicon); the compact provenance text follows.
        drawPentagon(g, pad + box / 2.0, h / 2.0, (box / 2.0) * STAMP_RADIUS_FRACTION);

        g.setColor(STAMP_TEXT_COLOR);
        g.setFont(labelFont);
        g.drawString(label, pad + box + gap, (h - textH) / 2 + ascent);
        g.dispose();

        return encode(badge);
    }

    /**
     * Draws the locked stamp pentagon (outline + five asymmetric reading dots + hub) centred at
     * {@code (centerX, centerY)} with the given unit radius, in the locked gray — the same geometry,
     * floor and hub ratio as the JavaFX {@code StampSigil} (Option A), so the pentagon is identical
     * in every medium.
     */
    private static void drawPentagon(Graphics2D g, double centerX, double centerY, double unitRadius) {
        Color gray = Color.decode(StampSigilGeometry.COLOR);
        Path2D pentagon = new Path2D.Double();
        for (int i = 0; i < StampSigilGeometry.AXIS_COUNT; i++) {
            double px = centerX + StampSigilGeometry.VERTICES[i][0] * unitRadius;
            double py = centerY + StampSigilGeometry.VERTICES[i][1] * unitRadius;
            if (i == 0) {
                pentagon.moveTo(px, py);
            } else {
                pentagon.lineTo(px, py);
            }
        }
        pentagon.closePath();
        g.setColor(gray);
        g.setStroke(new BasicStroke((float) StampSigilGeometry.STROKE_WIDTH_PX * SCALE,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(pentagon);

        double dotRadius = Math.max(StampSigilGeometry.DOT_RADIUS * unitRadius, SCALE);
        for (int i = 0; i < StampSigilGeometry.AXIS_COUNT; i++) {
            double reading = StampSigilGeometry.AXIS_DOT_RADII[i] * unitRadius;
            double dx = centerX + StampSigilGeometry.VERTICES[i][0] * reading;
            double dy = centerY + StampSigilGeometry.VERTICES[i][1] * reading;
            g.fill(new Ellipse2D.Double(dx - dotRadius, dy - dotRadius, dotRadius * 2, dotRadius * 2));
        }
        double hubRadius = dotRadius * (StampSigilGeometry.HUB_RADIUS / StampSigilGeometry.DOT_RADIUS);
        g.fill(new Ellipse2D.Double(centerX - hubRadius, centerY - hubRadius, hubRadius * 2, hubRadius * 2));
    }

    private static byte[] encode(BufferedImage badge) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(badge, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode Koncept badge PNG", e);
        }
    }
}
