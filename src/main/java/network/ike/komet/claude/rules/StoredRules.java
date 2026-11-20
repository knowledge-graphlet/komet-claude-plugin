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
import dev.ikm.tinkar.common.id.LongIdCollection;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.logic.PremiseType;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.PatternEntityVersion;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.KernelConcept;
import network.ike.foundation.ike.bindings.IkeConcept;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.authoring.BlockConcepts;
import network.ike.komet.document.ComponentText;
import network.ike.komet.document.LocalConcepts;
import network.ike.komet.document.SemanticVersions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The rule sets written in Komet, as the knowledge base keeps them (IKE-Network/ike-issues#1391).
 *
 * <p>A rule set is a concept that is a kind of {@code Komet rule set}, named as the person named
 * it. Its text is one semantic of the {@code Rule source pattern} on that concept, whose versions
 * are the versions of the text: the text as written, the rules it declares, each a concept that
 * is a kind of {@code Komet rule} named by the sentence in its {@code @Rule}, and the version of
 * the rules API the text was written for. The pattern has no field for whether the text is in
 * use: an active version is, an inactive one was removed.
 *
 * <p>What is written here is an unpublished change, as every edit in Komet is, and it is written
 * the way every new concept is ({@link BlockConcepts}). The rule set, its rules and its text are
 * one change, so one publish carries them together. An unpublished version is read as any other
 * is, so a rule set a person saved is there when Komet next starts, published or not.
 *
 * <p>A concept's identity derives from its name ({@link LocalConcepts}), and so does a rule
 * set's and a rule's. A name the knowledge base already has for a concept that is no rule set
 * is refused, in words that say so.
 *
 * <p>A knowledge base that lacks the rule model keeps no rule sets: nothing is read from it and
 * nothing is written to it, and the caller is told.
 *
 * <p>Rule text is Java that runs inside Komet. So what the knowledge base keeps comes into use
 * when Komet starts only where a person saved or accepted that very text on this installation
 * ({@link AcceptedRules}); any other text is proposed, and waits for the person's Accept.
 *
 * <p>Removing a rule set that was never published takes back the whole of it, its concept and
 * its rules' concepts with its text, so that a later publish does not publish a rule set with no
 * text. A published rule set keeps its concepts, which are its identity, and its text gets an
 * inactive version.
 */
public final class StoredRules {

    /** What keeping a rule set in the knowledge base, or taking it out, came to. */
    public enum Result {
        /** Written, as part of an unpublished change. */
        WRITTEN,
        /**
         * A rule set that was never published was taken back whole: its text, its concept and its
         * rules' concepts are out of the knowledge base.
         */
        TAKEN_BACK,
        /**
         * A text that was never published was taken back out of its unpublished change, and the
         * concepts of the rule set and of its rules stay.
         */
        TEXT_TAKEN_BACK,
        /** Nothing was written: the knowledge base already has it so. */
        AS_IT_WAS,
        /** Nothing was written: the knowledge base lacks the rule model, so it keeps no rule sets. */
        NO_RULE_MODEL,
        /** Nothing was written, for the reason given. */
        REFUSED
    }

    /**
     * What keeping a rule set, or taking it out, came to.
     *
     * @param result what happened
     * @param why    the reason nothing was written, for a person to act on, or what a person
     *               should know about what was taken back; empty otherwise
     */
    public record Outcome(Result result, String why) {

        /** @return whether the write was refused, so that what depends on it should not go on */
        public boolean refused() {
            return result == Result.REFUSED;
        }

        private static Outcome of(Result result) {
            return new Outcome(result, "");
        }

        private static Outcome refusedBecause(String why) {
            return new Outcome(Result.REFUSED, why);
        }
    }

    private final Supplier<ViewCalculator> view;
    private final Supplier<EditCoordinate> edit;

    /**
     * @param view the view the knowledge base is read under, asked for at each use
     * @param edit whose change a write is, in which module and on which path, asked for at each use
     */
    public StoredRules(Supplier<ViewCalculator> view, Supplier<EditCoordinate> edit) {
        this.view = view;
        this.edit = edit;
    }

    /**
     * @param view the view the knowledge base is read under
     * @return whether the knowledge base has the concepts and the pattern rule sets are kept with
     */
    public static boolean modelPresent(ViewCalculator view) {
        return has(IkePattern.RULE_SOURCE_PATTERN.publicId(), view)
                && has(IkeConcept.KOMET_RULE_SET.publicId(), view)
                && has(IkeConcept.KOMET_RULE.publicId(), view)
                && has(IkeConcept.RULE_SET_TEXT.publicId(), view)
                && has(IkeConcept.DECLARED_RULES.publicId(), view)
                && has(IkeConcept.RULES_API_VERSION.publicId(), view);
    }

    /**
     * Whether the knowledge base has the rule source pattern at all, asked without a view: what
     * Komet's start asks first, so a knowledge base that keeps no rule sets costs it nothing.
     *
     * @return whether the open knowledge base has the pattern
     */
    public static boolean patternPresent() {
        return PrimitiveData.get().hasPublicId(IkePattern.RULE_SOURCE_PATTERN.publicId());
    }

    /**
     * The identity of the rule set a name names: the concept the knowledge base has under that
     * name, or the identity a concept of that name will have once it is written.
     *
     * @param name the rule set's name
     * @param view the view the knowledge base is read under; null before there is one, when the
     *             identity is the one the name derives
     * @return the rule set's identity
     */
    public static UUID identity(String name, ViewCalculator view) {
        return view == null ? LocalConcepts.uuid(name) : firstUuid(LocalConcepts.identity(name, view));
    }

    /**
     * Reads every rule set whose text is in use, by name.
     *
     * @param view the view the knowledge base is read under
     * @return the rule sets, each with its concept's identity and name and its text as last written
     */
    public static List<RuleSource> read(ViewCalculator view) {
        List<RuleSource> found = new ArrayList<>();
        if (!modelPresent(view)) {
            return found;
        }
        int textField = fieldOf(IkeConcept.RULE_SET_TEXT, view);
        Set<Long> seen = new LinkedHashSet<>();
        EntityService.get().forEachSemanticOfPattern(IkePattern.RULE_SOURCE_PATTERN.nid(), semantic -> {
            Optional<SemanticEntityVersion> inUse = versionInUse(semantic.nid(), view);
            long ruleSet = semantic.referencedComponentNid();
            if (inUse.isPresent() && SemanticVersions.visible(ruleSet, view)
                    && inUse.get().fieldValues().get(textField) instanceof String text && seen.add(ruleSet)) {
                found.add(new RuleSource(firstUuid(EntityHandle.getEntityOrThrow(ruleSet).publicId()),
                        name(ruleSet, view), text));
            }
        });
        found.sort(Comparator.comparing(RuleSource::name).thenComparing(source -> source.id().toString()));
        return found;
    }

    /**
     * Gives the rule service every rule set the knowledge base keeps. One whose text a person
     * saved or accepted on this installation is put in use, as a person's own rule source is, and
     * one that no longer compiles is listed out of use with its findings. Any other is proposed:
     * compiled and listed, with none of its code run until a person accepts it.
     *
     * @param rules    the rule service, asked for only when the knowledge base keeps a rule set
     * @param view     the view the knowledge base is read under
     * @param accepted the texts a person saved or accepted on this installation
     * @return the rule sets as the rule service now lists them; empty when the knowledge base
     *         keeps none
     */
    public static List<ListedRuleSet> loadInto(Supplier<RuleService> rules, ViewCalculator view,
                                               AcceptedRules accepted) {
        List<ListedRuleSet> loaded = new ArrayList<>();
        for (RuleSource source : read(view)) {
            loaded.add(accepted.accepted(source) ? rules.get().putRuleSource(source)
                    : rules.get().proposeRuleSource(source));
        }
        return loaded;
    }

    /** @return whether the knowledge base read under this view has the rule model */
    public boolean modelPresent() {
        return modelPresent(view.get());
    }

    /**
     * @param name a rule set's name
     * @return the rule set's identity, as {@link #identity(String, ViewCalculator)} gives it
     */
    public UUID identity(String name) {
        return identity(name, view.get());
    }

    /**
     * @param id a rule set's identity
     * @return whether the knowledge base keeps a text in use for it
     */
    public boolean kept(UUID id) {
        ViewCalculator view = this.view.get();
        if (!modelPresent(view) || !has(PublicIds.of(id), view)) {
            return false;
        }
        return textOf(PrimitiveData.nid(PublicIds.of(id)), view).filter(text -> inUse(text.nid(), view)).isPresent();
    }

    /**
     * @param id a rule set's identity
     * @return the text the knowledge base keeps in use for it; empty when it keeps none
     */
    public Optional<String> keptText(UUID id) {
        ViewCalculator view = this.view.get();
        if (!modelPresent(view) || !has(PublicIds.of(id), view)) {
            return Optional.empty();
        }
        int textField = fieldOf(IkeConcept.RULE_SET_TEXT, view);
        return textOf(PrimitiveData.nid(PublicIds.of(id)), view)
                .flatMap(text -> versionInUse(text.nid(), view))
                .map(version -> version.fieldValues().get(textField))
                .filter(String.class::isInstance)
                .map(String.class::cast);
    }

    /**
     * Keeps a rule set: its concept and its rules' concepts where the knowledge base lacks them,
     * and its text as a new version, all in one unpublished change.
     *
     * @param source    the rule set: its identity, the name its concept takes when it is new, and
     *                  its text
     * @param ruleNames the names of the rules the text declares, as the rule service found them
     * @return what was written, or why nothing was
     */
    public Outcome keep(RuleSource source, List<String> ruleNames) {
        ViewCalculator view = this.view.get();
        if (!modelPresent(view)) {
            return Outcome.of(Result.NO_RULE_MODEL);
        }
        PublicId ruleSet = PublicIds.of(source.id());
        boolean lacking = !has(ruleSet, view);
        if (!lacking && !isKindOf(PrimitiveData.nid(ruleSet), IkeConcept.KOMET_RULE_SET, view)) {
            return Outcome.refusedBecause("“" + source.name() + "” is already the name of a concept that is"
                    + " not a rule set. Give this rule set a name of its own.");
        }
        Map<String, PublicId> rules = new LinkedHashMap<>();
        for (String ruleName : ruleNames) {
            PublicId rule = LocalConcepts.identity(ruleName, view);
            if (has(rule, view) && !isKindOf(PrimitiveData.nid(rule), IkeConcept.KOMET_RULE, view)) {
                return Outcome.refusedBecause("The rule “" + ruleName + "” has the name of a concept that is"
                        + " not a rule. Give the rule a name of its own in its @Rule annotation.");
            }
            rules.put(ruleName, rule);
        }

        Optional<SemanticEntity<SemanticEntityVersion>> text = lacking ? Optional.empty()
                : textOf(PrimitiveData.nid(ruleSet), view);
        boolean rulesAllThere = rules.values().stream().allMatch(rule -> has(rule, view));
        if (text.isPresent() && rulesAllThere && sameAsKept(text.get(), source.java(), rules.values(), view)) {
            return Outcome.of(Result.AS_IT_WAS);
        }

        EditCoordinate edit = this.edit.get();
        BlockConcepts writer = new BlockConcepts("Rule set: " + source.name(), view,
                ConceptFacade.make(edit.getAuthorNidForChanges()), ConceptFacade.make(edit.getDefaultModuleNid()),
                ConceptFacade.make(edit.getDefaultPathNid()));
        List<EntityFacade> unpublished = new ArrayList<>();
        if (!lacking) {
            addIfUnpublished(unpublished, PrimitiveData.nid(ruleSet));
        }
        text.ifPresent(semantic -> addIfUnpublished(unpublished, semantic.nid()));
        rules.values().stream().filter(rule -> has(rule, view))
                .forEach(rule -> addIfUnpublished(unpublished, PrimitiveData.nid(rule)));
        boolean joined = !unpublished.isEmpty();
        if (joined && !writer.continuePendingWorkOf(unpublished.toArray(EntityFacade[]::new))) {
            return Outcome.refusedBecause("“" + source.name() + "” or one of its rules is unpublished in a"
                    + " change this one cannot join (another author's, module's or path's, or more than one)."
                    + " Publish or discard that change first.");
        }

        PublicId textId = text.map(semantic -> (PublicId) semantic.publicId())
                .orElseGet(() -> LocalConcepts.derived(ruleSet, "rule-source"));
        ConceptFacade ruleSetConcept = EntityProxy.Concept.make(source.name(), ruleSet);
        try {
            if (lacking) {
                writer.concept(ruleSet, source.name(), List.of(), BlockConcepts.primitiveBelow(IkeConcept.KOMET_RULE_SET));
            }
            rules.forEach((ruleName, rule) -> {
                if (!has(rule, view)) {
                    writer.concept(rule, ruleName, List.of(), BlockConcepts.primitiveBelow(IkeConcept.KOMET_RULE));
                }
            });
            long[] declared = rules.values().stream().mapToLong(PrimitiveData::nid).toArray();
            PatternEntityVersion pattern = patternVersion(view);
            writer.semantic(textId, IkePattern.RULE_SOURCE_PATTERN, ruleSetConcept, fields -> {
                fields.get(index(pattern, IkeConcept.RULE_SET_TEXT)).setObjectValue(source.java());
                fields.get(index(pattern, IkeConcept.DECLARED_RULES)).setObjectValue(LongIds.set.of(declared));
                fields.get(index(pattern, IkeConcept.RULES_API_VERSION)).setObjectValue(RuleService.RULES_API_VERSION);
            });
            return Outcome.of(Result.WRITTEN);
        } catch (RuntimeException e) {
            if (joined) {
                // The change contains earlier work too: take back only the text written here.
                if (PrimitiveData.get().hasPublicId(textId)) {
                    writer.withdraw(PrimitiveData.nid(textId));
                }
            } else {
                writer.rollback();
            }
            return Outcome.refusedBecause("The knowledge base did not take it: " + e.getMessage());
        }
    }

    /**
     * Takes a rule set out of use in the knowledge base.
     *
     * <p>A rule set that was never published is taken back whole, when the unpublished change it
     * is in contains nothing else: its text, its concept and its rules' concepts, so that a later
     * publish does not publish a rule set with no text. When that change contains other work too,
     * only the text is taken back, and the concepts stay with the other work.
     *
     * <p>A published rule set keeps its concept and its rules' concepts, which are its identity.
     * An unpublished version of its text is taken back out of its unpublished change. A published
     * text then gets an inactive version, an unpublished change like any other, and the versions
     * before it stay as its history.
     *
     * @param id the rule set's identity
     * @return what was written or taken back, or why nothing was
     */
    public Outcome remove(UUID id) {
        ViewCalculator view = this.view.get();
        if (!modelPresent(view)) {
            return Outcome.of(Result.NO_RULE_MODEL);
        }
        PublicId ruleSet = PublicIds.of(id);
        if (!has(ruleSet, view)) {
            return Outcome.of(Result.AS_IT_WAS);
        }
        long ruleSetNid = PrimitiveData.nid(ruleSet);
        Optional<SemanticEntity<SemanticEntityVersion>> text = textOf(ruleSetNid, view);
        if (text.isEmpty() || !inUse(text.get().nid(), view)) {
            return Outcome.of(Result.AS_IT_WAS);
        }
        long textNid = text.get().nid();
        String name = name(ruleSetNid, view);
        EditCoordinate edit = this.edit.get();
        BlockConcepts writer = new BlockConcepts("Remove rule set: " + name, view,
                ConceptFacade.make(edit.getAuthorNidForChanges()), ConceptFacade.make(edit.getDefaultModuleNid()),
                ConceptFacade.make(edit.getDefaultPathNid()));
        boolean neverPublished = EntityHandle.get(ruleSetNid).orNull() instanceof Entity<?> concept
                && !published(concept);
        try {
            if (changeOfItsOwn(ruleSetNid, view).isPresent()) {
                // Never published, in a change that contains nothing else: the whole change is taken back.
                if (!writer.continuePendingWorkOf(EntityProxy.make(ruleSetNid))) {
                    return Outcome.refusedBecause("“" + name + "” is unpublished in a change this one cannot"
                            + " join (another author's, module's or path's). Publish or discard that change first.");
                }
                writer.rollback();
                if (has(ruleSet, view)) {
                    return Outcome.refusedBecause("The unpublished change that contains “" + name + "” could not be"
                            + " taken back.");
                }
                return Outcome.of(Result.TAKEN_BACK);
            }
            if (view.<SemanticEntityVersion>latest(textNid).get().uncommitted()) {
                if (!writer.continuePendingWorkOf(EntityProxy.make(textNid))) {
                    return Outcome.refusedBecause("The text of “" + name + "” is unpublished in a change this"
                            + " one cannot join (another author's, module's or path's). Publish or discard that"
                            + " change first.");
                }
                writer.withdraw(textNid);
                if (!SemanticVersions.visible(textNid, view) || !inUse(textNid, view)) {
                    // It was never published, or the version published before it was already removed.
                    return neverPublished
                            ? new Outcome(Result.TEXT_TAKEN_BACK, "The concepts of the rule set and of its rules"
                                    + " stay, because the unpublished change they are in contains other work too.")
                            : Outcome.of(Result.TEXT_TAKEN_BACK);
                }
            }
            writer.retire(text.get().publicId(), IkePattern.RULE_SOURCE_PATTERN, EntityProxy.make(ruleSetNid));
            return Outcome.of(Result.WRITTEN);
        } catch (RuntimeException e) {
            return Outcome.refusedBecause("The knowledge base did not take it: " + e.getMessage());
        }
    }

    /**
     * The unpublished change a rule set is in, when the rule set was never published and the
     * change contains nothing but the rule set: its concept, the concepts of rules that were never
     * published either, and the semantics on what it contains, the rule set's text among them.
     * Such a change can be taken back whole. A change that also contains another rule set, some
     * other concept, a pattern, or an edit of a component that is published is not the rule set's
     * own.
     */
    private static Optional<Transaction> changeOfItsOwn(long ruleSet, ViewCalculator view) {
        if (!(EntityHandle.get(ruleSet).orNull() instanceof ConceptEntity<?> concept) || published(concept)) {
            return Optional.empty();
        }
        Transaction change = null;
        for (EntityVersion version : concept.versions()) {
            if (!version.uncommitted()) {
                continue;
            }
            Optional<Transaction> holder = Transaction.forVersion(version);
            if (holder.isEmpty() || (change != null && holder.get() != change)) {
                return Optional.empty();
            }
            change = holder.get();
        }
        if (change == null) {
            return Optional.empty();
        }
        Set<Long> components = new LinkedHashSet<>();
        for (long nid : change.componentNids()) {
            components.add(nid);
        }
        for (long nid : components) {
            boolean itsOwn = switch (EntityHandle.get(nid).orNull()) {
                case StampEntity<?> _ -> true;
                case ConceptEntity<?> other -> !published(other)
                        && (nid == ruleSet || isKindOf(nid, IkeConcept.KOMET_RULE, view));
                case SemanticEntity<?> semantic -> !published(semantic)
                        && components.contains(semantic.referencedComponentNid());
                case null, default -> false;
            };
            if (!itsOwn) {
                return Optional.empty();
            }
        }
        return Optional.of(change);
    }

    /** Whether a component has a version that was published: one neither unpublished nor cancelled. */
    private static boolean published(Entity<?> entity) {
        for (EntityVersion version : entity.versions()) {
            if (!version.uncommitted() && !version.canceled()) {
                return true;
            }
        }
        return false;
    }

    // ── reading ──────────────────────────────────────────────────

    /** The semantic of the rule source pattern on a rule set, in use or not. */
    private static Optional<SemanticEntity<SemanticEntityVersion>> textOf(long ruleSet, ViewCalculator view) {
        List<SemanticEntity<SemanticEntityVersion>> found = new ArrayList<>();
        EntityService.get().forEachSemanticForComponentOfPattern(ruleSet, IkePattern.RULE_SOURCE_PATTERN.nid(), semantic -> {
            if (SemanticVersions.visible(semantic.nid(), view)) {
                found.add(semantic);
            }
        });
        // One semantic for each rule set; were there more, the one in use is the text.
        return found.stream().filter(semantic -> inUse(semantic.nid(), view)).findFirst()
                .or(() -> found.stream().findFirst());
    }

    private static boolean inUse(long text, ViewCalculator view) {
        return versionInUse(text, view).isPresent();
    }

    /**
     * The version of a text that is in use: its latest, when that is active. A text taken back
     * before it was ever published is a record with no version left, which has none in use.
     */
    private static Optional<SemanticEntityVersion> versionInUse(long text, ViewCalculator view) {
        if (!SemanticVersions.visible(text, view)) {
            return Optional.empty();
        }
        Latest<SemanticEntityVersion> latest = view.latest(text);
        return latest.isPresent() && latest.get().active() ? Optional.of(latest.get()) : Optional.empty();
    }

    /** Whether the text in use is this text, declares these rules and was written for this rules API. */
    private static boolean sameAsKept(SemanticEntity<SemanticEntityVersion> text, String java,
                                      java.util.Collection<PublicId> rules, ViewCalculator view) {
        Optional<SemanticEntityVersion> inUse = versionInUse(text.nid(), view);
        if (inUse.isEmpty()) {
            return false;
        }
        PatternEntityVersion pattern = patternVersion(view);
        List<Object> values = inUse.get().fieldValues().castToList();
        Set<Long> declared = new LinkedHashSet<>();
        if (values.get(index(pattern, IkeConcept.DECLARED_RULES)) instanceof LongIdCollection kept) {
            kept.forEach(declared::add);
        }
        Set<Long> wanted = new LinkedHashSet<>();
        rules.forEach(rule -> wanted.add(PrimitiveData.nid(rule)));
        return java.equals(values.get(index(pattern, IkeConcept.RULE_SET_TEXT)))
                && RuleService.RULES_API_VERSION.equals(values.get(index(pattern, IkeConcept.RULES_API_VERSION)))
                && declared.equals(wanted);
    }

    private static PatternEntityVersion patternVersion(ViewCalculator view) {
        return view.latestPatternEntityVersion(IkePattern.RULE_SOURCE_PATTERN.nid()).orElseThrow(() ->
                new IllegalStateException("The rule source pattern has no version on this view."));
    }

    private static int fieldOf(ConceptFacade meaning, ViewCalculator view) {
        return index(patternVersion(view), meaning);
    }

    /** A field is found by its meaning, not by its place, so a pattern that gains a field is still read. */
    private static int index(PatternEntityVersion pattern, ConceptFacade meaning) {
        int index = pattern.indexForMeaning(meaning.nid());
        if (index < 0) {
            throw new IllegalStateException("The rule source pattern has no field “" + meaning.description() + "”.");
        }
        return index;
    }

    /**
     * Whether a concept is a kind of another, directly. The stated definition is read first, so a
     * concept just written is found before the reasoner has run.
     */
    private static boolean isKindOf(long concept, ConceptFacade kind, ViewCalculator view) {
        Set<Long> parents = statedParents(concept, view);
        view.parentsOf(concept).forEach(parents::add);
        return parents.contains(kind.nid());
    }

    /** The concepts a concept's stated definition gives as what it is a kind of. */
    private static Set<Long> statedParents(long concept, ViewCalculator view) {
        Set<Long> parents = new LinkedHashSet<>();
        Latest<DiTreeEntity> stated = view.getAxiomTreeForEntity(concept, PremiseType.STATED);
        if (!stated.isPresent()) {
            return parents;
        }
        DiTreeEntity tree = stated.get();
        for (EntityVertex set : conjuncts(tree, tree.root())) {
            long meaning = set.getMeaningNid();
            if (meaning != KernelConcept.NECESSARY_SET.nid() && meaning != KernelConcept.SUFFICIENT_SET.nid()) {
                continue;
            }
            for (EntityVertex conjunct : conjuncts(tree, set)) {
                if (conjunct.getMeaningNid() == KernelConcept.CONCEPT_REFERENCE.nid()) {
                    ConceptFacade parent = conjunct.propertyFast(KernelConcept.CONCEPT_REFERENCE);
                    parents.add(parent.nid());
                }
            }
        }
        return parents;
    }

    /** The vertices under a vertex, with the vertices under an And in place of the And. */
    private static List<EntityVertex> conjuncts(DiTreeEntity tree, EntityVertex vertex) {
        List<EntityVertex> out = new ArrayList<>();
        for (EntityVertex child : tree.successors(vertex)) {
            if (child.getMeaningNid() == KernelConcept.AND.nid()) {
                out.addAll(tree.successors(child).castToList());
            } else {
                out.add(child);
            }
        }
        return out;
    }

    private static void addIfUnpublished(List<EntityFacade> unpublished, long nid) {
        if (EntityHandle.get(nid).expectEntity().uncommitted()) {
            unpublished.add(EntityProxy.make(nid));
        }
    }

    /** Whether the knowledge base has a component with a version the view sees, an unpublished one included. */
    private static boolean has(PublicId id, ViewCalculator view) {
        return SemanticVersions.exists(id, view);
    }

    /** A rule source is listed under one UUID, the concept's first. */
    private static UUID firstUuid(PublicId id) {
        return id.asUuidArray()[0];
    }

    /**
     * The name a rule set is listed under: its regular name, or its fully qualified name when it
     * has none, or what {@link ComponentText} calls a component with no name the view sees.
     */
    private static String name(long nid, ViewCalculator view) {
        return view.getRegularDescriptionText(nid)
                .or(() -> view.getFullyQualifiedNameText(nid))
                .orElseGet(() -> ComponentText.name(view, nid))
                .replaceAll("\\s+", " ").strip();
    }
}
