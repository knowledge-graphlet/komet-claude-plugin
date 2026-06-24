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
 * + the locked stamp pentagon with its compact provenance text (ike-issues#638) and the appearance
 * fixes (#742). Renders to {@link BufferedImage} only — no display, no JavaFX.
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
    void stampRendersTheGrayPentagonPlusCompactTextNoName() {
        BufferedImage img = decode(KonceptBadge.png(
                dummyIdenticon(), "Active · 2024-06-23 14:30 · KEC", KonceptKind.STAMP));

        assertTrue(hasColorNear(img, Color.decode(StampSigilGeometry.COLOR), 20),
                "the stamp pentagon renders in the locked gray");
        assertTrue(hasColorNear(img, new Color(0x5A, 0x57, 0x50), 18),
                "the compact provenance text renders in dark gray");
        assertFalse(hasColorNear(img, new Color(0x2a, 0x5a, 0x8a), 14),
                "a stamp is a gray provenance chip, never a blue name-pill");
        assertTrue(img.getWidth() > (4 + 16 + 4) * KonceptBadge.SCALE,
                "the chip widens to hold the provenance text beside the pentagon");
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
}
