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

import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-backed tests for {@link ComponentText} against the Tinkar starter data in an ephemeral
 * store ({@code IKE-Network/ike-issues#1170}). They hold the helper to its two rules — a name
 * that does not resolve is the component's UUID, and a component the store has no public id for
 * is stated as unidentified — and to the one behind both: no text form is ever a nid.
 *
 * <p>Two views are used. Under the default view every concept of the starter data has an
 * English description. Under {@link NidFree#viewWithNoDescriptions()} none has, so every name
 * takes its fallback — the case in which the calculator methods this helper replaces answer
 * with the nid.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentTextIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    /**
     * A nid the store has assigned to no component: the ephemeral store numbers components
     * upward from the bottom of the int range, so the top of the range is never reached.
     */
    private static final int UNASSIGNED_NID = Integer.MAX_VALUE - 1;

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
    void theIdentifierIsAUuidThatResolvesBackToTheSameComponent() {
        for (int nid : new int[]{KernelTerm.ENGLISH_LANGUAGE.nid(), KernelTerm.DESCRIPTION_PATTERN.nid(),
                aDescriptionSemanticNid(), aSemanticThatIsNotADescription()}) {
            String identifier = ComponentText.identifier(nid);
            assertEquals(nid, PrimitiveData.nid(PublicIds.of(identifier)),
                    "the identifier written in text finds the component it was written for");
            assertEquals(identifier, ComponentText.leastUuid(nid).orElseThrow().toString());
            assertEquals(PrimitiveData.publicId(nid).leastUuid().toString(), identifier,
                    "the identifier is the least of the component's UUIDs, whatever order the store lists them in");
            assertTrue(PublicId.equals(PrimitiveData.publicId(nid), ComponentText.publicId(nid).orElseThrow()));
            NidFree.assertNoNid("the identifier", identifier, nid);
        }
    }

    @Test
    void theDurableKeyHoldsEveryUuidInOrderAndResolvesBack() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        String key = ComponentText.publicIdKey(nid).orElseThrow();
        UUID[] uuids = Arrays.stream(key.split(",")).map(UUID::fromString).toArray(UUID[]::new);
        assertArrayEquals(PrimitiveData.publicId(nid).asUuidArray(), uuids);
        assertEquals(nid, PrimitiveData.nid(PublicIds.of(uuids)));

        String first = "e07f8c60-1234-1234-1234-1234567890ab";
        String second = "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0";
        assertEquals(first + "," + second, ComponentText.publicIdKey(PublicIds.of(first, second)),
                "a public id with two UUIDs keeps both, in order");
        assertThrows(IllegalArgumentException.class, () -> ComponentText.publicIdKey(PublicIds.of(new UUID[0])),
                "a public id with no UUID has no key");
    }

    @Test
    void underAViewThatDescribesTheConceptEveryNameIsADescription() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        assertEquals(view.getDescriptionText(nid).orElseThrow(), ComponentText.name(view, nid));
        assertEquals(view.getRegularDescriptionText(nid).orElseThrow(), ComponentText.preferredName(view, nid));
        assertEquals(view.getFullyQualifiedNameText(nid).orElseThrow(), ComponentText.fullyQualifiedName(view, nid));
    }

    @Test
    void aConceptWithNoDescriptionIsNamedByItsUuid() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        assertTrue(undescribed.getDescriptionText(nid).isEmpty()
                        && undescribed.getRegularDescriptionText(nid).isEmpty()
                        && undescribed.getFullyQualifiedNameText(nid).isEmpty(),
                "precondition: the view resolves no description for the concept");
        // The calculator methods this helper replaces answer with the nid here. That is the
        // defect of IKE-Network/ike-issues#1170; these two lines fail when tinkar-core stops.
        assertEquals(Integer.toString(nid), undescribed.getDescriptionTextOrNid(nid));
        assertEquals(Integer.toString(nid), undescribed.getPreferredDescriptionTextWithFallbackOrNid(nid));

        String uuid = ComponentText.identifier(nid);
        for (String name : List.of(ComponentText.name(undescribed, nid),
                ComponentText.preferredName(undescribed, nid),
                ComponentText.fullyQualifiedName(undescribed, nid))) {
            assertEquals(uuid, name, "a name that does not resolve is the component's UUID");
            NidFree.assertNoNid("the name", name, nid);
        }
    }

    @Test
    void aDescriptionSemanticIsNamedByItsOwnText() {
        int semanticNid = aDescriptionSemanticNid();
        String text = ComponentText.preferredName(view, semanticNid);
        assertFalse(text.isBlank());
        assertEquals(view.getDescriptionText(semanticNid).orElseThrow(), text,
                "a description has no description of its own; its name is the text it holds");
        NidFree.assertNoNid("the description's name", text, semanticNid);
    }

    @Test
    void aSemanticThatIsNotADescriptionIsNamedForWhatItIs() {
        int semanticNid = aSemanticThatIsNotADescription();
        SemanticEntity<?> semantic = (SemanticEntity<?>) EntityHandle.getEntityOrThrow(semanticNid);
        PatternEntityVersion pattern =
                view.stampCalculator().latestPatternEntityVersion(semantic.patternNid()).get();

        String expected = "[" + ComponentText.preferredName(view, pattern.semanticMeaningNid())
                + "] of <" + ComponentText.preferredName(view, KernelTerm.ENGLISH_LANGUAGE.nid())
                + "> for [" + ComponentText.preferredName(view, pattern.semanticPurposeNid()) + "]";
        assertEquals(expected, ComponentText.preferredName(view, semanticNid));
        assertEquals(expected, ComponentText.fullyQualifiedName(view, semanticNid),
                "with no fully qualified name, the preferred name stands in");
        assertEquals(ComponentText.identifier(semanticNid), ComponentText.name(view, semanticNid),
                "the view selects no description for it, so its plain name is its UUID");
        NidFree.assertNoNid("the semantic's name", expected, semanticNid);
    }

    @Test
    void aSemanticIsNamedWithoutANidEvenWhenNothingItNamesHasADescription() {
        // The form is [meaning] of <component> for [purpose]. Under this view none of the three
        // has a description, which is where LanguageCalculator.getSemanticText writes three nids.
        int semanticNid = aSemanticThatIsNotADescription();
        SemanticEntity<?> semantic = (SemanticEntity<?>) EntityHandle.getEntityOrThrow(semanticNid);
        PatternEntityVersion pattern =
                undescribed.stampCalculator().latestPatternEntityVersion(semantic.patternNid()).get();

        String text = ComponentText.preferredName(undescribed, semanticNid);
        assertEquals("[" + ComponentText.identifier(pattern.semanticMeaningNid())
                + "] of <" + ComponentText.identifier(KernelTerm.ENGLISH_LANGUAGE.nid())
                + "> for [" + ComponentText.identifier(pattern.semanticPurposeNid()) + "]", text);
        NidFree.assertNoNid("the semantic's name", text, semanticNid);
        NidFree.assertNoNid("the semantic's name", text, KernelTerm.ENGLISH_LANGUAGE.nid());
    }

    @Test
    void theBadgeIsAUuidTokenLabelledByTheView() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        String uuid = ComponentText.identifier(nid);

        String labelled = ComponentText.badge(view, nid).orElseThrow();
        assertEquals("k:uuid=" + uuid + "[" + view.getDescriptionText(nid).orElseThrow() + "]", labelled);

        assertEquals("k:uuid=" + uuid, ComponentText.badge(undescribed, nid).orElseThrow(),
                "with no description the token has no label; its badge still shows a resolved name");
        assertEquals("k:uuid=" + uuid, ComponentText.badge(null, nid).orElseThrow(),
                "with no view the token has no label");
        NidFree.assertNoNid("the badge token", labelled, nid);
    }

    @Test
    void aNidTheStoreHasNoPublicIdForIsStatedAsUnidentified() {
        assertTrue(ComponentText.publicId(UNASSIGNED_NID).isEmpty());
        assertTrue(ComponentText.leastUuid(UNASSIGNED_NID).isEmpty());
        assertTrue(ComponentText.publicIdKey(UNASSIGNED_NID).isEmpty());
        assertTrue(ComponentText.badge(view, UNASSIGNED_NID).isEmpty(),
                "no token can be written for a component with no public id");
        assertEquals(ComponentText.UNIDENTIFIED, ComponentText.identifier(UNASSIGNED_NID));
        NidFree.assertNoNid("the unidentified text", ComponentText.identifier(UNASSIGNED_NID), UNASSIGNED_NID);
    }

    /** The nid of a description of English Language. */
    private static int aDescriptionSemanticNid() {
        Optional<SemanticEntity<SemanticEntityVersion>> description = EntityService.get()
                .semanticsForComponentOfPattern(KernelTerm.ENGLISH_LANGUAGE.nid(), KernelTerm.DESCRIPTION_PATTERN.nid())
                .findFirst();
        assertTrue(description.isPresent(), "English Language must have description semantics");
        return description.get().nid();
    }

    /**
     * The nid of a semantic attached to English Language that is not a description, and so has
     * no text of its own under any view.
     */
    private int aSemanticThatIsNotADescription() {
        EntityFacade concept = KernelTerm.ENGLISH_LANGUAGE;
        for (SemanticEntity<SemanticEntityVersion> semantic
                : EntityService.get().semanticsForComponent(concept.nid()).toList()) {
            if (semantic.patternNid() != KernelTerm.DESCRIPTION_PATTERN.nid()
                    && view.getDescriptionText(semantic.nid()).isEmpty()) {
                return semantic.nid();
            }
        }
        throw new AssertionError("English Language must have a semantic that is not a description");
    }
}
