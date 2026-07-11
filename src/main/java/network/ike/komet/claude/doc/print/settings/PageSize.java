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
 * A closed vocabulary of paper sizes, in portrait, expressed in typographic points
 * (1&nbsp;point&nbsp;=&nbsp;1/72&nbsp;inch — the unit JavaFX printing lays pages out in). Each
 * constant is an {@link EnumConceptBinding} carrying a pinned {@code publicId()}, so the choice can
 * become a Tinkar concept later; the pinned UUIDs are frozen and guarded by a freeze test.
 */
public enum PageSize implements EnumConceptBinding {

    /** US Letter — 8.5&nbsp;&times;&nbsp;11&nbsp;in. */
    @PublicIdAnnotation(@UuidAnnotation("bd326ef8-a439-59d3-9241-cecfea10ade3"))
    @RegularName("US Letter")
    LETTER(612, 792),

    /** ISO A4 — 210&nbsp;&times;&nbsp;297&nbsp;mm. */
    @PublicIdAnnotation(@UuidAnnotation("a44e53c1-e456-5627-b845-97c0a370bf70"))
    @RegularName("A4")
    A4(595, 842),

    /** US Legal — 8.5&nbsp;&times;&nbsp;14&nbsp;in. */
    @PublicIdAnnotation(@UuidAnnotation("a714e250-e3a7-5ebe-b7c5-7b59d913f8bf"))
    @RegularName("US Legal")
    LEGAL(612, 1008);

    private final double widthPoints;
    private final double heightPoints;

    PageSize(double widthPoints, double heightPoints) {
        this.widthPoints = widthPoints;
        this.heightPoints = heightPoints;
    }

    /**
     * The page width in points, in portrait orientation.
     *
     * @return the portrait width in points (1/72&nbsp;inch)
     */
    public double widthPoints() {
        return widthPoints;
    }

    /**
     * The page height in points, in portrait orientation.
     *
     * @return the portrait height in points (1/72&nbsp;inch)
     */
    public double heightPoints() {
        return heightPoints;
    }
}
