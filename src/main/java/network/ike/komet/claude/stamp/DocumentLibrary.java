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
import network.ike.komet.claude.stamp.AsciiDocMarkdown;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The standard place for stamped documents: {@code ~/komet/adoc}, one git repository whose folders
 * the person arranges. It is outside the synced development tree on purpose: {@code .git} never
 * syncs, so a repository made there would reach other machines as bare files. A document moves
 * between machines by push and pull. What the navigator shows of a folder or a file is read here,
 * from the disk and from git, with nothing from the knowledge base.
 */
public final class DocumentLibrary {

    /** A system property naming another location, as a test or a second library needs. */
    static final String LOCATION_PROPERTY = "ike.document.library";

    /** How much of a document the navigator previews: enough to know it by, and quick to draw. */
    static final int PREVIEW_LENGTH = 6000;

    /** A block's attribute line that names a kind of block, with no {@code name=value} first. */
    private static final Pattern BLOCK_ATTRIBUTES = Pattern.compile("^\\[([a-z][a-z-]*)(?:,.*)?]$");
    private static final Pattern LISTING_DELIMITER = Pattern.compile("^(-{4,}|\\.{4,})$");
    private static final Pattern BLOCK_DELIMITER = Pattern.compile("^(-{2}|-{4,}|\\.{4,}|={4,}|\\+{4,})$");
    /** The block kinds that are themselves writing, which a preview shows as text. */
    private static final Set<String> WRITTEN_BLOCKS = Set.of("source", "listing", "literal", "quote", "verse", "example", "sidebar",
            "abstract", "partintro", "appendix", "bibliography", "glossary", "index", "preface", "horizontal", "qanda", "stem", "latexmath", "asciimath");
    /** A concept in the text: {@code k:ScanNow[Scan now?]} or {@code candidate[a new term]}, the last with what follows it on the line. */
    private static final Pattern CONCEPT_REFERENCE = Pattern.compile("\\b(?:k:[A-Za-z0-9_-]+|candidate)\\[([^\\]]*)](?:\\s+(?:@id|disp)=\\S+)*");
    /** A citation that says something: {@code <<id,the words>>}. */
    private static final Pattern NAMED_CITATION = Pattern.compile("<<[^>,\\s]+,\\s*([^>]+)>>");
    /** A citation that is only a key, which says nothing in a preview: {@code <<k040627>>}. */
    private static final Pattern BARE_CITATION = Pattern.compile("\\s*<<[^>,\\s]+>>");
    private static final Pattern ATTRIBUTE_ENTRY = Pattern.compile("^:!?[A-Za-z0-9_][A-Za-z0-9_-]*!?:.*$");
    private static final Pattern CROSS_REFERENCE = Pattern.compile("\\[([^\\]]+)]\\(xref:[^)]*\\)");

    private DocumentLibrary() { }

