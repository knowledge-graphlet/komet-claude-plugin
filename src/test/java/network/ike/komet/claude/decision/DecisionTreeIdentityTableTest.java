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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.VertexIdentityTable;
import dev.ikm.tinkar.entity.graph.adaptor.axiom.IdentityRule.Successors;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.komet.claude.block.BlockIdentities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The decision tree's vertex identity rules, and that the write path finds them for a tree read from its block. */
class DecisionTreeIdentityTableTest {

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
    void theWritePathFindsTheTableForATreeReadFromItsBlock() throws Exception {
        String text;
        try (InputStream in = DecisionTreeIdentityTableTest.class.getResourceAsStream("/decision/rda-scan-tree.adoc")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String body = text.substring(text.indexOf("----\n") + 5, text.lastIndexOf("----"));
        DiTreeEntity tree = DecisionTreeText.parse(body, DecisionTerms.fromBindings(), BlockIdentities.storeFree())
                .tree().orElseThrow().source();
        assertInstanceOf(DecisionTreeIdentityTable.class, VertexIdentityTable.forTree(tree).orElseThrow(),
                "every vertex of the tree, its nodes and its measures, is covered");
    }

    @Test
    void nodesAreToldApartByTheirConceptAndCostsByTheirUnit() {
        for (DecisionTreeIdentityTable.Meaning node : List.of(DecisionTreeIdentityTable.Meaning.DECISION,
                DecisionTreeIdentityTable.Meaning.ALTERNATIVE, DecisionTreeIdentityTable.Meaning.CHANCE,
                DecisionTreeIdentityTable.Meaning.EVENT, DecisionTreeIdentityTable.Meaning.OUTCOME)) {
            assertEquals(Successors.OPERANDS, DecisionTreeIdentityTable.rule(node).successors(), node.name());
            assertEquals(List.of(KernelConcept.CONCEPT_REFERENCE), DecisionTreeIdentityTable.rule(node).identifying().castToList());
        }
        assertEquals(List.of(), DecisionTreeIdentityTable.rule(DecisionTreeIdentityTable.Meaning.PROBABILITY).identifying().castToList(),
                "a node's one probability is identified by its meaning alone");
        assertEquals(List.of(KernelConcept.UNIT_OF_MEASURE),
                DecisionTreeIdentityTable.rule(DecisionTreeIdentityTable.Meaning.COUNT).identifying().castToList());
        assertEquals(List.of(KernelConcept.CONCEPT_REFERENCE),
                DecisionTreeIdentityTable.rule(DecisionTreeIdentityTable.Meaning.PARAMETER).identifying().castToList());
    }
}
