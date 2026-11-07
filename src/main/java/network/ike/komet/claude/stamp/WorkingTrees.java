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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The other working trees beside one, as the fleet's origin manifest lists them
 * (`scripts/origins.conf`, IKE-Network/ike-issues#1057): one line per repository under the
 * synced folder, its path relative to that folder and its clone URL. An assembly in one
 * repository may include topics a library in another holds — the UNLV proposal includes
 * ike-lab-documents topics — so a child that is not committed in the assembly's own tree is
 * looked for in these, in the manifest's order (topic {@code dev-adoc-packages},
 * IKE-Network/ike-issues#1345). The manifest is found by walking up from a tree; a tree with
 * none above it has no neighbours. Only data is read: a path that is not a directory holding a
 * {@code .git} entry is skipped.
 */
public final class WorkingTrees {

    /** The manifest's path under the synced folder. */
    public static final String ORIGINS = "scripts/origins.conf";
    /** How far up from a tree the manifest is looked for: a workspace member is three levels under the folder. */
    private static final int LEVELS = 6;

    private final Path base;
    private final List<Path> trees;

    private WorkingTrees(Path base, List<Path> trees) {
        this.base = base;
        this.trees = List.copyOf(trees);
    }

    /** No neighbours. */
    public static WorkingTrees none() {
        return new WorkingTrees(null, List.of());
    }

    /**
     * The trees beside one: those the nearest manifest above it lists, the tree itself left out.
     *
     * @param root a working tree
     * @return its neighbours, none when no manifest is found
     */
    public static WorkingTrees beside(Path root) {
        Path here = root.toAbsolutePath().normalize();
        Path dir = here;
        for (int level = 0; level < LEVELS && dir != null; level++) {
            Path manifest = dir.resolve(ORIGINS);
            if (Files.isRegularFile(manifest)) {
                return from(manifest, here);
            }
            dir = dir.getParent();
        }
        return none();
    }

    /**
     * The trees a manifest lists, except one.
     *
     * @param manifest the manifest file; its grandparent is the folder the entries are relative to
     * @param except   the tree to leave out, or {@code null}
     * @return the listed trees that exist as repositories
     */
    public static WorkingTrees from(Path manifest, Path except) {
        Path base = manifest.toAbsolutePath().normalize().getParent().getParent();
        List<String> lines;
        try {
            lines = Files.readAllLines(manifest);
        } catch (IOException e) {
            return none();
        }
        Path skip = except == null ? null : except.toAbsolutePath().normalize();
        List<Path> trees = new ArrayList<>();
        for (String entry : entries(lines)) {
            Path tree = base.resolve(entry).normalize();
            if (tree.equals(skip) || !Files.isDirectory(tree) || !Files.exists(tree.resolve(".git"))) {
                continue;
            }
            trees.add(tree);
        }
        return new WorkingTrees(base, trees);
    }

    /**
     * The repository paths a manifest's lines name, relative to the synced folder: the first
     * word of every line that is not blank or a comment.
     *
     * @param lines the manifest's lines
     * @return the entries in order
     */
    static List<String> entries(List<String> lines) {
        List<String> entries = new ArrayList<>();
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] words = text.split("\\s+");
            if (!words[0].isEmpty()) {
                entries.add(words[0]);
            }
        }
        return entries;
    }

    /** @return the synced folder the entries are relative to, or {@code null} when none was found */
    public Path base() {
        return base;
    }

    /** @return the neighbouring trees, in the manifest's order */
    public List<Path> trees() {
        return trees;
    }

    /**
     * The neighbours whose working tree holds a file.
     *
     * @param path the file, relative to a tree
     * @return the trees holding it, in the manifest's order
     */
    public List<Path> holding(String path) {
        return trees.stream().filter(tree -> Files.isRegularFile(tree.resolve(path))).toList();
    }
}
