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
package network.ike.komet.claude.anf;

import network.ike.komet.claude.anthropic.AskListener;

/**
 * The narrative lift's observer: an {@link AskListener} (turn / tool / usage / done /
 * error) extended with structured concept discovery, so a UI can show live progress
 * and a growing inventory of grounded concepts <em>as</em> the lift runs rather than
 * only at the end.
 *
 * <p>Discovery is typed to the three-way {@link AnfSlot} from the start. The Demo&nbsp;0
 * lift only ever delivers {@link AnfSlot.Grounded} values, but Candidate and Clarify
 * slots will flow through the same channel with no signature change.
 */
public interface AnfLiftListener extends AskListener {

    /**
     * Fired when the lift grounds, proposes, or surfaces a slot — as the model confirms a
     * concept with the {@code concept} tool, and as {@code emit_anf} validates a statement.
     * The slot may be a {@link AnfSlot.Grounded}, an {@link AnfSlot.Candidate}, or an
     * {@link AnfSlot.Clarify}.
     *
     * @param slot the discovered slot
     */
    default void onSlotDiscovered(AnfSlot slot) {
    }

    /**
     * Fired when {@code emit_anf} validates and records one statement of a multi-statement
     * lift, so a streaming UI can render statement {@code #index} as it lands rather than only
     * when the whole lift completes.
     *
     * @param statement the validated statement
     * @param index     its zero-based position in the lift's statement list
     */
    default void onStatementEmitted(AnfStatement statement, int index) {
    }

    /** The do-nothing lift listener. */
    AnfLiftListener NOOP = new AnfLiftListener() {
    };
}
