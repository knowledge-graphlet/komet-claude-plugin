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

import network.ike.komet.claude.anf.AnfAdoc;
import network.ike.komet.document.DecisionFigure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A device record and a findings lookup on the page, for a diagnostic test. The blocks were written
 * for materials; a test is characterized by its targets, so its record lists them and its lookup
 * gives a reason in their words, while a record of materials prints as it always has.
 */
class BenefitRiskTargetsPageTest {

    private static final String TEST_RECORD = """
            device: k:Applicant[The applicant's four-virus test (invented)] @id=applicant-test
              target: k:SGene[Spike protein (S) gene] @id=4e55f206-e929-4cb1-be3b-4ca9984be26a
              hazard: k:Changed[A missed infection when a variant changes the region a target looks for] @id=changed-target
            """;

    private static final String MATERIAL_RECORD = """
            device: k:Jaw[Interpositional jaw joint implant] @id=jaw
              material: k:Fep[Fluorinated ethylene propylene film] @id=fep
              hazard: k:Wear[Wear particles from a polymer bearing surface in a joint] @id=wear
              states = "A permanent implant in a joint"
            """;

    private static final String FINDINGS = """
            finding: k:TaqPath[The S-gene target of a test was not detected] @id=fda-2021-taqpath
              statement = anf:taqpath
              hazard: k:Changed[A missed infection when a variant changes the region a target looks for] @id=changed-target
              target: k:SGene[Spike protein (S) gene] @id=4e55f206-e929-4cb1-be3b-4ca9984be26a
              source = "FDA 2021"
            """;

    /** The words of a block's page figure, each cell's lines joined. */
    private static String words(BenefitRiskBlockRenderer.View view, String info, String body, ProfileBlocks context) {
        List<DecisionFigure> figures = BenefitRiskBlockRenderer.figures(view, info, body, context);
        assertEquals(1, figures.size());
        return figures.getFirst().marks().stream().filter(DecisionFigure.Text.class::isInstance).map(DecisionFigure.Text.class::cast)
                .map(DecisionFigure.Text::text).collect(Collectors.joining(" "));
    }

    @Test
    void aTestRecordListsItsTargets() {
        String page = words(BenefitRiskBlockRenderer.View.DEVICE, "device-record id=applicant", TEST_RECORD, new ProfileBlocks());
        assertTrue(page.contains("target Spike protein (S) gene"), page);
        assertFalse(page.contains("material"), page);
    }

    @Test
    void aRecordOfMaterialsPrintsAsItDid() {
        String page = words(BenefitRiskBlockRenderer.View.DEVICE, "device-record id=jaw", MATERIAL_RECORD, new ProfileBlocks());
        assertEquals("Interpositional jaw joint implant field value material Fluorinated ethylene propylene film "
                + "hazard Wear particles from a polymer bearing surface in a joint states A permanent implant in a joint", page);
    }

    @Test
    void aLookupGivesTheReasonInTheWordsOfTargets() {
        ProfileBlocks context = new ProfileBlocks();
        context.defineStatement("taqpath", AnfAdoc.parse("[anf,type=performance]\n--\n"
                + "topic:: candidate[Failure of the S-gene target to detect SARS-CoV-2] disp=PENDING\n"
                + "subject_of_information:: candidate[The TaqPath COVID-19 Combo Kit, on specimens of lineage B.1.1.7] disp=PENDING\n"
                + "statement_time:: [2021,2021] k:Year[year] @id=258707000\nresult:: [1,1] k:Presence[presence] @id=705057003\n--\n", null));
        BenefitRiskBlockRenderer.publish("device-record id=applicant", TEST_RECORD, context);
        BenefitRiskBlockRenderer.publish("findings id=mock", FINDINGS, context);
        String page = words(BenefitRiskBlockRenderer.View.LOOKUP, "findings-lookup device=applicant findings=mock", "", context);
        assertTrue(page.contains("The S-gene target of a test was not detected"), page);
        assertTrue(page.contains("Same target, same hazard"), page);
    }
}
