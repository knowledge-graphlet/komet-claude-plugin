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
package network.ike.komet.claude.semantic;

import network.ike.komet.claude.anthropic.AskListener;

/**
 * The semantic lift's observer: an {@link AskListener} (turn / tool / usage / done / error)
 * extended with structured slot discovery and per-instance emission, so a UI can show live
 * progress and a growing inventory <em>as</em> the lift runs. Callbacks fire on the worker thread;
 * a UI consumer marshals to the FX thread itself.
 */
public interface SemanticLiftListener extends AskListener {

    /**
     * Fired when the lift grounds, proposes, or surfaces a slot as {@code emit_semantic} validates
     * an instance. The slot may be {@link ComponentSlot.Grounded}, {@link ComponentSlot.Candidate},
     * or {@link ComponentSlot.Clarify}.
     *
     * @param slot the discovered slot
     */
    default void onSlotDiscovered(ComponentSlot slot) {
    }

    /**
     * Fired when {@code emit_semantic} validates and records one instance, so a streaming UI can
     * render it as it lands.
     *
     * @param instance the validated instance
     * @param index    its zero-based position in the lift's instance list
     */
    default void onInstanceEmitted(SemanticInstance instance, int index) {
    }

    /** The do-nothing lift listener. */
    SemanticLiftListener NOOP = new SemanticLiftListener() {
    };
}
