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
import network.ike.docs.konceptcore.KonceptStatus;
import network.ike.docs.konceptcore.StampSigilGeometry;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless pixel tests for the Java2D Zulip/email {@link KonceptBadge} — the cross-medium kind sigil
 * + the locked stamp pentagon with the STAMP's own identicon and compact provenance text (revised
 * ike-issues#638) and the appearance fixes (#742). Renders to {@link BufferedImage} only — no
 * display, no JavaFX.
 */
class KonceptBadgeTest {

    private static byte[] dummyIdenticon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3f, 0x76, 0xc4));
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasColorNear(BufferedImage img, Color target, int tolerance) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y), true);
                if (c.getAlpha() > 30
                        && Math.abs(c.getRed() - target.getRed()) <= tolerance
                        && Math.abs(c.getGreen() - target.getGreen()) <= tolerance
                        && Math.abs(c.getBlue() - target.getBlue()) <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void stampRendersPentagonThenIdenticonThenCompactText() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Active · 2024-06-23 14:30 · KEC", KonceptKind.STAMP));

        assertTrue(hasColorNear(img, Color.decode(StampSigilGeometry.COLOR), 20),
                "the stamp pentagon renders in the locked gray");
        assertTrue(hasColorNear(img, new Color(0x3f, 0x76, 0xc4), 12),
                "the STAMP's own identicon follows the pentagon — the sigil is never bare (revised #638)");
        assertTrue(hasColorNear(img, new Color(0x5A, 0x57, 0x50), 18),
                "the compact provenance text renders in dark gray");
        assertFalse(hasColorNear(img, new Color(0x2a, 0x5a, 0x8a), 14),
                "a stamp is a gray provenance chip, never a blue name-pill");
        assertTrue(img.getWidth() > (4 + 16 + 5 + 13 + 4) * KonceptBadge.SCALE,
                "the chip widens to hold the identicon and provenance text beside the pentagon");
    }

    @Test
    void stampWithoutIdenticonBytesFallsBackToPentagonPlusText() {
        BufferedImage img = decode(KonceptBadge.png(
                null, "Active · 2024-06-23 14:30 · KEC", KonceptKind.STAMP));

        assertTrue(hasColorNear(img, Color.decode(StampSigilGeometry.COLOR), 20),
                "the no-computable-identity fallback keeps the pentagon");
        assertTrue(hasColorNear(img, new Color(0x5A, 0x57, 0x50), 18),
                "the fallback keeps the compact provenance text");
    }

    @Test
    void descriptionPrependsItsAmberSigil() {
        BufferedImage img = decode(KonceptBadge.png(dummyIdenticon(), "Heart Failure", KonceptKind.DESCRIPTION));
        assertTrue(hasColorNear(img, Color.decode(KonceptKind.DESCRIPTION.colorHex()), 25),
                "a description prepends its amber D sigil");
    }

    @Test
    void conceptIsBareWithNoSigil() {
        BufferedImage img = decode(KonceptBadge.png(dummyIdenticon(), "Heart Failure", KonceptKind.CONCEPT));
        assertFalse(hasColorNear(img, Color.decode(KonceptKind.DESCRIPTION.colorHex()), 15),
                "a concept is bare — no amber sigil");
        assertFalse(hasColorNear(img, Color.decode(KonceptKind.PATTERN.colorHex()), 15),
                "a concept is bare — no violet sigil");
    }

    @Test
    void primitiveConceptLeadsWithItsCopulaAndWidensThePill() {
        BufferedImage bare = decode(KonceptBadge.png(
                dummyIdenticon(), "Heart Failure", KonceptKind.CONCEPT));
        BufferedImage primitive = decode(KonceptBadge.png(
                dummyIdenticon(), "Heart Failure", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, false));

        assertTrue(hasColorNear(primitive, Color.decode(KonceptStatus.PRIMITIVE.colorHex()), 25),
                "the ⊑ copula renders in the primitive grey (ike-issues#742 amendment, #862)");
        assertTrue(primitive.getWidth() > bare.getWidth(),
                "the leading cluster widens the pill");
    }

    @Test
    void multiParentAppendsTheForkInItsOwnBlue() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Array default", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, true));

        assertTrue(hasColorNear(img, Color.decode(KonceptStatus.PRIMITIVE.colorHex()), 25),
                "the copula keeps its status colour");
        assertTrue(hasColorNear(img, Color.decode(KonceptStatus.MULTI_PARENT_COLOR_HEX), 25),
                "the ⋎ fork renders in the multi-parent blue — the two-tone cluster");
    }

    @Test
    void definedConceptLeadsWithTheGreenEquivalence() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Sufficiently Defined", KonceptKind.CONCEPT,
                KonceptStatus.DEFINED, false));

        // ≡ is three hairline strokes — fully antialiased against the pill at this size, so no
        // pixel reaches the pure green. Green-dominance is the fingerprint instead: nothing else
        // in a concept badge (pill, label, blue dummy identicon) has a green-led channel.
        assertTrue(hasGreenDominantInk(img),
                "the ≡ copula renders in the defined green");
    }

    private static boolean hasGreenDominantInk(BufferedImage img) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                Color c = new Color(img.getRGB(x, y), true);
                if (c.getAlpha() > 30 && c.getGreen() > c.getRed() + 25
                        && c.getGreen() > c.getBlue() + 25) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void retiredConceptRendersStruckThroughInTheRetiredColour() {
        BufferedImage active = decode(KonceptBadge.png(
                dummyIdenticon(), "Old Concept", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, false, false));
        BufferedImage retired = decode(KonceptBadge.png(
                dummyIdenticon(), "Old Concept", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, false, true));

        assertTrue(hasColorNear(retired, Color.decode("#b00020"), 30),
                "the retired label renders in the retired colour (#742 parity, #862)");
        assertFalse(hasColorNear(retired, new Color(0x2a, 0x5a, 0x8a), 14),
                "…not the active blue");
        assertTrue(hasColorNear(active, new Color(0x2a, 0x5a, 0x8a), 25),
                "the active twin keeps the IKE blue");
    }

    @Test
    void pillCarriesTheFloatingBorder() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Heart Failure", KonceptKind.CONCEPT));

        assertTrue(hasColorNear(img, Color.decode("#c8d6e6"), 12),
                "the PNG floats in Zulip/email, so it carries the shared floating border (#862)");
    }

    @Test
    void bundledSmallCapsFaceResolvesFromKonceptCore() {
        assertTrue(KonceptBadge.smallCapsActive(),
                "the koncept-core jar ships the Alegreya Sans SC face and the compositor loads it"
                        + " — labels render as true small caps, not verbatim platform sans");
    }

    @Test
    void kindSigilExcludesTheStatusCluster() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Description Pattern", KonceptKind.PATTERN,
                KonceptStatus.PRIMITIVE, true));

        assertTrue(hasColorNear(img, Color.decode(KonceptKind.PATTERN.colorHex()), 25),
                "a pattern leads with its violet P");
        assertFalse(hasColorNear(img, Color.decode(KonceptStatus.MULTI_PARENT_COLOR_HEX), 15),
                "…never a status cluster — one leading mark, kind sigil XOR status");
    }
}
