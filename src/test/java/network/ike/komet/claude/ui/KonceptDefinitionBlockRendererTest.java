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
package network.ike.komet.claude.ui;

import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import network.ike.komet.claude.ui.KonceptDefinitionBlockRenderer.Proposal;
import network.ike.komet.claude.ui.KonceptDefinitionBlockRenderer.Ref;
import network.ike.komet.claude.ui.KonceptDefinitionBlockRenderer.RoleSpec;
import network.ike.komet.claude.ui.KonceptDefinitionBlockRenderer.SetSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free tests of the {@code koncept-definition} grammar ({@code IKE-Network/ike-issues#1292}).
 * Resolution, the definition tree and the control need a store (and the control a JavaFX
 * toolkit); {@code KonceptDefinitionBlockIT} covers the tree against the starter data.
 */
class KonceptDefinitionBlockRendererTest {

    private static final String PROPOSAL = """
            proposed: Eosinophilic esophagitis unresponsive to dupilumab
            necessary
              k:sctid=235599003[Eosinophilic esophagitis]
              group
                k:sctid=116676008[Associated morphology] = k:sctid=445520000[Eosinophilic infiltration]
                k:sctid=363698007[Finding site] = k:sctid=32849002[Esophageal structure]
              group
                k:sctid=263502005[Clinical course] = k:sctid=90734009[Chronic]
            gap: no attribute relates a disorder to non-response to a specific substance
            """;

    @Test
    void aProposalParsesIntoItsTitleSetsGroupsAndGaps() {
        Proposal proposal = KonceptDefinitionBlockRenderer.parse(PROPOSAL);

        assertNotNull(proposal);
        assertEquals("Eosinophilic esophagitis unresponsive to dupilumab", proposal.title());
        assertEquals(List.of("no attribute relates a disorder to non-response to a specific substance"), proposal.gaps());
        assertEquals(1, proposal.sets().size());

        SetSpec set = proposal.sets().getFirst();
        assertFalse(set.sufficient());
        assertEquals(List.of(new Ref("sctid", "235599003", "Eosinophilic esophagitis")), set.supertypes());
        assertTrue(set.roles().isEmpty(), "every role here is in a group");
        assertEquals(2, set.groups().size());
        assertEquals(List.of(
                new RoleSpec(new Ref("sctid", "116676008", "Associated morphology"),
                        new Ref("sctid", "445520000", "Eosinophilic infiltration")),
                new RoleSpec(new Ref("sctid", "363698007", "Finding site"),
                        new Ref("sctid", "32849002", "Esophageal structure"))), set.groups().get(0));
        assertEquals(1, set.groups().get(1).size());
    }

    @Test
    void aRoleLineBackAtTheGroupsIndentIsAnUngroupedRole() {
        Proposal proposal = KonceptDefinitionBlockRenderer.parse("""
                sufficient:
                  k:uuid=e07f8c60-1234-1234-1234-1234567890ab[Parent]
                  group:
                    k:sctid=1[A] = k:sctid=2[B]
                  k:sctid=3[C] = k:sctid=4[D]
                """);

        assertNotNull(proposal);
        assertNull(proposal.title());
        SetSpec set = proposal.sets().getFirst();
        assertTrue(set.sufficient());
        assertEquals(1, set.groups().getFirst().size());
        assertEquals(List.of(new RoleSpec(new Ref("sctid", "3", "C"), new Ref("sctid", "4", "D"))), set.roles());
    }

    @Test
    void aBlockMayHoldSeveralSetsAndUnlabelledTokens() {
        Proposal proposal = KonceptDefinitionBlockRenderer.parse("""
                necessary
                  k:sctid=1
                Sufficient
                  k:sctid=2 = k:sctid=3
                """);

        assertNotNull(proposal);
        assertEquals(2, proposal.sets().size());
        assertEquals("sctid=1", proposal.sets().get(0).supertypes().getFirst().display(),
                "a token without a label displays as its identifier");
        assertTrue(proposal.sets().get(1).sufficient());
    }

    @Test
    void aMalformedBlockIsDeclined() {
        assertNull(KonceptDefinitionBlockRenderer.parse("k:sctid=1[No set yet]"), "a token before any set");
        assertNull(KonceptDefinitionBlockRenderer.parse("group\n  k:sctid=1 = k:sctid=2"), "a group outside a set");
        assertNull(KonceptDefinitionBlockRenderer.parse("necessary\n  Finding site → Esophagus"), "prose, not tokens");
        assertNull(KonceptDefinitionBlockRenderer.parse("necessary\n  group\n    k:sctid=1[Lone]"),
                "a supertype inside a role group");
        assertNull(KonceptDefinitionBlockRenderer.parse("proposed: Something\ngap: nothing fits"),
                "no definition set at all");
    }

    @Test
    void onlyItsOwnTagIsRenderedAndNothingWithoutAView() {
        KonceptDefinitionBlockRenderer viewless = new KonceptDefinitionBlockRenderer(null, 13);
        assertTrue(viewless.render("koncept-definition", PROPOSAL, StyleAttributeMap.EMPTY).isEmpty(),
                "with no view to name concepts, the block falls through to preformatted text");
        assertTrue(viewless.render("koncept-tree", PROPOSAL, StyleAttributeMap.EMPTY).isEmpty());
    }
}
