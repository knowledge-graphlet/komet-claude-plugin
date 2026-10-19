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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where an assembly's includes point, from the committed text alone: through the document's
 * own attribute entries, through the topic-library convention when the entry is settled only
 * by the build, or relative to the including file — and by name when none of those holds
 * (IKE-Network/ike-issues#1307).
 */
class IncludeResolverTest {

    private static final Path ROOT = Path.of("/repo");

    /** The ANF aggregators: {@code :topics:} names a build-time copy, so the committed source root stands in. */
    @Test
    void aBuildSettledAttributeFallsBackToTheTopicLibraryConvention() {
        List<String> compendium = List.of(
                "= Compendium",
                ":doctype: book",
                "",
                "// where the build unpacks the topic library",
                ":topics: {generated}/topics-asciidoc",
                "",
                "== Introduction",
                "",
                "include::{topics}/topics/anf/why-standardize.adoc[leveloffset=+2]",
                "",
                "include::{topics}/topics/anf/missing.adoc[leveloffset=+2]");
        Set<Path> committed = Set.of(Path.of("topics/src/docs/asciidoc/topics/anf/why-standardize.adoc"));

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("compendium/src/docs/asciidoc/compendium.adoc"), compendium, committed::contains);

        assertEquals(2, resolved.size());
        assertEquals(Optional.of(Path.of("topics/src/docs/asciidoc/topics/anf/why-standardize.adoc")), resolved.get(0).resolved());
        assertEquals(9, resolved.get(0).line());
        assertTrue(resolved.get(1).resolved().isEmpty());
        assertEquals("{topics}/topics/anf/missing.adoc", resolved.get(1).target());
        assertTrue(resolved.get(1).reason().contains("generated"), "the unset attribute is named: " + resolved.get(1).reason());
        assertTrue(resolved.get(1).reason().contains("topics/src/docs/asciidoc/topics/anf/missing.adoc"), "and the convention that was tried: " + resolved.get(1).reason());
    }

    /** An attribute the text sets itself resolves relative to the including file, the entry's own attributes substituted first. */
    @Test
    void anAttributeTheDocumentSetsResolvesRelativeToTheIncludingFile() {
        List<String> guide = List.of(
                "= Guide",
                ":partsdir: ../parts",
                ":chapters: {partsdir}/chapters",
                "",
                "include::{chapters}/one.adoc[]",
                "include::{partsdir}/two.adoc[lines=1..3]");
        Set<Path> committed = Set.of(Path.of("docs/parts/chapters/one.adoc"), Path.of("docs/parts/two.adoc"));

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("docs/book/guide.adoc"), guide, committed::contains);

        assertEquals(Optional.of(Path.of("docs/parts/chapters/one.adoc")), resolved.get(0).resolved());
        assertEquals(Optional.of(Path.of("docs/parts/two.adoc")), resolved.get(1).resolved());
    }

    /** A plain path is relative to the including file; one that climbs out of the repository is refused by name. */
    @Test
    void aPlainPathIsRelativeAndOneOutsideTheRepositoryIsRefused() {
        List<String> book = List.of(
                "= Book",
                "",
                "include::chapters/one.adoc[]",
                "include::../../elsewhere/two.adoc[]",
                "include::https://example.test/three.adoc[]",
                "include::chapters/uncommitted.adoc[]");
        Set<Path> committed = Set.of(Path.of("docs/chapters/one.adoc"));

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("docs/book.adoc"), book, committed::contains);

        assertEquals(Optional.of(Path.of("docs/chapters/one.adoc")), resolved.get(0).resolved());
        assertTrue(resolved.get(1).resolved().isEmpty());
        assertTrue(resolved.get(1).reason().startsWith("outside this repository"), resolved.get(1).reason());
        assertTrue(resolved.get(2).resolved().isEmpty());
        assertTrue(resolved.get(2).reason().contains("URI"), resolved.get(2).reason());
        assertTrue(resolved.get(3).resolved().isEmpty());
        assertTrue(resolved.get(3).reason().startsWith("not committed in this repository"), resolved.get(3).reason());
    }

    /** Attribute entries count wherever they stand, body included, and the last entry for a name wins. */
    @Test
    void attributeEntriesComeFromHeaderAndBodyAlike() {
        Map<String, String> entries = IncludeResolver.attributeEntries(List.of(
                "= Title", ":a: one", "", "text", ":b: two", ":a: three", "", ":flag:"));
        assertEquals(Map.of("a", "three", "b", "two", "flag", ""), entries);
        assertEquals("three/two/{c}", IncludeResolver.substitute("{a}/{b}/{c}", entries), "an unset attribute stays as written");
    }
}
