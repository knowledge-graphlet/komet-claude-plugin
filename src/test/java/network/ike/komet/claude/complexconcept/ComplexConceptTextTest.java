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

import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Focused;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Parsed;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Spec;
import network.ike.komet.claude.complexconcept.ComplexConceptText.Uniform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The written form of a complex concept, read store-free: every identifier local to the block. */
class ComplexConceptTextTest {

    static final String DECISION = """
            concept: k:SurveillanceDecision[Surveillance imaging decision after aneurysm seal] @id=rda-surveillance-decision
              expansion: focused k:ImagingDecision[Surveillance imaging decision] @id=rda-imaging-decision
                prerequisite: k:AneurysmSealed[Intracranial aneurysm sealed by endovascular treatment] @id=rda-aneurysm-sealed
                  value = [1,1] k:Presence[presence] @id=rda-presence
                prerequisite: k:TimeSinceSeal[Time since the seal] @id=rda-time-since-seal
                  value = [2,2] k:Year[year] @id=rda-year
                prerequisite: k:AneurysmSymptoms[Symptoms attributable to the aneurysm] @id=rda-aneurysm-symptoms association=precondition
                  value = [0,0] k:Presence[presence] @id=rda-presence
            """;

    static final String PERRLA = """
            concept: k:Perrla[PERRLA panel] @id=perrla
              parent: k:AssessmentPanel[Clinical assessment panel] @id=assessment-panel
              expansion: uniform and k:Presence[presence] @id=presence
                member: k:PupilEquality[Pupil equality assessment] @id=pupil-equality
                member: k:PupilRoundness[Pupil roundness assessment] @id=pupil-roundness
                member: k:LightReactivity[Light reactivity assessment] @id=light-reactivity
                member: k:Accommodation[Accommodation assessment] @id=accommodation
            """;

    @Test
    void aFocusedDecisionPointReadsItsPrerequisitesAndTheirValues() {
        Parsed parsed = ComplexConceptText.parse(DECISION, BlockIdentities.storeFree());
        Spec spec = parsed.spec().orElseThrow();
        assertEquals("Surveillance imaging decision after aneurysm seal", spec.concept().label());
        assertTrue(spec.concept().local());
        assertTrue(spec.parent().isEmpty(), "no parent line");
        assertEquals("Surveillance imaging decision", spec.definitionParent().orElseThrow().label(), "a focused concept's parent defaults to its focus");
        Focused focused = (Focused) spec.shape().orElseThrow();
        assertEquals(3, focused.prerequisites().size());
        assertEquals("present", ComplexConceptText.valueWords(focused.prerequisites().get(0)));
        assertEquals("2 year", ComplexConceptText.valueWords(focused.prerequisites().get(1)));
        assertEquals("absent", ComplexConceptText.valueWords(focused.prerequisites().get(2)));
        assertEquals("precondition", focused.prerequisites().get(2).association());
        assertEquals(focused.prerequisites().get(0).unit().orElseThrow().nid(),
                focused.prerequisites().get(2).unit().orElseThrow().nid(), "one symbol, one local concept");
        assertEquals(List.of(), ComplexConceptText.completeness(spec));
        assertEquals(1, parsed.findings().size(), "only the local-symbols finding: " + parsed.findings());
        assertEquals(List.of("rda-surveillance-decision", "rda-imaging-decision", "rda-aneurysm-sealed", "rda-presence",
                "rda-time-since-seal", "rda-year", "rda-aneurysm-symptoms"), parsed.localSymbols(), "in reading order, a value before the next prerequisite");
        assertEquals("Complex concept, a kind of Surveillance imaging decision; focused on"
                + " Surveillance imaging decision, given Intracranial aneurysm sealed by endovascular"
                + " treatment present, Time since the seal 2 year, Symptoms attributable to the aneurysm absent.",
                ComplexConceptText.describe(spec));
    }

