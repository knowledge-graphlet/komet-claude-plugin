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

import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.framework.view.ViewProperties;
import dev.ikm.komet.rules.evrete.EvreteRulesService;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.terms.EntityFacade;
import javafx.application.Platform;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.anthropic.AnthropicTool;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static network.ike.komet.claude.rules.RuleSourceFixture.KOMETS_OFFER;
import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_METHOD;
import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_NAME;
import static network.ike.komet.claude.rules.RuleSourceFixture.THE_SOURCES_OFFER;
import static network.ike.komet.claude.rules.RuleSourceFixture.lineOf;
import static network.ike.komet.claude.rules.RuleSourceFixture.source;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assistant's rule tools (IKE-Network/ike-issues#1391), called as the model calls them, with
 * the input a tool call carries, on a rule service of the test's own and the starter set in a
 * memory-only store. Where a test needs a rule accepted, it accepts it on the rule service, as the
 * Rules card does for the person: no tool can.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleToolsIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");
    private static final Pattern ID_LINE = Pattern.compile("(?m)^id: ([0-9a-f-]{36})$");

    private ViewProperties viewProperties;
    private EvreteRulesService rules;
    private AtomicInteger toldTheCards;
    private Map<String, AnthropicTool> tools;

    @BeforeAll
    void setup() {
        try {
            HeadlessFx.select();
            Platform.startup(() -> { });
        } catch (IllegalStateException alreadyStarted) {
            // Started by another test class in this JVM.
        } catch (RuntimeException | Error noToolkit) {
            Assumptions.abort("no JavaFX toolkit here: " + noToolkit);
        }
        assertTrue(PB_STARTER_DATA.exists(), "Starter data must be present at " + PB_STARTER_DATA.getAbsolutePath());
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertTrue(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount() > 0);
        viewProperties = onFx(() -> new ObservableViewNoOverride(Coordinates.View.DefaultView()).makeOverridableViewProperties());
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @BeforeEach
    void startRulesWithNoSource() throws Exception {
        rules = new EvreteRulesService();
        toldTheCards = new AtomicInteger();
        tools = new LinkedHashMap<>();
        new RuleTools(() -> viewProperties, () -> rules, toldTheCards::incrementAndGet).tools()
                .forEach(tool -> tools.put(tool.name(), tool));
    }

    @Test
    void theToolsAreTheFiveTheContractNamesAndNoneAcceptsARule() {
        assertEquals(List.of("list_rules", "read_rule", "propose_rule", "discard_rule_proposal", "run_rules"),
                List.copyOf(tools.keySet()));
    }

    @Test
    void listRulesNamesKometsRuleSetsWithTheirRulesAndState() {
        String listed = call("list_rules", Map.of());

        assertTrue(listed.contains("**Component focus rules** · Komet · In use"), listed);
        assertTrue(listed.contains("Component focused and active"), listed);
    }

    @Test
    void aProposedRuleIsCompiledAndWaitsForThePersonWithoutRunning() {
        String proposed = call("propose_rule", Map.of("name", "Activation rules", "java", source()));

        assertTrue(proposed.startsWith("Proposed \"Activation rules\". It compiled"), proposed);
        assertTrue(proposed.contains("NOTHING IS IN USE"), proposed);
        assertTrue(proposed.contains("Rules: " + RULE_NAME), proposed);
        assertEquals(1, toldTheCards.get(), "the open Rules cards are told once");
        String listed = call("list_rules", Map.of());
        assertTrue(listed.contains("**Activation rules** · Written in Komet · Proposed: not in use until a person accepts it"),
                listed);
        List<String> offered = offers(call("run_rules", Map.of("id", id(KometConcept.GREATER_THAN))));
        assertTrue(offered.contains(KOMETS_OFFER), () -> "Komet's own rules still offer: " + offered);
        assertFalse(offered.contains(THE_SOURCES_OFFER), () -> "the proposed rule does not fire: " + offered);
    }

    @Test
    void aRuleThePersonAcceptedRunsAndRunRulesNamesTheRuleMethodThatOffered() {
        UUID id = idIn(call("propose_rule", Map.of("name", "Activation rules", "java", source())));
        rules.acceptRuleSource(id);

        String ran = call("run_rules", Map.of("id", id(KometConcept.GREATER_THAN)));

        assertTrue(ran.contains("as focused. Nothing was performed."), ran);
        assertTrue(ran.lines().anyMatch(line -> line.startsWith("- " + THE_SOURCES_OFFER + " (from ")
                && line.contains(RULE_METHOD)), ran);
        assertTrue(offers(ran).containsAll(List.of(THE_SOURCES_OFFER, KOMETS_OFFER)), ran);
        assertTrue(ran.contains(offers(ran).size() + " offers:"), ran);
    }

    @Test
    void aProposalThatDoesNotCompileReturnsTheCompilersLineAndIsReplacedByTheFix() {
        String unknownType = "NoSuchType nothing = null;";
        String broken = sourceWith(unknownType);

        String refused = call("propose_rule", Map.of("name", "Activation rules", "java", broken));

        assertTrue(refused.contains("it has findings and cannot be accepted as it is"), refused);
        assertTrue(refused.contains("- Line " + lineOf(unknownType, broken) + ": "), refused);
        assertTrue(refused.contains("cannot find symbol"), refused);

        String fixed = call("propose_rule",
                Map.of("name", "Activation rules", "java", source(), "id", idIn(refused).toString()));

        assertTrue(fixed.startsWith("Proposed \"Activation rules\". It compiled"), fixed);
        assertEquals(idIn(refused), idIn(fixed));
        assertEquals(1, rules.ruleSets().count(ruleSet -> !ruleSet.accepted()), "the fix took the proposal's place");
    }

    @Test
    void aNewProposalTakesTheIdentityItsNameGivesAndAnIdKeepsTheNameItHas() {
        String proposed = call("propose_rule", Map.of("name", "Activation rules", "java", source()));

        UUID id = idIn(proposed);
        assertEquals(StoredRules.identity("Activation rules", viewProperties.calculator()), id);

        String renamed = call("propose_rule",
                Map.of("name", "Rules for activation", "java", source(), "id", id.toString()));

        assertTrue(renamed.startsWith("Proposed \"Activation rules\". It compiled"), renamed);
        assertTrue(renamed.contains("It keeps the name \"Activation rules\""), renamed);
        assertEquals(1, rules.ruleSets().count(ruleSet -> !ruleSet.accepted()));
        String unknown = call("propose_rule",
                Map.of("name", "Other rules", "java", source(), "id", UUID.randomUUID().toString()));
        assertTrue(unknown.startsWith("Error: No rule source has the id"), unknown);
        assertTrue(unknown.contains("Omit the id to propose a new rule source"), unknown);
    }

    @Test
    void aProposalUnderTheNameOfARuleSetInUseIsAProposedEditOfIt() {
        UUID id = StoredRules.identity("Activation rules", viewProperties.calculator());
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));

        String proposed = call("propose_rule", Map.of("name", "Activation rules", "java",
                source().replace("isComponentActive", "isComponentInactive")));

        assertEquals(id, idIn(proposed));
        assertTrue(proposed.contains("A rule set named \"Activation rules\" is already in use, so this is proposed as"
                + " an edit of it."), proposed);
        assertEquals(2, rules.ruleSets().count(ruleSet -> ruleSet.source().map(RuleSource::id).filter(id::equals).isPresent()),
                "the rule set in use and its proposed edit");
    }

    @Test
    void readRuleReturnsTheSourceWithAProposedEditBeforeTheRuleInUse() {
        UUID id = UUID.randomUUID();
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));
        String edit = source().replace("isComponentActive", "isComponentInactive");
        call("propose_rule", Map.of("name", "Activation rules", "java", edit, "id", id.toString()));

        String read = call("read_rule", Map.of("id", id.toString()));

        int proposal = read.indexOf("Written in Komet · Proposed");
        int inUse = read.indexOf("Written in Komet · In use");
        assertTrue(proposal >= 0 && inUse > proposal, read);
        assertTrue(read.contains("```java\n" + edit.stripTrailing() + "\n```"), read);
        assertTrue(read.contains("```java\n" + source().stripTrailing() + "\n```"), read);
        assertTrue(offers(call("run_rules", Map.of("id", id(KometConcept.GREATER_THAN)))).contains(THE_SOURCES_OFFER),
                "the rule in use is as it was while its edit is proposed");
    }

    @Test
    void aWithdrawnProposalIsGoneAndWithdrawingAgainIsAnError() {
        UUID id = idIn(call("propose_rule", Map.of("name", "Activation rules", "java", source())));

        String withdrawn = call("discard_rule_proposal", Map.of("id", id.toString()));

        assertTrue(withdrawn.startsWith("Withdrew the proposed rule source"), withdrawn);
        assertEquals(2, toldTheCards.get(), "told at the proposal and at its withdrawal");
        assertFalse(call("list_rules", Map.of()).contains("Proposed"));
        assertTrue(call("discard_rule_proposal", Map.of("id", id.toString())).startsWith("Error: No proposed rule source"));
    }

    @Test
    void aRuleThatThrowsIsReportedTakenOutOfUseWithItsLine() {
        String throwing = "throw new IllegalStateException(\"no offer today\");";
        String source = sourceWith(throwing);
        rules.putRuleSource(new RuleSource(UUID.randomUUID(), "Throwing rules", source));

        String ran = call("run_rules", Map.of("id", id(KometConcept.GREATER_THAN)));

        assertTrue(offers(ran).contains(KOMETS_OFFER), ran);
        assertFalse(offers(ran).contains(THE_SOURCES_OFFER), ran);
        assertTrue(ran.contains("The rule source \"Throwing rules\" was taken out of use during this run:"), ran);
        assertTrue(ran.contains("- Line " + lineOf(throwing, source) + ": "), ran);
        assertTrue(ran.contains("no offer today"), ran);
    }

    @Test
    void whatCannotBeFoundComesBackAsAnError() {
        assertTrue(call("read_rule", Map.of("id", UUID.randomUUID().toString())).startsWith("Error: No rule source has the id"));
        assertTrue(call("read_rule", Map.of("id", "the activation rule")).startsWith("Error: \"the activation rule\" is not a rule source id"));
        assertTrue(call("propose_rule", Map.of("name", "Activation rules")).startsWith("Error: \"java\" is required"));
        assertTrue(call("run_rules", Map.of("id", UUID.randomUUID().toString())).startsWith("Error: "));
    }

    private String call(String tool, Map<String, Object> input) {
        return tools.get(tool).execute(input);
    }

    /** The offers a run_rules result lists, by name, in name order. */
    private static List<String> offers(String ran) {
        return ran.lines()
                .filter(line -> line.startsWith("- ") && line.contains(" (from "))
                .map(line -> line.substring(2, line.indexOf(" (from ")))
                .sorted()
                .toList();
    }

    private static UUID idIn(String result) {
        Matcher id = ID_LINE.matcher(result);
        assertTrue(id.find(), () -> "the result gives the rule source's id: " + result);
        return UUID.fromString(id.group(1));
    }

    private static String id(EntityFacade component) {
        return component.publicId().asUuidArray()[0].toString();
    }

    private static <T> T onFx(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e.getCause() == null ? e : e.getCause());
        }
    }
}
