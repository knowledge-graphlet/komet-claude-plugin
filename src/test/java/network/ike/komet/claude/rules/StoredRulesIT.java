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
import dev.ikm.komet.framework.rulebase.RuleService;
import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.rules.evrete.EvreteRulesService;
import dev.ikm.tinkar.common.id.LongIdCollection;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import javafx.application.Platform;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.HeadlessFx;
import network.ike.komet.claude.rules.StoredRules.Result;
import network.ike.komet.document.LocalConcepts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static network.ike.komet.claude.rules.RuleSourceFixture.RULE_NAME;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceNamed;
import static network.ike.komet.claude.rules.RuleSourceFixture.sourceWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule sets kept in the knowledge base (IKE-Network/ike-issues#1391): what keeping one writes,
 * what reading gives back, what removing one leaves, and what a rule service has in use once
 * the kept rule sets are loaded into it, as Komet does when it starts. The store is the starter
 * set in a memory-only store, the writes are the ones the Rules card makes, and the rule service
 * is Komet's own. Each test names a rule set of its own, since they share the store.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoredRulesIT {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private ViewCalculator view;
    private EditCoordinate edit;
    private StoredRules stored;

    @BeforeAll
    void startStore() {
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
        view = Calculators.View.Default();
        edit = Coordinates.Edit.Default();
        stored = new StoredRules(() -> view, () -> edit);
    }

    @AfterAll
    void stopStore() {
        PrimitiveData.stop();
    }

    @Test
    void theStarterSetHasTheRuleModelWithTheThreeFieldsOfTheRuleSourcePattern() {
        assertTrue(StoredRules.patternPresent());
        assertTrue(StoredRules.modelPresent(view));
        assertTrue(stored.modelPresent());

        PatternEntityVersion pattern = view.latestPatternEntityVersion(IkePattern.RULE_SOURCE_PATTERN.nid()).get();
        assertEquals(3, pattern.fieldDefinitions().size());
        assertEquals(IkeConcept.RULE_SOURCE.nid(), pattern.semanticMeaningNid());
        assertEquals(IkeConcept.RULE_EXECUTION.nid(), pattern.semanticPurposeNid());
        assertTrue(pattern.indexForMeaning(IkeConcept.RULE_SET_TEXT.nid()) >= 0);
        assertTrue(pattern.indexForMeaning(IkeConcept.DECLARED_RULES.nid()) >= 0);
        assertTrue(pattern.indexForMeaning(IkeConcept.RULES_API_VERSION.nid()) >= 0);
        assertEquals(List.of(), StoredRules.read(Calculators.View.Default()).stream()
                .filter(source -> source.name().startsWith("No such")).toList(), "reading an untouched store gives no error");
    }

    @Test
    void aKeptRuleSetIsAConceptWithItsTextItsRulesAndTheApiVersionInOneUnpublishedChange() {
        String name = "Kept rules, as one change";
        String ruleName = "Rule kept as one change";
        String java = sourceNamed(ruleName);
        UUID id = stored.identity(name);
        assertEquals(LocalConcepts.uuid(name), id, "a new rule set's identity is the one its name derives");
        assertFalse(stored.kept(id));

        StoredRules.Outcome kept = stored.keep(new RuleSource(id, name, java), List.of(ruleName));

        assertEquals(Result.WRITTEN, kept.result(), kept::why);
        assertTrue(stored.kept(id));
        long ruleSet = PrimitiveData.nid(PublicIds.of(id));
        long rule = PrimitiveData.nid(PublicIds.of(LocalConcepts.uuid(ruleName)));
        assertEquals(name, view.getFullyQualifiedNameText(ruleSet).orElseThrow());
        assertEquals(name, view.getRegularDescriptionText(ruleSet).orElseThrow());
        assertEquals(ruleName, view.getFullyQualifiedNameText(rule).orElseThrow());

        SemanticEntity<SemanticEntityVersion> text = textOf(ruleSet);
        SemanticEntityVersion version = view.<SemanticEntityVersion>latest(text.nid()).get();
        PatternEntityVersion pattern = view.latestPatternEntityVersion(IkePattern.RULE_SOURCE_PATTERN.nid()).get();
        assertEquals(java, version.fieldValues().get(pattern.indexForMeaning(IkeConcept.RULE_SET_TEXT.nid())));
        assertEquals(Set.of(rule), declared(version, pattern), "the text declares its one rule");
        assertEquals(RuleService.RULES_API_VERSION,
                version.fieldValues().get(pattern.indexForMeaning(IkeConcept.RULES_API_VERSION.nid())));
        assertTrue(version.active() && version.uncommitted(), "the text is in use and unpublished");

        Transaction change = Transaction.forVersion(version).orElseThrow();
        assertEquals(change, changeOf(ruleSet), "one change holds the text and the rule set's concept");
        assertEquals(change, changeOf(rule), "and the rule's concept, so one publish carries all three");

        assertEquals(Result.AS_IT_WAS, stored.keep(new RuleSource(id, name, java), List.of(ruleName)).result(),
                "keeping the same text again writes nothing, and takes the concept for the rule set it is");
        assertEquals(1, text.versions().size());
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, java)));
    }

    @Test
    void anUnpublishedTextIsRewrittenInPlaceAndAPublishedOneGetsANewVersion() {
        String name = "Kept rules, edited twice";
        UUID id = stored.identity(name);
        String ruleName = "Rule edited twice";
        String first = sourceNamed(ruleName);
        String second = first.replace("isComponentActive", "isComponentInactive");
        String third = second.replace("offerActivation", "offerActivationAgain");

        assertEquals(Result.WRITTEN, stored.keep(new RuleSource(id, name, first), List.of(ruleName)).result());
        assertEquals(Result.WRITTEN, stored.keep(new RuleSource(id, name, second), List.of(ruleName)).result());

        long text = textOf(PrimitiveData.nid(PublicIds.of(id))).nid();
        assertEquals(1, versionsOf(text), "one author's edits before a publish are one version");
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, second)));

        changeOf(text).commit();
        assertTrue(EntityHandle.get(text).expectEntity().committed(), "published");
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, second)), "a published text is read as before");

        assertEquals(Result.WRITTEN, stored.keep(new RuleSource(id, name, third), List.of(ruleName)).result());

        assertEquals(2, versionsOf(text), "an edit after a publish is a new version, and the published one stays");
        assertTrue(view.<SemanticEntityVersion>latest(text).get().uncommitted());
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, third)));
        assertEquals(1, StoredRules.read(view).stream().filter(source -> source.id().equals(id)).count());
    }

    @Test
    void removingATextThatWasNeverPublishedTakesItBackAndTheNameCanBeUsedAgain() {
        String name = "Kept rules, removed before publishing";
        UUID id = stored.identity(name);
        String ruleName = "Rule removed before publishing";
        String java = sourceNamed(ruleName);
        stored.keep(new RuleSource(id, name, java), List.of(ruleName));

        StoredRules.Outcome removed = stored.remove(id);

        assertEquals(Result.TAKEN_BACK, removed.result(), removed::why);
        assertFalse(stored.kept(id));
        assertTrue(StoredRules.read(view).stream().noneMatch(source -> source.id().equals(id)));
        assertEquals(Result.AS_IT_WAS, stored.remove(id).result(), "removing it again finds nothing to remove");

        assertEquals(Result.WRITTEN, stored.keep(new RuleSource(id, name, java), List.of(ruleName)).result());
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, java)),
                "the text kept again is the text, as it was before it was removed");
    }

    @Test
    void removingAPublishedTextWritesAnInactiveVersionAndKeepsItsHistory() {
        String name = "Kept rules, removed after publishing";
        UUID id = stored.identity(name);
        String ruleName = "Rule removed after publishing";
        String java = sourceNamed(ruleName);
        stored.keep(new RuleSource(id, name, java), List.of(ruleName));
        long text = textOf(PrimitiveData.nid(PublicIds.of(id))).nid();
        changeOf(text).commit();

        StoredRules.Outcome removed = stored.remove(id);

        assertEquals(Result.WRITTEN, removed.result(), removed::why);
        assertEquals(2, versionsOf(text), "the published text stays as history");
        Latest<SemanticEntityVersion> latest = view.latest(text);
        assertTrue(latest.isAbsent() || !latest.get().active(), "the latest version is not in use");
        assertFalse(stored.kept(id));
        assertTrue(StoredRules.read(view).stream().noneMatch(source -> source.id().equals(id)));

        assertEquals(Result.WRITTEN, stored.keep(new RuleSource(id, name, java), List.of(ruleName)).result(),
                "a removed rule set is put back by keeping it again");
        assertTrue(StoredRules.read(view).contains(new RuleSource(id, name, java)));
    }

    @Test
    void removingAPublishedTextWithAnUnpublishedEditTakesTheEditBackAndRetiresTheText() {
        String name = "Kept rules, removed while edited";
        UUID id = stored.identity(name);
        String ruleName = "Rule removed while edited";
        String java = sourceNamed(ruleName);
        stored.keep(new RuleSource(id, name, java), List.of(ruleName));
        long text = textOf(PrimitiveData.nid(PublicIds.of(id))).nid();
        changeOf(text).commit();
        stored.keep(new RuleSource(id, name, java.replace("isComponentActive", "isComponentInactive")),
                List.of(ruleName));
        assertEquals(2, versionsOf(text));

        StoredRules.Outcome removed = stored.remove(id);

        assertEquals(Result.WRITTEN, removed.result(), removed::why);
        assertFalse(stored.kept(id));
        assertTrue(StoredRules.read(view).stream().noneMatch(source -> source.id().equals(id)));
        List<EntityVersion> versions = new ArrayList<>();
        EntityHandle.get(text).expectEntity().versions().forEach(versions::add);
        assertEquals(1, versions.stream().filter(version -> !version.uncommitted()).count(), "the published text stays");
        assertTrue(versions.stream().filter(EntityVersion::uncommitted).noneMatch(EntityVersion::active),
                "what is unpublished is the removal, not the edit");
    }

    @Test
    void aNameThatBelongsToAConceptThatIsNotARuleSetOrNotARuleIsRefused() {
        String ruleName = "Rule of a rule set with a taken name";
        String java = sourceNamed(ruleName);
        UUID notARuleSet = IkeConcept.KOMET_RULE.publicId().asUuidArray()[0];

        StoredRules.Outcome asRuleSet = stored.keep(new RuleSource(notARuleSet, "Komet rule", java), List.of(ruleName));

        assertTrue(asRuleSet.refused());
        assertEquals("“Komet rule” is already the name of a concept that is not a rule set. Give this rule set a"
                + " name of its own.", asRuleSet.why());
        assertEquals(0, countTextsOn(IkeConcept.KOMET_RULE.nid()), "nothing was written on that concept");

        String ruleSetName = "Kept rules, whose name a rule then takes";
        UUID ruleSet = stored.identity(ruleSetName);
        stored.keep(new RuleSource(ruleSet, ruleSetName, java), List.of(ruleName));
        String other = "Kept rules, with a rule named as a rule set is";
        UUID otherId = stored.identity(other);

        StoredRules.Outcome asRule = stored.keep(new RuleSource(otherId, other, java), List.of(ruleSetName));

        assertTrue(asRule.refused());
        assertEquals("The rule “" + ruleSetName + "” has the name of a concept that is not a rule. Give the rule a"
                + " name of its own in its @Rule annotation.", asRule.why());
        assertFalse(PrimitiveData.get().hasPublicId(PublicIds.of(otherId)), "nothing of the refused rule set was written");
    }

    @Test
    void theRuleSetsKeptAreInUseOnceTheyAreLoadedAsKometDoesWhenItStarts() throws Exception {
        String name = "Kept rules, loaded at start";
        UUID id = stored.identity(name);
        String ruleName = "Rule loaded at start";
        String java = sourceNamed(ruleName);
        stored.keep(new RuleSource(id, name, java), List.of(ruleName));
        EvreteRulesService rulesAfterARestart = new EvreteRulesService();
        assertEquals(0, rulesAfterARestart.ruleSets().count(ruleSet -> ruleSet.origin() == ListedRuleSet.Origin.SOURCE));

        List<ListedRuleSet> loaded = StoredRules.loadInto(() -> rulesAfterARestart, view);

        ListedRuleSet ruleSet = loaded.stream()
                .filter(listed -> listed.source().map(RuleSource::id).filter(id::equals).isPresent()).findFirst().orElseThrow();
        assertTrue(ruleSet.accepted() && ruleSet.inUse(), ruleSet::toString);
        assertEquals(name, ruleSet.name());
        assertEquals(List.of(ruleName), ruleSet.ruleNames().castToList());
        assertEquals(java, ruleSet.source().orElseThrow().java());
        assertTrue(rulesAfterARestart.ruleSets().contains(ruleSet), "the rule service lists it with Komet's own");
    }

    @Test
    void aKeptTextThatNoLongerCompilesIsLoadedOutOfUseWithItsFindings() throws Exception {
        String name = "Kept rules, written for an API that changed";
        UUID id = stored.identity(name);
        // The card keeps only text that compiles; a text can stop compiling when Komet's rules API changes.
        String ruleName = "Rule written for an API that changed";
        stored.keep(new RuleSource(id, name, sourceWith("NoSuchType nothing = null;").replace(RULE_NAME, ruleName)),
                List.of(ruleName));
        EvreteRulesService rulesAfterAnUpgrade = new EvreteRulesService();

        List<ListedRuleSet> loaded = StoredRules.loadInto(() -> rulesAfterAnUpgrade, view);

        ListedRuleSet ruleSet = loaded.stream()
                .filter(listed -> listed.source().map(RuleSource::id).filter(id::equals).isPresent()).findFirst().orElseThrow();
        assertTrue(ruleSet.accepted() && !ruleSet.inUse(), ruleSet::toString);
        assertTrue(ruleSet.findings().getFirst().message().contains("cannot find symbol"), ruleSet::toString);
        assertTrue(rulesAfterAnUpgrade.ruleSets().count(ListedRuleSet::inUse) >= 4, "Komet's own rules are in use all the same");
    }

    private SemanticEntity<SemanticEntityVersion> textOf(long ruleSet) {
        List<SemanticEntity<SemanticEntityVersion>> texts = new ArrayList<>();
        EntityService.get().forEachSemanticForComponentOfPattern(ruleSet, IkePattern.RULE_SOURCE_PATTERN.nid(), texts::add);
        assertEquals(1, texts.size(), "a rule set has one text semantic");
        return texts.getFirst();
    }

    private int countTextsOn(long concept) {
        List<SemanticEntity<SemanticEntityVersion>> texts = new ArrayList<>();
        EntityService.get().forEachSemanticForComponentOfPattern(concept, IkePattern.RULE_SOURCE_PATTERN.nid(), texts::add);
        return texts.size();
    }

    private static int versionsOf(long nid) {
        return EntityHandle.get(nid).expectEntity().versions().size();
    }

    private Transaction changeOf(long nid) {
        return Transaction.forVersion(view.<EntityVersion>latest(nid).get()).orElseThrow();
    }

    private static Set<Long> declared(SemanticEntityVersion version, PatternEntityVersion pattern) {
        Set<Long> declared = new LinkedHashSet<>();
        ((LongIdCollection) version.fieldValues().get(pattern.indexForMeaning(IkeConcept.DECLARED_RULES.nid())))
                .forEach(declared::add);
        return declared;
    }
}
