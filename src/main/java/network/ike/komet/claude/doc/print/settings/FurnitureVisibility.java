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
 * Whether a piece of page furniture (the running head, the footer) is drawn. Modelled as a
 * concept-identity enum rather than a bare {@code boolean} so the choice carries a pinned,
 * freeze-tested {@code publicId()} and reads self-documentingly in settings. Each constant is an
 * {@link EnumConceptBinding}.
 */
public enum FurnitureVisibility implements EnumConceptBinding {

    /** The furniture is drawn on every page. */
    @PublicIdAnnotation(@UuidAnnotation("81d6f9f8-06f1-509b-9d42-f0f5c44368c4"))
    @RegularName("Shown")
    SHOWN,

    /** The furniture is omitted. */
    @PublicIdAnnotation(@UuidAnnotation("d6645aca-ab37-57f1-af38-2e08d8cd4123"))
    @RegularName("Hidden")
    HIDDEN;

    /**
     * Whether this value means the furniture is drawn.
     *
     * @return {@code true} for {@link #SHOWN}, {@code false} for {@link #HIDDEN}
     */
    public boolean shown() {
        return this == SHOWN;
    }
}
