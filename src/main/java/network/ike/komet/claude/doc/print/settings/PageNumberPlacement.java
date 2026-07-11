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

import dev.ikm.tinkar.common.bind.EnumConceptBinding;
import dev.ikm.tinkar.common.bind.annotations.names.RegularName;
import dev.ikm.tinkar.common.bind.annotations.publicid.PublicIdAnnotation;
import dev.ikm.tinkar.common.bind.annotations.publicid.UuidAnnotation;

/**
 * Where the page number is placed within the page furniture. {@link #NONE} suppresses it; the
 * remaining constants name a band (header or footer) and a horizontal position. Each constant is an
 * {@link EnumConceptBinding} with a pinned, freeze-tested {@code publicId()}.
 */
public enum PageNumberPlacement implements EnumConceptBinding {

    /** No page number is drawn. */
    @PublicIdAnnotation(@UuidAnnotation("1b46a001-86e2-5d72-8a7e-edc11e9275b9"))
    @RegularName("No page number")
    NONE(false, false),

    /** In the running head, right-aligned. */
    @PublicIdAnnotation(@UuidAnnotation("3974b5cb-5d21-5fa5-ab44-e1060bf8fcff"))
    @RegularName("Header, right")
    HEADER_RIGHT(true, false),

    /** In the footer, centered. */
    @PublicIdAnnotation(@UuidAnnotation("97368440-8362-5626-94e9-149a9c5de877"))
    @RegularName("Footer, centered")
    FOOTER_CENTER(false, true),

    /** In the footer, right-aligned. */
    @PublicIdAnnotation(@UuidAnnotation("0c27123f-7767-5e80-8bf8-54a724d741ea"))
    @RegularName("Footer, right")
    FOOTER_RIGHT(false, true);

    private final boolean inHeader;
    private final boolean inFooter;

    PageNumberPlacement(boolean inHeader, boolean inFooter) {
        this.inHeader = inHeader;
        this.inFooter = inFooter;
    }

    /**
     * Whether the page number is drawn in the running head.
     *
     * @return {@code true} when the number belongs in the header band
     */
    public boolean inHeader() {
        return inHeader;
    }

    /**
     * Whether the page number is drawn in the footer.
     *
     * @return {@code true} when the number belongs in the footer band
     */
    public boolean inFooter() {
        return inFooter;
    }

    /**
     * Whether a page number is drawn at all.
     *
     * @return {@code false} only for {@link #NONE}
     */
    public boolean showsNumber() {
        return this != NONE;
    }
}
