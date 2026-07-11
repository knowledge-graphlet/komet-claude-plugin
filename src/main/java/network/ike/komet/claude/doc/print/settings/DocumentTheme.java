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
 * The print document theme — the paper look applied to the paged rendering. A theme carries three
 * things: a CSS style class toggled on the print scene's root (so {@code print-theme.css} can style
 * paper, furniture, and rules), a base body font size in points, and a base prose font family (a
 * JavaFX logical family, so no bundled font is required). The prose family is threaded through the
 * Markdown renderer as a model attribute — the only way to give the body a serif face, since prose
 * fonts are not CSS-controlled. Each constant is an {@link EnumConceptBinding} with a pinned,
 * freeze-tested {@code publicId()}.
 */
public enum DocumentTheme implements EnumConceptBinding {

    /** A clean sans-serif page in the platform default family — matches the on-screen surface. */
    @PublicIdAnnotation(@UuidAnnotation("4bcbc690-4240-592f-b0c3-056470a68e01"))
    @RegularName("Default theme")
    DEFAULT("print-theme-default", 11, null),

    /** A serif "manuscript" page — a book-like body face at a slightly larger size. */
    @PublicIdAnnotation(@UuidAnnotation("1f426059-a645-575c-905c-06cdbc68d953"))
    @RegularName("Manuscript theme")
    MANUSCRIPT("print-theme-manuscript", 12, "Serif");

    private final String styleClass;
    private final double baseFontSize;
    private final String fontFamily;

    DocumentTheme(String styleClass, double baseFontSize, String fontFamily) {
        this.styleClass = styleClass;
        this.baseFontSize = baseFontSize;
        this.fontFamily = fontFamily;
    }

    /**
     * The CSS style class toggled on the print scene root so {@code print-theme.css} can style this
     * theme's paper, furniture, and rules.
     *
     * @return the theme's style class, never {@code null}
     */
    public String styleClass() {
        return styleClass;
    }

    /**
     * The base body font size in points (equivalently, the base size the block factory renders prose
     * at; JavaFX printing maps node points 1:1 to paper points).
     *
     * @return the base body font size in points
     */
    public double baseFontSize() {
        return baseFontSize;
    }

    /**
     * The base prose font family — a JavaFX logical family such as {@code "Serif"} — or {@code null}
     * to use the platform default. Code always renders monospaced regardless.
     *
     * @return the base prose font family, or {@code null} for the platform default
     */
    public String fontFamily() {
        return fontFamily;
    }
}
