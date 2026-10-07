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
package network.ike.komet.claude;

import dev.ikm.komet.terms.KometTerm;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.koncept.ComponentText;
import network.ike.komet.claude.koncept.NidFree;
import network.ike.komet.claude.tools.GraphTools;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text the assistant gives the model holds no nid ({@code IKE-Network/ike-issues#1170}).
 * Every graph tool is run against the IKE starter set in an ephemeral store, and what it
 * returns is examined; so is the request that opens a concept check. That text is what the
 * conversation file and the conversation journal keep.
 *
 * <p>Two views are used. Under the default view every concept has a description. Under
 * {@link NidFree#viewWithNoDescriptions()} none has, so every name a tool writes takes its
 * fallback — where a nid was written before. A tool that resolves its subject through the view
 * finds nothing under that view (a concept with no name is treated as absent), so the tools run
 * there are the two that take a UUID as given: {@code concept_semantics} and
 * {@code semantic_info}. Between them they write pattern names, field names, component values,
 * id sets, and definition trees.
 *
 * <p>The ephemeral store is not served over gRPC, so every tool takes its local path. In gRPC
 * mode the semantics come from tinkar-service already written as text, which this test does
 * not reach.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AssistantTextIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** Concepts of the starter data the tools are run on. */
    private static final List<EntityFacade> SUBJECTS = List.of(
            KernelTerm.ENGLISH_LANGUAGE, KernelTerm.LANGUAGE, KometTerm.PART_OF,
            KernelTerm.ROLE_TYPE, KernelTerm.DESCRIPTION_TYPE);

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
    void theToolsThatResolveAConceptWriteNoNid() {
        for (EntityFacade subject : SUBJECTS) {
            String uuid = uuidOf(subject);
            for (String tool : List.of("concept", "parents", "children", "ancestors", "descendants",
                    "axioms", "debug_concept", "concept_semantics")) {
                String text = run(view, tool, Map.of("id", uuid));
                assertRan(tool, subject, text);
                NidFree.assertNoNid(tool + " for " + subject.description(), text, subject.nid());
            }
        }
    }

    @Test
    void theIsATestWritesNoNid() {
        String text = run(view, "is_a", Map.of(
                "child", uuidOf(KernelTerm.ENGLISH_LANGUAGE), "parent", uuidOf(KernelTerm.LANGUAGE)));
        assertTrue(text.startsWith("YES — "), "English Language is a Language: " + text);
        NidFree.assertNoNid("is_a", text);

        text = run(view, "is_a", Map.of(
                "child", uuidOf(KernelTerm.LANGUAGE), "parent", uuidOf(KernelTerm.ENGLISH_LANGUAGE)));
        assertTrue(text.startsWith("NO — "), "Language is not an English Language: " + text);
        NidFree.assertNoNid("is_a", text);
    }

    @Test
    void theViewCoordinateIsDescribedWithoutANid() {
        String text = run(view, "view_info", Map.of());
        assertTrue(text.startsWith("Active view coordinate:\n"), text);
        NidFree.assertNoNid("view_info", text);
    }

    @Test
    void theAxiomsToolWritesTheDefinitionTree() {
        String text = run(view, "axioms", Map.of("id", uuidOf(KernelTerm.ENGLISH_LANGUAGE)));
        assertTrue(text.contains("Stated:\n   [0]"), "the stated definition is written as a tree: " + text);
        assertTrue(text.contains(ComponentText.name(view, KernelTerm.DEFINITION_ROOT.nid())),
                "the tree's root vertex is named: " + text);
        assertFalse(text.contains("DiTreeEntity"), "the tree is not the store's toString(): " + text);
    }

    @Test
    void theSemanticsOfAConceptAreWrittenWithoutANidWhenNothingHasADescription() {
        for (EntityFacade subject : SUBJECTS) {
            int attached = (int) EntityService.get().semanticsForComponent(subject.nid()).count();
            assertTrue(attached > 0, subject.description() + " has semantics attached");

            String text = run(undescribed, "concept_semantics", Map.of("id", uuidOf(subject)));
            assertTrue(text.endsWith("[" + attached + " semantics]"),
                    "every attached semantic is written, none is dropped for failing to render: " + text);
            assertTrue(text.startsWith(uuidOf(subject) + "\n"),
                    "the subject has no description under this view, so its UUID is the heading: " + text);
            assertTrue(text.contains("   [0]"), "the definition trees among them are written: " + text);
            NidFree.assertNoNid("concept_semantics for " + subject.description()
                    + " under a view with no descriptions", text, subject.nid());
        }
    }

    @Test
    void oneSemanticIsWrittenWithoutANidWhenNothingHasADescription() {
        for (EntityFacade subject : SUBJECTS) {
            for (SemanticEntity<SemanticEntityVersion> semantic
                    : EntityService.get().semanticsForComponent(subject.nid()).toList()) {
                int semanticNid = semantic.nid();
                String semanticUuid = ComponentText.identifier(semantic.nid());
                for (ViewCalculator calculator : List.of(view, undescribed)) {
                    String text = run(calculator, "semantic_info", Map.of("id", semanticUuid));
                    assertTrue(text.startsWith("Pattern: "), "the semantic is written: " + text);
                    NidFree.assertNoNid("semantic_info for a semantic of " + subject.description(),
                            text, semanticNid);
                    NidFree.assertNoNid("semantic_info for a semantic of " + subject.description(),
                            text, subject.nid());
                }
            }
        }
    }

    @Test
    void aCheckRequestNamesTheConceptByItsUuid() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        String uuid = uuidOf(KernelTerm.ENGLISH_LANGUAGE);

        String request = ClaudeCheckArea.checkRequest(view, nid, "Has a parent.");
        assertEquals("Concept under review: " + ComponentText.preferredName(view, nid) + "  [" + uuid + "].\n"
                + "Criterion: Has a parent.\n"
                + "Ground your assessment with the graph tools, then call report_result once.", request);
        NidFree.assertNoNid("the check request", request, nid);

        // The identifier in the request is one the graph tools accept: it resolves the concept.
        String resolved = run(view, "concept", Map.of("id", uuid));
        assertTrue(resolved.startsWith(ComponentText.fullyQualifiedName(view, nid)), resolved);

        String unnamed = ClaudeCheckArea.checkRequest(undescribed, nid, "Has a parent.");
        assertTrue(unnamed.startsWith("Concept under review: " + uuid + "  [" + uuid + "].\n"), unnamed);
        NidFree.assertNoNid("the check request under a view with no descriptions", unnamed, nid);
    }

    /** Runs one graph tool under a view. */
    private static String run(ViewCalculator calculator, String toolName, Map<String, Object> input) {
        for (AnthropicTool tool : new GraphTools(() -> calculator).tools()) {
            if (tool.name().equals(toolName)) {
                return tool.execute(input);
            }
        }
        throw new AssertionError("No graph tool named " + toolName);
    }

    /** Fails when a tool answered with an error or found nothing, so that an empty answer never passes. */
    private static void assertRan(String tool, EntityFacade subject, String text) {
        String what = tool + " for " + subject.description() + ": " + text;
        assertFalse(text.isBlank(), what);
        assertFalse(text.startsWith("Error"), what);
        assertFalse(text.startsWith("Tool error"), what);
        assertFalse(text.startsWith("No concept"), what);
        assertFalse(text.startsWith("No active"), what);
    }

    /** The UUID text identifies a component by: the same helper production uses. */
    private static String uuidOf(EntityFacade facade) {
        return ComponentText.identifier(facade.nid());
    }
}
