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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Records a document edited in Komet in git, as the person's own commit through their own
 * {@code git}, so their identity and signing apply. Signing is always stated, {@code -S} or
 * {@code --no-gpg-sign}, never left to a configured default; nothing here commits on anyone's
 * behalf, and only the person's confirmation in the card calls {@link #write}. The commit holds the
 * one file and nothing else: other staged changes refuse it.
 *
 * <p>{@link #draftMessage} is the first draft of the message, from what the edit changed, for
 * the person to rewrite.
 */
public final class DocumentCommit {

    private static final int TIMEOUT_SECONDS = 120;

    private DocumentCommit() { }

    /**
     * What a commit attempt did.
     *
     * @param ok     whether git made the commit
     * @param sha    the commit's SHA when it did
     * @param output what git said
     */
    public record Result(boolean ok, String sha, String output) { }

    /**
     * A first draft of the commit message: a subject naming the document and a body saying how
     * many lines the edit changed, added and removed, and which lines it changed.
     *
     * @param path      the document's repository-relative path
     * @param committed the committed lines
     * @param pending   the lines with the pending edit
     * @return the draft, subject first
     */
    public static String draftMessage(String path, List<String> committed, List<String> pending) {
        boolean[][] matched = DocumentEditor.matchInOrder(committed, pending);
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < committed.size(); i++) {
            if (!matched[0][i]) {
                removed.add(committed.get(i));
            }
        }
        List<String> written = new ArrayList<>();
        for (int j = 0; j < pending.size(); j++) {
            if (!matched[1][j]) {
                written.add(pending.get(j));
            }
        }
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        StringBuilder message = new StringBuilder("Edit ").append(name).append("\n\n");
        message.append("Edited in Komet: ").append(written.size()).append(" line(s) written, ")
                .append(removed.size()).append(" line(s) replaced or removed.\n");
        for (String line : written.subList(0, Math.min(5, written.size()))) {
            if (!line.isBlank()) {
                message.append("\n  ").append(line.length() > 72 ? line.substring(0, 69) + "..." : line);
            }
        }
        return message.toString().strip() + "\n";
    }

    /**
     * Commits one file, as it stands in the working tree, with the person's message and signing
     * choice.
     *
     * @param repoRoot the working tree root
     * @param path     the file's repository-relative path
     * @param message  the commit message, subject first
     * @param sign     whether to sign ({@code -S}) or not ({@code --no-gpg-sign}); never implicit
     * @return what git did
     * @throws IOException if git cannot be run
     */
    public static Result write(Path repoRoot, String path, String message, boolean sign) throws IOException {
        if (message == null || message.isBlank()) {
            return new Result(false, "", "A commit needs a message.");
        }
        Run staged = git(repoRoot, List.of("diff", "--cached", "--name-only"));
        for (String line : staged.output().lines().toList()) {
            if (!line.isBlank() && !line.strip().equals(path)) {
                return new Result(false, "", "The index has other staged changes (" + line.strip()
                        + "). Commit or unstage them first, so this commit records only the document.");
            }
        }
        List<String> args = new ArrayList<>(List.of("commit", "--quiet"));
        args.add(sign ? "-S" : "--no-gpg-sign");
        args.add("-F");
        args.add("-");
        args.add("--");
        args.add(path);
        Run run = git(repoRoot, args, message);
        if (!run.ok()) {
            return new Result(false, "", run.output().isEmpty() ? "git commit exited " + run.exit() : run.output());
        }
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            return new Result(true, repo.headRevision(), run.output());
        }
    }

    private record Run(int exit, String output) {
        boolean ok() {
            return exit == 0;
        }
    }

    private static Run git(Path repoRoot, List<String> args) throws IOException {
        return git(repoRoot, args, null);
    }

    private static Run git(Path repoRoot, List<String> args, String input) throws IOException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(args);
        Process process = new ProcessBuilder(command).directory(repoRoot.toFile()).redirectErrorStream(true).start();
        if (input != null) {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
        }
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Run(-1, "git " + args.getFirst() + " timed out after " + TIMEOUT_SECONDS + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Run(-1, "interrupted");
        }
        return new Run(process.exitValue(), output);
    }
}
