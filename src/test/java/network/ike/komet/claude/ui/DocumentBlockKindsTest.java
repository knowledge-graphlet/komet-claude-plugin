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
package network.ike.komet.claude.ui;

import network.ike.komet.claude.decision.DecisionTreeText;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision blocks as structured kinds of a stored document: an attribute line read as an info string. */
class DocumentBlockKindsTest {

    @Test
    void anAttributeLineReadsAsTheInfoStringTheRenderersRead() {
        String info = DocumentBlockKinds.info("[decision-tree, cohort=100, title=\"Inspect the site, or not?\"]");
        assertEquals("decision-tree cohort=100 title=\"Inspect the site, or not?\"", info, "a quoted comma stays in its value");
        assertTrue(DecisionTreeText.isTagged(info));
        assertEquals(Optional.of(100.0), DecisionTreeText.cohort(info));
        assertEquals("anf type=request id=req-inspection", DocumentBlockKinds.info("[anf,type=request,id=req-inspection]"));
        assertEquals("complex-concept", DocumentBlockKinds.info("[complex-concept]"));
        assertEquals("", DocumentBlockKinds.info(null));
    }

    @Test
    void anInfoStringReadsBackAsTheAttributeLineItCameFrom() {
        String line = "[decision-tree, health-price=50000, cohort=100, title=\"Inspect the site, or not?\"]";
        assertEquals(line, DocumentBlockKinds.attributeLine(DocumentBlockKinds.info(line)));
        assertEquals("[anf, type=request, id=req-inspection]",
                DocumentBlockKinds.attributeLine(DocumentBlockKinds.info("[anf,type=request,id=req-inspection]")),
                "spacing is the one thing not kept");
    }

    @Test
    void eachKindIsNamedByItsRenderersTag() {
        assertEquals(List.of("decision-tree", "complex-concept", "anf", "calculator", "calculate", "koncept-tree", "koncept-definition"),
                List.of(new DocumentBlockKinds.DecisionTree().style(), new DocumentBlockKinds.ComplexConcept().style(),
                        new DocumentBlockKinds.Anf().style(), new DocumentBlockKinds.Calculator().style(),
                        new DocumentBlockKinds.Calculation().style(), new DocumentBlockKinds.KonceptTree().style(),
                        new DocumentBlockKinds.KonceptDefinition().style()));
        assertEquals("Koncept tree", new DocumentBlockKinds.KonceptTree().menuName());
        assertEquals("ANF statement", new DocumentBlockKinds.Anf().menuName(), "a name already capitalized stays as it is");
    }

    /** Every kind the module provides offers Insert a skeleton, and the skeleton is one block of the kind's style (dev-document-edit-menu). */
    @Test
    void everyProvidedKindOffersASkeletonThatParsesAsOneBlockOfItsStyle() {
        List<network.ike.komet.claude.stamp.StructuredBlockKind> kinds = network.ike.komet.claude.stamp.StructuredBlocks.kinds();
        assertTrue(kinds.size() >= 15, "the provided kinds: " + kinds.stream().map(network.ike.komet.claude.stamp.StructuredBlockKind::style).toList());
        network.ike.komet.claude.stamp.StructuredBlockKind.Context context = new network.ike.komet.claude.stamp.StructuredBlockKind.Context(null);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (network.ike.komet.claude.stamp.StructuredBlockKind kind : kinds) {
            network.ike.komet.claude.stamp.DocumentInserts.Insertion insertion = network.ike.komet.claude.stamp.DocumentInserts.of(kind, context)
                    .orElseThrow(() -> new AssertionError(kind.style() + " offers Insert no skeleton"));
            assertTrue(ids.add(insertion.id()), "ids are distinct: " + insertion.id());
            List<String> lines = new java.util.ArrayList<>(List.of("= Title", "", "Lead.", ""));
            lines.addAll(insertion.text().lines().toList());
            design.knowledge.stamp.adoc.dialect.Document parsed = design.knowledge.stamp.adoc.dialect.IkeDialectParser.parse(lines);
            List<design.knowledge.stamp.adoc.dialect.Block> added = parsed.blocks().stream().filter(b -> b.firstLine() == 5).toList();
            assertEquals(1, added.size(), kind.style() + ": the skeleton forms one block starting at its attribute line");
            assertEquals(insertion.kind(), added.getFirst().kind(), kind.style() + ": the entry names the kind the text forms");
            assertTrue(added.getFirst().parameters().startsWith("[" + kind.style()), kind.style() + ": " + added.getFirst().parameters());
            assertEquals(lines.size(), added.getFirst().lastLine(), kind.style() + ": the block runs to the skeleton's last line");
        }
    }

    /** A rendering names the model the document has, and draws at once; with no model it is a tree of its own, opened for editing. */
    @Test
    void aTreeOrTornadoNamesTheDocumentsModelWhenItHasOne() {
        network.ike.komet.claude.stamp.StructuredBlockKind.Context none = new network.ike.komet.claude.stamp.StructuredBlockKind.Context(null);
        network.ike.komet.claude.stamp.StructuredBlockKind.Skeleton tree = new DocumentBlockKinds.DecisionTree().skeleton(none).orElseThrow();
        assertTrue(tree.opensForEditing());
        assertTrue(tree.text().contains("decision: k:Choice"));
        network.ike.komet.claude.stamp.StructuredBlockKind.Skeleton tornado = new DocumentBlockKinds.Tornado().skeleton(none).orElseThrow();
        assertTrue(tornado.opensForEditing(), "with no model to name, the person has to name one");
        assertTrue(tornado.text().startsWith("[tornado, model=model-id]"));
    }
}
