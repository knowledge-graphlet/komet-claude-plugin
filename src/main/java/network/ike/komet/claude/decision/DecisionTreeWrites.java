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
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.edit.EditCoordinate;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculator;
import dev.ikm.tinkar.entity.SemanticEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.terms.ConceptFacade;
import dev.ikm.tinkar.terms.EntityFacade;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.authoring.BlockConcepts;
import network.ike.komet.claude.block.BlockIdentities;
import network.ike.komet.claude.block.Identities;
import network.ike.komet.claude.stamp.LocalConcepts;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.stamp.SemanticVersions;
import network.ike.komet.claude.stamp.SemanticVersions.Versioned;
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
import java.util.function.Function;

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
 * <p>A name the store lacks becomes a concept first, under the identity its name derives
 * ({@link LocalConcepts}), the identity a complex concept's Propose gives the same name, so the
 * decision point a tree and a complex concept both name is one concept. The semantic's identity
 * derives from its root concept: one decision has one tree, and a tree that differs from one this
 * block did not state is held for the person rather than written over it. Nothing is committed:
 * the versions are kept or cancelled with the person's other changes.
 *
 * <p>A graphlet model's parameter that reads one of the document's statements
 * ({@code dev-decision-point-knowledge}, settled with KEC 2026-11-06) is stored as a parameter
 * vertex with no concept reference: under the Statement key it holds the statement the anf block
 * writes, by the identity {@link network.ike.komet.claude.anf.AddAnfStatement} derives for it,
 * its value under the interval keys and its scale under the unit-of-measure key; a measure that
 * reads the parameter is derived from the same statement. No concept is minted for a statement.
 * The model, and the concepts its block mints, are written in the block's module, never the
 * edit's default, as a statement about a stored document's block is.
 */
public final class DecisionTreeWrites {

    private DecisionTreeWrites() {
    }

    /** What a write did: the tree semantic's public id when one was written, and a sentence for the person. */
    public record Written(Optional<PublicId> tree, String message) {
        static Written refused(String message) {
            return new Written(Optional.empty(), message);
        }
    }

    /**
     * The document a model block is read against: the measures its statements state, read under
     * the identities the block is read under; the statement each name resolves to, the one the
     * anf block of that name wrote or will write; and the module the document's blocks are written
     * in. {@link #NONE} is a block with no document, whose parameters read no statements.
     *
     * @param measures   the statements' measures, by name, under the identities a parse uses
     * @param statements the statement an anf block's name resolves to, by its derived identity
     * @param module     the module the block is written in; empty for the edit's default
     */
    public record Document(Function<Identities, StatementMeasures> measures, Function<String, Optional<PublicId>> statements,
                           OptionalLong module) {

        /** No document: a block on its own, whose parameters read no statements. */
        public static final Document NONE = new Document(ids -> StatementMeasures.none(), name -> Optional.empty(), OptionalLong.empty());
    }

    /** The public id of the tree semantic about a root concept: one tree per decision, as its name is one concept. */
    public static PublicId treeId(PublicId root) {
        return LocalConcepts.derived(root, "decision-tree");
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
        return write(info, body, view, edit, LocalConcepts.Context.ALONE);
    }

    /**
     * Writes the tree a block states.
     *
     * @param info    the block's info string
     * @param body    the block's text
     * @param view    the view the block's identifiers are read under
     * @param edit    whose change it is, in which module and on which path
     * @param context how the block stands to its document
     * @return what was written, or why nothing was; a tree whose decision already has a different
     *         tree that this block did not state is held for the person
     */
    public static Written write(String info, String body, ViewCalculator view, EditCoordinate edit, LocalConcepts.Context context) {
        return write(info, body, view, edit, context, Document.NONE);
    }

