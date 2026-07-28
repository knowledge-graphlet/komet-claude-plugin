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

import dev.ikm.komet.framework.controls.KonceptBadge;
import dev.ikm.komet.framework.controls.KonceptStatus;
import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;

/**
 * Resolves the logical-status mark a Koncept badge leads with (ike-issues#742 amendment /
 * #862/#863) for the plugin's static renderers — the Zulip inline markdown, the Java2D PNG
 * compositor, and the HTML/email badge — which compute the status once at compose time
 * (static media have no live view to re-render under).
 *
 * <p>Classification is the framework's ({@link KonceptBadge#computeStatus}); the glyph and
 * colour vocabulary is the single-sourced koncept-core enum reached via
 * {@link KonceptStatus#core()}, so every medium renders the same cluster by construction.
 */
public final class KonceptStatusMark {

    private KonceptStatusMark() {
    }

    /**
     * The logical-status classification for {@code nid}, or {@link KonceptStatus#NONE} when
     * it cannot be resolved — a rendering must never fail because the status could not be
     * determined.
     *
     * @param nid  the component nid
     * @param view the view used to classify; {@code null} yields {@link KonceptStatus#NONE}
     * @return the resolved status, never {@code null}
     */
    public static KonceptStatus resolve(int nid, ViewCalculator view) {
        if (view == null) {
            return KonceptStatus.NONE;
        }
        try {
            return KonceptBadge.computeStatus(nid, view, PremiseType.INFERRED);
        } catch (RuntimeException e) {
            return KonceptStatus.NONE;
        }
    }

    /**
     * The cluster's plain-text form ({@code ≡}, {@code ⊑⋎}, {@code ⊤}, …) — the data channel
     * for media that carry the glyph but not necessarily its colour (Zulip markdown, matching
     * the FO/Prawn convention in the adoc renderer).
     *
     * @param status the resolved status
     * @return the cluster text, or the empty string for {@link KonceptStatus#NONE}
     */
    public static String clusterText(KonceptStatus status) {
        return status.core().cluster(status.isMultiParent());
    }
}
