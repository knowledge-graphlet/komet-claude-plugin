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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Frameless identicon renderings that avoid Zulip's inline-image grey frame by not
 * being an image at all:
 *
 * <ul>
 *   <li>{@link #latex(byte[], int)} — a KaTeX {@code \colorbox} matrix (math, no image
 *       frame). Depends on Zulip's KaTeX allowing {@code \colorbox} with hex colours.</li>
 *   <li>{@link #squares(byte[], int)} — a grid of coloured-square Unicode emoji
 *       (🟥🟦🟪…), which always render but quantise to a ~9-colour palette.</li>
 * </ul>
 *
 * Both sample the LifeHash identicon PNG into an {@code n × n} colour grid.
 */
public final class KonceptMatrix {

    /** The nine coloured-square emoji and their approximate sRGB centroids. */
    private static final String[] SQUARE_EMOJI = {
            "🟥", // 🟥 red
            "🟧", // 🟧 orange
            "🟨", // 🟨 yellow
            "🟩", // 🟩 green
            "🟦", // 🟦 blue
            "🟪", // 🟪 purple
            "🟫", // 🟫 brown
            "⬛",       // ⬛ black
            "⬜",       // ⬜ white
    };
    private static final int[][] SQUARE_RGB = {
            {0xDD, 0x2E, 0x44}, {0xF4, 0x90, 0x0C}, {0xFD, 0xCB, 0x58},
            {0x78, 0xB1, 0x59}, {0x55, 0xAC, 0xEE}, {0xAA, 0x8E, 0xD6},
            {0xC1, 0x69, 0x4F}, {0x31, 0x37, 0x3D}, {0xE6, 0xE7, 0xE8},
    };

    private KonceptMatrix() {
    }

    /**
     * Renders the identicon as a KaTeX coloured matrix (display math).
     *
     * @param identiconPng the identicon PNG bytes
     * @param n            the grid resolution (n×n cells); keep small (KaTeX load)
     * @return a {@code $$…$$} KaTeX string
     */
    public static String latex(byte[] identiconPng, int n) {
        BufferedImage img = decode(identiconPng);
        StringBuilder sb = new StringBuilder("$$\\begin{matrix}");
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int rgb = sample(img, r, c, n) & 0xFFFFFF;
                sb.append("\\colorbox{").append(String.format("#%06X", rgb)).append("}{\\phantom{X}}");
                if (c < n - 1) {
                    sb.append(" & ");
                }
            }
            if (r < n - 1) {
                sb.append(" \\\\ ");
            }
        }
        return sb.append("\\end{matrix}$$").toString();
    }

    /**
     * Renders the identicon as a grid of coloured-square Unicode emoji (newline rows).
     *
     * @param identiconPng the identicon PNG bytes
     * @param n            the grid resolution (n×n cells)
     * @return the emoji grid, rows separated by {@code \n}
     */
    public static String squares(byte[] identiconPng, int n) {
        BufferedImage img = decode(identiconPng);
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                sb.append(nearestSquare(sample(img, r, c, n)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static BufferedImage decode(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to decode identicon", e);
        }
    }

    private static int sample(BufferedImage img, int r, int c, int n) {
        int px = Math.min(img.getWidth() - 1, c * img.getWidth() / n + img.getWidth() / (2 * n));
        int py = Math.min(img.getHeight() - 1, r * img.getHeight() / n + img.getHeight() / (2 * n));
        return img.getRGB(px, py);
    }

    private static String nearestSquare(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int best = 0;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < SQUARE_RGB.length; i++) {
            long dr = red - SQUARE_RGB[i][0];
            long dg = green - SQUARE_RGB[i][1];
            long db = blue - SQUARE_RGB[i][2];
            long d = dr * dr + dg * dg + db * db;
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return SQUARE_EMOJI[best];
    }
}
