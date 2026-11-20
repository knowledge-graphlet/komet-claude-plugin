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
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.KometPreferencesImpl;
import dev.ikm.komet.rules.evrete.EvreteRulesService;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputControl;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.document.SemanticVersions;
import network.ike.komet.document.surface.MarkdownRichText;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_NAME;
import static network.ike.komet.claude.rules.RuleSourceFixture.lineOf;
import static network.ike.komet.claude.rules.RuleSourceFixture.source;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceNamed;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Rules card's content (IKE-Network/ike-issues#1391), where a person decides on a proposed
 * rule and writes and edits rules: the pane in a scene of its own, its buttons fired as a click
 * fires them and its editor filled as typing fills it, on a rule service of the test's own. The text beside the list goes through the renderer the card uses, on the
 * starter set in a memory-only store, which is also where the pane keeps what is saved and
 * accepted. The record of what was accepted on this installation is a preferences node of the
 * test's own. The tests share that store, so each names a rule set of its own.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RulesPaneIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private EvreteRulesService rules;
    private StoredRules store;
    private KometPreferences acceptedNode;
    private AcceptedRules acceptedHere;
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
        acceptedNode = KometPreferencesImpl.getConfigurationRootPreferences()
                .node("rule-sets-accepted-here-" + UUID.randomUUID());
        acceptedHere = new AcceptedRules(() -> acceptedNode);
    }

    @AfterAll
    void teardown() {
        try {
            acceptedNode.removeNode();
        } catch (Exception neverWritten) {
            // a node never flushed has no folder to delete
        }
        PrimitiveData.stop();
    }

    @BeforeEach
    void showThePaneOnRulesWithNoSource() throws Exception {
        rules = new EvreteRulesService();
        store = new StoredRules(Calculators.View::Default, Coordinates.Edit::Default);
        pane = onFx(() -> {
            RulesPane shown = new RulesPane(() -> rules, store, acceptedHere, markdown ->
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
        String name = "Activation rules, proposed and accepted";
        UUID id = store.identity(name);
        rules.proposeRuleSource(new RuleSource(id, name, source()));
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
        assertFalse(store.kept(id), "a proposal is not in the knowledge base");

        click("#rulesAccept");

        ListedRuleSet accepted = rules.ruleSets().getLast();
        assertTrue(accepted.accepted() && accepted.inUse(), accepted::toString);
        assertEquals(accepted, onFx(() -> pane.selected().orElseThrow()), "the accepted rule set stays selected");
        assertEquals("Accepted \"" + name + "\". Its rules are in use."
                + " It is kept in the knowledge base, as an unpublished change.", status());
        assertTrue(StoredRules.read(Calculators.View.Default()).contains(new RuleSource(id, name, source())),
                "accepting it kept it");
        assertTrue(acceptedHere.accepted(new RuleSource(id, name, source())), "and it is on record as accepted here");
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
        assertEquals(new RuleSource(store.identity("My rules"), "My rules", source()), saved.source().orElseThrow(),
                "its identity is the one its name gives");
        assertEquals(saved, onFx(() -> pane.selected().orElseThrow()), "the saved rule set is the one selected");
        assertEquals("Saved \"My rules\". Its rules are in use. It is kept in the knowledge base, as an unpublished change.",
                status());
        assertTrue(StoredRules.read(Calculators.View.Default()).contains(saved.source().orElseThrow()), "saving it kept it");
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
        String name = "Activation rules, edited and saved";
        UUID id = store.identity(name);
        rules.putRuleSource(new RuleSource(id, name, source()));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });
        click("#rulesEdit");
        assertTrue(disabled("#rulesEditorName"), "a rule set keeps the name it was first given");
        String edited = source().replace("isComponentActive", "isComponentInactive");
        type("#rulesEditorJava", edited);

        click("#rulesSave");

        assertEquals(1, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        ListedRuleSet saved = rules.ruleSets().getLast();
        assertTrue(saved.inUse(), saved::toString);
        assertEquals(edited, saved.source().orElseThrow().java());
        assertTrue(onFx(() -> pane.shownMarkdown()).contains("isComponentInactive"));
        assertTrue(StoredRules.read(Calculators.View.Default()).contains(new RuleSource(id, name, edited)),
                "the knowledge base keeps the edited text");
    }

    @Test
    void savingAnEditedProposalPutsItInUseAndTheProposalIsGone() {
        String name = "Activation rules, proposed with findings and fixed";
        UUID id = store.identity(name);
        rules.proposeRuleSource(new RuleSource(id, name, sourceWith("NoSuchType nothing = null;")));
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
        assertTrue(store.kept(id), "the person's own version is the one kept");
    }

    @Test
    void theNameIsGivenWhenARuleSetIsNewAndANameThatIsTakenIsRefused() {
        String name = "Activation rules, whose name is taken";
        rules.putRuleSource(new RuleSource(store.identity(name), name, source()));
        onFx(() -> {
            pane.reload();
            pane.newRuleSource();
            return null;
        });
        assertFalse(disabled("#rulesEditorName"), "a new rule set is named in the editor");
        type("#rulesEditorName", name);
        type("#rulesEditorJava", sourceNamed("Offer of a rule set with a taken name"));

        click("#rulesSave");

        assertTrue(onFx(() -> pane.editing()), "the editor stays open, for another name");
        assertEquals("Not saved. A rule set named \"" + name + "\" already exists. Give this one a name of its own,"
                + " or edit that one. The rules in use have not changed.", status());
        assertEquals(source(), rules.ruleSets().getLast().source().orElseThrow().java(), "the one that has the name stays");
        assertFalse(store.kept(store.identity(name)), "and nothing was written");
    }

    @Test
    void removeTakesAKeptRuleSetOutOfTheKnowledgeBaseToo() {
        String name = "Activation rules, saved and removed";
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorName", name);
        type("#rulesEditorJava", sourceNamed("Offer of a rule set saved and removed"));
        click("#rulesSave");
        UUID id = store.identity(name);
        assertTrue(store.kept(id));

        click("#rulesRemove");

        assertEquals(0, rules.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));
        assertEquals("Removed \"" + name + "\". Its rules are no longer in use."
                + " It was never published, so all of it is taken back out of the knowledge base: its text, and"
                + " the concepts of the rule set and of its rules.", status());
        assertFalse(store.kept(id));
        assertFalse(SemanticVersions.exists(PublicIds.of(id), Calculators.View.Default()),
                "its concept is taken back with its text");
        assertFalse(acceptedHere.accepted(new RuleSource(id, name, sourceNamed("Offer of a rule set saved and removed"))),
                "and it is no longer on record as accepted here");
    }

    @Test
    void aSavedRuleSetIsInUseInARuleServiceStartedLater() throws Exception {
        String name = "Activation rules, saved before a restart";
        String java = sourceNamed("Offer of a rule set saved before a restart");
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorName", name);
        type("#rulesEditorJava", java);
        click("#rulesSave");

        EvreteRulesService startedLater = new EvreteRulesService();
        List<ListedRuleSet> loaded = StoredRules.loadInto(() -> startedLater, Calculators.View.Default(), acceptedHere);

        ListedRuleSet again = loaded.stream().filter(ruleSet -> ruleSet.name().equals(name)).findFirst().orElseThrow();
        assertTrue(again.accepted() && again.inUse(), again::toString);
        assertEquals(new RuleSource(store.identity(name), name, java), again.source().orElseThrow());
    }

    @Test
    void aRuleSetTheKnowledgeBaseKeepsThatNobodyAcceptedHereWaitsForAcceptAndIsNotDiscarded() throws Exception {
        String name = "Activation rules, kept elsewhere";
        String ruleName = "Offer of a rule set kept elsewhere";
        String java = sourceNamed(ruleName);
        UUID id = store.identity(name);
        // Kept as a knowledge base that was copied or received keeps it: nobody accepted it here.
        assertEquals(StoredRules.Result.WRITTEN, store.keep(new RuleSource(id, name, java), List.of(ruleName)).result());
        // The store is shared, so loading also gives the rule service what other tests kept.
        StoredRules.loadInto(() -> rules, Calculators.View.Default(), acceptedHere);
        long inUse = rules.ruleSets().count(ListedRuleSet::inUse);
        long waiting = rules.ruleSets().count(ruleSet -> !ruleSet.accepted());
        onFx(() -> {
            pane.reload();
            return null;
        });

        assertEquals(inUse + " rule sets in use, " + waiting + " kept in the knowledge base and not accepted here.",
                status());
        assertTrue(onFx(() -> pane.select(id)));
        assertTrue(listLines().contains("Not accepted here · " + name), () -> listLines().toString());
        assertFalse(onFx(() -> pane.selected().orElseThrow()).accepted());
        String shown = onFx(() -> pane.shownMarkdown());
        assertTrue(shown.contains("Kept in the knowledge base, not accepted here: not in use until a person accepts it"),
                shown);
        assertFalse(disabled("#rulesAccept"));
        assertTrue(disabled("#rulesDiscard"), "what the knowledge base keeps is accepted or removed, not hidden");
        assertFalse(disabled("#rulesRemove"));
        assertEquals(inUse, rules.ruleSets().count(ListedRuleSet::inUse), "none of its rules are in use");

        click("#rulesAccept");

        ListedRuleSet now = onFx(() -> pane.selected().orElseThrow());
        assertTrue(now.accepted() && now.inUse(), now::toString);
        assertEquals("Accepted \"" + name + "\". Its rules are in use. The knowledge base already keeps this text.",
                status());
        assertTrue(acceptedHere.accepted(new RuleSource(id, name, java)), "so the next start puts it in use");
        assertTrue(listLines().contains(name), () -> listLines().toString());
    }

    @Test
    void removeTakesARuleSetNobodyAcceptedHereOutOfTheKnowledgeBase() throws Exception {
        String name = "Activation rules, kept elsewhere and removed";
        String ruleName = "Offer of a rule set kept elsewhere and removed";
        UUID id = store.identity(name);
        store.keep(new RuleSource(id, name, sourceNamed(ruleName)), List.of(ruleName));
        StoredRules.loadInto(() -> rules, Calculators.View.Default(), acceptedHere);
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });

        click("#rulesRemove");

        assertEquals("Removed \"" + name + "\". It was not in use."
                + " It was never published, so all of it is taken back out of the knowledge base: its text, and"
                + " the concepts of the rule set and of its rules.", status());
        assertFalse(store.kept(id));
        assertFalse(onFx(() -> pane.select(id)), "it is no longer listed");
        assertTrue(rules.ruleSets().noneSatisfy(ruleSet ->
                ruleSet.source().map(RuleSource::id).filter(id::equals).isPresent()), "nor does the rule service list it");
        assertFalse(SemanticVersions.exists(PublicIds.of(id), Calculators.View.Default()));
    }

    @Test
    void anAssistantsProposedEditOfAKeptRuleSetIsStillAProposalToDiscard() {
        String name = "Activation rules, kept, with an edit proposed";
        String ruleName = "Offer of a rule set kept, with an edit proposed";
        String java = sourceNamed(ruleName);
        UUID id = store.identity(name);
        onFx(() -> {
            pane.newRuleSource();
            return null;
        });
        type("#rulesEditorName", name);
        type("#rulesEditorJava", java);
        click("#rulesSave");
        rules.proposeRuleSource(new RuleSource(id, name, java.replace("isComponentActive", "isComponentInactive")));
        onFx(() -> {
            pane.reload();
            pane.select(id);
            return null;
        });

        assertFalse(onFx(() -> pane.selected().orElseThrow()).accepted(), "the proposal is the one selected");
        assertTrue(listLines().contains("Proposed · " + name), () -> listLines().toString());
        assertFalse(disabled("#rulesDiscard"), "a proposal that is not the kept text is discarded as before");
        assertTrue(disabled("#rulesRemove"));
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

    /** The lines of the list as they are drawn, after one layout pass. */
    private List<String> listLines() {
        return onFx(() -> {
            pane.applyCss();
            pane.layout();
            return pane.lookup("#rulesList").lookupAll(".list-cell").stream()
                    .map(cell -> ((ListCell<?>) cell).getText())
                    .filter(Objects::nonNull)
                    .toList();
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
