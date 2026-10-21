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
package network.ike.komet.claude.stamp.print;

import javafx.scene.text.Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * The fonts a stamped document is set in, on screen and in print: Noto Serif for the body and
 * Noto Sans Mono for verbatim text, bundled with the plugin under the SIL Open Font License
 * (see {@code fonts/OFL.txt}) so the JavaFX Page layout view and the Typst writer shape the
 * same glyphs at the same sizes ({@code dev-document-surface-pages}: "Noto fonts"). JavaFX
 * loads them from the plugin's resources once; typst reads them from a folder the plugin
 * extracts them into and names with {@code --font-path}.
 */
public final class DocumentFonts {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentFonts.class);

    /** The body family. */
    public static final String SERIF = "Noto Serif";
    /** The verbatim family. */
    public static final String MONO = "Noto Sans Mono";

    private static final String RESOURCE_FOLDER = "/network/ike/komet/claude/fonts/";
    private static final List<String> FILES = List.of("NotoSerif-Regular.ttf", "NotoSerif-Bold.ttf", "NotoSerif-Italic.ttf",
            "NotoSansMono-Regular.ttf", "OFL.txt");

    private static volatile boolean loaded;

    private DocumentFonts() { }

    /**
     * Loads the bundled fonts into JavaFX, once; a family name then resolves to them in any
     * font request. Safe to call before the toolkit is up: a failure is logged and the family
     * names fall back to whatever the platform has.
     *
     * @return whether every bundled face loaded
     */
    public static boolean loadForJavaFx() {
        if (loaded) {
            return true;
        }
        boolean all = true;
        for (String file : FILES) {
            if (!file.endsWith(".ttf")) {
                continue;
            }
            try (InputStream in = DocumentFonts.class.getResourceAsStream(RESOURCE_FOLDER + file)) {
                if (in == null) {
                    LOG.warn("Bundled font {} is missing", file);
                    all = false;
                    continue;
                }
                Font font = Font.loadFont(in, 12);
                if (font == null) {
                    LOG.warn("Bundled font {} did not load", file);
                    all = false;
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Bundled font {} could not be read", file, e);
                all = false;
            }
        }
        loaded = all;
        return all;
    }

    /**
     * Extracts the bundled fonts into a folder for typst's {@code --font-path}.
     *
     * @param folder the folder to write into; created when missing
     * @return the folder
     * @throws IOException if a font cannot be written
     */
    public static Path extractTo(Path folder) throws IOException {
        Files.createDirectories(folder);
        for (String file : FILES) {
            try (InputStream in = DocumentFonts.class.getResourceAsStream(RESOURCE_FOLDER + file)) {
                if (in == null) {
                    throw new IOException("bundled font " + file + " is missing");
                }
                Files.copy(in, folder.resolve(file), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return folder;
    }
}
