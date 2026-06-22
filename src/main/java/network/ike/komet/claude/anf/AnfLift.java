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

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.anthropic.AskListener;
import network.ike.komet.claude.tools.GraphTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The headless engine of the narrative lift: it grounds a clinical narrative into
 * Analysis Normal Form by running the assistant's read-only graph tools plus the
 * forced {@code emit_anf} tool, then returns the validated {@link AnfStatement}
 * (when the model emitted one) together with the model's final text.
 *
 * <p>It is UI-free on purpose, so the same engine drives an {@code AnfArea} and a
 * starter-data integration test. The lift system prompt is the distilled
 * {@code anf-lift-spec.md} resource (the projection of the ANF rules); concept
 * identity comes only from the live store via the tools, never from the model.
 */
public final class AnfLift {

    private static final Logger LOG = LoggerFactory.getLogger(AnfLift.class);

    private static final int MAX_TOKENS = 8192;
    private static final String SPEC_RESOURCE = "/network/ike/komet/claude/anf-lift-spec.md";
    private static final String FALLBACK_SYSTEM =
            "You lift a clinical narrative into Analysis Normal Form. Ground every clinical term with "
            + "the search and concept tools — never assert a code from memory — then call emit_anf exactly "
            + "once with the grounded statement.";

    private final ViewCalculator view;
    private final String apiKey;
    private final String model;

    /**
     * Creates a lift engine bound to a view, key, and model.
     *
     * @param view   the view calculator that grounds every term; must not be null
     * @param apiKey the Anthropic API key; must not be null
     * @param model  the model id (use {@code claude-opus-4-8} for the lift); null
     *               falls back to the client default
     */
    public AnfLift(ViewCalculator view, String apiKey, String model) {
        this.view = Objects.requireNonNull(view, "view");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = model;
    }

    /**
     * The outcome of a lift: the validated statement (or null if the model did not
     * emit one) and the model's final text (a clarification, or an error message).
     *
     * @param statement     the grounded statement, or null
     * @param assistantText the model's final text
     */
    public record Result(AnfStatement statement, String assistantText) {
        /**
         * Whether a validated statement was produced.
         *
         * @return true if {@link #statement()} is non-null
         */
        public boolean lifted() {
            return statement != null;
        }
    }

    /**
     * Lifts a clinical narrative to a grounded ANF statement.
     *
     * @param narrative the clinical narrative text
     * @return the lift outcome (never null)
     */
    public Result lift(String narrative) {
        return lift(narrative, AnfLiftListener.NOOP);
    }

    /**
     * Lifts a clinical narrative to a grounded ANF statement, reporting progress,
     * per-turn timing, token/cache usage, and each grounded concept to {@code listener}
     * — and to the log as fixed {@code ANF key=value} lines, so the lift's cost is
     * derivable from the log even when no UI is attached.
     *
     * @param narrative the clinical narrative text
     * @param listener  the progress/discovery observer; {@link AnfLiftListener#NOOP} when none
     * @return the lift outcome (never null)
     */
    public Result lift(String narrative, AnfLiftListener listener) {
        AnfLiftListener obs = (listener == null) ? AnfLiftListener.NOOP : listener;
        int[] sums = new int[4];
        AnfLiftListener combined = new AnfLiftListener() {
            @Override
            public void onTurnStart(int turn) {
                LOG.info("ANF turn={} start", turn);
                obs.onTurnStart(turn);
            }

            @Override
            public void onTurnEnd(int turn, long rtMillis, AskListener.Usage usage) {
                sums[0] += usage.input();
                sums[1] += usage.output();
                sums[2] += usage.cacheRead();
                sums[3] += usage.cacheCreate();
                LOG.info("ANF turn={} end rt_ms={} in={} out={} cache_read={} cache_create={}",
                        turn, rtMillis, usage.input(), usage.output(), usage.cacheRead(), usage.cacheCreate());
                obs.onTurnEnd(turn, rtMillis, usage);
            }

            @Override
            public void onToolCall(int turn, String tool, Map<String, Object> input) {
                obs.onToolCall(turn, tool, input);
            }

            @Override
            public void onToolResult(int turn, String tool, long execMillis, boolean isError) {
                LOG.info("ANF tool={} ms={} err={} turn={}", tool, execMillis, isError, turn);
                obs.onToolResult(turn, tool, execMillis, isError);
            }

            @Override
            public void onSlotDiscovered(AnfSlot slot) {
                obs.onSlotDiscovered(slot);
            }

            @Override
            public void onDone(String stopReason, int turns, long totalMillis) {
                LOG.info("ANF done stop={} turns={} total_ms={} in_sum={} out_sum={} cache_read_sum={}",
                        stopReason, turns, totalMillis, sums[0], sums[1], sums[2]);
                obs.onDone(stopReason, turns, totalMillis);
            }

            @Override
            public void onError(Throwable error, int turns, long totalMillis) {
                LOG.warn("ANF failed turns={} total_ms={} reason={}",
                        turns, totalMillis, (error == null) ? "?" : error.getMessage());
                obs.onError(error, turns, totalMillis);
            }
        };

        Consumer<AnfSlot> onDiscovered = combined::onSlotDiscovered;
        AtomicReference<AnfStatement> sink = new AtomicReference<>();
        List<AnthropicTool> tools = new ArrayList<>(new GraphTools(() -> view, onDiscovered).tools());
        tools.add(AnfTools.emitAnf(() -> view, sink, onDiscovered));

        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS);
        String text;
        try {
            // Stop as soon as the statement is recorded — the model's closing turn after
            // emit_anf is a discarded round-trip (~20% of wall-clock). This is correct while
            // a lift yields ONE statement (single sink); a multi-statement lift will instead
            // stop when the model itself is done.
            text = client.ask(systemPrompt(), tools, List.of(), narrative, combined,
                    () -> sink.get() != null);
        } catch (RuntimeException e) {
            text = "Lift failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return new Result(sink.get(), text);
    }

    /** Loads the distilled ANF lift spec from the classpath, falling back inline. */
    private static String systemPrompt() {
        try (InputStream in = AnfLift.class.getResourceAsStream(SPEC_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fall through to the inline fallback
        }
        return FALLBACK_SYSTEM;
    }
}
