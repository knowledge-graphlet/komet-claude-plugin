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
 * A closed vocabulary of page-margin presets, each a uniform inset on all four edges expressed in
 * typographic points (1&nbsp;point&nbsp;=&nbsp;1/72&nbsp;inch). Custom per-edge margins are a
 * deferred follow-on; the running head and footer are drawn <em>inside</em> the top and bottom
 * margins by the print engine. Each constant is an {@link EnumConceptBinding} with a pinned,
 * freeze-tested {@code publicId()}.
 */
public enum MarginPreset implements EnumConceptBinding {

    /** 0.5&nbsp;inch on every edge. */
    @PublicIdAnnotation(@UuidAnnotation("67887ae0-a7a4-5840-abca-0434caee122b"))
    @RegularName("Narrow margins")
    NARROW(36),

    /** 1&nbsp;inch on every edge. */
    @PublicIdAnnotation(@UuidAnnotation("76a0a876-afde-511d-9475-43d9bc105c24"))
    @RegularName("Normal margins")
    NORMAL(72),

    /** 1.5&nbsp;inch on every edge. */
    @PublicIdAnnotation(@UuidAnnotation("302018d4-c223-5fcc-8090-5f375e914342"))
    @RegularName("Wide margins")
    WIDE(108);

    private final double edgePoints;

    MarginPreset(double edgePoints) {
        this.edgePoints = edgePoints;
    }

    /**
     * The top margin in points.
     *
     * @return the top inset in points (1/72&nbsp;inch)
     */
    public double top() {
        return edgePoints;
    }

    /**
     * The right margin in points.
     *
     * @return the right inset in points (1/72&nbsp;inch)
     */
    public double right() {
        return edgePoints;
    }

    /**
     * The bottom margin in points.
     *
     * @return the bottom inset in points (1/72&nbsp;inch)
     */
    public double bottom() {
        return edgePoints;
    }

    /**
     * The left margin in points.
     *
     * @return the left inset in points (1/72&nbsp;inch)
     */
    public double left() {
        return edgePoints;
    }
}
