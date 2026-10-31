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
package network.ike.komet.claude.anf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import network.ike.komet.claude.anf.element.ClinicalElement;
import network.ike.komet.claude.anf.element.ElementSet;
import network.ike.komet.claude.anf.element.ElementText;
import network.ike.komet.claude.anf.element.Span;
import org.junit.jupiter.api.Test;

/** The lift history's file body, split back into the blocks it was written from. */
class AnfHistoryStoreTest {

    @Test
    void anElementSetsNotClinicalMarkIsABlockOfItsOwn() {
        String note = "A 62-year-old man presented for routine checkup.";
        ElementSet set = new ElementSet(note, List.of(
                ClinicalElement.proposed("el-1", "The patient is 62 years old.", List.of(new Span(0, 17)))),
                List.of(new Span(18, 47)));
        List<String> blocks = ElementText.toBlocks(set);
        List<String> split = AnfHistoryStore.splitBlocks(String.join("\n", blocks));
        assertEquals(blocks.size(), split.size(), "the not-clinical mark is not swallowed by the element before it");
        assertEquals(set.notClinical(), ElementText.parse(split, note, id -> Optional.empty()).orElseThrow().notClinical());
    }
}