    /**
     * Writes the tree or model a block states, against its document.
     *
     * @param info     the block's info string
     * @param body     the block's text
     * @param view     the view the block's identifiers are read under
     * @param edit     whose change it is and on which path; the module is the document's
     * @param context  how the block stands to its document
     * @param document the document's statements and module
     * @return what was written, or why nothing was; a model whose parameters read a statement the
     *         document has not stated is held until it is
     */
    public static Written write(String info, String body, ViewCalculator view, EditCoordinate edit, LocalConcepts.Context context,
                                Document document) {
        String what = GraphletModelText.isTagged(info) ? "model" : "tree";
        String notWritten = "The " + what + " was not written: ";
        DecisionTerms terms = DecisionTerms.fromBindings();
        BlockIdentities read = new BlockIdentities(new ViewIdentities(view));
        DecisionTreeText.Parsed first = DecisionTreeText.parse(body, terms, read, ExternalQuantities.none(), document.measures().apply(read));
        if (first.tree().isEmpty()) {
            return Written.refused("The block states no tree, so none was written.");
        }
        List<String> missingStatements = first.missingStatements();
        if (!missingStatements.isEmpty()) {
            return Written.refused(notWritten + "it reads " + (missingStatements.size() == 1 ? "a statement" : "statements")
                    + " the document does not state, " + String.join(", ", missingStatements.stream().map(name -> DecisionTreeText.ANF_REFERENCE + name).toList())
                    + "; it is written once the document states " + (missingStatements.size() == 1 ? "it" : "them") + ".");
        }
        List<String> problems = problems(first);
        if (!problems.isEmpty()) {
            return Written.refused(notWritten + problems.getFirst()
                    + (problems.size() > 1 ? ", and " + (problems.size() - 1) + " more." : "."));
        }
        ConceptFacade author = ConceptFacade.make(edit.getAuthorNidForChanges());
        ConceptFacade path = ConceptFacade.make(edit.getDefaultPathNid());
        ConceptFacade module = ConceptFacade.make(document.module().orElse(edit.getDefaultModuleNid()));
        // every name the store lacks becomes a concept, under the identity its name derives, once;
        // a statement's name resolves to the statement its anf block writes, and is never a concept
        Map<String, PublicId> concepts = new LinkedHashMap<>();
        Map<PublicId, String> missing = new LinkedHashMap<>();
        Map<PublicId, List<String>> others = new LinkedHashMap<>();
        Set<Long> statements = new java.util.HashSet<>();
        for (String identifier : read.localIdentifiers()) {
            if (identifier.startsWith(DecisionTreeText.ANF_REFERENCE)) {
                String name = identifier.substring(DecisionTreeText.ANF_REFERENCE.length());
                Optional<PublicId> statement = document.statements().apply(name);
                if (statement.isEmpty()) {
                    return Written.refused(notWritten + "the document has no anf block named " + identifier + ".");
                }
                concepts.put(identifier, statement.get());
                statements.add(PrimitiveData.nid(statement.get()));
                continue;
            }
            String fqn = context.names().fqn(identifier, read.label(read.nid(identifier).orElseThrow()));
            PublicId concept = LocalConcepts.identity(fqn, view);
            concepts.put(identifier, concept);
            if (!SemanticVersions.exists(concept, view)) {
                missing.put(concept, fqn);
                others.computeIfAbsent(concept, k -> new ArrayList<>()).addAll(context.names().others(identifier, fqn));
            }
        }
        BlockConcepts writer = new BlockConcepts("Decision tree block: " + first.tree().get().root().map(DecisionElement::concept).orElse(OptionalLong.empty()), view, author,
                module, path);
        try {
            missing.forEach((concept, fqn) -> writer.concept(concept, fqn, others.get(concept)));
            DecisionTree tree = storedTree(body, concepts, statements, document, view, terms).orElseThrow();
            OptionalLong root = tree.root().map(DecisionElement::concept).orElse(OptionalLong.empty());
            if (root.isEmpty()) {
                writer.rollback();
                return Written.refused(notWritten + "its root names no concept.");
            }
            String name = new ViewIdentities(view).label(root.getAsLong());
            PublicId semantic = treeId(PrimitiveData.publicId(root.getAsLong()));
            String wrote = missing.isEmpty() ? "" : ", with " + missing.size()
                    + (missing.size() == 1 ? " concept" : " concepts") + " the knowledge base lacked";
            if (!SemanticVersions.exists(semantic, view)) {
                writer.semantic(semantic, IkePattern.DECISION_TREE_PATTERN, EntityFacade.make(root.getAsLong()), tree.source());
                return new Written(Optional.of(semantic), "Wrote the " + what + " of " + name + " as its first version"
                        + wrote + "; uncommitted, commit to keep it.");
            }
            long nid = PrimitiveData.nid(semantic);
            Object head = view.latest(nid).isPresent() ? ((SemanticEntityVersion) view.latest(nid).get()).fieldValues().get(0) : null;
            if (tree.source().equals(head)) {
                return new Written(Optional.of(semantic), "The " + what + " of " + name + " is already in the knowledge base as this block states it; nothing was written.");
            }
            boolean revises = context.previous().flatMap(previous -> {
                try {
                    return storedTree(previous, concepts, statements, document, view, terms);
                } catch (RuntimeException differentSymbols) {
                    return Optional.empty();
                }
            }).map(before -> before.source().equals(head)).orElse(false);
            if (!revises) {
                writer.rollback();
                return Written.refused(held(what, name));
            }
            Versioned versioned = SemanticVersions.version(nid, IkePattern.DECISION_TREE_PATTERN.nid(),
                    Lists.immutable.of(tree.source()), view, edit, what, context.shared());
            if (versioned.refusal().isPresent()) {
                writer.rollback();
                return Written.refused(versioned.refusal().get());
            }
            return new Written(Optional.of(semantic), (versioned.replacedPending()
                    ? "Rewrote the " + what + " of " + name + " in its uncommitted version"
                    : "Wrote a new version of the " + what + " of " + name) + wrote + "; uncommitted, commit to keep it.");
        } catch (RuntimeException e) {
            writer.rollback();
            return Written.refused(notWritten + e.getMessage());
        }
    }

