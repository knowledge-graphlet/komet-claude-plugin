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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Identities on the live store: a written identifier, the comma-joined UUIDs of a public id as
 * the ANF block writes them, one UUID, or an SCTID, resolves to the nid of a component the store
 * holds; a nid writes as its public id's UUIDs and the name the view gives it. Any component
 * resolves, not only a concept, since a node's statements are semantics.
 */
public final class ViewIdentities implements Identities {

    private final ViewCalculator view;

    /**
     * @param view the view that names components; its stamp coordinate does not gate
     *             resolution, since a block may name what the view does not show
     */
    public ViewIdentities(ViewCalculator view) {
        this.view = view;
    }

    @Override
    public OptionalLong nid(String identifier) {
        List<UUID> uuids = uuids(identifier);
        if (uuids.isEmpty()) {
            return OptionalLong.empty();
        }
        long nid;
        try {
            nid = EntityService.get().nidForPublicId(PublicIds.of(uuids.toArray(UUID[]::new)));
        } catch (RuntimeException unresolvable) {
            return OptionalLong.empty();
        }
        // A nid may be minted for an identifier the store does not hold; only a component that
        // exists resolves.
        return EntityHandle.get(nid).entity().isPresent() ? OptionalLong.of(nid) : OptionalLong.empty();
    }

    @Override
    public Optional<String> identifier(long nid) {
        return ComponentText.publicIdKey(nid);
    }

    @Override
    public String label(long nid) {
        return view == null ? ComponentText.identifier(nid) : ComponentText.name(view, nid);
    }

    /** The UUIDs an identifier names: comma-joined UUIDs, one UUID, or an SCTID; none when it is none of these. */
    static List<UUID> uuids(String identifier) {
        List<UUID> uuids = new ArrayList<>();
        if (identifier == null || identifier.isBlank()) {
            return uuids;
        }
        for (String part : identifier.strip().split(",")) {
            String text = part.strip();
            if (text.isEmpty()) {
                continue;
            }
            try {
                uuids.add(UUID.fromString(text));
            } catch (IllegalArgumentException notUuid) {
                try {
                    uuids.add(UuidUtil.fromSNOMED(text));
                } catch (RuntimeException notSctid) {
                    return List.of();
                }
            }
        }
        return uuids;
    }
}
