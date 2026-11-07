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
package network.ike.komet.claude.terminology;

import network.ike.komet.claude.terminology.TerminologyGuard.FlaggedTerm;
import network.ike.komet.claude.terminology.TerminologyGuard.Hit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link TerminologyGuard}'s scan and rewrite instruction over the bundled
 * {@code flagged-terms.txt} and over a given term list.
 */
class TerminologyGuardTest {

    private final TerminologyGuard guard = TerminologyGuard.fromDefaultResource();

    @Test
    void bundledTermsLoad() {
        assertFalse(guard.terms().isEmpty(), "the bundled terms should load");
    }

    @Test
    void flagsOntologyAsAWordButNotInsideAnIdentifier() {
        List<Hit> hits = guard.scan("This is an ontology of concepts; the ontologyView is fine.");
        assertEquals(1, hits.size(), () -> "only the standalone word should match, got: " + hits);
        assertEquals("ontology", hits.get(0).term().name());
        assertTrue(hits.get(0).term().externalEscape(), "ontology may be kept for an external system");
    }

    @Test
    void matchingIgnoresCase() {
        assertEquals(3, guard.scan("An Ontology, an ONTOLOGY, ontological too.").size());
    }

    @Test
    void flagsFacetsAndSlots() {
        List<Hit> facets = guard.scan("Set the coordinate facets accordingly.");
        assertEquals(1, facets.size());
        assertEquals("facet", facets.get(0).term().name());
        assertFalse(facets.get(0).term().externalEscape(), "facet never names an IKE thing");

        List<Hit> slots = guard.scan("Put the finding in the topic slot, with its filler.");
        assertEquals(2, slots.size(), () -> "slot and filler should both match, got: " + slots);
        assertEquals("slot", slots.get(0).term().name());
    }

    @Test
    void codeSpansAndFencedBlocksAreNotScanned() {
        String reply = """
                The field is named in `ontology_ref`, and the source reads:

                ```java
                Slot slot = ontology.slot("facet");
                ```

                That is all.
                """;
        assertTrue(guard.scan(reply).isEmpty(), () -> "code should not be scanned, got: " + guard.scan(reply));
        assertEquals(1, guard.scan(reply + "\nIt is an ontology.").size(), "prose after the block is scanned");
    }

    @Test
    void ikeTermsFindNothing() {
        assertTrue(guard.scan("A knowledge graph of Complex Concepts, with coordinate dimensions and fields.").isEmpty());
    }

    @Test
    void instructionNamesEachTermAndItsReplacements() {
        String instruction = TerminologyGuard.correctionInstruction(
                guard.scan("This ontology models the domain, and this ontology too."));
        assertTrue(instruction.contains("\"ontology\""), () -> instruction);
        assertTrue(instruction.contains("knowledge graph"), () -> instruction);
        assertTrue(instruction.contains("external system"), () -> instruction);
        assertEquals(instruction.indexOf("\"ontology\""), instruction.lastIndexOf("\"ontology\""),
                () -> "each term is named once: " + instruction);
    }

    @Test
    void nothingFoundGivesNoInstruction() {
        assertEquals("", TerminologyGuard.correctionInstruction(guard.scan("A clear knowledge graph.")));
        assertEquals("", TerminologyGuard.correctionInstruction(List.of()));
    }

    @Test
    void scansAGivenTermList() {
        FlaggedTerm widget = new FlaggedTerm("widget",
                Pattern.compile("\\b(?:widget|widgets)\\b", Pattern.CASE_INSENSITIVE),
                List.of("component"), false, "");
        TerminologyGuard custom = TerminologyGuard.of(List.of(widget));
        assertEquals(2, custom.scan("a widget and two widgets").size());
        assertTrue(custom.scan("a knowledge graph").isEmpty());
    }

    @Test
    void nullOrBlankFindsNothing() {
        assertTrue(guard.scan(null).isEmpty());
        assertTrue(guard.scan("   ").isEmpty());
    }
}
