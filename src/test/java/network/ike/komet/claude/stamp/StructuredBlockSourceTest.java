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
    void aListingClosesOnlyWithTheFenceThatOpenedIt() {
        StructuredBlockKind.Source source = new StructuredBlockKind.Source("[decision-tree]", List.of(
                "[decision-tree]", "----", "decision: k:A[A] @id=a", "--", "  alternative: k:B[B] @id=b", "----"));
        assertEquals("decision: k:A[A] @id=a\n--\n  alternative: k:B[B] @id=b", source.content(), "a -- line in a listing is content");
    }
}
