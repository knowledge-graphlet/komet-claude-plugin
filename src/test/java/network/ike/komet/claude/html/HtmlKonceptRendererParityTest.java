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
package network.ike.komet.claude.html;

import network.ike.docs.konceptcore.KonceptAppearance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The #865 parity gate for the HTML/email medium: the inline-CSS constants every email badge
 * carries embed exactly the shared {@link KonceptAppearance} golden values — pill fill, the
 * floating border (email is a floating context), the spec radius and unified pads at
 * label-relative em ratios, small caps at normal weight, and the retired treatment. Drift
 * between the spec and the email output fails this build.
 */
class HtmlKonceptRendererParityTest {

    private static final KonceptAppearance SPEC = KonceptAppearance.defaults();

    @Test
    void pillEmbedsTheGoldenValues() {
        assertTrue(HtmlKonceptRenderer.PILL.contains("background:" + SPEC.pillFillHex()),
                "spec pill fill");
        assertTrue(HtmlKonceptRenderer.PILL.contains(
                        "border:1px solid " + SPEC.floatingBorderHex()),
                "the shared floating border — email is a floating context");
        assertTrue(HtmlKonceptRenderer.PILL.contains("border-radius:0.50em"),
                "spec radius at the label-relative ratio");
        assertTrue(HtmlKonceptRenderer.PILL.contains("padding:0.08em 0.50em 0.08em 0.33em"),
                "the spec's unified 1/6/1/4 pads");
    }

    @Test
    void labelIsSmallCapsNormalWeightInTheSpecColours() {
        assertTrue(HtmlKonceptRenderer.LABEL.contains("color:" + SPEC.labelColorHex()));
        assertTrue(HtmlKonceptRenderer.LABEL.contains("font-variant:small-caps"));
        assertFalse(HtmlKonceptRenderer.LABEL.contains("font-weight"),
                "normal weight (#863 — the semibold was the one non-normal renderer)");
    }

    @Test
    void retiredLabelStrikesThroughInTheRetiredColour() {
        assertTrue(HtmlKonceptRenderer.LABEL_INACTIVE.contains(
                "color:" + SPEC.labelColorInactiveHex()));
        assertTrue(HtmlKonceptRenderer.LABEL_INACTIVE.contains("text-decoration:line-through"));
    }
}
