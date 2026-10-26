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
package network.ike.komet.claude.provider;

import network.ike.komet.claude.anthropic.AskListener;
import network.ike.komet.claude.anthropic.AnthropicTool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Store-free unit tests for the {@link OpenAiProvider} wire-format handling: request-side tool
 * definitions and the response-side navigation (choices / message / tool_calls / arguments /
 * usage) that the tool-use loop runs on. The live request path is covered by {@link OpenAiLiveIT}.
 */
class OpenAiProviderTest {

    /** A tool that records its last input, so tests can assert on the parsed arguments. */
    private static final class FakeTool implements AnthropicTool {
        private final String name;
        private final Map<String, Object> schema;
        private Map<String, Object> lastInput;

        FakeTool(String name, Map<String, Object> schema) {
            this.name = name;
            this.schema = schema;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "a fake " + name;
        }

        @Override
        public Map<String, Object> inputSchema() {
            return schema;
        }

        @Override
        public String execute(Map<String, Object> input) {
            lastInput = input;
            return "result-of-" + name;
        }
    }

    @Test
    void constructorRejectsBlankBaseUrl() {
        assertThrows(LlmProviderException.class,
                () -> new OpenAiProvider("k", null, "m", 100, 1));
        assertThrows(LlmProviderException.class,
                () -> new OpenAiProvider("k", "   ", "m", 100, 1));
    }

    @Test
    void constructorRejectsBlankModel() {
        assertThrows(LlmProviderException.class,
                () -> new OpenAiProvider("k", "https://api.openai.com/v1", null, 100, 1));
        assertThrows(LlmProviderException.class,
                () -> new OpenAiProvider("k", "https://api.openai.com/v1", "", 100, 1));
    }

    @Test
    void blankKeyIsAcceptedForLocalServers() {
        OpenAiProvider provider = new OpenAiProvider("", "http://localhost:11434/v1", "llama3", 100, 1);
        assertEquals(ProviderConfig.OPENAI, provider.id());
        assertEquals("OpenAI-compatible", provider.displayName());
    }

    @Test
    void toolDefsUseTheOpenAiFunctionShape() {
        Map<String, Object> schema = Map.of("type", "object");
        FakeTool tool = new FakeTool("lookup", schema);

        List<Map<String, Object>> defs = OpenAiProvider.toolDefs(List.of(tool));

        assertEquals(1, defs.size());
        Map<String, Object> def = defs.get(0);
        assertEquals("function", def.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) def.get("function");
        assertEquals("lookup", function.get("name"));
        assertEquals("a fake lookup", function.get("description"));
        assertEquals(schema, function.get("parameters"));
    }

    @Test
    void toolDefsOmittedWhenThereAreNoTools() {
        assertTrue(OpenAiProvider.toolDefs(List.of()).isEmpty());
    }

    @Test
    void parseArgsReadsTheJsonStringArguments() {
        Map<String, Object> call = Map.of("function",
                Map.of("name", "lookup", "arguments", "{\"term\":\"fever\"}"));

        Map<String, Object> args = OpenAiProvider.parseArgs(call);

        assertEquals("fever", args.get("term"));
    }

    @Test
    void parseArgsPassesThroughAAlreadyParsedMap() {
        Map<String, Object> args = Map.of("term", "fever");
        Map<String, Object> call = Map.of("function", Map.of("name", "lookup", "arguments", args));

        assertEquals(args, OpenAiProvider.parseArgs(call));
    }

    @Test
    void parseArgsYieldsEmptyOnMalformedOrMissingArguments() {
        assertTrue(OpenAiProvider.parseArgs(Map.of("function",
                Map.of("name", "lookup", "arguments", "{not json"))).isEmpty());
        assertTrue(OpenAiProvider.parseArgs(Map.of("function",
                Map.of("name", "lookup", "arguments", "   "))).isEmpty());
        assertTrue(OpenAiProvider.parseArgs(Map.of("function",
                Map.of("name", "lookup"))).isEmpty());
        assertTrue(OpenAiProvider.parseArgs(Map.of("id", "call_1")).isEmpty());
    }

    @Test
    void functionNameAcceptsBothShapes() {
        assertEquals("lookup", OpenAiProvider.functionName(
                Map.of("function", Map.of("name", "lookup", "arguments", "{}"))));
        assertEquals("top", OpenAiProvider.functionName(Map.of("name", "top")));
    }

