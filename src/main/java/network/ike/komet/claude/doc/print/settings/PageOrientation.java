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
 * Page orientation — whether the {@link PageSize}'s portrait dimensions are used as given
 * ({@link #PORTRAIT}) or swapped ({@link #LANDSCAPE}). Each constant is an
 * {@link EnumConceptBinding} with a pinned, freeze-tested {@code publicId()}.
 */
public enum PageOrientation implements EnumConceptBinding {

    /** Taller than wide — the page size's dimensions as given. */
    @PublicIdAnnotation(@UuidAnnotation("703e1b62-f5c3-5451-8762-82c21a769c92"))
    @RegularName("Portrait")
    PORTRAIT,

    /** Wider than tall — the page size's width and height swapped. */
    @PublicIdAnnotation(@UuidAnnotation("eb7ec990-3447-5e9c-b6ee-d353f4182844"))
    @RegularName("Landscape")
    LANDSCAPE;

    /**
     * Whether this orientation swaps the page size's portrait width and height.
     *
     * @return {@code true} for {@link #LANDSCAPE}, {@code false} for {@link #PORTRAIT}
     */
    public boolean swapsDimensions() {
        return this == LANDSCAPE;
    }
}
