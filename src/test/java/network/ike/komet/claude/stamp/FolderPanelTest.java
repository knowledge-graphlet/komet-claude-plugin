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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The panel's reading of the disk, on real folders; what it draws is checked in a running Komet. */
class FolderPanelTest {

    @TempDir
    Path root;

    @Test
    void theLibraryIsAPlaceBeforeItExists() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Files.createDirectories(home.resolve("Documents"));
        Path library = home.resolve("komet/docs");

        List<FolderPanel.Place> places = FolderPanel.places(home, library, null, root.resolve("no-volumes"));

        assertTrue(places.stream().anyMatch(place -> library.equals(place.path())));
        assertTrue(places.stream().anyMatch(place -> home.resolve("Documents").equals(place.path())));
        assertFalse(places.stream().anyMatch(place -> home.resolve("Desktop").equals(place.path())), "a folder that is not there is not listed");
        assertFalse(places.stream().anyMatch(place -> "Locations".equals(place.name())), "no volumes, no heading");
    }

    @Test
    void theKnownWorkingTreeAndTheVolumesAreListed() throws Exception {
        Path home = Files.createDirectories(root.resolve("home"));
        Path known = Files.createDirectories(root.resolve("repository"));
        Path volumes = Files.createDirectories(root.resolve("Volumes"));
        Files.createDirectories(volumes.resolve("Backup"));
        Files.createDirectories(volumes.resolve(".hidden"));

        List<FolderPanel.Place> places = FolderPanel.places(home, home.resolve("docs"), known, volumes);

        assertTrue(places.stream().anyMatch(place -> known.equals(place.path())));
        assertTrue(places.stream().anyMatch(place -> "Backup".equals(place.name())));
        assertFalse(places.stream().anyMatch(place -> ".hidden".equals(place.name())));
    }

    @Test
    void theColumnsRunFromTheDeepestPlaceDownToTheFolder() {
        Path home = root.resolve("home");
        Path docs = home.resolve("Documents");
        Path deep = docs.resolve("a/b");
        List<FolderPanel.Place> places = List.of(FolderPanel.Place.heading("Favorites"),
                new FolderPanel.Place("home", "i", home), new FolderPanel.Place("Documents", "i", docs));

        assertEquals(docs, FolderPanel.baseOf(deep, places));
        assertEquals(List.of(docs, docs.resolve("a"), deep), FolderPanel.chain(docs, deep));
        assertEquals(deep.getRoot(), FolderPanel.chain(null, deep).getFirst());
        assertEquals(root.getRoot(), FolderPanel.baseOf(root.resolve("elsewhere"), places));
    }

    @Test
    void aFolderThatDoesNotExistShowsTheNearestOneThatDoes() throws Exception {
        Path there = Files.createDirectories(root.resolve("there"));

        assertEquals(there, FolderPanel.nearestFolder(there.resolve("komet/docs")));
        assertEquals(there, FolderPanel.nearestFolder(there));
    }

    @Test
    void aTypedFolderIsAFullPathOrStartsAtHome() {
        assertEquals(Path.of(System.getProperty("user.home"), "komet/docs"), FolderPanel.folderOf(" ~/komet/docs ").orElseThrow());
        assertEquals(Path.of("/tmp"), FolderPanel.folderOf("/tmp").orElseThrow());
        assertTrue(FolderPanel.folderOf("komet/docs").isEmpty());
        assertTrue(FolderPanel.folderOf("  ").isEmpty());
    }

    @Test
    void newFolderMakesOneFolderDirectlyInside() throws Exception {
        Path made = FolderPanel.made(root, "  papers ");

        assertEquals(root.resolve("papers"), made);
        assertTrue(Files.isDirectory(made));
        assertThrows(IOException.class, () -> FolderPanel.made(root, "papers"));
        assertThrows(IllegalArgumentException.class, () -> FolderPanel.made(root, ""));
        assertThrows(IllegalArgumentException.class, () -> FolderPanel.made(root, "../escaped"));
        assertThrows(IllegalArgumentException.class, () -> FolderPanel.made(root, "a/b"));
        assertFalse(Files.exists(root.resolveSibling("escaped")));
    }
}