    @Test
    void toolCallsOfDistinguishesTextAndToolTurns() {
        assertTrue(OpenAiProvider.toolCallsOf(Map.of("content", "just text")).isEmpty());
        Map<String, Object> call = Map.of("id", "call_1",
                "function", Map.of("name", "lookup", "arguments", "{}"));
        assertEquals(1, OpenAiProvider.toolCallsOf(Map.of("tool_calls", List.of(call))).size());
    }

    @Test
    void textOfIsNullSafe() {
        assertEquals("", OpenAiProvider.textOf(Map.of()));
        assertEquals("hello", OpenAiProvider.textOf(Map.of("content", "hello")));
    }

    @Test
    void firstChoiceAndMessageOfNavigateAWellFormedResponse() {
        Map<String, Object> message = Map.of("role", "assistant", "content", "hi");
        Map<String, Object> choice = Map.of("index", 0, "message", message, "finish_reason", "stop");
        Map<String, Object> resp = Map.of("choices", List.of(choice));

        assertEquals(message, OpenAiProvider.messageOf(OpenAiProvider.firstChoice(resp)));
    }

    @Test
    void firstChoiceRejectsAResponseWithoutChoices() {
        assertThrows(LlmProviderException.class, () -> OpenAiProvider.firstChoice(Map.of()));
        assertThrows(LlmProviderException.class,
                () -> OpenAiProvider.firstChoice(Map.of("choices", List.of())));
        assertThrows(LlmProviderException.class,
                () -> OpenAiProvider.messageOf(Map.of("index", 0)));
    }

    @Test
    void usageOfMapsTheOpenAiCounters() {
        Map<String, Object> resp = Map.of("usage",
                Map.of("prompt_tokens", 11, "completion_tokens", 22));
        AskListener.Usage usage = OpenAiProvider.usageOf(resp);
        assertEquals(11, usage.input());
        assertEquals(22, usage.output());

        assertEquals(new AskListener.Usage(0, 0, 0, 0), OpenAiProvider.usageOf(Map.of()));
    }

    @Test
    void assistantTurnMessageEchoesContentAndToolCalls() {
        Map<String, Object> call = Map.of("id", "call_1",
                "function", Map.of("name", "lookup", "arguments", "{}"));
        Map<String, Object> message = Map.of("content", "thinking…", "tool_calls", List.of(call));

        Map<String, Object> echo = OpenAiProvider.assistantTurnMessage(message, List.of(call));

        assertEquals("assistant", echo.get("role"));
        assertEquals("thinking…", echo.get("content"));
        assertNotNull(echo.get("tool_calls"));
        assertEquals(List.of(call), echo.get("tool_calls"));
    }

    @Test
    void toolResultMessageExecutesTheRequestedToolWithParsedArgs() {
        FakeTool tool = new FakeTool("lookup", Map.of("type", "object"));
        Map<String, Object> call = Map.of("id", "call_1",
                "function", Map.of("name", "lookup", "arguments", "{\"term\":\"fever\"}"));

        Map<String, Object> result = OpenAiProvider.toolResultMessage(call,
                Map.of("lookup", tool), 0, AskListener.NOOP);

        assertEquals("tool", result.get("role"));
        assertEquals("call_1", result.get("tool_call_id"));
        assertEquals("result-of-lookup", result.get("content"));
        assertEquals("fever", tool.lastInput.get("term"));
    }

    @Test
    void toolResultMessageReportsUnknownTools() {
        Map<String, Object> call = Map.of("id", "call_2",
                "function", Map.of("name", "nope", "arguments", "{}"));

        Map<String, Object> result = OpenAiProvider.toolResultMessage(call, Map.of(), 0, AskListener.NOOP);

        assertEquals("tool", result.get("role"));
        assertEquals("call_2", result.get("tool_call_id"));
        assertInstanceOf(String.class, result.get("content"));
        assertTrue(((String) result.get("content")).startsWith("Unknown tool:"));
    }

    @Test
    void providerIdentityAndConfigDefaults() {
        OpenAiProvider provider = new OpenAiProvider("  sk-x  ", "http://host:1/v1/", "gpt-4o", 0, 0);
        assertInstanceOf(OpenAiProvider.class, provider);
        assertEquals(ProviderConfig.OPENAI, provider.id());
    }
}
