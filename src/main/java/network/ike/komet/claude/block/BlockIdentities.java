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
package network.ike.komet.claude.block;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Identities over a store's, admitting identities local to the block being read. A block
 * written for the documentation build names its concepts by symbols the store has never seen
 * ({@code @id=rda-scan-now}); the store-free reader there holds them as strings, but the live
 * surface reads into a property graph, whose concept-valued properties are nids. So a symbol
 * the store lacks is given a nid local to the block: the same symbol gets the same nid while
 * the block is read, references between its lines hold, and the block rolls back and writes
 * again with its symbols intact. The label the symbol was written with is what it shows as.
 *
 * <p>A local nid is read-only by construction. It lies above every nid a store issues, where
 * no layout encodes a component, so a tree carrying one cannot be serialized: the store refuses
 * the nid before anything is written. To store such a tree, its concepts are created first and
 * the block rewritten with their identities.
 */
public final class BlockIdentities implements Identities {
    /** The first local nid, counting down: above every nid a 64-bit or sequential layout issues. */
    static final long FIRST_LOCAL = Long.MAX_VALUE - 1;

    private static final Identities NO_STORE = new Identities() {
        @Override
        public OptionalLong nid(String identifier) {
            return OptionalLong.empty();
        }

        @Override
        public Optional<String> identifier(long nid) {
            return Optional.empty();
        }

        @Override
        public String label(long nid) {
            return "";
        }
    };

    private final Identities store;
    private final Map<String, Long> nids = new LinkedHashMap<>();
    private final Map<Long, String> identifiers = new HashMap<>();
    private final Map<Long, String> labels = new HashMap<>();

    /** Local identities over a store's. */
    public BlockIdentities(Identities store) {
        this.store = store;
    }

    /** Local identities over no store at all: every identifier is local, as the documentation build reads. */
    public static BlockIdentities storeFree() {
        return new BlockIdentities(NO_STORE);
    }

    @Override
    public OptionalLong nid(String identifier) {
        OptionalLong stored = store.nid(identifier);
        if (stored.isPresent()) {
            return stored;
        }
        Long local = nids.get(identifier);
        return local == null ? OptionalLong.empty() : OptionalLong.of(local);
    }

    @Override
    public OptionalLong local(String identifier, String label) {
        Long nid = nids.get(identifier);
        if (nid == null) {
            nid = FIRST_LOCAL - nids.size();
            nids.put(identifier, nid);
            identifiers.put(nid, identifier);
            labels.put(nid, label == null || label.isBlank() ? identifier : label);
        }
        return OptionalLong.of(nid);
    }

    @Override
    public boolean isLocal(long nid) {
        return identifiers.containsKey(nid);
    }

    @Override
    public Optional<String> identifier(long nid) {
        String local = identifiers.get(nid);
        return local != null ? Optional.of(local) : store.identifier(nid);
    }

    @Override
    public String label(long nid) {
        String local = labels.get(nid);
        return local != null ? local : store.label(nid);
    }

    /** The identifiers made local so far, in the order they were first read. */
    public List<String> localIdentifiers() {
        return List.copyOf(nids.keySet());
    }
}
