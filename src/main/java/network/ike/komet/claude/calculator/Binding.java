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
package network.ike.komet.claude.calculator;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import network.ike.komet.claude.decision.Bounds;

/**
 * What a document binds to one input of a calculator ({@code dev-calculator-blocks}): an observed
 * ANF statement by identity, with the value read from it; a value supplied in the block, an
 * {@code assume} line or an inline value where no statement exists; nothing, so the input's
 * when-missing policy decides; or a clarification the author left open. An observed statement is
 * immutable, so a what-if never changes it: it adds a {@link Supplied} binding beside it, and the
 * evaluation says which it used.
 */
public sealed interface Binding permits Binding.Observed, Binding.Supplied, Binding.Missing, Binding.Clarify {

    /** The value an observed ANF statement carries: its result, or the concept it is about. */
    record Observed(Bounds bounds, long semanticNid, OptionalLong conceptValue, long statementNid,
                    Optional<Instant> statementTime) implements Binding {
        public static Observed measure(Bounds bounds, long semanticNid, long statementNid) {
            return new Observed(bounds, semanticNid, OptionalLong.empty(), statementNid, Optional.empty());
        }

        public static Observed concept(long conceptNid, long statementNid) {
            return new Observed(Bounds.point(1), 0, OptionalLong.of(conceptNid), statementNid, Optional.empty());
        }
    }

    /** A value the block supplies itself: an assumption, or a value stated inline with no statement behind it. */
    record Supplied(Bounds bounds, long semanticNid, OptionalLong conceptValue, boolean assumption) implements Binding {
        public static Supplied measure(Bounds bounds, long semanticNid) {
            return new Supplied(bounds, semanticNid, OptionalLong.empty(), false);
        }

        public static Supplied assumed(Bounds bounds, long semanticNid) {
            return new Supplied(bounds, semanticNid, OptionalLong.empty(), true);
        }

        public static Supplied concept(long conceptNid, boolean assumption) {
            return new Supplied(Bounds.point(1), 0, OptionalLong.of(conceptNid), assumption);
        }
    }

    /** Nothing bound: the input's when-missing policy decides. */
    record Missing() implements Binding {
    }

    /** A question the author left open in place of a binding; evaluated as missing. */
    record Clarify(String question) implements Binding {
    }
}
