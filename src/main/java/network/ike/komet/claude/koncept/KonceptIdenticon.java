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

import com.sparrowwallet.toucan.LifeHash;
import com.sparrowwallet.toucan.LifeHashVersion;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Renders Komet's LifeHash identicon as PNG bytes (non-JavaFX) for the Zulip
 * Koncept badge. Byte-identical to {@code dev.ikm.komet.framework.Identicon} and
 * to {@code network.ike.docs.koncept.IdenticonRenderer} because it uses the same
 * library ({@code com.sparrowwallet:toucan}), the same algorithm version
 * ({@link LifeHashVersion#VERSION2}), and the same input (the Tinkar
 * {@code PublicId.idString()}).
 *
 * <p>Provisional copy living in the plugin so the Zulip identicon works without a
 * new staged plugin-layer dependency (toucan is already in the komet runtime).
 * To be folded into the shared {@code koncept-core} module (ike-issues#623), at
 * which point this class is deleted in favour of the shared {@code IdenticonRenderer}.
 */
public final class KonceptIdenticon {

    /** VERSION2's 32×32 native grid × 4 = 128×128, matching Komet's displayed size. */
    public static final int DISPLAY_MODULE_SIZE = 4;

    private KonceptIdenticon() {
    }

    /**
     * Renders the identicon for a Tinkar {@code idString} as PNG bytes.
     *
     * @param idString   the {@code PublicId.idString()} (the LifeHash input)
     * @param moduleSize the per-cell pixel size (&gt; 0); output is {@code 32 * moduleSize} square
     * @return the PNG-encoded identicon
     * @throws UncheckedIOException if PNG encoding fails
     */
    public static byte[] png(String idString, int moduleSize) {
        LifeHash.Image image =
                LifeHash.makeFromUTF8(idString, LifeHashVersion.VERSION2, moduleSize, false);
        BufferedImage buffered = LifeHash.getBufferedImage(image);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(buffered, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode identicon PNG for " + idString, e);
        }
    }

    /**
     * Renders the identicon downscaled to an arbitrary square pixel size — for an
     * inline-with-text identicon that should sit near the text height. Rendered from the
     * 32×32 native grid and bilinear-downscaled (at this size the cells are sub-pixel, so
     * a smooth colour signature reads better than blocky nearest-neighbour).
     *
     * @param idString the {@code PublicId.idString()} (the LifeHash input)
     * @param targetPx the output square size in pixels (&gt; 0)
     * @return the PNG-encoded identicon at {@code targetPx} square
     * @throws UncheckedIOException if PNG encoding fails
     */
    public static byte[] pngAt(String idString, int targetPx) {
        LifeHash.Image image =
                LifeHash.makeFromUTF8(idString, LifeHashVersion.VERSION2, 1, false);
        BufferedImage source = LifeHash.getBufferedImage(image); // 32×32 native
        BufferedImage scaled = new BufferedImage(targetPx, targetPx, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, targetPx, targetPx, null);
        g.dispose();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(scaled, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode scaled identicon PNG for " + idString, e);
        }
    }
}