    /** @return the library's folder, which need not exist yet */
    public static Path root() {
        String named = System.getProperty(LOCATION_PROPERTY);
        return (named == null || named.isBlank() ? Path.of(System.getProperty("user.home"), "komet", "adoc") : Path.of(named))
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
     * entries are left out, {@code .git} among them, and so is a folder that holds files but no
     * document, as a folder of figures does. An empty folder stays, since someone has just made it.
     *
     * @param folder the folder
     * @return the entries
     * @throws IOException if the folder cannot be read
     */
    public static List<Path> children(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.filter(entry -> !entry.getFileName().toString().startsWith("."))
                    .filter(entry -> Files.isDirectory(entry) ? !holdsOnlyOtherFiles(entry) : isDocument(entry))
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

    private static boolean holdsOnlyOtherFiles(Path folder) {
        try (var entries = Files.list(folder)) {
            return entries.findAny().isPresent() && documentsUnder(folder) == 0;
        } catch (IOException e) {
            return true;
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
     * The opening of a document as Markdown, for the navigator to show under what it says of the
     * file. It reads the working tree, so it shows the file as it is now, whatever the knowledge
     * base holds. The title is left out, since the detail already heads with it, and so are the
     * includes of an assembly, which name files rather than say anything, with attribute entries,
     * comments and citation keys. A structured block is one line saying what it is, since its
     * source says nothing to a reader and drawing it needs the knowledge base, and so is a table,
     * whose columns would be tall and thin in the pane; a reference to a concept is its name.
     *
     * @param file the document
     * @return the preview
     * @throws IOException if the file cannot be read
     */
    public static String preview(Path file) throws IOException {
        String source = withoutBlockSource(Files.readString(file).lines().toList());
        source = CONCEPT_REFERENCE.matcher(source).replaceAll("$1");
        source = NAMED_CITATION.matcher(source).replaceAll("$1");
        String text = AsciiDocMarkdown.markdown(BARE_CITATION.matcher(source).replaceAll(""));
        text = CROSS_REFERENCE.matcher(text).replaceAll("$1");
        text = text.replace("{{plus}}", "+").replace("{plus}", "+");
        if (text.startsWith("# ")) {
            int end = text.indexOf('\n');
            text = end < 0 ? "" : text.substring(end).strip() + "\n";
        }
        if (text.length() <= PREVIEW_LENGTH) {
            return text;
        }
        int end = text.lastIndexOf("\n\n", PREVIEW_LENGTH);
        String opening = text.substring(0, end > 0 ? end : PREVIEW_LENGTH).strip();
        boolean inCode = opening.split("```", -1).length % 2 == 0;           // cut inside a listing: close it
        return opening + (inCode ? "\n```" : "") + "\n\n_The preview stops here. Open in Journal reads the whole document._\n";
    }

    /** The source lines of a preview: includes gone, and each structured block one line, repeats run together. */
    private static String withoutBlockSource(List<String> lines) {
        List<String> kept = new ArrayList<>();
        String lastBlock = "";
        String listing = null;                                 // inside a listing, block syntax is an example of itself
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (listing != null) {
                kept.add(line);
                listing = line.equals(listing) ? null : listing;
                continue;
            }
            if (LISTING_DELIMITER.matcher(line).matches()) {
                listing = line;
                kept.add(line);
                continue;
            }
            if (line.equals("////") || line.equals("++++")) {        // a comment, or markup for the page alone
                i = endOf(lines, i);
                continue;
            }
            if (line.equals("|===")) {                         // a table's columns do not fit a narrow pane
                i = endOf(lines, i);
                kept.add("");
                kept.add("_A table, drawn when the document opens._");
                kept.add("");
                continue;
            }
            if (line.startsWith("include::") || line.startsWith("//") || ATTRIBUTE_ENTRY.matcher(line).matches()) {
                continue;
            }
            Matcher block = BLOCK_ATTRIBUTES.matcher(line);
            if (block.matches() && !WRITTEN_BLOCKS.contains(block.group(1))) {
                if (i + 1 < lines.size() && BLOCK_DELIMITER.matcher(lines.get(i + 1)).matches()) {
                    i = endOf(lines, i + 1);
                }
                String named = block.group(1).equals("anf") ? "ANF statement" : block.group(1).replace('-', ' ');
                if (!named.equals(lastBlock)) {
                    kept.add("");
                    kept.add("_" + Character.toUpperCase(named.charAt(0)) + named.substring(1) + ", drawn when the document opens._");
                    kept.add("");
                }
                lastBlock = named;
                continue;
            }
            if (!line.isBlank()) {
                lastBlock = "";
            }
            kept.add(line);
        }
        return String.join("\n", kept);
    }

    /** The index of the line closing the delimited block that opens at a line, or the last line when it never closes. */
    private static int endOf(List<String> lines, int opening) {
        int i = opening + 1;
        while (i < lines.size() && !lines.get(i).equals(lines.get(opening))) {
            i++;
        }
        return Math.min(i, lines.size() - 1);
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
