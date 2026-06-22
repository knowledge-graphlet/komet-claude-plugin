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
package network.ike.komet.claude.anthropic;

import java.util.Map;

/**
 * An optional observer of an {@link AnthropicClient#ask} tool-use exchange: it
 * receives one event per turn (one API round-trip) and per tool call, with
 * wall-clock timing and token/cache usage, plus a terminal completion or error.
 *
 * <p>It carries no knowledge of any tool's domain — discovery of domain objects
 * (for example grounded ANF slots) is wired separately into the tools themselves —
 * so this interface stays in the generic client package and never depends on a
 * feature package.
 *
 * <p>All methods default to no-ops; a caller overrides only what it needs, and
 * {@link #NOOP} is the do-nothing instance the un-instrumented {@code ask}
 * overloads pass. Callbacks fire on the calling (worker) thread; a UI consumer
 * must marshal to the FX thread itself.
 */
public interface AskListener {

    /**
     * Fired when a turn (one API round-trip) is about to be sent.
     *
     * @param turn the zero-based turn index
     */
    default void onTurnStart(int turn) {
    }

    /**
     * Fired when a turn's response has been received.
     *
     * @param turn     the zero-based turn index
     * @param rtMillis the wall-clock round-trip time of the API call, in milliseconds
     * @param usage    the token/cache usage reported for the turn
     */
    default void onTurnEnd(int turn, long rtMillis, Usage usage) {
    }

    /**
     * Fired before a requested tool is executed.
     *
     * @param turn  the zero-based turn index
     * @param tool  the tool name
     * @param input the tool input arguments
     */
    default void onToolCall(int turn, String tool, Map<String, Object> input) {
    }

    /**
     * Fired after a requested tool has executed.
     *
     * @param turn       the zero-based turn index
     * @param tool       the tool name
     * @param execMillis the wall-clock execution time of the tool, in milliseconds
     * @param isError    whether the tool reported an error
     */
    default void onToolResult(int turn, String tool, long execMillis, boolean isError) {
    }

    /**
     * Fired once when the exchange completes normally — a final text answer or the
     * turn cap.
     *
     * @param stopReason  the API stop reason, or a sentinel for the turn cap
     * @param turns       the number of turns taken
     * @param totalMillis the total wall-clock time of the exchange, in milliseconds
     */
    default void onDone(String stopReason, int turns, long totalMillis) {
    }

    /**
     * Fired once if the exchange fails (after retries).
     *
     * @param error       the failure
     * @param turns       the number of turns attempted
     * @param totalMillis the total wall-clock time before the failure, in milliseconds
     */
    default void onError(Throwable error, int turns, long totalMillis) {
    }

    /**
     * Token and cache usage for a single turn.
     *
     * @param input       input tokens
     * @param output      output tokens
     * @param cacheRead   tokens read from the prompt cache
     * @param cacheCreate tokens written to the prompt cache
     */
    record Usage(int input, int output, int cacheRead, int cacheCreate) {

        /**
         * Coerces a Messages API {@code usage} map (whose numbers may parse as
         * {@code Long} or {@code Double}) into integer counters, defaulting any
         * absent field — for example {@code cache_creation_input_tokens} on a cache
         * hit — to zero so every consumer sees a complete record.
         *
         * @param usage the raw usage map, or null
         * @return a fully-populated usage record (never null)
         */
        public static Usage from(Map<String, Object> usage) {
            if (usage == null) {
                return new Usage(0, 0, 0, 0);
            }
            return new Usage(
                    intOf(usage, "input_tokens"),
                    intOf(usage, "output_tokens"),
                    intOf(usage, "cache_read_input_tokens"),
                    intOf(usage, "cache_creation_input_tokens"));
        }

        private static int intOf(Map<String, Object> usage, String key) {
            Object value = usage.get(key);
            return value instanceof Number n ? n.intValue() : 0;
        }
    }

    /** The do-nothing listener used by the un-instrumented {@code ask} overloads. */
    AskListener NOOP = new AskListener() {
    };
}
