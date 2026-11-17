/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.rules;

import dev.ikm.komet.framework.performance.Measures;
import dev.ikm.komet.framework.performance.Statement;
import dev.ikm.komet.framework.performance.Topic;
import dev.ikm.komet.framework.performance.impl.ObservationRecord;
import dev.ikm.komet.framework.rulebase.Consequence;
import dev.ikm.komet.framework.rulebase.ConsequenceAction;
import dev.ikm.komet.framework.rulebase.ConsequenceMenu;
import dev.ikm.komet.framework.rulebase.ListedRuleSet;
import dev.ikm.komet.framework.rulebase.RuleService;
import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.entity.EntityVersion;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.authoring.ConceptProposalTools;
import network.ike.komet.claude.tools.GraphTools;
import network.ike.komet.claude.tools.GraphTools.Component;
import org.controlsfx.control.action.Action;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The assistant's tools for Komet's rules (IKE-Network/ike-issues#1391): list the rule sets, read
 * a rule source, propose one, withdraw a proposal, and run the rules in use on a component.
 *
 * <p>A rule the assistant writes is proposed, never put in use: Komet compiles it at once and
 * answers the compiler's findings, none of its code runs, and it waits in the Rules card for the
 * person to accept or discard it. No tool here accepts a rule.
 *
 * <p>Running the rules performs nothing. The rules fire as they do when a component is focused,
 * and the tool reads back what they offered.
 */
public final class RuleTools {

    private static final String NOT_ATTACHED =
            "The assistant is not attached to a journal yet, so there is no view to run the rules under.";

    private final Supplier<ViewProperties> view;
    private final Supplier<RuleService> rules;
    private final Runnable rulesChanged;

    /**
     * The tools on Komet's rule service, telling the open Rules cards when a proposal changes.
     *
     * @param view the assistant's view; may supply null before the card is bound
     */
    public RuleTools(Supplier<ViewProperties> view) {
        this(view, RuleService::get, RulesCard::refreshOpenCards);
    }

    /**
     * The tools on a rule service of the caller's choosing.
     *
     * @param view         the assistant's view; may supply null before the card is bound
     * @param rules        the rule service
     * @param rulesChanged run after a proposal is made or withdrawn, so what shows the rules can
     *                     show them again
     */
    RuleTools(Supplier<ViewProperties> view, Supplier<RuleService> rules, Runnable rulesChanged) {
        this.view = view;
        this.rules = rules;
        this.rulesChanged = rulesChanged;
    }

    /**
     * The tools to register with the Anthropic client.
     *
     * @return the tools
     */
    public List<AnthropicTool> tools() {
        return List.of(listRules(), readRule(), proposeRule(), discardRuleProposal(), runRules());
    }

    private AnthropicTool listRules() {
        return tool("list_rules", """
                        List every rule set Komet has loaded: its own, each plugin's, and each rule source \
                        written in Komet, with its rules and whether it is in use, proposed (waiting for the \
                        person to accept it) or out of use with findings. A rule source is listed with its id. \
                        Call it before answering which rules exist or are in use, and before proposing a rule, \
                        because a rule's name must be one no other rule has. Takes no input. It only reads.""",
                objectSchema(Map.of(), List.of()),
                input -> RuleSetText.list(rules.get().ruleSets().castToList()));
    }

    private AnthropicTool readRule() {
        return tool("read_rule", """
                        Read one rule source written in Komet, by the id list_rules gives: its name, whether it \
                        is in use or proposed, its findings, and its Java source. When a rule source in use has \
                        a proposed edit, both are returned, the proposal first. Komet's own rule sets and a \
                        plugin's are compiled classes with no source to read; list_rules names their rules. It \
                        only reads.""",
                objectSchema(Map.of("id", strProp("The rule source's id, as list_rules gives it.")), List.of("id")),
                input -> {
                    UUID id = id(input);
                    List<ListedRuleSet> found = sourcesWith(id);
                    if (found.isEmpty()) {
                        throw new IllegalArgumentException("No rule source has the id " + id
                                + ". list_rules gives the ids of the rule sources there are.");
                    }
                    // The proposal first: it is what the person is asked to decide on.
                    List<ListedRuleSet> proposalFirst = new ArrayList<>(found);
                    proposalFirst.sort((a, b) -> Boolean.compare(a.accepted(), b.accepted()));
                    StringBuilder text = new StringBuilder();
                    for (ListedRuleSet ruleSet : proposalFirst) {
                        text.append(text.isEmpty() ? "" : "\n\n").append(RuleSetText.detail(ruleSet));
                    }
                    return text.toString();
                });
    }

    private AnthropicTool proposeRule() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", strProp("The name the rule source is listed under, in plain words: what its rules are for."));
        properties.put("java", strProp("""
                The whole Java source: one public class of rules, written as the Rule authoring skill \
                describes."""));
        properties.put("id", strProp("""
                Optional. The id of the rule source this replaces: your own proposal, after fixing its \
                findings, or a rule source in use that you propose to edit. Omit it for a new rule source."""));
        return tool("propose_rule", """
                        Propose rules as Java source. Komet compiles the source at once and returns the \
                        compiler's findings with their line numbers. None of its code runs, and its rules are \
                        NOT in use: the proposal waits in the Rules card for the person to accept or discard, so \
                        tell them it is waiting there. You cannot accept a rule. Load the Rule authoring skill \
                        before you write one. A proposal with findings cannot be accepted: fix them and propose \
                        again with the same id. A proposed edit leaves the rule source in use as it is until the \
                        person accepts the edit. Returns the proposal's id, its rules and its findings.""",
                objectSchema(properties, List.of("name", "java")),
                input -> {
                    String name = required(input, "name");
                    String java = required(input, "java");
                    UUID id = input.get("id") == null ? UUID.randomUUID() : id(input);
                    ListedRuleSet proposed = rules.get().proposeRuleSource(new RuleSource(id, name.strip(), java));
                    rulesChanged.run();
                    return proposed.findings().isEmpty()
                            ? """
                            Proposed "%s". It compiled, and it is waiting in the Rules card for the person to \
                            accept or discard. NOTHING IS IN USE until they accept it.
                            id: %s
                            Rules: %s""".formatted(proposed.name(), id, proposed.ruleNames().makeString("; "))
                            : """
                            Proposed "%s", but it has findings and cannot be accepted as it is. Fix them and \
                            propose again with the same id.
                            id: %s
                            Findings:
                            %s""".formatted(proposed.name(), id, findings(proposed));
                });
    }

    private AnthropicTool discardRuleProposal() {
        return tool("discard_rule_proposal", """
                        Withdraw a rule source you proposed that the person has not accepted, by its id. The \
                        rule source in use with that id, if there is one, stays as it is. Use it when the person \
                        asks you to drop the proposal, or when you proposed in error.""",
                objectSchema(Map.of("id", strProp("The proposal's id, as propose_rule returned it.")), List.of("id")),
                input -> {
                    UUID id = id(input);
                    if (!rules.get().discardProposedRuleSource(id)) {
                        throw new IllegalArgumentException("No proposed rule source has the id " + id
                                + ". Nothing was withdrawn.");
                    }
                    rulesChanged.run();
                    return "Withdrew the proposed rule source " + id + ". The rules in use are as they were.";
                });
    }

    private AnthropicTool runRules() {
        return tool("run_rules", """
                        Run the rules in use on a component, as Komet runs them when the component is focused, \
                        and read what they offered: each offer and the rule method that made it. Nothing is \
                        performed and nothing is written. Use it to say what the rules offer for a component, \
                        and to check a rule the person has accepted. A proposed rule does not run. A rule source \
                        whose rule throws is taken out of use, and the result says so with the line.""",
                objectSchema(Map.of("id", strProp("The component to focus: an SCTID, or a concept's, pattern's or "
                        + "semantic's own UUID.")), List.of("id")),
                this::run);
    }

    private String run(Map<String, Object> input) {
        ViewProperties viewProperties = view.get();
        if (viewProperties == null) {
            throw new IllegalStateException(NOT_ATTACHED);
        }
        Component component = GraphTools.component(required(input, "id"), viewProperties.calculator());
        Latest<EntityVersion> latest = viewProperties.calculator().<EntityVersion>latest(component.entity().nid());
        if (!latest.isPresent()) {
            throw new IllegalArgumentException(component.name() + " has no version under the assistant's view, "
                    + "so there is nothing to focus.");
        }
        RuleService service = rules.get();
        ImmutableList<ListedRuleSet> before = service.ruleSets();
        // Rules build menu items, so they fire where Komet fires them: on the JavaFX thread.
        ImmutableList<Consequence<?>> consequences = ConceptProposalTools.onFxThread(() -> service.execute(
                "Assistant run_rules", Lists.immutable.<Statement>of(
                        new ObservationRecord(Topic.COMPONENT_FOCUSED, latest.get(), Measures.present())),
                viewProperties, viewProperties.nodeView().editCoordinate()));

        StringBuilder text = new StringBuilder();
        text.append("Ran the rules in use on ").append(component.name()).append(" [").append(component.uuid())
                .append("], as focused. Nothing was performed.\n");
        if (consequences.isEmpty()) {
            text.append("The rules offered nothing.");
        } else {
            text.append(consequences.size()).append(consequences.size() == 1 ? " offer:" : " offers:");
            for (Consequence<?> consequence : consequences) {
                text.append("\n- ").append(offered(consequence)).append(" (from ").append(consequence.ruleMethod())
                        .append(')');
            }
        }
        for (ListedRuleSet after : service.ruleSets()) {
            boolean wasInUse = before.anySatisfy(ruleSet -> ruleSet.inUse() && ruleSet.accepted()
                    && ruleSet.source().equals(after.source()));
            if (after.source().isPresent() && wasInUse && !after.inUse()) {
                text.append("\nThe rule source \"").append(after.name())
                        .append("\" was taken out of use during this run:\n").append(findings(after));
                rulesChanged.run();
            }
        }
        return text.toString();
    }

    private static String offered(Consequence<?> consequence) {
        return switch (consequence) {
            case ConsequenceMenu menu -> "menu \"" + menu.generatedMenu().getText() + "\"";
            case ConsequenceAction action when action.generatedAction() instanceof Action offer -> offer.getText();
            default -> String.valueOf(consequence.get());
        };
    }

    private List<ListedRuleSet> sourcesWith(UUID id) {
        return rules.get().ruleSets()
                .select(ruleSet -> ruleSet.source().map(RuleSource::id).filter(id::equals).isPresent())
                .castToList();
    }

    private static String findings(ListedRuleSet ruleSet) {
        return ruleSet.findings().collect(found -> "- " + RuleSetText.finding(found).replace("\n", "\n  "))
                .makeString("\n");
    }

    private static UUID id(Map<String, Object> input) {
        String id = required(input, "id");
        try {
            return UUID.fromString(id.strip());
        } catch (IllegalArgumentException notAnId) {
            throw new IllegalArgumentException("\"" + id + "\" is not a rule source id. list_rules gives the ids.");
        }
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("\"" + key + "\" is required.");
        }
        return value.toString();
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> strProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    /** A tool whose refusals come back to the model as errors. */
    private static AnthropicTool tool(String name, String description, Map<String, Object> schema,
                                      Function<Map<String, Object>, String> body) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> inputSchema() {
                return schema;
            }

            @Override
            public String execute(Map<String, Object> input) {
                try {
                    return body.apply(input);
                } catch (RuntimeException e) {
                    return "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        };
    }
}
