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
package network.ike.komet.claude.complexconcept;

import dev.ikm.tinkar.common.service.PrimitiveData;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.komet.claude.anf.model.AnfStatement;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.complexconcept.model.ComplexConcept;
import network.ike.komet.claude.complexconcept.model.ComplexConcept.Expansion;
import network.ike.komet.claude.complexconcept.model.ComplexConceptBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A block's spec built into a specification tree through the builder, every local symbol a proxy
 * under its derived public id, as a proposal writes it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComplexConceptTextStoreTest {

    @BeforeAll
    static void startStore() {
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
    }

    @AfterAll
    static void stopStore() {
        PrimitiveData.stop();
    }

    @Test
    void theDecisionPointBuildsAsAFocusedSpecificationWithThreePreconditions() {
        Spec spec = ComplexConceptText.parse(ComplexConceptTextTest.DECISION, BlockIdentities.storeFree()).spec().orElseThrow();
        ComplexConceptBuilder builder = ComplexConceptText.builder(spec, ComplexConceptText::facade);
        assertEquals(List.of(), builder.completeness());
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
        ComplexConcept.Root root = builder.root();
        assertTrue(root.definition().orElseThrow().expression().isPresent(), "a primitive subtype of its focus");
        Expansion.Focused focused = (Expansion.Focused) root.expansion().orElseThrow();
        assertEquals(ComplexConceptText.facade(spec.definitionParent().orElseThrow()).nid(), focused.focus().orElseThrow().nid());
        assertEquals(3, focused.prerequisites().size());
        ComplexConcept.Prerequisite time = focused.prerequisites().get(1);
        assertEquals(IkeConcept.PRECONDITION_ASSOCIATION.nid(), time.association().orElseThrow().nid());
        AnfStatement.Attribute.Measure value = ((AnfStatement.Circumstance.Performance)
                time.statement().orElseThrow().root().circumstance().orElseThrow()).result().orElseThrow();
        assertEquals(new BigDecimal("2.0"), value.lowerBound().orElseThrow());
        assertEquals(new BigDecimal("2.0"), value.upperBound().orElseThrow());
        ComplexConcept.Prerequisite symptoms = focused.prerequisites().get(2);
        AnfStatement.Attribute.Measure absent = ((AnfStatement.Circumstance.Performance)
                symptoms.statement().orElseThrow().root().circumstance().orElseThrow()).result().orElseThrow();
        assertEquals(BigDecimal.valueOf(0.0), absent.upperBound().orElseThrow(), "absence is the interval from zero to zero");
    }

    @Test
    void thePanelBuildsAsAUniformAndSpecification() {
        Spec spec = ComplexConceptText.parse(ComplexConceptTextTest.PERRLA, BlockIdentities.storeFree()).spec().orElseThrow();
        ComplexConceptBuilder builder = ComplexConceptText.builder(spec, ComplexConceptText::facade);
        assertEquals(List.of(), builder.completeness());
        assertEquals(List.of(), ComplexConceptBuilder.wellFormedness(builder.asDiTree()));
        Expansion.Uniform uniform = (Expansion.Uniform) builder.root().expansion().orElseThrow();
        assertEquals(IkeConcept.AND_CONJUNCTION.nid(), uniform.conjunction().orElseThrow().nid());
        assertEquals(4, uniform.members().size());
    }

    @Test
    void aLocalSymbolProposesUnderOneDerivedIdentityWhereverItIsWritten() {
        Spec spec = ComplexConceptText.parse(ComplexConceptTextTest.DECISION, BlockIdentities.storeFree()).spec().orElseThrow();
        ComplexConceptText.Focused focused = (ComplexConceptText.Focused) spec.shape().orElseThrow();
        assertEquals(ComplexConceptText.localPublicId(focused.prerequisites().get(0).unit().orElseThrow()),
                ComplexConceptText.localPublicId(focused.prerequisites().get(2).unit().orElseThrow()));
        assertEquals(ComplexConceptText.facade(spec.definitionParent().orElseThrow()).nid(), ComplexConceptText.facade(focused.focus()).nid());
    }
}
