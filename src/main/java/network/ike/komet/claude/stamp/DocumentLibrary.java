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

import design.knowledge.stamp.adoc.git.GitRepo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The standard place for stamped documents: {@code ~/komet/docs}, one git repository whose folders
 * the person arranges. It is outside the synced development tree on purpose: {@code .git} never
 * syncs, so a repository made there would reach other machines as bare files. A document moves
 * between machines by push and pull. What the navigator shows of a folder or a file is read here,
 * from the disk and from git, with nothing from the knowledge base.
 */
public final class DocumentLibrary {

    /** A system property naming another location, as a test or a second library needs. */
    static final String LOCATION_PROPERTY = "ike.document.library";

    private DocumentLibrary() { }

    /** @return the library's folder, which need not exist yet */
    public static Path root() {
        String named = System.getProperty(LOCATION_PROPERTY);
        return (named == null || named.isBlank() ? Path.of(System.getProperty("user.home"), "komet", "docs") : Path.of(named))
                .toAbsolutePath().normalize();
    }

    /** @return the library's folder as a person writes it, with {@code ~} for their home */
    public static String shownRoot() {
        Path root = root();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return root.startsWith(home) ? "~/" + home.relativize(root) : root.toString();
    }

    /**
     * Makes the library when it does not exist yet: the folder and its repository, with nothing
     * committed.
     *
     * @return the library's folder
     * @throws IOException if the folder cannot be made, git cannot be run, or git refuses
     */
    public static Path ensure() throws IOException {
        Path root = root();
        if (Files.exists(root.resolve(".git"))) {
            return root;
        }
        GitOperations.Run made = GitOperations.init(root);
        if (!made.ok()) {
            throw new IOException(made.report("init"));
        }
        return root;
    }

    /** @return whether a path is an AsciiDoc file */
    public static boolean isDocument(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return (name.endsWith(".adoc") || name.endsWith(".asciidoc")) && !name.startsWith(".") && Files.isRegularFile(path);
    }

    /**
     * What the navigator lists in a folder: its folders, then its documents, each by name. Hidden
     * entries are left out, {@code .git} among them.
     *
     * @param folder the folder
     * @return the entries
     * @throws IOException if the folder cannot be read
     */
    public static List<Path> children(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> !entry.getFileName().toString().startsWith("."))
                    .filter(entry -> Files.isDirectory(entry) || isDocument(entry))
                    .sorted(Comparator.comparing((Path entry) -> !Files.isDirectory(entry))
                            .thenComparing(entry -> entry.getFileName().toString().toLowerCase()))
                    .toList();
        }
    }

    /**
     * What the navigator says of a file or a folder.
     *
     * @param path       the file or folder
     * @param folder     whether it is a folder
     * @param title      the document's title, or empty when it has none or is a folder
     * @param repository the repository holding it, or empty when none does
     * @param relative   its path in the repository, or empty when none holds it
     * @param branch     the repository's branch, or empty when none holds it
     * @param status     the file's status: {@code clean}, {@code modified}, {@code staged}, {@code untracked}, or empty
     * @param last       the last commit of the file, or of the repository for a folder, when there is one
     * @param commits    how many commits touched the file
     * @param documents  how many documents a folder holds, at any depth
     */
    public record Details(Path path, boolean folder, String title, Optional<Path> repository, String relative, String branch,
                          String status, Optional<GitOperations.Commit> last, int commits, int documents) {

        /** @return the path git knows it by, relative to the repository */
        Optional<Path> inRepository() {
            return repository.isEmpty() || relative.isEmpty() ? Optional.empty() : Optional.of(Path.of(relative));
        }
    }

    /**
     * What the knowledge base holds of a document.
     *
     * @param lines    its lines
     * @param affirmed how many of them someone affirmed
     */
    public record Stored(int lines, int affirmed) { }

    /**
     * Reads what the navigator says of a path; off the FX thread.
     *
     * @param path the file or folder
     * @return the details
     * @throws IOException if git cannot be run or a file cannot be read
     */
    public static Details details(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        boolean folder = Files.isDirectory(absolute);
        Optional<Path> repository = GitRepo.findRoot(absolute).map(root -> root.toAbsolutePath().normalize());
        String title = folder ? "" : title(absolute);
        int documents = folder ? documentsUnder(absolute) : 0;
        if (repository.isEmpty()) {
            return new Details(absolute, folder, title, repository, "", "", "", Optional.empty(), 0, documents);
        }
        Path root = repository.get();
        Path relative = root.relativize(absolute);
        Path file = folder ? null : relative;
        GitOperations.RepositoryState state = GitOperations.state(root, file);
        List<GitOperations.Commit> history = GitOperations.log(root, file, 1000);
        return new Details(absolute, folder, title, repository, relative.toString().replace('\\', '/'), state.branch(), state.status(),
                history.stream().findFirst(), history.size(), documents);
    }

    /** The first level-0 heading in a file's opening lines, where AsciiDoc puts a document's title. */
    private static String title(Path file) throws IOException {
        try (var lines = Files.lines(file)) {
            return lines.limit(50).filter(line -> line.startsWith("= ")).findFirst().map(line -> line.substring(2).strip()).orElse("");
        }
    }

    /** Counts the documents under a folder, leaving hidden folders out. */
    private static int documentsUnder(Path folder) throws IOException {
        int[] count = {0};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                return !dir.equals(folder) && dir.getFileName().toString().startsWith(".") ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (isDocument(file)) {
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    /**
     * The navigator's detail, as Markdown: one read-only text the person can select and copy.
     *
     * @param details what the disk and git say
     * @param stored  what the knowledge base holds of a document, or empty when it holds nothing
     * @return the text
     */
    public static String markdown(Details details, Optional<Stored> stored) {
        List<String> facts = new ArrayList<>();
        String name = details.path().getFileName() == null ? details.path().toString() : details.path().getFileName().toString();
        StringBuilder text = new StringBuilder("### ").append(details.title().isEmpty() ? name : details.title()).append("\n\n");
        text.append('`').append(details.path()).append("`\n\n");
        if (details.folder()) {
            facts.add("**Documents** " + details.documents());
        }
        if (details.repository().isEmpty()) {
            facts.add("**Repository** none: opening a document here offers it one");
        } else {
            Path root = details.repository().get();
            facts.add("**Repository** " + root.getFileName() + (details.branch().isEmpty() ? "" : ", on " + details.branch()));
        }
        if (!details.folder() && details.repository().isPresent()) {
            facts.add("**Status** " + switch (details.status()) {
                case "clean" -> "committed, no changes";
                case "modified" -> "changed since the last commit";
                case "staged" -> "changes staged, not committed";
                case "untracked" -> "not committed yet: opening it offers a first commit";
                default -> details.status();
            });
            facts.add("**Commits** " + details.commits());
        }
        details.last().ifPresent(commit -> facts.add("**Last commit** " + commit.shortSha() + ", " + commit.date() + ", "
                + commit.author() + ": " + commit.summary()));
        if (!details.folder()) {
            facts.add("**Knowledge base** " + stored.map(held -> held.lines() + " lines, " + held.affirmed() + " affirmed")
                    .orElse("not read in yet: opening it reads its history"));
        }
        facts.forEach(fact -> text.append("- ").append(fact).append('\n'));
        return text.toString();
    }
}
