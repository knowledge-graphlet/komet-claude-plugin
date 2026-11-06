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
package network.ike.komet.claude.benefitrisk;

import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.claude.anf.AnfStatement;
import network.ike.komet.claude.benefitrisk.EffectsReading.Row;
import network.ike.komet.claude.benefitrisk.EffectsReading.Verdict;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings reaching a device through a material, a family or a hazard, on the shape of the jaw
 * implant case of ike-lab-documents topic {@code rda-jaw-implant-record}, and an effects table read
 * as it stood in a year.
 */
class FindingsTest {

    private static final String DEVICE = """
            device: k:Jaw[Interpositional jaw joint implant] @id=jaw
              material: k:Fep[Fluorinated ethylene propylene film] @id=fep
              material: k:Composite[Porous PTFE and carbon composite] @id=composite
              family: k:Fluoro[Fluoropolymers] @id=fluoro members=fep;ptfe;composite
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              states = "A permanent implant in a joint"
            """;

    private static final String GROUP = "candidate[The implant in patients] disp=PENDING";
    private static final String SHARE = "candidate[Population share] disp=PENDING";
    private static final String PRESENT = "[1,1] k:Presence[presence] @id=705057003";

    /** The statements of the document, as anf blocks. */
    private static final Map<String, AnfStatement> STATEMENTS = statements(Map.of(
            "charnley", performance("candidate[Tissue reaction to wear particles] disp=PENDING", "candidate[PTFE hip sockets in patients] disp=PENDING",
                    PRESENT, 1963, ""),
            "silicone", performance("candidate[Synovitis] disp=PENDING", "candidate[Silicone finger joints in patients] disp=PENDING",
                    PRESENT, 1977, ""),
            "gallagher", "[anf,type=narrative]\n--\ntopic:: candidate[Capping of the cut jawbone] disp=PENDING\n"
                    + "subject_of_information:: candidate[Capped jawbones in patients] disp=PENDING\n"
                    + "statement_time:: [1982,1982] k:Year[year] @id=258707000\ntext:: \"12 patients, the disc left in place\"\n--\n",
            "metal", performance("candidate[Wear of a metal bearing] disp=PENDING", "candidate[Metal bearings on a hip simulator] disp=PENDING",
                    "(10,inf) candidate[Fold increase in wear] disp=PENDING", 2004, ""),
            "fontenot", performance("candidate[Wear-through of the bearing surface] disp=PENDING", "candidate[The implant on a joint simulator] disp=PENDING",
                    "[1,1] " + SHARE, 1992, "timing:: [0,11] k:Hour[hour] @id=258702006\nmethod:: candidate[Simulator wear test] disp=PENDING\n"),
            "fontenot-count", performance("candidate[Implants tested] disp=PENDING", "candidate[The implant on a joint simulator] disp=PENDING",
                    "[6,6] candidate[Implants] disp=PENDING", 1992, ""),
            "henry", performance("candidate[Removal of the implant] disp=PENDING", GROUP, "[0.58,0.58] " + SHARE, 1993,
                    "timing:: [7,7] k:Year[year] @id=258707000\n"),
            "henry-count", performance("candidate[Patients followed] disp=PENDING", GROUP, "[107,107] candidate[Patients] disp=PENDING", 1993, ""),
            "five", performance("candidate[Doing well after the operation] disp=PENDING", "candidate[Disc removal with no implant] disp=PENDING",
                    "[0.83,0.95] " + SHARE, 2004, "timing:: [5,29] k:Year[year] @id=258707000\n")));

    private static final String FINDINGS = """
            finding: k:Charnley[PTFE wears into particles] @id=charnley
              statement = anf:charnley
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              material: k:Ptfe[Polytetrafluoroethylene] @id=ptfe
              source = "Charnley 1963"
            finding: k:Silicone[Silicone implants shed particles] @id=silicone
              statement = anf:silicone
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              material: k:Elastomer[Silicone elastomer] @id=elastomer
              source = "Christie 1977"
            finding: k:Gallagher[The composite capped cut bone] @id=gallagher
              statement = anf:gallagher
              material: k:Composite[Porous PTFE and carbon composite] @id=composite
              source = "Gallagher 1982"
            finding: k:Metal[Metal bearing wear rose tenfold] @id=metal
              statement = anf:metal
              hazard: k:MetalWear[Metal wear under adverse loading] @id=metal-wear
              material: k:CoCr[Cobalt-chromium alloy] @id=cocr
              source = "Williams 2004"
            finding: k:Fontenot[Bearing surface wore through on all six] @id=fontenot
              statement = anf:fontenot among=anf:fontenot-count
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              material: k:Fep[Fluorinated ethylene propylene film] @id=fep
              source = "Fontenot 1992"
              gate = worn-through
            finding: k:Henry[Surgeons removed the implant from 58 of 100] @id=henry
              statement = anf:henry among=anf:henry-count
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              material: k:Fep[Fluorinated ethylene propylene film] @id=fep
              source = "Henry 1993"
            finding: k:Five[83 to 95 of 100 did well] @id=five
              statement = anf:five
              source = "Five studies"
            """;

