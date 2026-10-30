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

import dev.ikm.tinkar.common.id.Nid;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.util.uuid.UuidUtil;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.SemanticEntity;
import network.ike.komet.claude.koncept.ComponentText;

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

    private static final long NONE = Integer.MIN_VALUE;

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
    public Optional<ComponentValue.Grounded> ground(String id, ComponentValue.Kind requiredKind) {
        long nid = resolveRaw(id);
        if (Nid.isNone(nid)) {
            return Optional.empty();
        }
        ComponentValue.Kind kind;
        try {
            kind = kindOf(nid);
        } catch (RuntimeException notResolvable) {
            return Optional.empty();
        }
        if (kind == null) {
            // A stamp or other non-referenceable entity; not a valid component field value.
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
        return build(nid, kind);
    }

    /** Resolves an SCTID, UUID, or comma-joined PublicId array to a nid, WITHOUT walking up to a concept. */
    private static long resolveRaw(String id) {
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
        // A lookup mints nothing: an id the store does not hold grounds nothing, where resolving
        // it would give it a nid, and a store keyed by pattern refuses that (IKE-Network/ike-issues#1313).
        if (!dev.ikm.tinkar.common.service.PrimitiveData.get().hasPublicId(PublicIds.of(uuid))) {
            return NONE;
        }
        return EntityService.get().nidForPublicId(PublicIds.of(uuid));
    }

    /** Resolves a comma-joined {@code PublicId} UUID array (the durable round-trip key) to a nid. */
    private static long resolvePublicIdArray(String array) {
        String[] parts = array.split(",");
        List<UUID> uuids = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                uuids.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException notUuid) {
                return NONE;
            }
        }
        if (uuids.isEmpty()
                || !dev.ikm.tinkar.common.service.PrimitiveData.get().hasPublicId(PublicIds.of(uuids.toArray(new UUID[0])))) {
            return NONE;
        }
        return EntityService.get().nidForPublicId(PublicIds.of(uuids.toArray(new UUID[0])));
    }

    /**
     * The component kind of a nid from the entity hierarchy, or {@code null} when the entity is
     * neither concept, semantic, nor pattern. Throws when the nid resolves to no entity.
     */
    private static ComponentValue.Kind kindOf(long nid) {
        Entity<?> entity = EntityHandle.getEntityOrThrow(nid);
        if (entity instanceof ConceptEntity) {
            return ComponentValue.Kind.CONCEPT;
        }
        if (entity instanceof SemanticEntity) {
            return ComponentValue.Kind.SEMANTIC;
        }
        if (entity instanceof PatternEntity) {
            return ComponentValue.Kind.PATTERN;
        }
        return null;
    }

    /**
     * Builds the grounded value of a component. Its key and its identifier come from the public
     * id — the durable, coordinate-independent round-trip key — and never from the nid
     * ({@code IKE-Network/ike-issues#1170}).
     *
     * @return the grounded value, or empty when the store has no public id for the component
     */
    private Optional<ComponentValue.Grounded> build(long nid, ComponentValue.Kind kind) {
        Optional<PublicId> publicId = ComponentText.publicId(nid);
        if (publicId.isEmpty()) {
            return Optional.empty();
        }
        String label = ComponentText.fullyQualifiedName(view, nid);
        String identifier = publicId.get().leastUuid().toString();
        return Optional.of(new ComponentValue.Grounded(
                nid, kind, ComponentText.publicIdKey(publicId.get()), identifier, label));
    }
}
