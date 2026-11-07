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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The working trees beside one, as the origin manifest lists them: found by walking up from a
 * tree, the tree itself left out, entries that are not repositories skipped
 * (IKE-Network/ike-issues#1345).
 */
class WorkingTreesTest {

    @TempDir
    Path folder;

    @Test
    void theManifestAboveATreeNamesItsNeighbours() throws Exception {
        Files.createDirectories(folder.resolve("scripts"));
        Files.writeString(folder.resolve(WorkingTrees.ORIGINS), """
                # origins.conf — one line per repository
                ike-lab-documents https://github.com/kec/ike-lab-documents.git
                ike-lab-proposals https://github.com/ike-lab-unlv/ike-lab-proposals.git

                ike-komet-wsr https://github.com/IKE-Network/ike-komet-wsr.git
                ike-komet-wsr/komet-claude-plugin https://github.com/knowledge-graphlet/komet-claude-plugin.git
                not-checked-out https://github.com/example/none.git
                """);
        for (String tree : List.of("ike-lab-documents", "ike-lab-proposals", "ike-komet-wsr", "ike-komet-wsr/komet-claude-plugin")) {
            Files.createDirectories(folder.resolve(tree).resolve(".git"));
        }
        Files.createDirectories(folder.resolve("ike-lab-documents/topics/src/docs/asciidoc/network.ike.lab/dev"));
        Files.writeString(folder.resolve("ike-lab-documents/topics/src/docs/asciidoc/network.ike.lab/dev/overview.adoc"), "= Overview\n");

        WorkingTrees beside = WorkingTrees.beside(folder.resolve("ike-lab-proposals"));

        assertEquals(folder.toAbsolutePath().normalize(), beside.base());
        assertEquals(List.of(folder.resolve("ike-lab-documents"), folder.resolve("ike-komet-wsr"), folder.resolve("ike-komet-wsr/komet-claude-plugin")),
                beside.trees(), "the manifest's order, the tree itself and the entry with no checkout left out");
        assertEquals(List.of(folder.resolve("ike-lab-documents")), beside.holding("topics/src/docs/asciidoc/network.ike.lab/dev/overview.adoc"));
        assertEquals(List.of(), beside.holding("topics/src/docs/asciidoc/network.ike.lab/dev/absent.adoc"));

        WorkingTrees nested = WorkingTrees.beside(folder.resolve("ike-komet-wsr/komet-claude-plugin"));
        assertTrue(nested.trees().contains(folder.resolve("ike-lab-documents")), "a workspace member finds the manifest two levels up");
        assertTrue(!nested.trees().contains(folder.resolve("ike-komet-wsr/komet-claude-plugin")), "and is not its own neighbour");
    }

    @Test
    void aTreeWithNoManifestAboveItHasNoNeighbours() throws Exception {
        Path alone = Files.createDirectories(folder.resolve("elsewhere/repo"));

        WorkingTrees none = WorkingTrees.beside(alone);

        assertNull(none.base());
        assertEquals(List.of(), none.trees());
        assertEquals(List.of("a", "b/c"), WorkingTrees.entries(List.of("# comment", "", "a https://x", "  b/c   https://y  ", "   ")));
    }
}
