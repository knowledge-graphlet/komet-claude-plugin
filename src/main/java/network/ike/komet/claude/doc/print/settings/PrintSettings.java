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

import dev.ikm.tinkar.common.binary.Decoder;
import dev.ikm.tinkar.common.binary.DecoderInput;
import dev.ikm.tinkar.common.binary.Encodable;
import dev.ikm.tinkar.common.binary.Encoder;
import dev.ikm.tinkar.common.binary.EncoderOutput;

import java.util.Objects;

/**
 * The whole, persisted settings value for the paged {@link network.ike.komet.claude.doc.DocumentSurface}
 * print rendering — page size, orientation, margins, theme, and page-furniture choices. Persisted as
 * one {@link Encodable} value (the {@link dev.ikm.komet.layout.LayoutOverrides} idiom), stored under
 * {@link DocumentSurfaceSettingKeys#PRINT_SETTINGS} via the
 * {@link dev.ikm.komet.layout.settings.ControlSettings} seam — never field-by-field.
 *
 * <p>Every field is a concept-identity value enum, so there are no string constants; the record is
 * immutable and copied with the {@code with…} methods when a single choice changes. Enum constants
 * are persisted by {@code name()}; the constant names are therefore part of the wire format and must
 * not be renamed without a migration (their pinned {@code publicId()}s are frozen by a freeze test).
 *
 * <p>There is no separate "footer visible" field: the footer's only content is the page number, so
 * {@link #pageNumbers()} fully determines it (a {@code FOOTER_*} placement draws a footer, otherwise
 * none). The header, by contrast, carries the independent running head, so {@link #runningHead()}
 * remains a distinct toggle.
 *
 * @param pageSize    the paper size (portrait dimensions)
 * @param orientation portrait or landscape
 * @param margins     the four-edge margin preset
 * @param theme       the print document theme (paper look, base font size, prose family)
 * @param runningHead whether the running head (conversation name) is drawn
 * @param pageNumbers where the page number is placed (or {@code NONE})
 */
public record PrintSettings(
        PageSize pageSize,
        PageOrientation orientation,
        MarginPreset margins,
        DocumentTheme theme,
        FurnitureVisibility runningHead,
        PageNumberPlacement pageNumbers
) implements Encodable {

    /**
     * The default print settings: US&nbsp;Letter, portrait, normal (1&nbsp;inch) margins, the serif
     * manuscript theme, with a running head and a centered footer page number.
     */
    public static final PrintSettings DEFAULT = new PrintSettings(
            PageSize.LETTER,
            PageOrientation.PORTRAIT,
            MarginPreset.NORMAL,
            DocumentTheme.MANUSCRIPT,
            FurnitureVisibility.SHOWN,
            PageNumberPlacement.FOOTER_CENTER);

    /**
     * Validates that no choice is {@code null} — every field is a closed vocabulary with a defined
     * default, so a null would be a programming error, not a user state.
     */
    public PrintSettings {
        Objects.requireNonNull(pageSize, "pageSize is null");
        Objects.requireNonNull(orientation, "orientation is null");
        Objects.requireNonNull(margins, "margins is null");
        Objects.requireNonNull(theme, "theme is null");
        Objects.requireNonNull(runningHead, "runningHead is null");
        Objects.requireNonNull(pageNumbers, "pageNumbers is null");
    }

    /**
     * @param value the new page size
     * @return a copy with the page size replaced
     */
    public PrintSettings withPageSize(PageSize value) {
        return new PrintSettings(value, orientation, margins, theme, runningHead, pageNumbers);
    }

    /**
     * @param value the new orientation
     * @return a copy with the orientation replaced
     */
    public PrintSettings withOrientation(PageOrientation value) {
        return new PrintSettings(pageSize, value, margins, theme, runningHead, pageNumbers);
    }

    /**
     * @param value the new margin preset
     * @return a copy with the margins replaced
     */
    public PrintSettings withMargins(MarginPreset value) {
        return new PrintSettings(pageSize, orientation, value, theme, runningHead, pageNumbers);
    }

    /**
     * @param value the new theme
     * @return a copy with the theme replaced
     */
    public PrintSettings withTheme(DocumentTheme value) {
        return new PrintSettings(pageSize, orientation, margins, value, runningHead, pageNumbers);
    }

    /**
     * @param value the new running-head visibility
     * @return a copy with the running-head visibility replaced
     */
    public PrintSettings withRunningHead(FurnitureVisibility value) {
        return new PrintSettings(pageSize, orientation, margins, theme, value, pageNumbers);
    }

    /**
     * @param value the new page-number placement
     * @return a copy with the page-number placement replaced
     */
    public PrintSettings withPageNumbers(PageNumberPlacement value) {
        return new PrintSettings(pageSize, orientation, margins, theme, runningHead, value);
    }

    /**
     * Encodes the settings as the six enum constant names, in field order. The {@link Encodable}
     * framework writes the version and class-name envelope; this writes only the field data.
     *
     * @param out the output to write the field data to
     */
    @Override
    @Encoder
    public void encode(EncoderOutput out) {
        out.writeString(pageSize.name());
        out.writeString(orientation.name());
        out.writeString(margins.name());
        out.writeString(theme.name());
        out.writeString(runningHead.name());
        out.writeString(pageNumbers.name());
    }

    /**
     * Reconstructs a {@code PrintSettings} from its encoded field data.
     *
     * @param in the decoder input positioned at this object's field data
     * @return the decoded settings
     */
    @Decoder
    public static PrintSettings decode(DecoderInput in) {
        Encodable.checkVersion(in);
        return new PrintSettings(
                PageSize.valueOf(in.readString()),
                PageOrientation.valueOf(in.readString()),
                MarginPreset.valueOf(in.readString()),
                DocumentTheme.valueOf(in.readString()),
                FurnitureVisibility.valueOf(in.readString()),
                PageNumberPlacement.valueOf(in.readString()));
    }
}
