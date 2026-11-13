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
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Findings reaching a diagnostic test through its targets, on the shape of the respiratory panel
 * scenario: a test is characterized by the targets it detects, not by materials, and a finding about
 * a target lost to a variant reaches the review of another test with that target. The family of a
 * test's targets is the organism. The targets carry the identifiers of DeX's own concepts.
 */
class TargetReachTest {

    private static final String S_GENE = "4e55f206-e929-4cb1-be3b-4ca9984be26a";
    private static final String N_GENE = "2ab297ad-3720-4f14-a359-ce0694694fc9";
    private static final String E_GENE = "9023c50b-ddd3-45fa-a598-f5edfb665d91";
    private static final String RDRP_GENE = "b0d31dbc-a006-4070-a408-87a15528e584";
    private static final String CHANGED = "hazard: k:Changed[A missed infection when a variant changes the region a target looks for] @id=changed-target";

    /** The applicant's record for SARS-CoV-2: one target, the S gene, in the family of the virus's targets. */
    private static final String APPLICANT = """
            device: k:Applicant[The applicant's four-virus test (invented)] @id=applicant-test
              target: k:SGene[Spike protein (S) gene] @id=%s
              family: k:SarsCoV2[SARS-CoV-2] @id=813723ea-20f1-4e3a-be53-54d4f233e2da members=%s;%s;%s;%s
              %s
              states = "It finds SARS-CoV-2 through one target."
            """.formatted(S_GENE, S_GENE, N_GENE, E_GENE, RDRP_GENE, CHANGED);

    /** The predicate's record for SARS-CoV-2: three targets, and no family stated. */
    private static final String PREDICATE = """
            device: k:Predicate[Xpert Xpress CoV-2/Flu/RSV plus] @id=predicate
              target: k:NGene[Nucleocapsid protein (N) gene] @id=%s
              target: k:EGene[Envelope protein (E) gene] @id=%s
              target: k:RdRP[RNA-dependent RNA polymerase protein (RdRP) gene] @id=%s
              %s
            """.formatted(N_GENE, E_GENE, RDRP_GENE, CHANGED);

    /** A record that names IS481 but not the hazard that a related species carries it. */
    private static final String PERTUSSIS = """
            device: k:Panel[The applicant's syndromic panel (invented)] @id=panel
              target: k:IS481[Insertion sequence IS481] @id=is481
              %s
            """.formatted(CHANGED);

    private static final String FINDINGS = """
            finding: k:TaqPath[With lineage B.1.1.7, the S-gene target of a test with three targets was not detected] @id=fda-2021-taqpath
              statement = anf:taqpath
              %s
              target: k:SGene[Spike protein (S) gene] @id=%s
              source = "FDA 2021"
            finding: k:Revogene[A deletion in the N gene affected a test whose only target is there] @id=fda-2022-revogene
              statement = anf:revogene
              %s
              target: k:NGene[Nucleocapsid protein (N) gene] @id=%s
              source = "FDA 2022"
            finding: k:Reischl[An assay for IS481 detected Bordetella holmesii] @id=reischl-2001
              statement = anf:reischl
              hazard: k:Related[A false detection when a related species carries the target] @id=related-species
              target: k:IS481[Insertion sequence IS481] @id=is481
              source = "Reischl and others 2001"
            finding: k:Loinc[IS481 is in up to 100 copies in B. pertussis, and 8 to 10 in B. holmesii] @id=loinc-78921-4
              statement = anf:loinc
              target: k:IS481[Insertion sequence IS481] @id=is481
              source = "LOINC 78921-4"
            """.formatted(CHANGED, S_GENE, CHANGED, N_GENE);

    private static final Map<String, AnfStatement> STATEMENTS = statements(Map.of(
            "taqpath", "[anf,type=performance]\n--\ntopic:: candidate[Failure of the S-gene target to detect SARS-CoV-2] disp=PENDING\n"
                    + "subject_of_information:: candidate[The TaqPath COVID-19 Combo Kit, on specimens of lineage B.1.1.7] disp=PENDING\n"
                    + "statement_time:: [2021,2021] k:Year[year] @id=258707000\nresult:: [1,1] k:Presence[presence] @id=705057003\n--\n",
            "revogene", narrative("Failure of a test to detect SARS-CoV-2", "The Revogene SARS-CoV-2 test, which has one target, in the N gene", 2022,
                    "A deletion of nine nucleotides in the N gene affects the single target"),
            "reischl", "[anf,type=performance]\n--\ntopic:: candidate[Detection of Bordetella holmesii by an assay for IS481] disp=PENDING\n"
                    + "subject_of_information:: candidate[Isolates of Bordetella holmesii] disp=PENDING\n"
                    + "statement_time:: [2001,2001] k:Year[year] @id=258707000\nresult:: [1,1] k:Presence[presence] @id=705057003\n--\n",
            "loinc", narrative("Presence of IS481 in Bordetella holmesii", "Bordetella pertussis and Bordetella holmesii", 2025,
                    "IS481 is present in up to 100 copies in B. pertussis, and 8 to 10 copies in B. holmesii")));