    /** The question a held tree asks: the same name with different field values. */
    static String held(String what, String name) {
        return "The " + what + " was not written: \u201c" + name + "\u201d already has a different tree in the knowledge base, and this block "
                + "did not state it. Ask the person whether they are the same decision. If they are, keep one tree; if not, give this "
                + "decision a more specific name, since its label is its fully qualified name.";
    }

    /** A block's tree over the concepts its symbols name and the statements its parameters read, in its stored form. */
    private static Optional<DecisionTree> storedTree(String body, Map<String, PublicId> concepts, Set<Long> statements,
                                                     Document document, ViewCalculator view, DecisionTerms terms) {
        WrittenIdentities ids = new WrittenIdentities(new ViewIdentities(view), concepts);
        Optional<DecisionTree> tree = DecisionTreeText.parse(body, terms, ids, ExternalQuantities.none(), document.measures().apply(ids)).tree();
        tree.ifPresent(parsed -> stored(parsed.source(), terms, statements));
        return tree;
    }

    /**
     * The tree in its stored form, in place: a property that names a component holds the
     * component, and one that names several a list of them, never a bare nid, which a store
     * numbers for itself; a number is exact, never the float a double is stored as. The tree
     * reads back the same through its adaptor.
     */
    static DiTreeEntity stored(DiTreeEntity tree, DecisionTerms terms) {
        return stored(tree, terms, Set.of());
    }

    /**
     * The tree in its stored form, in place, with a parameter that reads a statement holding the
     * statement under the Statement key and no concept reference: the block reads such a
     * parameter under an identity local to it, which the write resolves to the statement.
     *
     * @param tree       the tree as the block was read, with its symbols resolved
     * @param terms      the profile's terms
     * @param statements the nids of the statements its parameters read
     */
    static DiTreeEntity stored(DiTreeEntity tree, DecisionTerms terms, Set<Long> statements) {
        Set<Long> components = Set.of(terms.conceptReference(), terms.unitOfMeasure(), terms.statement(),
                terms.derivedFrom(), terms.operands(), terms.measureBasis(), terms.basisSource(), terms.treeReference(),
                terms.inputKind());
        for (EntityVertex vertex : tree.vertexMap()) {
            MutableLongObjectMap<Object> properties = LongObjectMaps.mutable.empty();
            vertex.properties().forEachKeyValue((key, value) -> {
                if (key == terms.conceptReference() && vertex.getMeaningNid() == terms.parameterMeasure()
                        && value instanceof Long nid && statements.contains(nid)) {
                    properties.put(terms.statement(), storedComponent(value));
                    return;
                }
                properties.put(key, components.contains(key) ? storedComponent(value) : storedValue(value));
            });
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

    /**
     * The block's own id, {@code id=…} in its info string, when it names one.
     *
     * @param info the block's info string, {@code decision-tree id=rda-site-inspection}
     * @return the id
     */
    public static Optional<String> blockId(String info) {
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
     * The identities a block is read under once its symbols are concepts: what the store holds,
     * and each of the block's symbols as the concept written under the identity it derives.
     */
    private record WrittenIdentities(Identities store, Map<String, PublicId> concepts) implements Identities {
        @Override
        public OptionalLong nid(String identifier) {
            OptionalLong stored = store.nid(identifier);
            if (stored.isPresent() || !concepts.containsKey(identifier)) {
                return stored;
            }
            return OptionalLong.of(PrimitiveData.nid(concepts.get(identifier)));
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
