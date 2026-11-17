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

import dev.ikm.komet.framework.rulebase.ListedRuleSet;
import dev.ikm.komet.framework.rulebase.RuleFinding;
import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.rules.evrete.EvreteRulesService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputControl;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.document.surface.MarkdownRichText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_NAME;
import static network.ike.komet.claude.rules.RuleSourceFixture.lineOf;
import static network.ike.komet.claude.rules.RuleSourceFixture.source;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Rules card's content (IKE-Network/ike-issues#1391), where a person decides on a proposed
 * rule and writes and edits rules: the pane in a scene of its own, its buttons fired as a click
 * fires them and its editor filled as typing fills it, on a rule service of the test's own. The text beside the list goes through the renderer the card uses, on the
 * starter set in a memory-only store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RulesPaneIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private EvreteRulesService rules;
    private RulesPane pane;

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
    }

    @AfterAll
    void teardown() {
        PrimitiveData.stop();
    }

    @BeforeEach
    void showThePaneOnRulesWithNoSource() throws Exception {
        rules = new EvreteRulesService();
        pane = onFx(() -> {
            RulesPane shown = new RulesPane(() -> rules, markdown ->
                    new MarkdownRichText(Calculators.View.Default(), MarkdownRichText.DEFAULT_BASE).renderMarkdown(markdown));
            // A scene and one layout pass give the pane's controls their place in the scene graph.
            new Scene(shown, 1000, 700);
            shown.applyCss();
            shown.layout();
            shown.reload();
            return shown;
        });
    }

    @Test
    void kometsOwnRuleSetsAreListedAndCannotBeAcceptedDiscardedOrRemoved() {
        ListedRuleSet selected = onFx(() -> pane.selected().orElseThrow());

        assertEquals(ListedRuleSet.Origin.KOMET, selected.origin());
        assertTrue(onFx(() -> pane.shownMarkdown()).contains("compiled into Komet, so it has no source to show here"));
        assertTrue(disabled("#rulesAccept") && disabled("#rulesDiscard") && disabled("#rulesRemove"));
    }

    @Test
    void aProposalIsListedFirstWithItsSourceAndAcceptPutsItsRulesInUse() {
        UUID id = UUID.randomUUID();
        rules.proposeRuleSource(new RuleSource(id, "Activation rules", source()));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });

        assertFalse(onFx(() -> pane.selected().orElseThrow()).accepted());
        String shown = onFx(() -> pane.shownMarkdown());
        assertTrue(shown.contains("Proposed: not in use until a person accepts it"), shown);
        assertTrue(shown.contains("```java\n" + source().stripTrailing() + "\n```"), shown);
        assertFalse(disabled("#rulesAccept"));

        click("#rulesAccept");

        ListedRuleSet accepted = rules.ruleSets().getLast();
        assertTrue(accepted.accepted() && accepted.inUse(), accepted::toString);
        assertEquals(accepted, onFx(() -> pane.selected().orElseThrow()), "the accepted rule set stays selected");
        assertEquals("Accepted \"Activation rules\". Its rules are in use.", status());
        assertTrue(disabled("#rulesAccept") && disabled("#rulesDiscard"));
        assertFalse(disabled("#rulesRemove"));
    }

    @Test
    void acceptIsNotAvailableWhileAProposalHasFindingsAndDiscardRemovesIt() {
        UUID id = UUID.randomUUID();
        rules.proposeRuleSource(new RuleSource(id, "Broken rules", sourceWith("NoSuchType nothing = null;")));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });

        assertTrue(onFx(() -> pane.shownMarkdown()).contains("## Findings\n\n- Line "), onFx(() -> pane.shownMarkdown()));
        assertTrue(disabled("#rulesAccept"));
        assertFalse(disabled("#rulesDiscard"));

        click("#rulesDiscard");

        assertEquals(0, rules.ruleSets().count(ruleSet -> !ruleSet.accepted()));
        assertEquals("Discarded the proposed rule source \"Broken rules\".", status());
    }

    @Test
    void discardingAProposedEditLeavesTheRuleSetInUse() {
        UUID id = UUID.randomUUID();
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));
        rules.proposeRuleSource(new RuleSource(id, "Activation rules",
                source().replace("isComponentActive", "isComponentInactive")));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });
        assertFalse(onFx(() -> pane.selected().orElseThrow()).accepted(), "the proposal is the one selected");

        click("#rulesDiscard");

        ListedRuleSet stays = rules.ruleSets().getLast();
        assertTrue(stays.inUse(), stays::toString);
        assertEquals(source(), stays.source().orElseThrow().java());
    }

    @Test
    void removeTakesARuleSetWrittenInKometOutOfUse() {
        UUID id = UUID.randomUUID();
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });

        click("#rulesRemove");

        assertEquals(0, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        assertEquals("Removed \"Activation rules\". Its rules are no longer in use.", status());
        assertFalse(onFx(() -> pane.shownMarkdown()).contains(RULE_NAME));
    }

    @Test
    void theStatusLineCountsWhatIsInUseAndWhatWaits() {
        rules.proposeRuleSource(new RuleSource(UUID.randomUUID(), "Activation rules", source()));
        onFx(() -> {
            pane.reload();
            return null;
        });

        long inUse = rules.ruleSets().count(ListedRuleSet::inUse);
        assertEquals(inUse + " rule sets in use, 1 proposed and waiting for you.", status());
    }

    @Test
    void newOpensAStartingShapeThatCompiles() {
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });

        assertTrue(onFx(() -> pane.editing()));
        assertEquals(RulesPane.NEW_SOURCE, text("#rulesEditorJava"));
        assertEquals("Line 1, column 1", onFx(() -> ((Label) pane.lookup("#rulesEditorPlace")).getText()));

        click("#rulesCheck");

        assertEquals(List.of(), findings());
        assertEquals("No findings. Rules: New rule. Nothing has changed yet.", status());
    }

    @Test
    void aNewRuleSourceIsInUseAsSoonAsItIsSavedWithNoAccept() {
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorName", "My rules");
        type("#rulesEditorJava", source());

        click("#rulesSave");

        assertFalse(onFx(() -> pane.editing()));
        ListedRuleSet saved = rules.ruleSets().getLast();
        assertTrue(saved.accepted() && saved.inUse(), saved::toString);
        assertEquals(new RuleSource(saved.source().orElseThrow().id(), "My rules", source()), saved.source().orElseThrow());
        assertEquals(saved, onFx(() -> pane.selected().orElseThrow()), "the saved rule set is the one selected");
        assertEquals("Saved \"My rules\". Its rules are in use.", status());
    }

    @Test
    void checkListsTheCompilersFindingsAndChangesNothing() {
        String unknownType = "NoSuchType nothing = null;";
        String broken = sourceWith(unknownType);
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorJava", broken);

        click("#rulesCheck");

        assertEquals(1, findings().size(), () -> "one finding: " + findings());
        assertEquals(lineOf(unknownType, broken), findings().getFirst().line());
        assertEquals("1 finding. Nothing has changed.", status());
        assertEquals(0, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        assertTrue(onFx(() -> pane.editing()), "the editor stays open");
    }

    @Test
    @SuppressWarnings("unchecked")
    void choosingAFindingSelectsItsLineInTheSource() {
        String unknownType = "NoSuchType nothing = null;";
        String broken = sourceWith(unknownType);
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorJava", broken);
        click("#rulesCheck");

        onFx(() -> {
            ((ListView<RuleFinding>) pane.lookup("#rulesEditorFindings")).getSelectionModel().selectFirst();
            return null;
        });

        assertEquals(broken.lines().toList().get(lineOf(unknownType, broken) - 1),
                onFx(() -> ((TextInputControl) pane.lookup("#rulesEditorJava")).getSelectedText()));
    }

    @Test
    void savingSourceWithFindingsSavesNothingAndTheRuleInUseStays() {
        UUID id = UUID.randomUUID();
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });
        click("#rulesEdit");
        assertEquals(source(), text("#rulesEditorJava"), "the editor opens on the rule's source");
        type("#rulesEditorJava", sourceWith("NoSuchType nothing = null;"));

        click("#rulesSave");

        assertTrue(onFx(() -> pane.editing()), "the editor stays open on what was typed");
        assertEquals("Not saved. 1 finding. The rules in use have not changed.", status());
        ListedRuleSet stays = rules.ruleSets().getLast();
        assertTrue(stays.inUse(), stays::toString);
        assertEquals(source(), stays.source().orElseThrow().java());
    }

    @Test
    void anEditedRuleInUseIsReplacedWhenItIsSaved() {
        UUID id = UUID.randomUUID();
        rules.putRuleSource(new RuleSource(id, "Activation rules", source()));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });
        click("#rulesEdit");
        String edited = source().replace("isComponentActive", "isComponentInactive");
        type("#rulesEditorJava", edited);

        click("#rulesSave");

        assertEquals(1, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        ListedRuleSet saved = rules.ruleSets().getLast();
        assertTrue(saved.inUse(), saved::toString);
        assertEquals(edited, saved.source().orElseThrow().java());
        assertTrue(onFx(() -> pane.shownMarkdown()).contains("isComponentInactive"));
    }

    @Test
    void savingAnEditedProposalPutsItInUseAndTheProposalIsGone() {
        UUID id = UUID.randomUUID();
        rules.proposeRuleSource(new RuleSource(id, "Activation rules", sourceWith("NoSuchType nothing = null;")));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });
        assertTrue(disabled("#rulesAccept"), "a proposal with findings cannot be accepted");
        click("#rulesEdit");
        type("#rulesEditorJava", source());

        click("#rulesSave");

        assertEquals(0, rules.ruleSets().count(ruleSet -> !ruleSet.accepted()), "the proposal is gone");
        ListedRuleSet saved = rules.ruleSets().getLast();
        assertTrue(saved.accepted() && saved.inUse(), saved::toString);
        assertEquals(source(), saved.source().orElseThrow().java());
    }

    @Test
    void cancelLeavesTheRulesAsTheyWere() {
        long inUse = rules.ruleSets().count(ListedRuleSet::inUse);
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorJava", source());

        click("#rulesCancel");

        assertFalse(onFx(() -> pane.editing()));
        assertEquals(0, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        assertEquals(inUse + " rule sets in use.", status());
    }

    @Test
    void aProposalMadeWhileTheEditorIsOpenIsListedAndTheEditorKeepsItsText() {
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorJava", "// half written");
        UUID proposed = UUID.randomUUID();
        rules.proposeRuleSource(new RuleSource(proposed, "Activation rules", source()));

        onFx(() -> {
            pane.reload();
            return null;
        });

        assertTrue(onFx(() -> pane.editing()));
        assertEquals("// half written", text("#rulesEditorJava"));
        click("#rulesCancel");
        assertTrue(onFx(() -> pane.select(proposed)), "the proposal is in the list");
    }

    private void type(String field, String text) {
        onFx(() -> {
            ((TextInputControl) pane.lookup(field)).setText(text);
            return null;
        });
    }

    private String text(String field) {
        return onFx(() -> ((TextInputControl) pane.lookup(field)).getText());
    }

    @SuppressWarnings("unchecked")
    private List<RuleFinding> findings() {
        return onFx(() -> List.copyOf(((ListView<RuleFinding>) pane.lookup("#rulesEditorFindings")).getItems()));
    }

    private void click(String button) {
        onFx(() -> {
            ((Button) pane.lookup(button)).fire();
            return null;
        });
    }

    private boolean disabled(String button) {
        return onFx(() -> pane.lookup(button).isDisabled());
    }

    private String status() {
        return onFx(() -> ((Label) pane.lookup("#rulesStatus")).getText());
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
