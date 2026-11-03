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
package network.ike.komet.claude.decision;

import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.LongIds;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.util.uuid.UuidT5Generator;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.SemanticVersionRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.transaction.StampedWriter;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import dev.ikm.tinkar.terms.State;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.complexconcept.ComplexConceptText;
import network.ike.komet.claude.stamp.StampNames;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.LongObjectMaps;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;

import java.math.BigDecimal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Writes the decision tree a block states as a version of its tree semantic, uncommitted
 * (ike-lab-documents topics {@code dev-decision-document}, "The document is a stored document",
 * and {@code dev-decision-tree-blocks}, "Round trip through git"). The block's text is the
 * canonical form; the semantic, of the {@code Decision tree pattern}, holds the tree in its one
 * {@code DITREE} field, about the tree's root concept, and is derived from the text: a block
 * that parses and validates writes a first version, and each edit of it after a new version of
 * the same semantic, never a second tree. The write path keeps each vertex's identity from
 * version to version by {@link DecisionTreeIdentityTable}.
 *
 * <p>A symbol the store lacks becomes a concept first, under the identity its symbol derives
 * ({@link ComplexConceptText#localPublicId(String)}), the identity a complex concept's Propose
 * gives the same symbol, so the decision point a tree and a complex concept both name is one
 * concept. The semantic's identity derives from the block's {@code id=}, or its root's
 * {@code @id} when the block names none. Nothing is committed: the versions are kept or cancelled
 * with the person's other changes.
 */
public final class DecisionTreeWrites {

    /** The namespace a tree semantic's public id is derived in. */
    static final UUID TREE_NAMESPACE = UUID.fromString("388278f8-5f52-40b0-b456-2e3e3211fde9");

    private DecisionTreeWrites() {
    }

    /** What a write did: the tree semantic's public id when one was written, and a sentence for the person. */
    public record Written(Optional<PublicId> tree, String message) {
        static Written refused(String message) {
            return new Written(Optional.empty(), message);
        }
    }

    /** The public id of the tree semantic a block with this id writes. */
    public static PublicId treeId(String id) {
        return PublicIds.of(UuidT5Generator.get(TREE_NAMESPACE, "decision-tree block: " + id));
    }

    /**
     * Writes the tree a block states.
     *
     * @param info the block's info string, {@code decision-tree id=rda-site-inspection cohort=100}
     * @param body the block's text
     * @param view the view the block's identifiers are read under
     * @param edit whose change it is, in which module and on which path
     * @return what was written, or why nothing was
     */
    public static Written write(String info, String body, ViewCalculator view, EditCoordinate edit) {
        DecisionTerms terms = DecisionTerms.fromBindings();
        BlockIdentities read = new BlockIdentities(new ViewIdentities(view));
        DecisionTreeText.Parsed first = DecisionTreeText.parse(body, terms, read);
        if (first.tree().isEmpty()) {
            return Written.refused("The block states no tree, so none was written.");
        }
        List<String> problems = problems(first);
        if (!problems.isEmpty()) {
            return Written.refused("The tree was not written: " + problems.getFirst()
                    + (problems.size() > 1 ? ", and " + (problems.size() - 1) + " more." : "."));
        }
        Optional<String> id = blockId(info).or(() -> LinkedTrees.rootIdentifier(first.tree().get(), read));
        if (id.isEmpty()) {
            return Written.refused("The tree was not written: its block names no id= and its root no @id.");
        }
        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        // every symbol the store lacks becomes a concept, under the identity the symbol derives, once
        Map<PublicId, String> missing = new LinkedHashMap<>();
        for (String identifier : read.localIdentifiers()) {
            PublicId concept = PublicIds.of(ComplexConceptText.localPublicId(identifier));
            if (!exists(concept, view)) {
                missing.put(concept, read.label(read.nid(identifier).orElseThrow()));
            }
        }
        StampedWriter writer = missing.isEmpty() ? null : StampedWriter.open("Decision tree block: " + id.get(),
                State.ACTIVE, author, ConceptFacade.make(edit.getDefaultModuleNid()), path);
        try {
            if (writer != null) {
                missing.forEach((concept, label) -> {
                    writer.concept(concept);
                    name(writer, concept, label);
                });
            }
            Identities written = new WrittenIdentities(new ViewIdentities(view), Set.copyOf(read.localIdentifiers()));
            DecisionTree tree = DecisionTreeText.parse(body, terms, written).tree().orElseThrow();
            stored(tree.source(), terms);
            OptionalLong root = tree.root().map(DecisionElement::concept).orElse(OptionalLong.empty());
            if (root.isEmpty()) {
                cancel(writer);
                return Written.refused("The tree was not written: its root names no concept.");
            }
            PublicId semantic = treeId(id.get());
            String concepts = missing.isEmpty() ? "" : ", with " + missing.size()
                    + (missing.size() == 1 ? " concept" : " concepts") + " the knowledge base lacked";
            if (!exists(semantic, view)) {
                StampedWriter firstVersion = writer != null ? writer : StampedWriter.open("Decision tree block: " + id.get(),
                        State.ACTIVE, author, ConceptFacade.make(edit.getDefaultModuleNid()), path);
                firstVersion.semantic(semantic, IkePattern.DECISION_TREE_PATTERN.publicId(), PrimitiveData.publicId(root.getAsLong()),
                        tree.source());
                return new Written(Optional.of(semantic), "Wrote the tree " + id.get() + " as its first version"
                        + concepts + "; uncommitted, commit to keep it.");
            }
            Versioned versioned = version(semantic, tree, view, edit);
            if (versioned.refusal().isPresent()) {
                cancel(writer);
                return Written.refused(versioned.refusal().get());
            }
            return new Written(Optional.of(semantic), (versioned.replacedPending()
                    ? "Rewrote the tree " + id.get() + " in its uncommitted version"
                    : "Wrote a new version of the tree " + id.get()) + concepts + "; uncommitted, commit to keep it.");
        } catch (RuntimeException e) {
            cancel(writer);
            return Written.refused("The tree was not written: " + e.getMessage());
        }
    }

    /**
     * What writing a version did: why nothing was written, or whether the version replaced the
     * author's own uncommitted one, as one author's edits before a commit are one change.
     */
    private record Versioned(Optional<String> refusal, boolean replacedPending) {
        static Versioned refused(String why) {
            return new Versioned(Optional.of(why), false);
        }
    }

    /** A version of an existing tree semantic: a new one, or the author's pending one rewritten. */
    private static Versioned version(PublicId semantic, DecisionTree tree, ViewCalculator view, EditCoordinate edit) {
        long nid = PrimitiveData.nid(semantic);
        if (!(EntityHandle.get(nid).orNull() instanceof SemanticRecord record)
                || record.patternNid() != IkePattern.DECISION_TREE_PATTERN.nid()) {
            return Versioned.refused("The tree was not written: its identity names something other than a decision tree.");
        }
        Latest<SemanticEntityVersion> latest = view.latest(nid);
        if (latest.isAbsent()) {
            return Versioned.refused("The tree was not written: no version of it is visible in this view.");
        }
        SemanticEntityVersion head = latest.get();
        Optional<Transaction> pending = Optional.empty();
        if (head.uncommitted()) {
            if (head.stamp().authorNid() != edit.getAuthorNidForChanges()) {
                return Versioned.refused("The tree was not written: a version by " + StampNames.name(view, head.stamp().authorNid())
                        + " is pending; it is committed or cancelled before anyone else writes the tree.");
            }
            pending = Transaction.forVersion(head);
        }
        Transaction transaction = pending.orElseGet(() -> Transaction.make("Decision tree " + semantic.idString()));
        try {
            StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, edit.getAuthorNidForChanges(),
                    head.moduleNid(), edit.getDefaultPathNid());
            SemanticRecord updated = record.with(new SemanticVersionRecord(record, stamp.nid(),
                    Lists.immutable.of(tree.source()))).build();
            transaction.addComponent(updated);
            EntityService.get().putEntity(updated);
            return new Versioned(Optional.empty(), pending.isPresent());
        } catch (RuntimeException e) {
            if (pending.isEmpty()) {
                transaction.cancel();
            }
            throw e;
        }
    }

    /**
     * The tree in its stored form, in place: a property that names a component holds the
     * component, and one that names several a list of them, never a bare nid, which a store
     * numbers for itself; a number is exact, never the float a double is stored as. The tree
     * reads back the same through its adaptor.
     */
    static DiTreeEntity stored(DiTreeEntity tree, DecisionTerms terms) {
        Set<Long> components = Set.of(terms.conceptReference(), terms.unitOfMeasure(), terms.statement(),
                terms.derivedFrom(), terms.operands(), terms.measureBasis(), terms.basisSource(), terms.treeReference(),
                terms.inputKind());
        for (EntityVertex vertex : tree.vertexMap()) {
            MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
            vertex.properties().forEachKeyValue((key, value) ->
                    properties.put(key, components.contains(key) ? storedComponent(value) : storedValue(value)));
            vertex.setProperties(properties);
        }
        return tree;
    }

    private static Object storedComponent(Object value) {
        return switch (value) {
            case Long nid -> EntityFacade.make(nid);
            case long[] nids -> LongIds.list.of(nids);
            case Object[] items -> LongIds.list.of(java.util.Arrays.stream(items)
                    .mapToLong(item -> item instanceof EntityFacade facade ? facade.nid() : ((Number) item).longValue()).toArray());
            default -> value;
        };
    }

    private static Object storedValue(Object value) {
        return switch (value) {
            case Double number -> BigDecimal.valueOf(number);
            case Float number -> new BigDecimal(number.toString());
            default -> value;
        };
    }

    /** What keeps a block from being written: its findings and its tree's, the note of local symbols aside. */
    static List<String> problems(DecisionTreeText.Parsed parsed) {
        List<String> problems = new ArrayList<>();
        parsed.findings().forEach(finding -> {
            if (!DecisionTreeFeedback.isLocalSymbolsNote(finding)) {
                problems.add(finding.message());
            }
        });
        parsed.tree().ifPresent(tree -> tree.check().forEach(finding -> problems.add(finding.message())));
        return problems;
    }

    /** The block's own id, {@code id=…} in its info string, when it names one. */
    static Optional<String> blockId(String info) {
        if (info == null) {
            return Optional.empty();
        }
        for (String word : Tokens.words(info)) {
            if (word.startsWith("id=") && word.length() > 3) {
                return Optional.of(word.substring(3).replace("\"", ""));
            }
        }
        return Optional.empty();
    }

    /**
     * Whether a component has a version this view sees, uncommitted ones included: a record whose
     * versions were all cancelled, by a write that failed after it began, is written again.
     */
    private static boolean exists(PublicId publicId, ViewCalculator view) {
        if (!PrimitiveData.get().hasPublicId(publicId)) {
            return false;
        }
        try {
            return view.latest(PrimitiveData.nid(publicId)).isPresent();
        } catch (IllegalStateException noVersions) {
            // a record left with no version at all, its one version cancelled
            return false;
        }
    }

    private static void cancel(StampedWriter writer) {
        if (writer != null) {
            writer.cancel();
        }
    }

    /** The fully qualified name and a preferred regular name, both in US English. */
    private static void name(StampedWriter writer, PublicId concept, String name) {
        writer.fullyQualifiedName(concept, name);
        PublicId regularName = PublicIds.newRandom();
        writer.semantic(regularName, KernelPattern.DESCRIPTION_PATTERN.publicId(), concept,
                KernelConcept.ENGLISH_LANGUAGE, name, KernelConcept.DESCRIPTION_NOT_CASE_SENSITIVE,
                KernelConcept.REGULAR_NAME_DESCRIPTION_TYPE);
        writer.semantic(PublicIds.newRandom(), KernelPattern.US_DIALECT_PATTERN.publicId(), regularName,
                KernelConcept.PREFERRED);
    }

    /**
     * The identities a block is read under once its symbols are concepts: what the store holds,
     * and each of the block's symbols as the concept written under the identity it derives.
     */
    private record WrittenIdentities(Identities store, Set<String> symbols) implements Identities {
        @Override
        public OptionalLong nid(String identifier) {
            OptionalLong stored = store.nid(identifier);
            if (stored.isPresent() || !symbols.contains(identifier)) {
                return stored;
            }
            return OptionalLong.of(PrimitiveData.nid(PublicIds.of(ComplexConceptText.localPublicId(identifier))));
        }

        @Override
        public Optional<String> identifier(long nid) {
            return store.identifier(nid);
        }

        @Override
        public String label(long nid) {
            return store.label(nid);
        }

        @Override
        public OptionalLong local(String identifier, String label) {
            return nid(identifier);
        }
    }
}
