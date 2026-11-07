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
package network.ike.komet.claude.koncept;

import dev.ikm.komet.framework.controls.KonceptStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the plain-text cluster the Zulip message leads a Koncept with: the glyph alone carries
 * the status there, so a concept with no stated definition must not lose its {@code C}.
 */
class KonceptStatusMarkTest {

    @Test
    void aConceptWithNoStatedDefinitionReadsAsC() {
        assertEquals("C", KonceptStatusMark.clusterText(KonceptStatus.UNDEFINED));
    }

    @Test
    void aCopulaCarriesTheForkItsStatusNames() {
        assertEquals("≡", KonceptStatusMark.clusterText(KonceptStatus.DEFINED));
        assertEquals("⊑⋎", KonceptStatusMark.clusterText(KonceptStatus.PRIMITIVE_MULTIPARENT));
        assertEquals("⊤", KonceptStatusMark.clusterText(KonceptStatus.ROOT));
    }

    @Test
    void noMarkIsTheEmptyString() {
        assertEquals("", KonceptStatusMark.clusterText(KonceptStatus.NONE));
    }
}
