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
package network.ike.komet.claude.doc;

import network.ike.komet.claude.doc.DocumentSurface.TextSelection;
import network.ike.komet.claude.doc.DocumentSurface.Viewpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the {@link Viewpoint} preferences encoding (ike-issues#943): the same value that carries a
 * reader across a re-render is what persists on leaving and restores on reopen, so the round trip
 * must be lossless — and a malformed stored value must degrade to "no viewpoint", never throw.
 */
class DocumentSurfaceViewpointTest {

    @Test
    void scrollOnlyViewpointRoundTrips() {
        Viewpoint viewpoint = new Viewpoint(12, 0.375, null);
        assertEquals(viewpoint, Viewpoint.decode(viewpoint.encode()));
    }

    @Test
    void viewpointWithSelectionRoundTrips() {
        Viewpoint viewpoint = new Viewpoint(3, 0.0, new TextSelection(5, 0, 10, 2, 24));
        assertEquals(viewpoint, Viewpoint.decode(viewpoint.encode()));
    }

    @Test
    void blankAndNullDecodeToNoViewpoint() {
        assertNull(Viewpoint.decode(null));
        assertNull(Viewpoint.decode(""));
        assertNull(Viewpoint.decode("   "));
    }

    @Test
    void malformedValueDecodesToNoViewpointRatherThanThrowing() {
        assertNull(Viewpoint.decode("not-a-viewpoint"));
        assertNull(Viewpoint.decode("3"));
        assertNull(Viewpoint.decode("3:0.5:junk:junk:junk:junk:junk"));
    }
}
