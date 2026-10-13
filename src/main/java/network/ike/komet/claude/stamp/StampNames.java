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
package network.ike.komet.claude.stamp;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.terms.KernelTerm;
import network.ike.komet.claude.koncept.ComponentText;

/**
 * Names for provenance surfaces. Content materialized from git stamps on the path derived from
 * its branch and in the module derived from its repository, which a Komet view's coordinates
 * rarely include; the view then resolves no description and the badge would show an
 * identifier. A provenance surface wants the name as recorded, whatever the view selects, so
 * this looks through the view first and then reads the component's own descriptions directly,
 * the latest committed one of any type on any path.
 */
final class StampNames {

    private StampNames() { }

    /**
     * The name of a component: the view's choice when it resolves one, else the latest
     * description the store holds for the component, else its identifier.
     *
     * @param view the view that would select the description
     * @param nid  the component's nid in the open store
     * @return the name; never a nid
     */
    static String name(ViewCalculator view, long nid) {
        String viewed = ComponentText.name(view, nid);
        if (!viewed.equals(ComponentText.identifier(nid))) {
            return viewed;
        }
        return recordedName(nid).orElse(viewed);
    }

    /** The text of the component's latest committed description, of any type and on any path. */
    static java.util.Optional<String> recordedName(long nid) {
        String best = null;
        long bestTime = Long.MIN_VALUE;
        for (SemanticEntity<SemanticEntityVersion> description : EntityService.get()
                .semanticsForComponentOfPattern(nid, KernelTerm.DESCRIPTION_PATTERN.nid()).toList()) {
            for (SemanticEntityVersion version : description.versions()) {
                if (version.time() == Long.MAX_VALUE || version.time() <= bestTime) {
                    continue;
                }
                for (Object value : version.fieldValues()) {
                    if (value instanceof String text && !text.isBlank()) {
                        best = text;
                        bestTime = version.time();
                        break;
                    }
                }
            }
        }
        return java.util.Optional.ofNullable(best);
    }
}
