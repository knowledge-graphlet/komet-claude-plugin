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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Identities local to a block: what the store has passes through, what it lacks is made local and stays so. */
class BlockIdentitiesTest {

    /** A store of one concept, {@code stored}, numbered 7. */
    private static final Identities STORE = new Identities() {
        @Override
        public OptionalLong nid(String identifier) {
            return "stored".equals(identifier) ? OptionalLong.of(7) : OptionalLong.empty();
        }

        @Override
        public Optional<String> identifier(long nid) {
            return nid == 7 ? Optional.of("stored") : Optional.empty();
        }

        @Override
        public String label(long nid) {
            return nid == 7 ? "Stored concept" : "";
        }
    };

    @Test
    @DisplayName("What the store has resolves there and is not local")
    void storePassesThrough() {
        BlockIdentities ids = new BlockIdentities(STORE);
        assertEquals(OptionalLong.of(7), ids.nid("stored"));
        assertEquals(Optional.of("stored"), ids.identifier(7));
        assertEquals("Stored concept", ids.label(7));
        assertFalse(ids.isLocal(7));
        assertEquals(OptionalLong.empty(), ids.nid("rda-scan-now"), "an unknown symbol is not resolved by asking");
    }

    @Test
    @DisplayName("A symbol the store lacks is made local once, keeps its nid, its identifier and its written label")
    void localSymbolsAreStable() {
        BlockIdentities ids = new BlockIdentities(STORE);
        long scan = ids.local("rda-scan-now", "Scan now?").orElseThrow();
        long reopening = ids.local("rda-reopening-hazard", "Late reopening hazard").orElseThrow();
        assertNotEquals(scan, reopening);
        assertEquals(scan, ids.local("rda-scan-now", "another label").orElseThrow(), "the first label stands");
        assertEquals(OptionalLong.of(scan), ids.nid("rda-scan-now"), "once local, the symbol resolves");
        assertTrue(ids.isLocal(scan));
        assertEquals(Optional.of("rda-scan-now"), ids.identifier(scan));
        assertEquals("Scan now?", ids.label(scan));
        assertEquals("rda-usd", ids.label(ids.local("rda-usd", "").orElseThrow()), "no label: the symbol shows");
        assertEquals(List.of("rda-scan-now", "rda-reopening-hazard", "rda-usd"), ids.localIdentifiers());
    }

    @Test
    @DisplayName("A local nid lies above every nid a store issues, so it can never be mistaken for one")
    void localNidsAreOutOfEveryStore() {
        BlockIdentities ids = BlockIdentities.storeFree();
        long first = ids.local("a", "a").orElseThrow();
        long second = ids.local("b", "b").orElseThrow();
        assertEquals(Long.MAX_VALUE - 1, first);
        assertEquals(Long.MAX_VALUE - 2, second);
        assertTrue(first > Integer.MAX_VALUE && second > Integer.MAX_VALUE);
    }
}
