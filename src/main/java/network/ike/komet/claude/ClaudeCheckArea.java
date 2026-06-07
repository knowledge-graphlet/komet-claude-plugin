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
package network.ike.komet.claude;

import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.layout.KlArea;
import dev.ikm.komet.layout.area.AreaGridSettings;
import dev.ikm.komet.layout.check.CheckResult;
import dev.ikm.komet.layout.preferences.KlPreferencesFactory;
import dev.ikm.komet.layout_engine.blueprint.AbstractCheckArea;
import dev.ikm.komet.layout_engine.blueprint.SupplementalAreaBlueprint;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.komet.claude.anthropic.AnthropicClient;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.claude.tools.GraphTools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link AbstractCheckArea check area} backed by Claude: it asks the model whether the focused
 * concept satisfies an author-configured criterion and renders a green / red verdict.
 *
 * <p>It reuses the {@code komet-claude-plugin} machinery — the outbound {@link AnthropicClient}
 * tool-use loop and the read-only {@link GraphTools} grounded in the live {@link ViewCalculator}
 * — and adds a single forced-decision tool, {@code report_result}, so the verdict is structured
 * (PASS / FAIL + reason) rather than parsed from prose. Per the assistant's grounding contract,
 * the system prompt requires the model to ground all claims through the tools.
 *
 * <p>The Anthropic API key and model are read from the shared per-OS-user Komet preferences
 * (the same keys the Claude Assistant uses); the criterion is persisted per placed area.
 */
public final class ClaudeCheckArea extends AbstractCheckArea {

    private static final String CHECK_TITLE = "Claude check";
    private static final String PREF_API_KEY = "network.ike.komet.claude.apiKey";
    private static final String PREF_MODEL = "network.ike.komet.claude.model";
    private static final String PREF_CRITERION = "network.ike.komet.claude.check.criterion";
    private static final int MAX_TOKENS = 4096;
    private static final String DEFAULT_CRITERION =
            "The concept is active and has at least one IS-A (parent) relationship.";

    private static final String SYSTEM_PROMPT = """
            You are a terminology check assistant embedded in Komet. You evaluate whether a
            specific concept in the open knowledge base satisfies a stated criterion. Ground
            every factual claim by calling the read-only graph tools — never rely on memory for
            concept identity, parents, children, or axioms. When you have gathered enough
            evidence, call report_result exactly once with verdict PASS or FAIL and a
            single-sentence reason. Do not guess identifiers.""";

    private String criterion = DEFAULT_CRITERION;

    /**
     * Restore constructor.
     *
     * @param preferences the preferences node backing this area
     */
    public ClaudeCheckArea(KometPreferences preferences) {
        super(preferences);
        init();
    }

    /**
     * Create constructor.
     *
     * @param preferencesFactory factory for this area's preferences node
     * @param areaFactory        the factory creating this area
     */
    public ClaudeCheckArea(KlPreferencesFactory preferencesFactory, KlArea.Factory areaFactory) {
        super(preferencesFactory, areaFactory);
        init();
    }

    private void init() {
        setCheckTitle(CHECK_TITLE);
        fxObject().setId("ClaudeCheckArea");
    }

    /**
     * Returns the criterion the focused concept is checked against.
     *
     * @return the criterion text
     */
    public String getCriterion() {
        return criterion;
    }

    /**
     * Sets the criterion the focused concept is checked against (blank resets to the default).
     *
     * @param criterion the criterion text
     */
    public void setCriterion(String criterion) {
        this.criterion = (criterion == null || criterion.isBlank()) ? DEFAULT_CRITERION : criterion;
    }

