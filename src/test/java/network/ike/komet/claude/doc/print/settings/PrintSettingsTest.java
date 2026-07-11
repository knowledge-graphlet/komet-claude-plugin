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
package network.ike.komet.claude.doc.print.settings;

import dev.ikm.tinkar.common.binary.DecoderInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@link PrintSettings} value: its defaults, and that its {@code Encodable} encode/decode is
 * symmetric across every field (the exact bytes {@code KometPreferences.putObject} persists and
 * {@code getObject} restores). The round-trip walks the same envelope the framework writes — a
 * version and class name, then the field data — decoding the field data with {@code PrintSettings}'
 * own {@code @Decoder}.
 */
class PrintSettingsTest {

    @Test
    void defaultsAreManuscriptLetterNormalWithFooterPageNumber() {
        PrintSettings d = PrintSettings.DEFAULT;
        assertEquals(PageSize.LETTER, d.pageSize());
        assertEquals(PageOrientation.PORTRAIT, d.orientation());
        assertEquals(MarginPreset.NORMAL, d.margins());
        assertEquals(DocumentTheme.MANUSCRIPT, d.theme());
        assertEquals(FurnitureVisibility.SHOWN, d.runningHead());
        assertEquals(PageNumberPlacement.FOOTER_CENTER, d.pageNumbers());
    }

    @Test
    void defaultRoundTrips() {
        assertEquals(PrintSettings.DEFAULT, roundTrip(PrintSettings.DEFAULT));
    }

    @Test
    void everyFieldRoundTrips() {
        PrintSettings original = PrintSettings.DEFAULT
                .withPageSize(PageSize.A4)
                .withOrientation(PageOrientation.LANDSCAPE)
                .withMargins(MarginPreset.WIDE)
                .withTheme(DocumentTheme.DEFAULT)
                .withRunningHead(FurnitureVisibility.HIDDEN)
                .withPageNumbers(PageNumberPlacement.HEADER_RIGHT);
        assertEquals(original, roundTrip(original));
    }

    /**
     * Encodes to the whole preference byte array ({@code toBytes()} = version + class name + fields)
     * and decodes it back through the framework envelope, mirroring
     * {@code KometPreferences.getObject}'s {@code Encodable.decode(bytes)} without depending on the
     * modular service loader.
     */
    private static PrintSettings roundTrip(PrintSettings settings) {
        DecoderInput in = new DecoderInput(settings.toBytes());
        String className = in.readString(); // the framework writes the class name after the version
        assertEquals(PrintSettings.class.getName(), className);
        return PrintSettings.decode(in);
    }
}
