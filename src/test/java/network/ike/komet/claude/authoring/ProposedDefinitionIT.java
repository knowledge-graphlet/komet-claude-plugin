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
package network.ike.komet.claude.authoring;

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.koncept.ConceptDefinition;
import network.ike.komet.claude.koncept.ConceptDefinition.Role;
import network.ike.komet.claude.koncept.DefinitionText;
import network.ike.komet.claude.authoring.ProposedDefinition.Built;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The definition tree a {@code koncept-definition} block builds, against the starter data
 * ({@code IKE-Network/ike-issues#1292}). The tree must have the shape a stored definition has, so
 * it is read back with {@link ConceptDefinition} — the reader of stored definitions — and must
 * yield the supertypes and role groups the block stated. The block renderer previews this tree
 * and {@code propose_concept} stores it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProposedDefinitionIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** An identifier no store holds. */
    private static final String ABSENT = "k:uuid=00000000-0000-4000-8000-000000000001[Invented filler]";

    private ViewCalculator view;

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
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void aProposalBuildsTheTreeAStoredDefinitionWouldHave() {
        String block = "proposed: A proposed language\n"
                + "necessary\n"
                + "  " + token(KernelConcept.LANGUAGE) + "\n"
                + "  " + token(KernelConcept.ROLE_TYPE) + " = " + token(KernelConcept.ENGLISH_LANGUAGE) + "\n"
                + "  group\n"
                + "    " + token(KernelConcept.DESCRIPTION_TYPE) + " = " + token(KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE) + "\n"
                + "    " + token(KernelConcept.LANGUAGE_CONCEPT_NID_FOR_DESCRIPTION) + " = " + token(KernelConcept.ENGLISH_LANGUAGE) + "\n"
                + "gap: nothing names this yet\n";

        Built built = ProposedDefinition.build(ProposedDefinition.parse(block));

        assertNotNull(built.tree());
        assertEquals(List.of("nothing names this yet"), built.gaps());
        ConceptDefinition definition = ConceptDefinition.of(built.tree()).orElseThrow();
        assertFalse(definition.defined(), "a necessary set is primitive");
        assertArrayEquals(new long[]{KernelConcept.LANGUAGE.nid()}, definition.supertypes());
        assertEquals(List.of(new Role(KernelConcept.ROLE_TYPE.nid(), KernelConcept.ENGLISH_LANGUAGE.nid())),
                definition.ungroupedRoles());
        assertEquals(List.of(List.of(
                new Role(KernelConcept.DESCRIPTION_TYPE.nid(), KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE.nid()),
                new Role(KernelConcept.LANGUAGE_CONCEPT_NID_FOR_DESCRIPTION.nid(), KernelConcept.ENGLISH_LANGUAGE.nid()))),
                definition.roleGroups());

        String text = DefinitionText.tree(built.tree(), view);
        assertTrue(text.contains(ComponentText.identified(view, KernelConcept.LANGUAGE.nid())),
                "the proposal reads as a stored definition does:\n" + text);
    }

    @Test
    void aSufficientSetIsADefinedDefinition() {
        Built built = ProposedDefinition.build(ProposedDefinition.parse(
                "sufficient\n  " + token(KernelConcept.LANGUAGE) + "\n"));

        assertTrue(ConceptDefinition.of(built.tree()).orElseThrow().defined());
    }

    @Test
    void anIdentifierTheStoreDoesNotHoldIsAGapAndNotANode() {
        String block = "necessary\n"
                + "  " + token(KernelConcept.LANGUAGE) + "\n"
                + "  " + ABSENT.replace("filler", "parent") + "\n"
                + "  group\n"
                + "    " + token(KernelConcept.DESCRIPTION_TYPE) + " = " + ABSENT + "\n";

        Built built = ProposedDefinition.build(ProposedDefinition.parse(block));

        ConceptDefinition definition = ConceptDefinition.of(built.tree()).orElseThrow();
        assertArrayEquals(new long[]{KernelConcept.LANGUAGE.nid()}, definition.supertypes(),
                "the unresolved supertype is left out");
        assertTrue(definition.roleGroups().isEmpty(), "a group whose every role is a gap is left out");
        assertEquals(2, built.gaps().size(), "one gap per part left out: " + built.gaps());
        assertTrue(built.gaps().get(0).startsWith("Invented parent"), built.gaps().get(0));
        assertTrue(built.gaps().get(1).contains("Invented filler")
                && built.gaps().get(1).contains("filler is not in this knowledge base"), built.gaps().get(1));
    }

    @Test
    void aBlockInWhichNothingResolvesHasNoTreeOnlyGaps() {
        Built built = ProposedDefinition.build(
                ProposedDefinition.parse("necessary\n  " + ABSENT + "\n"));

        assertNull(built.tree());
        assertEquals(1, built.gaps().size());
    }

    /** A UUID token for a starter-data concept, as the assistant would write it. */
    private String token(EntityFacade concept) {
        return ComponentText.badge(view, concept.nid()).orElseThrow();
    }
}
