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

import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.anthropic.AskListener;
import network.ike.komet.claude.tools.GraphTools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The headless engine of the semantic lift: it grounds a request into a {@link SemanticInstance}
 * of a bound pattern by running the assistant's read-only graph tools plus the forced
 * {@code emit_semantic} tool (whose schema is built from the pattern's field definitions), then
 * returns the validated instances together with the model's final text.
 *
 * <p>It is the generalization of {@code AnfLift}: where the narrative lift hard-codes the ANF
 * schema and prompt, this builds both from the bound pattern's {@link PatternFields}. The system
 * prompt is three layers — the fixed invariants, the user-editable general guidance, and the
 * computed field digest — and concept identity comes only from the live store via the tools and
 * the {@link Grounder}, never from the model. UI-free on purpose, so the same engine drives a
 * future area and a starter-data integration test.
 */
public final class SemanticLift {

    private static final int MAX_TOKENS = 8192;
    /** Tool-use turn cap: a backstop, not the normal stop — the model signals completion with {@code finish_semantic}. */
    private static final int LIFT_MAX_TURNS = 40;
    private static final String INVARIANTS_RESOURCE = "/network/ike/komet/claude/semantic-lift-invariants.md";
    private static final String GUIDANCE_RESOURCE = "/network/ike/komet/claude/semantic-lift-guidance.md";
    private static final String FALLBACK_INVARIANTS =
            "You build a structured semantic for a pattern by grounding a request against the open "
            + "knowledge base. Produce it ONLY by calling emit_semantic. Never invent an identifier; every "
            + "component reference must be one the tools returned, and emit_semantic rejects any that does "
            + "not resolve. Honor each field's datatype. You are read-only.";
    private static final String FALLBACK_GUIDANCE =
            "Work field by field, using each field's meaning, purpose, and datatype to decide what belongs "
            + "there. Fill the fields the request supports; leave the others empty.";

    private final ViewCalculator view;
    private final String apiKey;
    private final String model;
    private final int patternNid;

    /**
     * Creates a lift engine bound to a view, key, model, and target pattern.
     *
     * @param view       the view calculator that grounds every reference; must not be null
     * @param apiKey     the Anthropic API key; must not be null
     * @param model      the model id (use the more capable model for the lift); null falls back to
     *                   the client default
     * @param patternNid the nid of the pattern to lift a semantic for
     */
    public SemanticLift(ViewCalculator view, String apiKey, String model, int patternNid) {
        this.view = Objects.requireNonNull(view, "view");
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = model;
        this.patternNid = patternNid;
    }

    /**
     * The outcome of a lift: the validated instances in emit order, whether the lift was truncated
     * at the turn cap, and the model's final text.
     *
     * @param instances     the grounded instances in emit order; never null (possibly empty)
     * @param truncated     true if the lift stopped at the turn cap, so more may have been intended
     * @param assistantText the model's final text (a clarification or an error message)
     */
    public record Result(List<SemanticInstance> instances, boolean truncated, String assistantText) {
        /**
         * Validates and defensively copies the result.
         */
        public Result {
            instances = (instances == null) ? List.of() : List.copyOf(instances);
        }

        /**
         * Whether at least one instance was produced.
         *
         * @return true if {@link #instances()} is non-empty
         */
        public boolean lifted() {
            return !instances.isEmpty();
        }
    }

    /**
     * Lifts a request into grounded semantic instances of the bound pattern.
     *
     * @param request the request text (e.g. "a semantic for type 2 diabetes in a patient on ozempic")
     * @return the lift outcome (never null)
     */
    public Result lift(String request) {
        return lift(request, SemanticLiftListener.NOOP);
    }

    /**
     * Lifts a request, reporting progress and discovery to {@code listener}.
     *
     * @param request  the request text
     * @param listener the progress/discovery observer; {@link SemanticLiftListener#NOOP} when none
     * @return the lift outcome (never null)
     */
    public Result lift(String request, SemanticLiftListener listener) {
        SemanticLiftListener obs = (listener == null) ? SemanticLiftListener.NOOP : listener;

        Latest<PatternEntityVersion> latestPattern = view.stampCalculator().latest(patternNid);
        if (!latestPattern.isPresent()) {
            return new Result(List.of(), false,
                    "No active pattern version is available to lift a semantic for (nid " + patternNid + ").");
        }
        PatternFields fields = PatternFields.from(patternNid, latestPattern.get(), view);

        List<SemanticInstance> instances = new CopyOnWriteArrayList<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<String> stopReason = new AtomicReference<>("");

        SemanticTools.InstanceSink sink = instance -> {
            if (instances.contains(instance)) {
                return false;
            }
            instances.add(instance);
            obs.onInstanceEmitted(instance, instances.size() - 1);
            return true;
        };
        Consumer<ComponentSlot> onDiscovered = slot -> {
            try {
                obs.onSlotDiscovered(slot);
            } catch (RuntimeException ignored) {
                // a UI callback must never break the lift
            }
        };

        List<AnthropicTool> tools = new ArrayList<>(new GraphTools(() -> view).tools());
        tools.add(SemanticTools.emitSemantic(fields, SemanticGrounding.forView(view), sink, onDiscovered));
        tools.add(SemanticTools.finishLift(() -> finished.set(true)));

        AskListener wrapper = new AskListener() {
            @Override
            public void onTurnStart(int turn) {
                obs.onTurnStart(turn);
            }

            @Override
            public void onTurnEnd(int turn, long rtMillis, Usage usage) {
                obs.onTurnEnd(turn, rtMillis, usage);
            }

            @Override
            public void onToolCall(int turn, String tool, java.util.Map<String, Object> input) {
                obs.onToolCall(turn, tool, input);
            }

            @Override
            public void onToolResult(int turn, String tool, long execMillis, boolean isError) {
                obs.onToolResult(turn, tool, execMillis, isError);
            }

            @Override
            public void onRetry(int turn, int attempt, int maxAttempts, long waitMillis, String reason) {
                obs.onRetry(turn, attempt, maxAttempts, waitMillis, reason);
            }

            @Override
            public void onDone(String reason, int turns, long totalMillis) {
                stopReason.set(reason == null ? "" : reason);
                obs.onDone(reason, turns, totalMillis);
            }

            @Override
            public void onError(Throwable error, int turns, long totalMillis) {
                obs.onError(error, turns, totalMillis);
            }
        };

        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS, LIFT_MAX_TURNS);
        String text;
        try {
            text = client.ask(systemPrompt(fields), tools, List.of(), request, wrapper, finished::get);
        } catch (RuntimeException e) {
            text = "Lift failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        boolean truncated = "max_turns".equals(stopReason.get());
        return new Result(instances, truncated, text);
    }

    /** Assembles the three-layer system prompt: fixed invariants, editable guidance, field digest. */
    private static String systemPrompt(PatternFields fields) {
        return loadResource(INVARIANTS_RESOURCE, FALLBACK_INVARIANTS)
                + "\n\n" + loadResource(GUIDANCE_RESOURCE, FALLBACK_GUIDANCE)
                + "\n\n" + fields.digest();
    }

    /** Loads a classpath resource as UTF-8, falling back to an inline default. */
    private static String loadResource(String resource, String fallback) {
        try (InputStream in = SemanticLift.class.getResourceAsStream(resource)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fall through to the inline fallback
        }
        return fallback;
    }
}
