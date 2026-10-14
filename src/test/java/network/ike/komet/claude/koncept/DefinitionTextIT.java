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
package network.ike.komet.claude.koncept;

import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-backed tests for {@link DefinitionText} against the IKE starter set in an ephemeral
 * store ({@code IKE-Network/ike-issues#1170}): the layout of the text, the names in it, and
 * that no nid is in it — for a tree built here with every kind of property value, and for the
 * stated and inferred definitions the starter data holds.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefinitionTextIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** Concepts of the starter data whose stated and inferred definitions are rendered. */
    private static final List<EntityFacade> DEFINED = List.of(
            KernelConcept.ENGLISH_LANGUAGE, KernelConcept.LANGUAGE, KometConcept.PART_OF,
            KernelConcept.ROLE_TYPE, KernelConcept.NECESSARY_SET, KernelConcept.DESCRIPTION_TYPE);

    private ViewCalculator view;
    private ViewCalculator undescribed;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(),
                "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath()
                        + " (copied by maven-dependency-plugin in process-test-resources).");
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
        view = Calculators.View.Default();
        undescribed = NidFree.viewWithNoDescriptions();
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void aTreeIsWrittenOneVertexPerLineWithItsPropertiesBeneathInNameOrder() {
        DiTreeEntity tree = aTreeWithEveryKindOfPropertyValue();

        // Role's two properties are written in the order of their names, whatever order the
        // vertex holds them in. The structure is named; each component it holds is identified.
        List<String> roleProperties = new ArrayList<>(List.of(
                name(KernelConcept.ROLE_TYPE) + ": " + identified(KometConcept.PART_OF),
                name(KernelConcept.ROLE_OPERATOR) + ": " + identified(KernelConcept.EXISTENTIAL_RESTRICTION)));
        roleProperties.sort(null);

        String expected = "   [0]➞[1] " + name(KernelConcept.DEFINITION_ROOT) + "\n"
                + "     [1]➞[2] " + name(KernelConcept.NECESSARY_SET) + "\n"
                + "       [2]➞[3,4,5] " + name(KernelConcept.AND) + "\n"
                + "         [3] " + name(KernelConcept.CONCEPT_REFERENCE) + ": " + identified(KernelConcept.LANGUAGE) + "\n"
                + "         [4] " + name(KernelConcept.ROLE) + "\n"
                + "            •" + roleProperties.get(0) + "\n"
                + "            •" + roleProperties.get(1) + "\n"
                + "         [5] " + name(KernelConcept.PROPERTY_SET) + "\n"
                + "            •" + name(KernelConcept.PROPERTY_SEQUENCE) + ": ["
                + identified(KometConcept.PART_OF) + ", " + identified(KernelConcept.ROLE_TYPE) + "]\n";

        String text = DefinitionText.tree(tree, view);
        assertEquals(expected, text);
        NidFree.assertNoNid("the tree", text);
    }

    @Test
    void theStoreWritesNidsForTheSameTreeAndThisTextDoesNot() {
        // The reason DefinitionText exists. Under a view with no descriptions every name here
        // is a UUID; an id list is a list of UUIDs. DiTreeEntity.toString() writes the nid of
        // every element of an id list whatever the view — the assertion on it fails when
        // tinkar-core stops, which is the moment to reconsider this class.
        DiTreeEntity tree = aTreeWithEveryKindOfPropertyValue();
        long partOf = KometConcept.PART_OF.nid();

        assertTrue(tree.toString().contains("<" + partOf + ">"),
                "tinkar-core writes the nid of each element of an id list");

        String text = DefinitionText.tree(tree, undescribed);
        NidFree.assertNoNid("the tree under a view with no descriptions", text, partOf);
        assertTrue(text.contains("[" + ComponentText.identified(undescribed, partOf) + ", "
                        + ComponentText.identified(undescribed, KernelConcept.ROLE_TYPE.nid()) + "]"),
                "an id list is the list of its components, identified; with no description, named by UUID");
        assertTrue(text.startsWith("   [0]➞[1] " + ComponentText.identifier(KernelConcept.DEFINITION_ROOT.nid()) + "\n"),
                "a vertex whose meaning has no description is named by the meaning's UUID");
    }

    @Test
    void theDefinitionsOfTheStarterDataAreWrittenWithoutANid() {
        int rendered = 0;
        for (EntityFacade concept : DEFINED) {
            for (ViewCalculator calculator : List.of(view, undescribed)) {
                for (Latest<DiTreeEntity> definition : List.of(
                        calculator.logicCalculator().getStatedLogicalExpressionForEntity(concept.nid(), calculator.stampCalculator()),
                        calculator.logicCalculator().getInferredLogicalExpressionForEntity(concept.nid(), calculator.stampCalculator()))) {
                    if (!definition.isPresent()) {
                        continue;
                    }
                    DiTreeEntity tree = definition.get();
                    String text = DefinitionText.tree(tree, calculator);
                    NidFree.assertNoNid("the definition of " + concept.description(), text);
                    // tinkar-core's own text has the same lines between "DiTreeEntity{" and "}":
                    // one per vertex and one per property that is not the vertex's own value.
                    assertEquals(tree.toString().lines().count() - 2, text.lines().count(),
                            "the layout follows DiTreeEntity.toString() line for line");
                    assertEquals(tree.vertexCount(),
                            text.lines().filter(line -> line.stripLeading().startsWith("[")).count(),
                            "one line per vertex");
                    rendered++;
                }
            }
        }
        assertTrue(rendered >= DEFINED.size(), "the starter data holds definitions for these concepts");
    }

    @Test
    void everyComponentADefinitionRefersToCarriesItsIdentifierAndTheStructureDoesNot() {
        String text = DefinitionText.tree(aTreeWithEveryKindOfPropertyValue(), view);
        for (EntityFacade referred : List.of(KernelConcept.LANGUAGE, KometConcept.PART_OF,
                KernelConcept.EXISTENTIAL_RESTRICTION, KernelConcept.ROLE_TYPE)) {
            assertTrue(text.contains(ComponentText.typedIdentifier(view, referred.nid())),
                    referred.description() + " is identified in\n" + text);
        }
        for (EntityFacade structure : List.of(KernelConcept.DEFINITION_ROOT, KernelConcept.NECESSARY_SET,
                KernelConcept.AND, KernelConcept.CONCEPT_REFERENCE, KernelConcept.ROLE, KernelConcept.PROPERTY_SET,
                KernelConcept.ROLE_OPERATOR, KernelConcept.PROPERTY_SEQUENCE)) {
            assertFalse(text.contains(ComponentText.typedIdentifier(view, structure.nid())),
                    structure.description() + " is a word of the structure and stays bare in\n" + text);
        }
    }

    @Test
    void aVertexOnItsOwnIsOneLineOfNames() {
        EntityVertex reference = EntityVertex.make(KernelConcept.CONCEPT_REFERENCE);
        setProperties(reference, KernelConcept.CONCEPT_REFERENCE, KernelConcept.LANGUAGE);
        assertEquals(name(KernelConcept.CONCEPT_REFERENCE) + ": " + identified(KernelConcept.LANGUAGE),
                DefinitionText.vertex(reference, view));

        EntityVertex bare = EntityVertex.make(KernelConcept.AND);
        assertEquals(name(KernelConcept.AND), DefinitionText.vertex(bare, view));

        EntityVertex role = EntityVertex.make(KernelConcept.ROLE);
        setProperties(role, KernelConcept.ROLE_TYPE, KometConcept.PART_OF);
        String text = DefinitionText.vertex(role, view);
        assertEquals(name(KernelConcept.ROLE) + " {" + name(KernelConcept.ROLE_TYPE) + "=" + identified(KometConcept.PART_OF) + "}",
                text);
        NidFree.assertNoNid("the vertex", text, KometConcept.PART_OF.nid());
    }

    /**
     * A small definition: a root, a necessary set, and under an And a concept reference (a
     * component held under the vertex's own meaning), a role (two component properties), and a
     * vertex that holds an id list.
     */
    private static DiTreeEntity aTreeWithEveryKindOfPropertyValue() {
        EntityVertex root = EntityVertex.make(KernelConcept.DEFINITION_ROOT);
        EntityVertex necessarySet = EntityVertex.make(KernelConcept.NECESSARY_SET);
        EntityVertex and = EntityVertex.make(KernelConcept.AND);

        EntityVertex reference = EntityVertex.make(KernelConcept.CONCEPT_REFERENCE);
        setProperties(reference, KernelConcept.CONCEPT_REFERENCE, KernelConcept.LANGUAGE);

        EntityVertex role = EntityVertex.make(KernelConcept.ROLE);
        MutableLongObjectMap<Object> roleProperties = LongObjectMaps.mutable.empty();
        roleProperties.put(KernelConcept.ROLE_TYPE.nid(), KometConcept.PART_OF);
        roleProperties.put(KernelConcept.ROLE_OPERATOR.nid(), KernelConcept.EXISTENTIAL_RESTRICTION);
        role.setProperties(roleProperties);

        EntityVertex propertySet = EntityVertex.make(KernelConcept.PROPERTY_SET);
        setProperties(propertySet, KernelConcept.PROPERTY_SEQUENCE,
                LongIds.list.of(KometConcept.PART_OF.nid(), KernelConcept.ROLE_TYPE.nid()));

        DiTreeEntity.Builder builder = DiTreeEntity.builder();
        builder.setRoot(root);
        builder.addEdge(necessarySet, root);
        builder.addEdge(and, necessarySet);
        builder.addEdge(reference, and);
        builder.addEdge(role, and);
        builder.addEdge(propertySet, and);
        return builder.build();
    }

    /** Gives a vertex one property. */
    private static void setProperties(EntityVertex vertex, EntityFacade key, Object value) {
        MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
        properties.put(key.nid(), value);
        vertex.setProperties(properties);
    }

    private String name(EntityFacade component) {
        return ComponentText.name(view, component.nid());
    }

    private String identified(EntityFacade component) {
        return ComponentText.identified(view, component.nid());
    }
}
