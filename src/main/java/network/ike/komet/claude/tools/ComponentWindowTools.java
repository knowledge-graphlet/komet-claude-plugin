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
package network.ike.komet.claude.tools;

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.kview.events.MakeKLWindowEvent;
import dev.ikm.komet.kview.events.genediting.MakeGenEditingWindowEvent;
import dev.ikm.komet.layout.editor.StandardEditorWindows;
import dev.ikm.tinkar.events.Evt;
import dev.ikm.tinkar.events.EvtBusFactory;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.tools.GraphTools.Component;
import network.ike.komet.claude.tools.GraphTools.ComponentKind;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Opens any component in the person's journal: {@code open_component}, for what
 * {@code open_concept_proposal} does for a proposal. The identifier is resolved as the read tools
 * resolve it, under the assistant's view, and the window is given that view, so it shows what the
 * assistant reads.
 *
 * <p>A concept or pattern opens in its standard KL window. A semantic opens in Komet's window for
 * a semantic, the one search results open, while no KL window shows a single semantic.
 *
 * <p>Opening writes nothing: the standard window opens through
 * {@link MakeKLWindowEvent#VIEW_STANDARD_WINDOW}, which leaves out the seeding that can commit. What
 * the person then does in the window is theirs.
 */
public final class ComponentWindowTools {

    private final Supplier<ViewProperties> view;
    private final Supplier<UUID> journalTopic;

    /**
     * @param view         the assistant's view; may supply null before the card is bound
     * @param journalTopic the journal the window opens in; may supply null
     */
    public ComponentWindowTools(Supplier<ViewProperties> view, Supplier<UUID> journalTopic) {
        this.view = view;
        this.journalTopic = journalTopic;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the tools
     */
    public List<AnthropicTool> tools() {
        return List.of(openComponent());
    }

    private AnthropicTool openComponent() {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "open_component";
            }

            @Override
            public String description() {
                return """
                        Open a concept, pattern or semantic in a new window of the person's journal, under the
                        view your read tools use, so the window shows what you read. A concept or pattern opens
                        in its KL window; a semantic opens in Komet's window for a semantic. Use it ONLY when the
                        person asks to open or see a component in Komet, never on your own initiative. To open a
                        proposal you wrote, use open_concept_proposal; a KL window is never how a proposal is
                        reviewed. Opening writes nothing, commits nothing and publishes nothing. Each call opens
                        a new window. An identifier that does not resolve opens nothing and returns an error.
                        Returns the component's name, UUID and kind.""";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of("type", "object",
                        "properties", Map.of(
                                "id", Map.of("type", "string", "description",
                                        "An SCTID, or a concept's, pattern's or semantic's own UUID."),
                                "kind", Map.of("type", "string",
                                        "enum", Arrays.stream(ComponentKind.values()).map(ComponentKind::word).toList(),
                                        "description", """
                                                Optional. What you expect the component to be; a component of \
                                                another kind is refused. Omit it to take the kind from the \
                                                identifier.""")),
                        "required", List.of("id"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return open(input);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    return "Error: " + e.getMessage() + " Nothing was opened.";
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()) + " Nothing was opened.";
                }
            }
        };
    }

    private String open(Map<String, Object> input) {
        ViewProperties viewProperties = view.get();
        UUID topic = journalTopic.get();
        if (viewProperties == null || topic == null) {
            throw new IllegalStateException("The assistant is not attached to a journal yet, so there is nowhere to open a window.");
        }
        ComponentKind expected = expectedKind(input.get("kind"));
        String id = input.get("id") == null ? null : input.get("id").toString();
        Component component = GraphTools.component(id, viewProperties.calculator());
        if (expected != null && expected != component.kind()) {
            throw new IllegalArgumentException("'%s' is a %s (%s), not a %s."
                    .formatted(id, component.kind().word(), component.name(), expected.word()));
        }
        Evt event = windowEvent(component, viewProperties);
        ConceptProposalTools.onFxThread(() -> {
            EvtBusFactory.getDefaultEvtBus().publish(topic, event);
            return null;
        });
        return """
                Opened a new %s window in the person's journal.
                name: %s
                uuid: %s
                kind: %s%s
                Nothing was written.""".formatted(switch (component.kind()) {
                    case CONCEPT -> "KL concept";
                    case PATTERN -> "KL pattern";
                    case SEMANTIC -> "semantic";
                }, component.name(), component.uuid(), component.kind().word(),
                component.retired() ? "\nstate: retired in this view" : "");
    }

    /** The kind asked for, or null when none was. */
    private static ComponentKind expectedKind(Object kind) {
        if (kind == null || kind.toString().isBlank()) {
            return null;
        }
        return Arrays.stream(ComponentKind.values())
                .filter(candidate -> candidate.word().equalsIgnoreCase(kind.toString().strip()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "kind must be concept, semantic or pattern, not '" + kind + "'."));
    }

    /** The event that opens the component's window under the view. */
    private Evt windowEvent(Component component, ViewProperties viewProperties) {
        return switch (component.kind()) {
            case CONCEPT -> MakeKLWindowEvent.viewStandardWindow(this, component.entity(),
                    StandardEditorWindows.CONCEPT_WINDOW, viewProperties);
            case PATTERN -> MakeKLWindowEvent.viewStandardWindow(this, component.entity(),
                    StandardEditorWindows.PATTERN_WINDOW, viewProperties);
            case SEMANTIC -> new MakeGenEditingWindowEvent(this, MakeGenEditingWindowEvent.OPEN_GEN_EDIT,
                    component.entity(), viewProperties);
        };
    }
}
