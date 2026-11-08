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

import network.ike.komet.claude.stamp.KonceptChips;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A wrapped name is balanced: never split inside a word, never past three lines, whatever cap the fit asks. */
class KonceptChipsBalanceTest {

    private static final double PER_CHARACTER = 7;
    private static final double CHROME = 40;

    private static double natural(String name) {
        return CHROME + name.length() * PER_CHARACTER;
    }

    @Test
    void aLongNameRunsToThreeLinesAtMost() {
        String name = "inspection finds the problem and the data are excluded from the submission";
        double cap = KonceptChips.balancedCap(100, natural(name), name, CHROME);
        double textPerLine = cap - CHROME;
        assertTrue(name.length() * PER_CHARACTER / textPerLine <= 3, "lines: " + name.length() * PER_CHARACTER / textPerLine);
    }

    @Test
    void aWordIsNeverBroken() {
        String name = "pharmacoepidemiological note";
        double cap = KonceptChips.balancedCap(60, natural(name), name, CHROME);
        assertTrue(cap - CHROME >= "pharmacoepidemiological".length() * PER_CHARACTER);
    }

    @Test
    void aCapWideEnoughStands() {
        String name = "site data";
        assertEquals(400, KonceptChips.balancedCap(400, natural(name), name, CHROME));
    }
}
