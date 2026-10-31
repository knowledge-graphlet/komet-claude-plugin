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
package network.ike.komet.claude.anf.element;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import network.ike.komet.claude.anf.AnfLiftListener;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Step one of the two-step lift ({@code dev-anf-element-isolation}): reads a note and returns its
 * clinical elements through the {@link IsolateElementsTool}, under the isolation spec, the ANF
 * guide's identify-elements procedure distilled. It searches nothing; grounding is step two's.
 */
public final class ElementIsolation {

    private static final Logger LOG = LoggerFactory.getLogger(ElementIsolation.class);
    private static final int MAX_TOKENS = 8192;
    private static final int MAX_TURNS = 4;
    private static final String SPEC_RESOURCE = "/network/ike/komet/claude/anf-isolate-spec.md";
    private static final String FALLBACK = "Isolate the clinical elements of the note: one fact per element, "
            + "each a plain standalone sentence with the exact words of the note it rests on. Call "
            + IsolateElementsTool.NAME + " once with them all.";

    private final String apiKey;
    private final String model;

    public ElementIsolation(String apiKey, String model) {
        this.apiKey = Objects.requireNonNull(apiKey, "apiKey");
        this.model = model;
    }

    /** What an isolation produced: the elements when the tool recorded them, and the model's text. */
    public record Result(Optional<ElementSet> elements, String assistantText) {
    }

    public Result isolate(String narrative, AnfLiftListener listener) {
        AtomicReference<ElementSet> recorded = new AtomicReference<>();
        List<AnthropicTool> tools = List.of(new IsolateElementsTool(narrative, recorded::set));
        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS, MAX_TURNS);
        String text;
        try {
            text = client.ask(systemPrompt(), tools, List.of(), narrative,
                    listener == null ? AnfLiftListener.NOOP : listener, () -> recorded.get() != null);
        } catch (RuntimeException e) {
            LOG.warn("isolation failed", e);
            text = "Isolation failed: " + (e.getMessage() == null ? e.toString() : e.getMessage());
        }
        return new Result(Optional.ofNullable(recorded.get()), text);
    }

    static String systemPrompt() {
        try (InputStream in = ElementIsolation.class.getResourceAsStream(SPEC_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // the inline fallback
        }
        return FALLBACK;
    }
}
