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

import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.TinkarTerm;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link SemanticGrounding} against the Tinkar starter data — the live,
 * datastore-backed counterpart to the store-free {@link SemanticToolsTest}. Loads the starter
 * dataset into an ephemeral store and confirms the kind-aware grounding the unit tests faked:
 * a concept grounds as {@link ComponentSlot.Kind#CONCEPT}, a pattern as {@code PATTERN}, a
 * semantic as {@code SEMANTIC} <em>without</em> being canonicalized up to its concept, a field's
 * required kind is enforced, and an unresolvable id never grounds.
 *
 * <p>The starter set carries only the Tinkar metadata (no SNOMED/LOINC/RxNorm yet), so these
 * tests ground its stable metadata components — not clinical concepts.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SemanticGroundingIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/tinkar-starter-data-reasoned-pb.zip");

    private ViewCalculator view;
    private Grounder grounder;

    @BeforeAll
    void setupDatabase() {
        assertTrue(PB_STARTER_DATA.exists(),
                "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath()
                        + " (copied by maven-dependency-plugin in process-test-resources).");
        // No CachingService.clearAll(): the CachingService provider list is intentionally NOT
        // registered (no META-INF/services file for it), since clearAll() would drive
        // ExecutorProvider.reset() and its concrete-Controller lookup. Each *IT runs in its own
        // fork (reuseForks=false), so no cross-class cache reset is needed. Mirrors
        // complex-clause-plugin's classpath IT setup.
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertTrue(count > 0, "Should load entities from the starter-data protobuf file");
        view = Calculators.View.Default();
        grounder = SemanticGrounding.forView(view);
    }

    @AfterAll
    void teardownDatabase() {
        PrimitiveData.stop();
    }

    @Test
    void groundsAConceptAsConcept() {
        Optional<ComponentSlot.Grounded> grounded = grounder.ground(uuidOf(TinkarTerm.ENGLISH_LANGUAGE), null);
        assertTrue(grounded.isPresent(), "English Language should ground");
        assertEquals(ComponentSlot.Kind.CONCEPT, grounded.get().kind());
        assertEquals(TinkarTerm.ENGLISH_LANGUAGE.nid(), grounded.get().nid());
    }

    @Test
    void groundsAPatternAsPattern() {
        Optional<ComponentSlot.Grounded> grounded = grounder.ground(uuidOf(TinkarTerm.DESCRIPTION_PATTERN), null);
        assertTrue(grounded.isPresent(), "Description Pattern should ground");
        assertEquals(ComponentSlot.Kind.PATTERN, grounded.get().kind());
        assertEquals(TinkarTerm.DESCRIPTION_PATTERN.nid(), grounded.get().nid());
    }

    @Test
    void groundsASemanticAsSemanticAndDoesNotWalkUpToItsConcept() {
        int semanticNid = aDescriptionSemanticNid();
        Optional<ComponentSlot.Grounded> grounded = grounder.ground(uuidOf(semanticNid), null);
        assertTrue(grounded.isPresent(), "a description semantic should ground");
        assertEquals(ComponentSlot.Kind.SEMANTIC, grounded.get().kind());
        assertEquals(semanticNid, grounded.get().nid(),
                "a semantic must NOT be canonicalized up to its enclosing concept");
    }

    @Test
    void aConceptOnlyFieldRejectsASemanticReferent() {
        int semanticNid = aDescriptionSemanticNid();
        assertTrue(grounder.ground(uuidOf(semanticNid), ComponentSlot.Kind.CONCEPT).isEmpty(),
                "a semantic must not satisfy a concept-only field");
    }

    @Test
    void aConceptOnlyFieldAcceptsAConceptButNotUnderASemanticConstraint() {
        assertTrue(grounder.ground(uuidOf(TinkarTerm.ENGLISH_LANGUAGE), ComponentSlot.Kind.CONCEPT).isPresent());
        assertTrue(grounder.ground(uuidOf(TinkarTerm.ENGLISH_LANGUAGE), ComponentSlot.Kind.SEMANTIC).isEmpty(),
                "a concept must not satisfy a semantic-only field");
    }

    @Test
    void anUnresolvableIdNeverGrounds() {
        // A well-formed UUID that resolves to no entity, and a malformed identifier.
        assertTrue(grounder.ground("00000000-0000-0000-0000-000000000001", null).isEmpty());
        assertTrue(grounder.ground("not-an-identifier", null).isEmpty());
    }

    /** The nid of a description semantic on English Language — a stable non-concept component to ground. */
    private static int aDescriptionSemanticNid() {
        int[] descriptionNids = EntityService.get().semanticNidsForComponentOfPattern(
                TinkarTerm.ENGLISH_LANGUAGE.nid(), TinkarTerm.DESCRIPTION_PATTERN.nid());
        assertTrue(descriptionNids.length > 0, "English Language must carry description semantics");
        return descriptionNids[0];
    }

    private static String uuidOf(EntityFacade facade) {
        return facade.publicId().asUuidArray()[0].toString();
    }

    private static String uuidOf(int nid) {
        return PrimitiveData.publicId(nid).asUuidArray()[0].toString();
    }
}