    @Override
    protected CheckResult evaluate(EntityFacade item, ViewProperties viewProperties) {
        if (viewProperties == null) {
            return CheckResult.error("No view available to ground the check.");
        }
        String apiKey = PreferencesService.userPreferences().get(PREF_API_KEY, "");
        if (apiKey.isBlank()) {
            return CheckResult.error("No Anthropic API key configured (set it in the Claude Assistant).");
        }
        String model = PreferencesService.userPreferences().get(PREF_MODEL, AnthropicClient.DEFAULT_MODEL);
        ViewCalculator viewCalculator = viewProperties.calculator();
        String conceptName = viewCalculator.getPreferredDescriptionTextWithFallbackOrNid(item.nid());

        AtomicReference<CheckResult> verdict = new AtomicReference<>();
        List<AnthropicTool> allTools = new ArrayList<>(new GraphTools(() -> viewCalculator).tools());
        allTools.add(reportResultTool(verdict));

        String userMessage = "Concept under review: " + conceptName + " (nid " + item.nid() + ").\n"
                + "Criterion: " + criterion + "\n"
                + "Ground your assessment with the graph tools, then call report_result once.";

        AnthropicClient client = new AnthropicClient(apiKey, model, MAX_TOKENS);
        String reply = client.ask(SYSTEM_PROMPT, allTools, userMessage);

        CheckResult result = verdict.get();
        if (result != null) {
            return result;
        }
        String detail = (reply == null || reply.isBlank()) ? "No verdict returned." : reply.strip();
        return CheckResult.unknown(detail);
    }

    private static AnthropicTool reportResultTool(AtomicReference<CheckResult> sink) {
        return new AnthropicTool() {
            @Override
            public String name() {
                return "report_result";
            }

            @Override
            public String description() {
                return "Report the final verdict for the criterion. Call exactly once, after grounding "
                        + "your assessment with the other tools.";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "verdict", Map.of(
                                        "type", "string",
                                        "enum", List.of("PASS", "FAIL")),
                                "reason", Map.of(
                                        "type", "string",
                                        "description", "One-sentence justification.")),
                        "required", List.of("verdict", "reason"));
            }

            @Override
            public String execute(Map<String, Object> input) {
                String verdict = String.valueOf(input.get("verdict"));
                String reason = String.valueOf(input.get("reason"));
                sink.set("PASS".equalsIgnoreCase(verdict)
                        ? CheckResult.pass(reason)
                        : CheckResult.fail(reason));
                return "recorded";
            }
        };
    }

    @Override
    protected void subAreaRestoreFromPreferencesOrDefault() {
        this.criterion = preferences().get(PREF_CRITERION, DEFAULT_CRITERION);
    }

    @Override
    protected void subAreaSave() {
        preferences().put(PREF_CRITERION, criterion);
    }

    /**
     * Returns a new factory for this area.
     *
     * @return a {@link Factory}
     */
    public static Factory factory() {
        return new Factory();
    }

    /**
     * Restores an area from preferences.
     *
     * @param preferences the preferences node
     * @return the restored area
     */
    public static ClaudeCheckArea restore(KometPreferences preferences) {
        return factory().restore(preferences);
    }

    /**
     * Creates a new area with the given grid settings.
     *
     * @param preferencesFactory factory for the area's preferences node
     * @param areaGridSettings   the grid placement for the new area
     * @return the new area
     */
    public static ClaudeCheckArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
        return factory().create(preferencesFactory, areaGridSettings);
    }

    /**
     * {@code ServiceLoader}-discoverable factory for {@link ClaudeCheckArea}.
     */
    public static final class Factory implements SupplementalAreaBlueprint.Factory<ClaudeCheckArea> {

        /**
         * Restores an area from preferences.
         *
         * @param preferences the preferences node
         * @return the restored area
         */
        @Override
        public ClaudeCheckArea restore(KometPreferences preferences) {
            return new ClaudeCheckArea(preferences);
        }

        /**
         * Creates a new area with the given grid settings.
         *
         * @param preferencesFactory factory for the area's preferences node
         * @param areaGridSettings   the grid placement for the new area
         * @return the new area
         */
        @Override
        public ClaudeCheckArea create(KlPreferencesFactory preferencesFactory, AreaGridSettings areaGridSettings) {
            ClaudeCheckArea area = new ClaudeCheckArea(preferencesFactory, this);
            area.setAreaLayout(areaGridSettings.with(this.getClass()));
            return area;
        }
    }
}