    @Test
    void aUniformPanelReadsItsConjunctionDomainAndMembers() {
        Parsed parsed = ComplexConceptText.parse(PERRLA, BlockIdentities.storeFree());
        Spec spec = parsed.spec().orElseThrow();
        assertEquals("Clinical assessment panel", spec.parent().orElseThrow().label());
        Uniform uniform = (Uniform) spec.shape().orElseThrow();
        assertEquals("and", uniform.conjunction());
        assertEquals("presence", uniform.domain().orElseThrow().label());
        assertEquals(4, uniform.members().size());
        assertTrue(uniform.unsafe().isEmpty());
        assertEquals(List.of(), ComplexConceptText.completeness(spec));
        assertEquals("Complex concept, a kind of Clinical assessment panel; every member on presence: Pupil equality"
                + " assessment, Pupil roundness assessment, Light reactivity assessment, Accommodation assessment.",
                ComplexConceptText.describe(spec));
    }

    @Test
    void whatASpecificationStillLacksIsListedNotRefused() {
        String or = """
                concept: k:Nvd[NVD screening] @id=nvd
                  expansion: uniform or k:Presence[presence] @id=presence
                    member: k:Nausea[Nausea assessment] @id=nausea
                """;
        Parsed parsed = ComplexConceptText.parse(or, BlockIdentities.storeFree());
        Spec spec = parsed.spec().orElseThrow();
        assertEquals(List.of("a definition: a parent line", "at least two members",
                "how the OR conjunction handles a non-absent value: unsafe=refuse, flag or retain"),
                ComplexConceptText.completeness(spec));

        String bare = """
                concept: k:Thing[A thing] @id=thing
                  expansion: focused k:Thing[] @id=thing
                    prerequisite: k:Condition[A condition] @id=condition
                """;
        Parsed bareParsed = ComplexConceptText.parse(bare, BlockIdentities.storeFree());
        Spec bareSpec = bareParsed.spec().orElseThrow();
        assertEquals(List.of("a fixed value for A condition"), ComplexConceptText.completeness(bareSpec));
        assertTrue(bareParsed.findings().stream().anyMatch(f -> f.contains("the focus is the complex concept itself")),
                bareParsed.findings().toString());
        assertEquals("(no value)", ComplexConceptText.valueWords(((Focused) bareSpec.shape().orElseThrow()).prerequisites().get(0)));
    }

    @Test
    void aMisreadLineIsAFindingAndTheRootMustBeAConcept() {
        Parsed notConcept = ComplexConceptText.parse("decision: k:X[x] @id=x\n", BlockIdentities.storeFree());
        assertTrue(notConcept.spec().isEmpty());
        assertEquals(List.of("line 1: the root is not a concept"), notConcept.findings());

        String odd = """
                concept: k:Perrla[PERRLA panel] @id=perrla
                  parent: k:AssessmentPanel[Clinical assessment panel] @id=assessment-panel
                  expansion: uniform and k:Presence[presence] @id=presence unsafe=refuse
                    member: k:PupilEquality[Pupil equality assessment] @id=pupil-equality
                    member: k:PupilEquality[Pupil equality assessment] @id=pupil-equality
                    prerequisite: k:Nope[nope] @id=nope
                  colour: k:Red[red] @id=red
                """;
        Parsed parsed = ComplexConceptText.parse(odd, BlockIdentities.storeFree());
        Spec spec = parsed.spec().orElseThrow();
        Uniform uniform = (Uniform) spec.shape().orElseThrow();
        assertEquals(1, uniform.members().size(), "a member at most once");
        assertTrue(uniform.unsafe().isEmpty(), "an AND conjunction carries no policy");
        List<String> findings = parsed.findings();
        assertTrue(findings.stream().anyMatch(f -> f.contains("only an OR conjunction carries an unsafe policy")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.contains("is a member already")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.contains("holds member lines, not prerequisite")), findings.toString());
        assertTrue(findings.stream().anyMatch(f -> f.contains("unknown word colour")), findings.toString());
        assertFalse(findings.stream().anyMatch(f -> f.contains("does not resolve")), "symbols are local, never unresolved");
    }

    @Test
    void theTagMatchesItsOwnFenceOnly() {
        assertTrue(ComplexConceptText.isTagged("complex-concept"));
        assertTrue(ComplexConceptText.isTagged("complex-concept title=x"));
        assertFalse(ComplexConceptText.isTagged("decision-tree"));
        assertFalse(ComplexConceptText.isTagged(null));
    }
}
