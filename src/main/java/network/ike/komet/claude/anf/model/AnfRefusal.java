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

/**
 * An edit the {@link AnfExpressionBuilder} refuses, with the reason: a second circumstance, a
 * child its parent does not admit, a measure whose lower bound exceeds its upper, an association
 * recorded against its canonical direction, and the rest of the statement tree's rules. The
 * builder never refuses silently, and never writes a tree that breaks a rule; the editor reports
 * the reason on the row, and the assistant's tool returns it to the model.
 */
public final class AnfRefusal extends RuntimeException {

    /**
     * A refusal with its reason.
     *
     * @param reason why the edit was refused, for a person or the model; names no nid
     */
    public AnfRefusal(String reason) {
        super(reason);
    }
}