    private static String performance(String topic, String group, String result, int inPrint, String more) {
        return "[anf,type=performance]\n--\ntopic:: " + topic + "\nsubject_of_information:: " + group
                + "\nstatement_time:: [" + inPrint + "," + inPrint + "] k:Year[year] @id=258707000\nresult:: " + result + "\n" + more + "--\n";
    }

    private static Map<String, AnfStatement> statements(Map<String, String> blocks) {
        Map<String, AnfStatement> read = new HashMap<>();
        blocks.forEach((name, block) -> read.put(name, AnfAdoc.parse(block, null)));
        return read;
    }

    private static Findings parse(String body) {
        return Findings.parse(body, name -> Optional.ofNullable(STATEMENTS.get(name)));
    }

    private static final String TREE = """
            goal: k:Choice[Treatment after disc removal] @id=goal
              gate: k:Hold[Bearing surface holds under load] @id=hold pass=intact
              domain: k:Unfav[Unfavorable effects] @id=unfav polarity=unfavorable
                criterion: k:Removed[Implants removed] @id=removed better=lower
            """;

    private static final String TABLE = """
            alternative: k:Implant[Implant] @id=implant
            alternative: k:None[No implant] @id=none
            row: k:Hold[] @id=hold
              arm: implant finding=fontenot
              arm: none result=not-applicable
            row: k:Removed[] @id=removed
              arm: implant finding=henry
              arm: none result=not-applicable
            """;

    private static DeviceRecord record() {
        return DeviceRecord.parse(DEVICE);
    }

    private static Findings findings() {
        Findings parsed = parse(FINDINGS);
        assertEquals(List.of(), parsed.problems());
        return parsed;
    }

    private static String why(String id) {
        return record().reaches(findings().byId(id).orElseThrow()).orElse("");
    }

    @Test
    void aFindingReachesByHazardMaterialOrFamily() {
        assertEquals("Same hazard; fluoropolymers, the family of its materials", why("charnley"));
        assertEquals("Same hazard, another material", why("silicone"));
        assertEquals("Same material, another use", why("gallagher"));
        assertEquals("Same material, same hazard", why("fontenot"));
    }

    @Test
    void aFindingAboutAnotherHazardAndMaterialDoesNotReach() {
        assertEquals("", why("metal"));
    }

    @Test
    void onlyWhatWasInPrintReaches() {
        assertEquals(List.of("charnley", "silicone", "gallagher"),
                record().reached(findings(), 1983).stream().map(r -> r.finding().id()).toList());
        assertEquals(5, record().reached(findings(), 9999).size());
        assertEquals(7, findings().all().size());
    }

    @Test
    void theGateIsOpenIn1983AndFailedNow() {
        ValueTree tree = ValueTree.parse(TREE);
        EffectsTable table = EffectsTable.parse(TABLE);
        Row then = EffectsReading.of(tree, table, findings(), 1983).orElseThrow().rows().getFirst();
        assertEquals(Verdict.NOT_DETERMINABLE, then.verdict());
        assertEquals("No evidence", EffectsReading.words(then.firstArm()));
        Row now = EffectsReading.of(tree, table, findings(), 9999).orElseThrow().rows().getFirst();
        assertEquals(Verdict.GATE_FAILED, now.verdict());
        assertEquals("Implant does not pass", now.favors());
        assertEquals("Wear-through of the bearing surface: all 6, by 11 hours", EffectsReading.words(now.firstArm()));
    }

    @Test
    void whatIsMissingIn1983IsSaidAndAFindingCarriesItsSource() {
        EffectsReading then = EffectsReading.of(ValueTree.parse(TREE), EffectsTable.parse(TABLE), findings(), 1983).orElseThrow();
        assertTrue(then.findings().contains("No evidence for Bearing surface holds under load."));
        EffectsReading now = EffectsReading.of(ValueTree.parse(TREE), EffectsTable.parse(TABLE), findings(), 9999).orElseThrow();
        assertFalse(now.findings().stream().anyMatch(f -> f.contains("no source")));
    }

