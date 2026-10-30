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
package network.ike.komet.claude.anf;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import network.ike.komet.claude.koncept.ComponentText;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ephemeral store the ANF adaptor layer tests run against, and the concepts they mint in it: a
 * concept here is a proxy whose nid the store assigns, with no descriptions written, so the
 * {@link AnfProjection.Grounding} answers labels from the names the test minted.
 */
final class AnfTestStore {

    /** Fixed type-5 namespace for the test concepts. */
    static final UUID NAMESPACE = UUID.fromString("3f2c1a7e-9b4d-5e6f-8a1b-2c3d4e5f6a7b");

    // nid-keyed, never public-id-keyed: nids are the store's own identities.
    private static final Map<Long, String> NAMES = new ConcurrentHashMap<>();

    private AnfTestStore() {
    }

    static void start() {
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
    }

    static void stop() {
        PrimitiveData.stop();
    }

    /** Mints a test concept by name; the same name is the same concept. */
    static EntityProxy.Concept concept(String name) {
        EntityProxy.Concept concept = EntityProxy.Concept.make(name, UuidT5Generator.get(NAMESPACE, "anf:" + name));
        NAMES.put(concept.nid(), name);
        return concept;
    }

    /** The grounded value of a minted concept. */
    static AnfValue.Grounded grounded(ConceptFacade concept) {
        return grounded(concept.nid());
    }

    private static AnfValue.Grounded grounded(long nid) {
        String name = NAMES.get(nid);
        if (name == null) {
            return null;
        }
        PublicId publicId = PrimitiveData.publicId(nid);
        return new AnfValue.Grounded(nid, ComponentText.publicIdKey(publicId), publicId.leastUuid().toString(), name);
    }

    /** The grounding that answers the minted concepts, and nothing else. */
    static AnfProjection.Grounding grounding() {
        return concept -> grounded(concept.nid());
    }

    /** The {@code [anf]} codec's resolver over the minted concepts, by the written public id. */
    static AnfAdoc.ConceptResolver resolver() {
        return identifier -> {
            PublicId publicId = PublicIds.of(identifier.split(","));
            if (!PrimitiveData.get().hasPublicId(publicId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(grounded(PrimitiveData.nid(publicId)));
        };
    }
}
