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
package network.ike.komet.claude.anf.model;

import java.util.List;
import java.util.Objects;

/**
 * What an attribute vertex carries in place of the concept it would reference, when there is no
 * concept yet: a {@link Candidate}, a clear meaning the knowledge base does not carry, or a
 * {@link Clarification}, a question the narrative leaves open. The narrative lift emits both;
 * the editor shows either as an unsettled row. The vertex keeps its meaning and its place in
 * the tree, so settling it later is an update of that vertex, never a new one.
 */
public sealed interface AnfProvisional permits AnfProvisional.Candidate, AnfProvisional.Clarification {

    /**
     * A clear meaning the knowledge base does not yet carry.
     *
     * @param label          a label for the provisional concept; never null
     * @param text           the narrative text the meaning came from; never null
     * @param disposition    what is to be done with it, the name of the lift's disposition;
     *                       never null
     * @param nearestMatches identifiers of the nearest existing concepts; never null, possibly
     *                       empty
     */
    record Candidate(String label, String text, String disposition, List<String> nearestMatches)
            implements AnfProvisional {
        /** Validates and defensively copies the candidate. */
        public Candidate {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(disposition, "disposition");
            nearestMatches = nearestMatches == null ? List.of() : List.copyOf(nearestMatches);
        }
    }

    /**
     * A question the narrative leaves open about an attribute.
     *
     * @param field    the attribute the question is about; never null
     * @param question the question to put to the author; never null
     */
    record Clarification(String field, String question) implements AnfProvisional {
        /** Validates the clarification. */
        public Clarification {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(question, "question");
        }
    }
}
