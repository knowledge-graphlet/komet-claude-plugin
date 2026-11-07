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

import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelPattern;
import javafx.application.Platform;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.koncept.ComponentText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pattern graph restored from its saved arrangement, against the starter set in a memory-only
 * store. The arrangement is kept in preferences, by UUID, so it can name patterns this knowledge
 * base does not hold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatternGraphRestoreIT {

    private static final File PB_STARTER_DATA =
            new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewProperties viewProperties;

    @BeforeAll
    void setup() {
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        viewProperties = ConceptProposalTools.onFxThread(() ->
                new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @Test
    void aSavedArrangementRestoresWhatTheStoreHoldsAndMintsNothing() {
        EntityFacade parent = KernelPattern.DESCRIPTION_PATTERN;
        EntityFacade child = KernelPattern.IDENTIFIER_PATTERN;
        EntityFacade orphan = KernelPattern.STATED_NAVIGATION_PATTERN;
        UUID unknownPattern = UUID.randomUUID();
        UUID unknownParent = UUID.randomUUID();
        String saved = String.join("\n",
                uuid(parent) + ";0;S",
                unknownPattern + ";0;S",
                uuid(child) + ";1;" + uuid(parent),
                uuid(orphan) + ";0;" + unknownParent);

        List<PatternGraphPanel.PatternNode> nodes = ConceptProposalTools.onFxThread(() -> {
            PatternGraphPanel panel = new PatternGraphPanel(viewProperties::calculator);
            panel.restoreFrom(saved);
            return List.copyOf(panel.nodes());
        });

        assertEquals(List.of(parent.nid(), child.nid(), orphan.nid()),
                nodes.stream().map(node -> node.patternNid).toList(), "the unknown pattern is left out");
        assertEquals(List.of(parent.nid()), List.copyOf(nodes.get(1).parents), "a stored parent is kept");
        assertTrue(nodes.get(1).ambient.get());
        assertEquals(List.of(PatternGraphPanel.SUBJECT), List.copyOf(nodes.get(2).parents),
                "an unknown parent leaves the pattern on the subject");

        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(unknownPattern)), "the unknown pattern was minted");
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(unknownParent)), "the unknown parent was minted");
    }

    private static String uuid(EntityFacade component) {
        return ComponentText.leastUuid(component.nid()).orElseThrow().toString();
    }
}
