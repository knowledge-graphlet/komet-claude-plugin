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
package network.ike.komet.claude.stamp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A structured block's source: the content between its fences, a listing's or an open block's. */
class StructuredBlockSourceTest {

    @Test
    void anOpenBlockReadsBetweenItsTwoDashFences() {
        StructuredBlockKind.Source source = new StructuredBlockKind.Source("[anf,type=request,id=req-inspection]", List.of(
                "[anf,type=request,id=req-inspection]", "--",
                "topic::   candidate[clinical site inspection] disp=PENDING", "--"));
        assertEquals("topic::   candidate[clinical site inspection] disp=PENDING", source.content());
        assertEquals("[anf,type=request,id=req-inspection]\n--\ntopic:: x\n--", source.withContent("topic:: x"));
    }

    @Test
    void aWriteBackMayRestateTheAttributeLineAndKeepsTheTitle() {
        StructuredBlockKind.Source source = new StructuredBlockKind.Source("[decision-tree, health-price=100000]", List.of(
                ".Should we scan?", "[decision-tree, health-price=100000]", "----", "decision: k:A[A] @id=a", "----"));
        assertEquals(".Should we scan?\n[decision-tree, health-price=50000]\n----\ndecision: k:B[B] @id=b\n----",
                source.withParameters("[decision-tree, health-price=50000]", "decision: k:B[B] @id=b"));
        assertEquals(source.withContent("x"), source.withParameters("[decision-tree, health-price=100000]", "x"),
                "the same attribute line is the content alone restated");
    }

    @Test
    void aSavedTextReadsBackAsItsSource() {
        StructuredBlockKind.Source source = StructuredBlockKind.Source.of(
                ".Should we scan?\n[decision-tree, id=scan]\n----\ndecision: k:A[A] @id=a\n[not, an, attribute, line]\n----");
        assertEquals("[decision-tree, id=scan]", source.parameters(), "the attribute line is the last before the fence");
        assertEquals("decision: k:A[A] @id=a\n[not, an, attribute, line]", source.content());
    }

    @Test
    void aListingClosesOnlyWithTheFenceThatOpenedIt() {
        StructuredBlockKind.Source source = new StructuredBlockKind.Source("[decision-tree]", List.of(
                "[decision-tree]", "----", "decision: k:A[A] @id=a", "--", "  alternative: k:B[B] @id=b", "----"));
        assertEquals("decision: k:A[A] @id=a\n--\n  alternative: k:B[B] @id=b", source.content(), "a -- line in a listing is content");
    }
}
