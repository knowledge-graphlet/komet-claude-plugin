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
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writes the Zulip/email PNG variant samples to {@code target/badge-samples/} — the #865
 * eyeball-review pieces for this medium: an active and a retired concept (same referent), a
 * status-clustered concept, and the STAMP chip. The assertions only prove the files were
 * written; the parity locks live in {@link KonceptBadgeTest}.
 */
class KonceptBadgeSampleWriterTest {

    private static byte[] sampleIdenticon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x3f, 0x76, 0xc4));
        g.fillRect(0, 0, 16, 16);
        g.setColor(new Color(0xd7, 0xa4, 0x3e));
        g.fillRect(4, 4, 8, 8);
        g.dispose();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void writeVariantSamples() throws Exception {
        Path dir = Path.of("target", "badge-samples");
        Files.createDirectories(dir);
        byte[] icon = sampleIdenticon();

        Files.write(dir.resolve("png-active.png"), KonceptBadge.png(
                icon, "Chronic disease (disorder)", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, false, false));
        Files.write(dir.resolve("png-retired.png"), KonceptBadge.png(
                icon, "Chronic disease (disorder)", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, false, true));
        Files.write(dir.resolve("png-multiparent.png"), KonceptBadge.png(
                icon, "Array default", KonceptKind.CONCEPT,
                KonceptStatus.PRIMITIVE, true, false));
        Files.write(dir.resolve("png-stamp.png"), KonceptBadge.png(
                icon, "Active · Inception · IKE Community", KonceptKind.STAMP,
                KonceptStatus.NONE, false, false));

        assertTrue(Files.size(dir.resolve("png-active.png")) > 0
                && Files.size(dir.resolve("png-retired.png")) > 0);
    }

    /** Decodes a written sample — a sanity hook, not a lock. */
    static BufferedImage decode(byte[] png) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(png));
    }
}
