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
 * A tool that Claude may call during a conversation. Implementations are
 * executed <em>in-process</em> by {@link AnthropicClient} when Claude emits a
 * matching {@code tool_use} block, and their textual output is returned to
 * Claude as a {@code tool_result}.
 *
 * <p>For the Komet assistant these are read-only knowledge-graph queries
 * (concept resolution, taxonomy navigation, axiom read-out, search) backed by
 * the active window's {@code ViewCalculator}.
 */
public interface AnthropicTool {

    /**
     * The tool's unique name, as sent in the Messages API {@code tools} array
     * and matched against {@code tool_use.name}.
     *
     * @return the tool name (stable, lowercase-with-underscores by convention)
     */
    String name();

    /**
     * A natural-language description telling Claude when and how to use the
     * tool. Be prescriptive about <em>when</em> to call it.
     *
     * @return the tool description
     */
    String description();

    /**
     * The JSON Schema for the tool's input, as a nested {@link Map}/{@link
     * java.util.List} structure (serialized to the {@code input_schema} field).
     *
     * @return the input schema as a JSON-shaped map
     */
    Map<String, Object> inputSchema();

    /**
     * Executes the tool against Claude-supplied input and returns a textual
     * result. Implementations must not mutate the knowledge base.
     *
     * @param input the parsed {@code tool_use.input} object
     * @return the result text to hand back to Claude
     */
    String execute(Map<String, Object> input);
}
