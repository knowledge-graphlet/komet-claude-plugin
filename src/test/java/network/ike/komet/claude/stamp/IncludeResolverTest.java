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
 * own attribute entries, under the AsciiDoc roots the caller lists when the entry is settled
 * only by the build, or relative to the including file — and by name when none of those holds
 * (IKE-Network/ike-issues#1307, #1345; topic {@code dev-adoc-packages}).
 */
class IncludeResolverTest {

    private static final Path ROOT = Path.of("/repo");

    /** The ANF aggregators: {@code :topics:} names a build-time copy, so the committed file under a library's root stands in. */
    @Test
    void aBuildSettledAttributeResolvesUnderTheAsciiDocRoots() {
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
        List<String> listing = committed.stream().map(Path::toString).toList();

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("compendium/src/docs/asciidoc/compendium.adoc"),
                compendium, committed::contains, rest -> IncludeResolver.endingWith(listing, rest));

        assertEquals(2, resolved.size());
        assertEquals(Optional.of(Path.of("topics/src/docs/asciidoc/topics/anf/why-standardize.adoc")), resolved.get(0).resolved());
        assertEquals(9, resolved.get(0).line());
        assertTrue(resolved.get(1).resolved().isEmpty());
        assertEquals("{topics}/topics/anf/missing.adoc", resolved.get(1).target());
        assertTrue(resolved.get(1).reason().contains("generated"), "the unset attribute is named: " + resolved.get(1).reason());
        assertTrue(resolved.get(1).reason().contains("*/src/docs/asciidoc/topics/anf/missing.adoc"), "and the roots that were tried: " + resolved.get(1).reason());
    }

    /** The package layout: a library of any module name holds the file under its root, and nothing about the name is assumed. */
    @Test
    void aPackagePathResolvesUnderAnyLibrarysRoot() {
        List<String> proposal = List.of(
                "= Proposal",
                "",
                "include::{adoc}/network.ike.lab/lab/infrastructure-imperative.adoc[leveloffset=+1]",
                "include::{adoc}/network.ike.lab/ikm/health-it-challenges.adoc[leveloffset=+1]");
        List<String> listing = List.of(
                "unlv-topics/src/docs/asciidoc/network.ike.lab/lab/infrastructure-imperative.adoc",
                "unlv-topics/src/docs/asciidoc/META-INF/ike/network.ike.lab/unlv-topics/manifest.yaml",
                "ike-lab-unlv-proposal/src/docs/asciidoc/ike-lab-unlv-proposal.adoc");
        Set<Path> committed = listing.stream().map(Path::of).collect(java.util.stream.Collectors.toSet());

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("ike-lab-unlv-proposal/src/docs/asciidoc/ike-lab-unlv-proposal.adoc"),
                proposal, committed::contains, rest -> IncludeResolver.endingWith(listing, rest));

        assertEquals(Optional.of(Path.of("unlv-topics/src/docs/asciidoc/network.ike.lab/lab/infrastructure-imperative.adoc")), resolved.get(0).resolved());
        assertTrue(resolved.get(1).resolved().isEmpty(), "the ikm topic is in another repository: not here");
        assertTrue(resolved.get(1).reason().contains("adoc not set"), resolved.get(1).reason());
        assertTrue(resolved.get(1).reason().contains("*/src/docs/asciidoc/network.ike.lab/ikm/health-it-challenges.adoc"), resolved.get(1).reason());
    }

    /** Two libraries in one tree holding one path is a refusal that names both, never a guess. */
    @Test
    void twoLibrariesHoldingOnePathIsAmbiguous() {
        List<String> book = List.of("= Book", "", "include::{topics}/topics/ext/literature.adoc[]");
        List<String> listing = List.of(
                "topics/src/docs/asciidoc/topics/ext/literature.adoc",
                "unlv-topics/src/docs/asciidoc/topics/ext/literature.adoc");
        Set<Path> committed = listing.stream().map(Path::of).collect(java.util.stream.Collectors.toSet());

        List<IncludeResolver.Resolution> resolved = IncludeResolver.resolve(ROOT, Path.of("book/src/docs/asciidoc/book.adoc"),
                book, committed::contains, rest -> IncludeResolver.endingWith(listing, rest));

        assertTrue(resolved.get(0).resolved().isEmpty());
        assertTrue(resolved.get(0).reason().startsWith("ambiguous under the AsciiDoc roots"), resolved.get(0).reason());
        assertTrue(resolved.get(0).reason().contains("topics/src/docs/asciidoc/topics/ext/literature.adoc")
                && resolved.get(0).reason().contains("unlv-topics/src/docs/asciidoc/topics/ext/literature.adoc"), resolved.get(0).reason());
        assertEquals(List.of(Path.of("topics/src/docs/asciidoc/topics/ext/literature.adoc"), Path.of("unlv-topics/src/docs/asciidoc/topics/ext/literature.adoc")),
                IncludeResolver.underRoots("{topics}/topics/ext/literature.adoc", Map.of(), rest -> IncludeResolver.endingWith(listing, rest)),
                "the rule alone, for a caller looking in another tree");
        assertEquals(List.of(), IncludeResolver.underRoots("chapters/one.adoc", Map.of(), rest -> IncludeResolver.endingWith(listing, rest)),
                "a plain path names nothing under the roots");
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
