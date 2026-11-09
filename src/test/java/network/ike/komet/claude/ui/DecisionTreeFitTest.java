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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a decision tree takes the width it is given: narrower names first, an outline when the
 * drawing left to right would be scaled past reading, the drawing left to right again when the
 * region has grown, and never two changes at one width.
 */
class DecisionTreeFitTest {

    private static DecisionTreeFit fit() {
        return new DecisionTreeFit(16, 8);
    }

    @Test
    @DisplayName("A tree that fits is left as it is drawn")
    void aTreeThatFitsIsLeftAlone() {
        DecisionTreeFit fit = fit();
        assertFalse(fit.given(1200, 1000));
        assertEquals(16, fit.ems(), 1e-9);
        assertFalse(fit.outline());
    }

    @Test
    @DisplayName("A tree a little too wide is drawn with narrower names, still left to right")
    void aLittleTooWideNarrowsTheNames() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(900, 1000));
        assertEquals(14, fit.ems(), 1e-9);
        assertFalse(fit.outline());
    }

    @Test
    @DisplayName("A tree that narrow names could not bring to a readable scale becomes an outline at once, with full names")
    void farTooWideBecomesAnOutline() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(631, 2400));
        assertTrue(fit.outline());
        assertEquals(16, fit.ems(), 1e-9, "an outline has room the drawing left to right had not");
    }

    @Test
    @DisplayName("With its narrowest names a tree scaled below the least becomes an outline; at the least or above it stays")
    void theLeastScaleDecidesAtTheNarrowestNames() {
        DecisionTreeFit stays = fit();
        assertTrue(stays.given(900, 1700), "narrower names first");
        assertEquals(8, stays.ems(), 1e-9);
        assertFalse(stays.outline());
        assertFalse(stays.given(900, 1250), "scaled to 0.72, which can be read");
        assertFalse(stays.outline());

        DecisionTreeFit turns = fit();
        assertTrue(turns.given(900, 1700));
        assertTrue(turns.given(900, 1400), "scaled to 0.64, which cannot");
        assertTrue(turns.outline());
    }

    @Test
    @DisplayName("An outline too wide for its region takes narrower names, and nothing changes again at that width")
    void anOutlineSettlesAtOneWidth() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(631, 2400));
        assertTrue(fit.given(631, 700), "the outline is itself a little too wide");
        assertTrue(fit.outline());
        assertEquals(14, fit.ems(), 1e-9);
        for (double natural : new double[] {620, 500, 300, 620}) {
            assertFalse(fit.given(631, natural), "settled, at a natural width of " + natural);
            assertTrue(fit.outline());
            assertEquals(14, fit.ems(), 1e-9);
        }
    }

    @Test
    @DisplayName("When the region grows to where the drawing left to right can be read, it comes back, narrowest names first")
    void theDrawingLeftToRightComesBack() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(631, 2400));
        assertFalse(fit.given(631, 600));
        assertFalse(fit.given(800, 600), "not yet: with its narrowest names it needs 1,200 at least, and 800 is two thirds of that");
        assertTrue(fit.outline());
        assertTrue(fit.given(900, 600));
        assertFalse(fit.outline());
        assertEquals(8, fit.ems(), 1e-9);
        assertFalse(fit.given(900, 1250), "it stays, scaled to 0.72");
        assertFalse(fit.outline());
    }

    @Test
    @DisplayName("A drawing left to right that came back too soon gives way once, and is not tried again until the region grows")
    void aFailedReturnIsNotRepeated() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(631, 2400));
        assertTrue(fit.given(850, 600), "1,200 was only the least it could need");
        assertFalse(fit.outline());
        assertTrue(fit.given(850, 1300), "it needs 1,300, and 850 scales that to 0.65");
        assertTrue(fit.outline());
        assertFalse(fit.given(850, 600));
        assertFalse(fit.given(880, 600), "910 is the least that would show 1,300 at the least scale");
        assertTrue(fit.outline());
        assertTrue(fit.given(920, 600));
        assertFalse(fit.outline());
    }

    @Test
    @DisplayName("A tree with room to spare after its names were narrowed widens them again only when the region has grown")
    void namesWidenWhenTheRegionGrows() {
        DecisionTreeFit fit = fit();
        assertTrue(fit.given(900, 1000));
        assertEquals(14, fit.ems(), 1e-9);
        assertFalse(fit.given(900, 700), "never at the width they were narrowed for");
        assertTrue(fit.given(1200, 700));
        assertTrue(fit.ems() > 14);
        assertFalse(fit.outline());
    }
}
