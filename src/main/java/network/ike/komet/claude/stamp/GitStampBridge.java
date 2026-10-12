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
import design.knowledge.stamp.adoc.identity.GitIdentityAdapter;
import design.knowledge.stamp.adoc.model.StampFile;
import design.knowledge.stamp.adoc.model.StampId;
import design.knowledge.stamp.adoc.model.StampRecord;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads one file's STAMP attribution out of its git repository and expresses every STAMP as
 * Tinkar terms. The repository's directory name is the module (by IKE naming the repository,
 * the artifact and the directory share one name, and a Kompendium component's repository is
 * named for the component), the checked-out branch is the path, and the repository's
 * {@code .mailmap}, if any, consolidates author identities.
 *
 * @param file   the file, relative to the repository root
 * @param stamps the file's attribution at HEAD
 * @param terms  each STAMP as Tinkar terms, keyed as {@link StampFile#stamps()} is
 */
public record GitStampBridge(Path file, StampFile stamps, Map<StampId, GitStampTerms.GitStamp> terms) {

    /**
     * @param repoRoot working-tree root of the repository
     * @param file     path within it, absolute or root-relative
     * @return the file's STAMPs as git records and as Tinkar terms
     * @throws IOException if the repository or the file cannot be read
     */
    public static GitStampBridge read(Path repoRoot, Path file) throws IOException {
        GitIdentityAdapter adapter = GitIdentityAdapter.fromMailmap(repoRoot);
        String component = repoRoot.toAbsolutePath().normalize().getFileName().toString();
        try (GitRepo repo = GitRepo.open(repoRoot)) {
            String branch = repo.branch();
            StampFile atHead = repo.stampHistoryOf(file).atHead();
            Map<StampId, GitStampTerms.GitStamp> terms = new LinkedHashMap<>();
            for (Map.Entry<StampId, StampRecord> e : atHead.stamps().entrySet()) {
                terms.put(e.getKey(), GitStampTerms.GitStamp.of(adapter.coordinatesOf(e.getValue(), component, branch)));
            }
            return new GitStampBridge(atHead.file(), atHead, terms);
        }
    }
}
