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
package network.ike.komet.claude.complexconcept.model;

/**
 * Why {@link ComplexConceptBuilder} refused an edit: a sentence for a person or the model, naming
 * the rule the edit would break. Nothing is written when an edit is refused
 * (IKE-Network/ike-issues#1312).
 */
public final class ComplexConceptRefusal extends RuntimeException {

    /**
     * A refusal with its reason.
     *
     * @param reason why the edit was refused, for a person or the model; names no nid
     */
    public ComplexConceptRefusal(String reason) {
        super(reason);
    }
}
