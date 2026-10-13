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
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticEntity;
import dev.ikm.tinkar.entity.SemanticEntityVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Graph to git ({@code IKE-Network/ike-issues#468}): gives a materialized document back as the
 * text file it came from. The journal manifest's latest version orders the line elements and
 * each line's latest text is the line, so the export is the lines joined by newlines — the
 * structure the parse added is an overlay and plays no part. Written to the repository's
 * working tree at the path the git-source semantic records, the result is the file as git
 * holds it at the materialized commit, which {@link #compare} checks against HEAD line for
 * line.
 *
 * <p>This is the identity half of the round trip. Edits made in the graph will travel the
 * same way once they exist: the lines are the document, and a commit the person makes in git
 * records them with their provenance.
 */
public final class GitDocumentExporter {

    private GitDocumentExporter() { }

    /**
     * A document given back as text.
     *
     * @param anchor the document anchor's id
     * @param path   the repository-relative path the latest git-source version records
     * @param commit the commit SHA that version records
     * @param lines  the lines in manifest order
     */
    public record Exported(PublicId anchor, String path, String commit, List<String> lines) {

        /** @return the lines joined by newlines, with a trailing newline */
        public String text() {
            return lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
        }
    }

    /** How an export relates to the file in a working tree. */
    public enum Relation { IDENTICAL_TO_HEAD, DIFFERS_FROM_HEAD, NOT_IN_HEAD }

    /**
     * The result of a comparison with HEAD.
     *
     * @param relation      how the export relates to HEAD
     * @param firstDifference the first 1-based line that differs, or 0 when none does
     * @param exportedLines how many lines the export has
     * @param headLines     how many lines HEAD has, or 0 when the file is not in HEAD
     */
    public record Comparison(Relation relation, int firstDifference, int exportedLines, int headLines) { }

    /**
     * Gives a document back from the store.
     *
     * @param anchor the document anchor's id
     * @return the document, or empty when the store holds no manifest for it
     */
    public static Optional<Exported> export(PublicId anchor) {
        Optional<GitDocumentMaterializer.StoredDocument> stored = GitDocumentMaterializer.load(anchor);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        GitDocumentMaterializer.StoredDocument document = stored.get();
        String path = "";
        long anchorNid = EntityService.get().nidForPublicId(anchor);
        for (SemanticEntity<SemanticEntityVersion> source : EntityService.get()
                .semanticsForComponentOfPattern(anchorNid, StampedDocumentTerms.GIT_SOURCE_PATTERN.nid()).toList()) {
            SemanticEntityVersion latest = null;
            for (SemanticEntityVersion v : source.versions()) {
                if (v.time() != Long.MAX_VALUE && (latest == null || v.time() > latest.time())) {
                    latest = v;
                }
            }
            if (latest != null) {
                path = String.valueOf(latest.fieldValues().get(0));
            }
        }
        List<String> lines = document.lines().stream().map(GitDocumentMaterializer.StoredLine::text).toList();
        return Optional.of(new Exported(anchor, path, document.commit(), lines));
    }

    /**
     * Writes the export into a working tree at its recorded path, creating directories as
     * needed and replacing what is there.
     *
     * @param exported the document
     * @param repoRoot the working tree root
     * @return the file written
     * @throws IOException if the file cannot be written
     */
    public static Path writeTo(Exported exported, Path repoRoot) throws IOException {
        Path file = repoRoot.resolve(exported.path());
        Files.createDirectories(file.getParent());
        Files.writeString(file, exported.text(), StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Compares the export with the file as committed at HEAD in a working tree.
     *
     * @param exported the document
     * @param repoRoot the working tree root
     * @return how they relate
     * @throws IOException if the repository cannot be read
     */
    public static Comparison compare(Exported exported, Path repoRoot) throws IOException {
        List<String> head;
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            head = repo.headLines(Path.of(exported.path()));
        } catch (IOException | RuntimeException e) {
            return new Comparison(Relation.NOT_IN_HEAD, 0, exported.lines().size(), 0);
        }
        if (head == null) {
            return new Comparison(Relation.NOT_IN_HEAD, 0, exported.lines().size(), 0);
        }
        int n = Math.min(head.size(), exported.lines().size());
        for (int i = 0; i < n; i++) {
            if (!head.get(i).equals(exported.lines().get(i))) {
                return new Comparison(Relation.DIFFERS_FROM_HEAD, i + 1, exported.lines().size(), head.size());
            }
        }
        if (head.size() != exported.lines().size()) {
            return new Comparison(Relation.DIFFERS_FROM_HEAD, n + 1, exported.lines().size(), head.size());
        }
        return new Comparison(Relation.IDENTICAL_TO_HEAD, 0, exported.lines().size(), head.size());
    }
}
