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
import network.ike.komet.claude.tools.GraphTools;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<AnfStatement> sink = new AtomicReference<>();
        List<AnthropicTool> tools = new ArrayList<>(new GraphTools(() -> view).tools());
        tools.add(AnfTools.emitAnf(() -> view, sink));

        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS);
        String text;
        try {
            text = client.ask(systemPrompt(), tools, narrative);
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
