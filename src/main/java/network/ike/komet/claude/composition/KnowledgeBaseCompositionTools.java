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
package network.ike.komet.claude.composition;

import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import network.ike.komet.claude.anthropic.AnthropicTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The assistant's composition tool (IKE-Network/ike-issues#1304), registered beside the graph
 * tools. {@code kb_composition} answers what the open knowledge base is made of — the releases
 * loaded, the inputs of the latest assembly, the assembly history, and the exports — from the same
 * registrations, and in the same words, as the Knowledge Base Composition Journal card, so the
 * assistant can answer without the card open. It only reads.
 */
public final class KnowledgeBaseCompositionTools {

    private static final String NO_VIEW = "No active knowledge-base view is available.";

    private KnowledgeBaseCompositionTools() { }

    /**
     * @param view the view that names sources; the supplier may return {@code null} before bind
     * @return the composition tool
     */
    public static List<AnthropicTool> tools(Supplier<ViewCalculator> view) {
        return List.of(kbComposition(view));
    }

    static AnthropicTool kbComposition(Supplier<ViewCalculator> view) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "kb_composition";
            }

            @Override
            public String description() {
                return "Read what the open knowledge base is made of, from the registrations the IKE Registrar wrote "
                        + "into it: when it was assembled and by which classifier, each release loaded (name, publisher's "
                        + "release date, artifact and resolved version, checksum, importer and options, components "
                        + "written, versioned, unchanged and retired, the releases it was layered on, and the importer "
                        + "versions that have read it), the inputs of the latest assembly, the assembly history, and the "
                        + "exports taken from it. Call this before answering which "
                        + "sources, releases or versions the knowledge base contains, when it was built, or whether a "
                        + "given release is loaded. Takes no input.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "object");
                schema.put("properties", Map.of());
                schema.put("required", List.of());
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    ViewCalculator v = view.get();
                    if (v == null) {
                        return NO_VIEW;
                    }
                    return KnowledgeBaseComposition.text(v, KnowledgeBaseComposition.read());
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }
}