    private static String narrative(String topic, String group, int inPrint, String text) {
        return "[anf,type=narrative]\n--\ntopic:: candidate[" + topic + "] disp=PENDING\nsubject_of_information:: candidate[" + group
                + "] disp=PENDING\nstatement_time:: [" + inPrint + "," + inPrint + "] k:Year[year] @id=258707000\ntext:: \"" + text + "\"\n--\n";
    }

    private static Map<String, AnfStatement> statements(Map<String, String> blocks) {
        Map<String, AnfStatement> read = new HashMap<>();
        blocks.forEach((name, block) -> read.put(name, AnfAdoc.parse(block, null)));
        return read;
    }

    private static Findings parse(String body) {
        return Findings.parse(body, name -> Optional.ofNullable(STATEMENTS.get(name)));
    }

    private static Findings findings() {
        Findings parsed = parse(FINDINGS);
        assertEquals(List.of(), parsed.problems());
        return parsed;
    }

    private static String why(String record, String finding) {
        return DeviceRecord.parse(record).reaches(findings().byId(finding).orElseThrow()).orElse("");
    }

    @Test
    void aTestRecordNamesItsTargetsAndNoMaterials() {
        DeviceRecord record = DeviceRecord.parse(APPLICANT);
        assertEquals(List.of(), record.problems());
        assertEquals(List.of(S_GENE), record.targets().stream().map(Findings.Named::id).toList());
        assertEquals(List.of(), record.materials());
        assertEquals("SARS-CoV-2", record.family().orElseThrow().name());
        assertEquals(4, record.relatives().size());
        assertTrue(record.isTestRecord());
        assertFalse(DeviceRecord.parse("device: k:D[A device] @id=d\n  material: k:M[A material] @id=m\n").isTestRecord());
    }

    @Test
    void aFindingFiledByTargetNamesItsTarget() {
        Findings.Finding taqpath = findings().byId("fda-2021-taqpath").orElseThrow();
        assertEquals(S_GENE, taqpath.target().orElseThrow().id());
        assertTrue(taqpath.material().isEmpty());
        assertEquals("targets", findings().filedAgainst());
    }

    @Test
    void aFindingReachesATestWithItsTarget() {
        assertEquals("Same target, same hazard", why(APPLICANT, "fda-2021-taqpath"));
        assertEquals("Same target, same hazard", why(PREDICATE, "fda-2022-revogene"));
    }

    @Test
    void aFindingReachesThroughTheOrganismItsTargetsBelongTo() {
        assertEquals("Same hazard; SARS-CoV-2, the family of its targets", why(APPLICANT, "fda-2022-revogene"));
    }

    @Test
    void aFindingReachesATestThatBringsItsHazard() {
        assertEquals("Same hazard, another target", why(PREDICATE, "fda-2021-taqpath"));
        assertEquals("Same hazard, another target", why(PERTUSSIS, "fda-2021-taqpath"));
    }

    @Test
    void aFindingAboutATestsOwnTargetReachesItWhateverTheHazard() {
        assertEquals("Same target, another hazard", why(PERTUSSIS, "reischl-2001"));
        assertEquals("Same target", why(PERTUSSIS, "loinc-78921-4"));
    }

    @Test
    void aFindingAboutAnotherTargetAndHazardDoesNotReach() {
        assertEquals("", why(APPLICANT, "reischl-2001"));
        assertEquals("", why(PREDICATE, "loinc-78921-4"));
    }

    @Test
    void onlyWhatWasInPrintReaches() {
        DeviceRecord applicant = DeviceRecord.parse(APPLICANT);
        assertEquals(List.of("fda-2021-taqpath"), applicant.reached(findings(), 2021).stream().map(r -> r.finding().id()).toList());
        assertEquals(List.of("fda-2021-taqpath", "fda-2022-revogene"), applicant.reached(findings(), 9999).stream().map(r -> r.finding().id()).toList());
    }

    @Test
    void aFindingThatNamesNeitherSpeaksOfWhatTheRecordNames() {
        Findings.Finding hazardOnly = parse("finding: k:X[A variant escaped a target] @id=x\n  statement = anf:taqpath\n  " + CHANGED
                + "\n  source = \"S\"\n").byId("x").orElseThrow();
        assertEquals(Optional.of("Same hazard, another target"), DeviceRecord.parse(APPLICANT).reaches(hazardOnly));
        assertEquals(Optional.of("Same hazard, another material"),
                DeviceRecord.parse("device: k:D[A device] @id=d\n  material: k:M[A material] @id=m\n  " + CHANGED + "\n").reaches(hazardOnly));
    }

    @Test
    void anEntryFiledAgainstAMaterialAndATargetIsSaid() {
        Findings both = parse("finding: k:X[It] @id=x\n  statement = anf:taqpath\n  material: k:M[A material] @id=m\n"
                + "  target: k:T[A target] @id=t\n  source = \"S\"\n");
        assertEquals(List.of("It is filed against a material and a target; an entry is filed against one."), both.problems());
        assertEquals("materials or targets", both.filedAgainst());
    }
}
