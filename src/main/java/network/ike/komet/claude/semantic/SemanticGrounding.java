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
package network.ike.komet.claude.semantic;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.SemanticEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The live {@link Grounder}: resolves a component identifier against a {@link ViewCalculator} and
 * grounds it only when it exists, is active in the view, and matches the field's required kind.
 *
 * <p>It deliberately does <strong>not</strong> canonicalize a semantic reference up to its
 * enclosing concept — unlike the narrative lift's concept-only {@code GraphTools.resolveConcept},
 * which walks a description up to its concept. A semantic field must ground to the semantic
 * itself, so the generalized lift resolves the component as given and reports its actual kind
 * (concept, semantic, or pattern) from the entity hierarchy.
 */
public final class SemanticGrounding implements Grounder {

    private static final int NONE = Integer.MIN_VALUE;

    private final ViewCalculator view;

    private SemanticGrounding(ViewCalculator view) {
        this.view = Objects.requireNonNull(view, "view");
    }

    /**
     * Creates a grounder bound to a view.
     *
     * @param view the view that resolves and active-gates components; must not be null
     * @return a live grounder
     */
    public static Grounder forView(ViewCalculator view) {
        return new SemanticGrounding(view);
    }

    @Override
    public Optional<ComponentSlot.Grounded> ground(String id, ComponentSlot.Kind requiredKind) {
        int nid = resolveRaw(id);
        if (nid == NONE) {
            return Optional.empty();
        }
        ComponentSlot.Kind kind;
        try {
            kind = kindOf(nid);
        } catch (RuntimeException notResolvable) {
            return Optional.empty();
        }
        if (kind == null) {
            // A stamp or other non-referenceable entity; not a valid component slot filler.
            return Optional.empty();
        }
        // Active-only grounding: a retired (or absent) component never grounds.
        if (!view.stampCalculator().isLatestActive(nid)) {
            return Optional.empty();
        }
        // Kind enforcement: a field constrained to a kind rejects a referent of another kind.
        if (requiredKind != null && kind != requiredKind) {
            return Optional.empty();
        }
        return Optional.of(build(nid, kind));
    }

    /** Resolves an SCTID, UUID, or comma-joined PublicId array to a nid, WITHOUT walking up to a concept. */
    private static int resolveRaw(String id) {
        if (id == null) {
            return NONE;
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return NONE;
        }
        if (trimmed.indexOf(',') >= 0) {
            return resolvePublicIdArray(trimmed);
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(trimmed);
        } catch (IllegalArgumentException notUuid) {
            try {
                uuid = UuidUtil.fromSNOMED(trimmed);
            } catch (RuntimeException notSctid) {
                return NONE;
            }
        }
        return EntityService.get().nidForPublicId(PublicIds.of(uuid));
    }

    /** Resolves a comma-joined {@code PublicId} UUID array (the durable round-trip key) to a nid. */
    private static int resolvePublicIdArray(String array) {
        String[] parts = array.split(",");
        List<UUID> uuids = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                uuids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException notUuid) {
                return NONE;
            }
        }
        if (uuids.isEmpty()) {
            return NONE;
        }
        return EntityService.get().nidForPublicId(PublicIds.of(uuids.toArray(new UUID[0])));
    }

    /**
     * The component kind of a nid from the entity hierarchy, or {@code null} when the entity is
     * neither concept, semantic, nor pattern. Throws when the nid resolves to no entity.
     */
    private static ComponentSlot.Kind kindOf(int nid) {
        Entity<?> entity = EntityHandle.getEntityOrThrow(nid);
        if (entity instanceof ConceptEntity) {
            return ComponentSlot.Kind.CONCEPT;
        }
        if (entity instanceof SemanticEntity) {
            return ComponentSlot.Kind.SEMANTIC;
        }
        if (entity instanceof PatternEntity) {
            return ComponentSlot.Kind.PATTERN;
        }
        return null;
    }

    private ComponentSlot.Grounded build(int nid, ComponentSlot.Kind kind) {
        String label = view.getFullyQualifiedNameText(nid)
                .orElseGet(() -> view.getPreferredDescriptionTextWithFallbackOrNid(nid));
        UUID[] uuids = PrimitiveData.publicId(nid).asUuidArray();
        String publicId = publicIdKey(uuids, nid);
        String identifier = uuids.length > 0 ? uuids[0].toString() : "nid=" + nid;
        return new ComponentSlot.Grounded(nid, kind, publicId, identifier, label);
    }

    /** The comma-joined PublicId UUID array — the durable, coordinate-independent round-trip key. */
    private static String publicIdKey(UUID[] uuids, int nid) {
        if (uuids.length == 0) {
            return "nid=" + nid;
        }
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < uuids.length; i++) {
            if (i > 0) {
                key.append(',');
            }
            key.append(uuids[i]);
        }
        return key.toString();
    }
}
