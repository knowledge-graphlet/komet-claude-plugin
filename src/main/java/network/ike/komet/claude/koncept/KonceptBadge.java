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

import network.ike.docs.konceptcore.KonceptAppearance;
import network.ike.docs.konceptcore.KonceptKind;
import network.ike.docs.konceptcore.KonceptStatus;
import network.ike.docs.konceptcore.StampSigilGeometry;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Composites the Koncept badge — LifeHash identicon, label, and a rounded pill — into a single PNG
 * (Java2D), so a renderer that can only show images (Zulip / email) still gets the whole badge as
 * one picture. Honest about the component {@link KonceptKind} (ike-issues#638): a concept is the
 * bare identicon + label; every other kind prepends its coloured letter sigil; a stamp is the gray
 * {@link StampSigilGeometry} pentagon, then the STAMP's own identicon, then its compact provenance
 * text in place of a name (never the blue name-pill) — the sigil always immediately precedes an
 * identicon, never bare, and the identicon tells one STAMP from another at a glance.
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

    /** The one shared badge appearance (ike-issues#742/#860) every value below reads from. */
    private static final KonceptAppearance SPEC = KonceptAppearance.defaults();

    /**
     * The badge background — painted opaque (NOT left transparent) because Zulip's image pipeline
     * flattens PNG alpha onto grey. Matching Zulip's light-theme message background lets the
     * rounded-pill corners blend into the message. (A dark theme would need a different fill.)
     */
    private static final Color MESSAGE_BG = Color.WHITE;
    private static final Color PILL_FILL = Color.decode(SPEC.pillFillHex());
    private static final Color LABEL_COLOR = Color.decode(SPEC.labelColorHex());
    private static final Color LABEL_INACTIVE_COLOR = Color.decode(SPEC.labelColorInactiveHex());

    /** The floating-context border (#862): the PNG floats inside Zulip/email, a surface we don't control. */
    private static final Color FLOATING_BORDER = Color.decode(SPEC.floatingBorderHex());

    /** Gray metadata/provenance chip for the STAMP kind — never the blue name-pill (ike-issues#638). */
    private static final Color STAMP_CHIP_FILL = Color.decode(SPEC.pillFillStampHex());

    /** Dark-gray text for the stamp's compact provenance (status · date-time · author). */
    private static final Color STAMP_TEXT_COLOR = new Color(0x5A, 0x57, 0x50);

    /** Pill corner radius (px, pre-scale), from the spec. */
    private static final int PILL_ARC = (int) SPEC.cornerRadiusPx();

    /**
     * The bundled true-small-caps face from the koncept-core jar (#860's font-distribution
     * decision), loaded once; {@code null} when unavailable, in which case labels fall back to
     * the platform sans — a badge must never fail on a missing font resource.
     */
    private static final Font SMALL_CAPS_BASE = loadSmallCaps();

    private static Font loadSmallCaps() {
        try (InputStream in = KonceptAppearance.smallCapsFont()) {
            if (in == null) {
                return null;
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            return null;
        }
    }

    /** Whether the bundled small-caps face resolved — an observation point for the tests. */
    static boolean smallCapsActive() {
        return SMALL_CAPS_BASE != null;
    }

    /**
     * The label font at the spec size: the bundled Alegreya Sans SC face (true small caps —
     * capitals full height, the rest small capitals, the name in its natural case), else the
     * platform sans verbatim fallback. Struck through for a retired referent when the spec
     * says so — {@code drawString} honours the attribute-derived font.
     */
    private static Font labelFont(boolean inactive) {
        float size = (float) (SPEC.labelSizePx() * SCALE);
        Font base = SMALL_CAPS_BASE != null
                ? SMALL_CAPS_BASE.deriveFont(size)
                : new Font(Font.SANS_SERIF, Font.PLAIN, Math.round(size));
        if (inactive && SPEC.inactiveStrikethrough()) {
            return base.deriveFont(Map.of(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON));
        }
        return base;
    }

    /** Fills the pill and strokes the spec's floating border inside the image bounds. */
    private static void paintPill(Graphics2D g, int w, int h, Color fill) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, w - 1, h - 1, PILL_ARC * SCALE, PILL_ARC * SCALE);
        float stroke = (float) (SPEC.floatingBorderWidthPx() * SCALE);
        g.setColor(FLOATING_BORDER);
        g.setStroke(new BasicStroke(stroke));
        double inset = stroke / 2.0;
        g.draw(new RoundRectangle2D.Double(inset, inset, w - 1 - stroke, h - 1 - stroke,
                PILL_ARC * SCALE, PILL_ARC * SCALE));
    }

    /** Pentagon unit-radius as a fraction of the half-box — kept identical to the JavaFX {@code StampSigil}. */
    private static final double STAMP_RADIUS_FRACTION = 0.92;

    private KonceptBadge() {
    }

    /**
     * Renders the badge for an identicon + label, honest about the component {@code kind}
     * (ike-issues#638): a {@link KonceptKind#CONCEPT} is the bare identicon + label pill; a
     * {@link KonceptKind#STAMP} is the gray {@link #stampPng(byte[], String) pentagon chip} —
     * pentagon, then the STAMP's own identicon, then its compact provenance text; every other kind
     * prepends its coloured letter sigil ({@code D}/{@code S}/{@code P}/{@code ?}).
     *
     * <p>Appearance reconciled to the JavaFX/adoc spec (ike-issues#742): normal-weight label, subtle
     * pill radius, no border. (Java2D has no small-caps, so the label is drawn verbatim.)
     *
     * @param identiconPng the component's LifeHash identicon PNG bytes (any size; drawn
     *                     pixel-crisp) — for a stamp, the STAMP's own identicon
     * @param label        the concept label, or — for a stamp — its compact provenance text
     * @param kind         the component kind; {@code null} is treated as {@link KonceptKind#CONCEPT}
     * @return the composited badge PNG bytes
     * @throws UncheckedIOException if decoding the identicon or encoding the badge fails
     */
    public static byte[] png(byte[] identiconPng, String label, KonceptKind kind) {
        return png(identiconPng, label, kind, KonceptStatus.NONE, false);
    }

    /**
     * Renders the badge with its full one-leading-mark form (ike-issues#742 amendment, #862): a
     * letter kind leads with its coloured sigil; a bare Koncept leads with its logical-status
     * copula cluster — {@code ≡} sufficiently defined, {@code ⊑} primitive, {@code ⊤} root, with
     * the {@code ⋎} fork appended for a multi-parent concept — each glyph in its own colour from
     * the single-sourced {@link KonceptStatus} vocabulary. Kind sigils and status marks never
     * co-occur. The caller computes the status at compose time (this compositor is store-free);
     * a font that cannot display the copula glyphs degrades to the bare pill rather than tofu.
     *
     * @param identiconPng the component's LifeHash identicon PNG bytes
     * @param label        the concept label, or — for a stamp — its compact provenance text
     * @param kind         the component kind; {@code null} is treated as {@link KonceptKind#CONCEPT}
     * @param status       the Koncept's logical-definition status; {@link KonceptStatus#NONE}
     *                     (or {@code null}) stays bare
     * @param multiParent  whether the concept has more than one stated parent
     * @return the composited badge PNG bytes
     * @throws UncheckedIOException if decoding the identicon or encoding the badge fails
     */
    public static byte[] png(byte[] identiconPng, String label, KonceptKind kind,
                             KonceptStatus status, boolean multiParent) {
        return png(identiconPng, label, kind, status, multiParent, false);
    }

    /**
     * Renders the badge in its full spec-driven form (#862): the one-leading-mark rule (kind
     * sigil or status cluster), the {@link KonceptAppearance} palette, geometry, and floating
     * border, true small caps from the bundled koncept-core face, and — for a retired
     * referent — the struck-through label in the retired colour.
     *
     * @param identiconPng the component's LifeHash identicon PNG bytes
     * @param label        the concept label, or — for a stamp — its compact provenance text
     * @param kind         the component kind; {@code null} is treated as {@link KonceptKind#CONCEPT}
     * @param status       the Koncept's logical-definition status; {@link KonceptStatus#NONE}
     *                     (or {@code null}) stays bare
     * @param multiParent  whether the concept has more than one stated parent
     * @param inactive     whether the referent's latest version is inactive (retired parity)
     * @return the composited badge PNG bytes
     * @throws UncheckedIOException if decoding the identicon or encoding the badge fails
     */
    public static byte[] png(byte[] identiconPng, String label, KonceptKind kind,
                             KonceptStatus status, boolean multiParent, boolean inactive) {
        KonceptKind resolved = (kind == null) ? KonceptKind.CONCEPT : kind;
        if (resolved.isStamp()) {
            return stampPng(identiconPng, label);
        }
        // Geometry from the spec (#862): the unified pads, identicon edge, gap, and label size —
        // the same reference pixels every renderer draws, at this compositor's SCALE density.
        final int padTop = (int) SPEC.padTopPx() * SCALE;
        final int padRight = (int) SPEC.padRightPx() * SCALE;
        final int padBottom = (int) SPEC.padBottomPx() * SCALE;
        final int padLeft = (int) SPEC.padLeftPx() * SCALE;
        final int icon = (int) SPEC.identiconSizePx() * SCALE;
        final int gap = (int) SPEC.iconLabelGapPx() * SCALE;
        // #742: the label is normal weight; true small caps come from the bundled face (the
        // verbatim platform-sans fallback keeps the badge alive without it). The kind sigil is a
        // distinct bold mark at its 15:12 spec ratio; the status cluster at the 10:12 ratio.
        final Font labelFont = labelFont(inactive);
        final Font sigilFont = new Font(Font.SANS_SERIF, Font.BOLD,
                (int) Math.round(SPEC.labelSizePx() * 15.0 / 12.0) * SCALE);
        final String sigilGlyph = resolved.hasLetterGlyph() ? resolved.glyph() : null;
        // The status cluster is Koncept-only (one leading mark, never beside a sigil). Degrade to
        // bare if the platform sans font cannot display the DL glyphs — never tofu.
        final Font statusFont = new Font(Font.SANS_SERIF, Font.PLAIN,
                (int) Math.round(SPEC.labelSizePx() * 10.0 / 12.0) * SCALE);
        KonceptStatus resolvedStatus = (status == null) ? KonceptStatus.NONE : status;
        String cluster = resolved == KonceptKind.CONCEPT
                ? resolvedStatus.cluster(multiParent) : "";
        if (!cluster.isEmpty() && statusFont.canDisplayUpTo(cluster) != -1) {
            cluster = "";
        }

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
        int clusterW = 0;
        if (!cluster.isEmpty()) {
            pg.setFont(statusFont);
            clusterW = pg.getFontMetrics().stringWidth(cluster) + gap;
        }
        pg.dispose();

        int contentH = Math.max(icon, textH);
        int w = padLeft + sigilW + clusterW + icon + gap + textW + padRight;
        int h = padTop + contentH + padBottom;

        BufferedImage badge = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // #742: opaque message background (Zulip flattens alpha) + the spec pill with the shared
        // floating border — the PNG floats inside Zulip/email, a surface we don't control (#862).
        g.setColor(MESSAGE_BG);
        g.fillRect(0, 0, w, h);
        paintPill(g, w, h, PILL_FILL);

        int x = padLeft;
        if (sigilGlyph != null) {
            g.setColor(Color.decode(resolved.colorHex()));
            g.setFont(sigilFont);
            FontMetrics sfm = g.getFontMetrics();
            g.drawString(sigilGlyph, x, (h - (sfm.getAscent() + sfm.getDescent())) / 2 + sfm.getAscent());
            x += sigilW;
        }
        if (!cluster.isEmpty()) {
            // Copula in its status colour, fork (when present) in the multi-parent blue — the
            // same two-tone cluster every other medium renders.
            g.setFont(statusFont);
            FontMetrics cfm = g.getFontMetrics();
            int baseline = (h - (cfm.getAscent() + cfm.getDescent())) / 2 + cfm.getAscent();
            String copula = resolvedStatus.glyph();
            g.setColor(Color.decode(resolvedStatus.colorHex()));
            g.drawString(copula, x, baseline);
            if (cluster.length() > copula.length()) {
                g.setColor(Color.decode(KonceptStatus.MULTI_PARENT_COLOR_HEX));
                g.drawString(KonceptStatus.MULTI_PARENT_GLYPH, x + cfm.stringWidth(copula), baseline);
            }
            x += clusterW;
        }

        // Identicon — at this small size the 32×32 cells are sub-pixel, so bilinear (a smooth colour
        // signature) reads better than nearest-neighbour's dropped cells.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(identicon, x, (h - icon) / 2, icon, icon, null);
        x += icon + gap;

        g.setColor(inactive ? LABEL_INACTIVE_COLOR : LABEL_COLOR);
        g.setFont(labelFont);
        g.drawString(label, x, (h - textH) / 2 + ascent);
        g.dispose();

        return encode(badge);
    }

    /**
     * The STAMP kind badge: the locked gray pentagon (from the shared {@link StampSigilGeometry}) in
     * a gray metadata chip, then the STAMP's own identicon, then the compact provenance text in
     * place of a name — the sigil always immediately precedes an identicon, never bare, and the
     * identicon tells one STAMP from another at a glance (revised ike-issues#638). The pentagon
     * geometry is the byte-identical port of the JavaFX {@code StampSigil}, so the same pentagon
     * renders in every medium. Without identicon bytes the chip falls back to pentagon + text — the
     * no-computable-identity fallback, matching the adoc renderer.
     *
     * @param identiconPng the STAMP's own LifeHash identicon PNG bytes, or {@code null}/empty when
     *                     no identity is computable
     * @param label        the compact stamp text ({@code status · date-time · author})
     * @return the composited stamp-badge PNG bytes
     */
    private static byte[] stampPng(byte[] identiconPng, String label) {
        final int box = 16 * SCALE;
        final int padTop = (int) SPEC.padTopPx() * SCALE;
        final int padRight = (int) SPEC.padRightPx() * SCALE;
        final int padBottom = (int) SPEC.padBottomPx() * SCALE;
        final int padLeft = (int) SPEC.padLeftPx() * SCALE;
        final int icon = (int) SPEC.identiconSizePx() * SCALE;
        final int gap = (int) SPEC.iconLabelGapPx() * SCALE;
        // Provenance is data, not a name: plain sans at the spec label size, never small caps.
        final Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN,
                (int) SPEC.labelSizePx() * SCALE);

        BufferedImage identicon = null;
        if (identiconPng != null && identiconPng.length > 0) {
            try {
                identicon = ImageIO.read(new ByteArrayInputStream(identiconPng));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to decode identicon for stamp badge", e);
            }
        }

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(labelFont);
        FontMetrics fm = pg.getFontMetrics();
        int textW = fm.stringWidth(label);
        int ascent = fm.getAscent();
        int textH = ascent + fm.getDescent();
        pg.dispose();

        int iconW = (identicon != null) ? icon + gap : 0;
        int contentH = Math.max(box, textH);
        int w = padLeft + box + gap + iconW + textW + padRight;
        int h = padTop + contentH + padBottom;

        BufferedImage badge = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = badge.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(MESSAGE_BG);
        g.fillRect(0, 0, w, h);
        paintPill(g, w, h, STAMP_CHIP_FILL);

        // The pentagon leads, the STAMP's identicon follows it, and the provenance text closes.
        drawPentagon(g, padLeft + box / 2.0, h / 2.0, (box / 2.0) * STAMP_RADIUS_FRACTION);

        int x = padLeft + box + gap;
        if (identicon != null) {
            // Same bilinear rationale as the concept badge: at this size the cells are sub-pixel.
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(identicon, x, (h - icon) / 2, icon, icon, null);
            x += icon + gap;
        }

        g.setColor(STAMP_TEXT_COLOR);
        g.setFont(labelFont);
        g.drawString(label, x, (h - textH) / 2 + ascent);
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