    @Test
    void aShareTakesItsWilsonInterval() {
        double[] share = Findings.wilson(58, 107);
        assertEquals(58, share[0], 1e-9);
        assertEquals(48.5, share[1], 0.1);
        assertEquals(67.0, share[2], 0.1);
    }

    @Test
    void aStatementGivesAFindingWhatWasCountedAndOverWhatTime() {
        Findings.Finding henry = findings().byId("henry").orElseThrow();
        assertEquals(1993, henry.inPrint());
        assertEquals("58 of 100", henry.counted());
        assertEquals("58 of 100, among 107 patients", henry.countedAmong());
        assertEquals("at 7 years", henry.period());
        assertEquals("The implant in patients", henry.group());
        assertEquals("Removal of the implant", henry.topic());
        assertEquals(58, henry.sharePercent().orElseThrow(), 1e-9);
        assertEquals(107, henry.among().orElseThrow());
    }

    @Test
    void eachResultReadsInWords() {
        assertEquals("Present; no count is cited here", findings().byId("charnley").orElseThrow().counted());
        assertEquals("All 6", findings().byId("fontenot").orElseThrow().counted());
        assertEquals("All 6 implants", findings().byId("fontenot").orElseThrow().countedAmong());
        assertEquals("Fold increase in wear: more than 10", findings().byId("metal").orElseThrow().counted());
        Findings.Finding five = findings().byId("five").orElseThrow();
        assertEquals("83 to 95 of 100", five.counted());
        assertEquals("5 to 29 years", five.period());
        assertTrue(five.sharePercent().isEmpty(), "a span across studies is not one share to plot");
    }

    @Test
    void aFindingWithNoScaleIsNarrativeAGapAndNotAProblem() {
        Findings held = findings();
        Findings.Finding gallagher = held.byId("gallagher").orElseThrow();
        assertTrue(gallagher.narrative());
        assertEquals("12 patients, the disc left in place", gallagher.counted());
        assertEquals(1, held.narrativeOnly());
    }

    private static List<String> problems(String entry, Map<String, String> blocks) {
        Map<String, AnfStatement> statements = statements(blocks);
        return Findings.parse(entry, name -> Optional.ofNullable(statements.get(name))).problems();
    }

    @Test
    void anEntryThatNamesNoStatementOrAMissingOneIsSaid() {
        assertEquals(List.of("It names no statement."), problems("finding: k:X[It] @id=x\n  source = \"S\"\n", Map.of()));
        assertEquals(List.of("It names anf:gone, which no anf block of this document states."),
                problems("finding: k:X[It] @id=x\n  statement = anf:gone\n  source = \"S\"\n", Map.of()));
    }

    @Test
    void aStatementThatLacksAYearOrAGroupIsSaid() {
        String entry = "finding: k:X[It] @id=x\n  statement = anf:x\n  source = \"S\"\n";
        assertEquals(List.of("It does not say when it was in print: its statement has no statement time."),
                problems(entry, Map.of("x", "[anf,type=performance]\n--\ntopic:: candidate[T] disp=PENDING\n"
                        + "subject_of_information:: candidate[G] disp=PENDING\nresult:: " + PRESENT + "\n--\n")));
        assertEquals(List.of("It says no group: its statement has no subject of information."),
                problems(entry, Map.of("x", "[anf,type=performance]\n--\ntopic:: candidate[T] disp=PENDING\n"
                        + "statement_time:: [1990,1990] k:Year[year] @id=258707000\nresult:: " + PRESENT + "\n--\n")));
    }

    @Test
    void anEntryWithNoSourceIsSaid() {
        assertEquals(List.of("It has no source."), problems("finding: k:X[It] @id=x\n  statement = anf:x\n",
                Map.of("x", performance("candidate[T] disp=PENDING", GROUP, PRESENT, 1990, ""))));
    }

    @Test
    void aCountOfAnotherGroupIsSaid() {
        assertEquals(List.of("It counts another group: anf:n has a different subject of information."),
                problems("finding: k:X[It] @id=x\n  statement = anf:x among=anf:n\n  source = \"S\"\n", Map.of(
                        "x", performance("candidate[T] disp=PENDING", GROUP, "[0.5,0.5] " + SHARE, 1990, ""),
                        "n", performance("candidate[N] disp=PENDING", "candidate[Others] disp=PENDING", "[10,10] candidate[Patients] disp=PENDING", 1990, ""))));
    }

    @Test
    void aRequestIsNotAFinding() {
        assertEquals(List.of("It names a request, and a finding is what someone observed."),
                problems("finding: k:X[It] @id=x\n  statement = anf:x\n  source = \"S\"\n",
                        Map.of("x", "[anf,type=request]\n--\ntopic:: candidate[T] disp=PENDING\n--\n")));
    }
}
