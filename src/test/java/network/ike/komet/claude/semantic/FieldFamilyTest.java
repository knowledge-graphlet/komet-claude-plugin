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

import dev.ikm.tinkar.component.FieldDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the datatype → handling family mapping that drives the {@code emit_semantic} schema and
 * value parsing — the load-bearing classification that a component reference is grounded while a
 * literal is not, and that the generic component datatype pins no kind.
 */
class FieldFamilyTest {

    @Test
    void conceptAndSemanticFieldsPinTheirKind() {
        assertEquals(FieldFamily.CONCEPT_REF, FieldFamily.of(FieldDataType.CONCEPT));
        assertEquals(ComponentSlot.Kind.CONCEPT, FieldFamily.of(FieldDataType.CONCEPT).requiredKind());
        assertEquals(FieldFamily.SEMANTIC_REF, FieldFamily.of(FieldDataType.SEMANTIC));
        assertEquals(ComponentSlot.Kind.SEMANTIC, FieldFamily.of(FieldDataType.SEMANTIC).requiredKind());
        assertEquals(FieldFamily.PATTERN_REF, FieldFamily.of(FieldDataType.PATTERN));
        assertEquals(ComponentSlot.Kind.PATTERN, FieldFamily.of(FieldDataType.PATTERN).requiredKind());
    }

    @Test
    void genericComponentPinsNoKind() {
        FieldFamily family = FieldFamily.of(FieldDataType.IDENTIFIED_THING);
        assertEquals(FieldFamily.COMPONENT_REF, family);
        assertTrue(family.isComponentReference());
        assertNull(family.requiredKind(), "the generic component datatype must accept any kind");
    }

    @Test
    void collectionsAreComponentCollections() {
        assertEquals(FieldFamily.COMPONENT_SET, FieldFamily.of(FieldDataType.COMPONENT_ID_SET));
        assertEquals(FieldFamily.COMPONENT_LIST, FieldFamily.of(FieldDataType.COMPONENT_ID_LIST));
        assertTrue(FieldFamily.of(FieldDataType.COMPONENT_ID_SET).isComponentCollection());
        assertTrue(FieldFamily.of(FieldDataType.COMPONENT_ID_LIST).isComponentCollection());
    }

    @Test
    void literalsAreLiteralsAndNotReferences() {
        assertEquals(FieldFamily.STRING, FieldFamily.of(FieldDataType.STRING));
        assertEquals(FieldFamily.INTEGER, FieldFamily.of(FieldDataType.INTEGER));
        assertEquals(FieldFamily.INTEGER, FieldFamily.of(FieldDataType.LONG));
        assertEquals(FieldFamily.FLOAT, FieldFamily.of(FieldDataType.FLOAT));
        assertEquals(FieldFamily.FLOAT, FieldFamily.of(FieldDataType.DECIMAL));
        assertEquals(FieldFamily.BOOLEAN, FieldFamily.of(FieldDataType.BOOLEAN));
        for (FieldDataType literal : new FieldDataType[]{FieldDataType.STRING, FieldDataType.INTEGER,
                FieldDataType.LONG, FieldDataType.FLOAT, FieldDataType.DECIMAL, FieldDataType.BOOLEAN}) {
            FieldFamily family = FieldFamily.of(literal);
            assertTrue(family.isLiteral(), literal + " must be a literal");
            assertFalse(family.isComponentReference(), literal + " must not be a reference");
        }
    }

    @Test
    void structuredAndUnknownAreDeferred() {
        assertEquals(FieldFamily.DEFERRED, FieldFamily.of(FieldDataType.DITREE));
        assertEquals(FieldFamily.DEFERRED, FieldFamily.of(FieldDataType.DIGRAPH));
        assertEquals(FieldFamily.DEFERRED, FieldFamily.of(null));
    }
}
