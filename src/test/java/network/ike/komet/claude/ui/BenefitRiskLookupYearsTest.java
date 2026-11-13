/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The years a findings lookup lets the reader choose between. A lookup that named no year once
 * drew two choices that both read "Known now"; a choice is offered only when there is one.
 */
class BenefitRiskLookupYearsTest {

    private static final int NOW = BenefitRiskBlockRenderer.NOW;

    @Test
    void aLookupThatNamesNoYearOffersNoChoice() {
        assertEquals(List.of(NOW), BenefitRiskBlockRenderer.yearChoices(NOW, NOW));
    }

    @Test
    void aDocumentNotYetReadAtAnyYearOffersNoChoice() {
        assertEquals(List.of(NOW), BenefitRiskBlockRenderer.yearChoices(NOW, Integer.MAX_VALUE));
    }

    @Test
    void aLookupThatNamesAYearOffersThatYearAndNow() {
        assertEquals(List.of(1983, NOW), BenefitRiskBlockRenderer.yearChoices(1983, 1983));
        assertEquals(List.of(1983, NOW), BenefitRiskBlockRenderer.yearChoices(1983, NOW));
    }

    @Test
    void aLookupThatNamesNoYearOffersTheYearAnotherLookupSet() {
        assertEquals(List.of(1983, NOW), BenefitRiskBlockRenderer.yearChoices(NOW, 1983));
    }

    @Test
    void aLookupOffersItsOwnYearAndTheYearAnotherLookupSet() {
        assertEquals(List.of(1983, 2002, NOW), BenefitRiskBlockRenderer.yearChoices(2002, 1983));
    }
}
