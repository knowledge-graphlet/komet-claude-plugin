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

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentFooterTest {

    @Test
    void aCountOfNothingSaysNothing() {
        assertEquals("37 lines \u00b7 3 blocks \u00b7 34 edited, uncommitted", DocumentFooter.line(37, 3, 0, 0, 0, 34, ""));
    }

    @Test
    void whatIsThereIsCountedInTheSingularToo() {
        assertEquals("1 line \u00b7 1 block \u00b7 1 reference \u00b7 2 affirmed \u00b7 1 included",
                DocumentFooter.line(1, 1, 1, 2, 1, 0, ""));
    }

    @Test
    void aDocumentWithNoStructureAndALostIncludeSaySo() {
        assertEquals("4 lines \u00b7 no structure; re-materialize to add it \u00b7 1 include left as lines: a.adoc",
                DocumentFooter.line(4, -1, 0, 0, 0, 0, "1 include left as lines: a.adoc"));
    }
}
