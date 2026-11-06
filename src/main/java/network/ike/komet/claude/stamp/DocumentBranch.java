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

import dev.ikm.tinkar.common.id.PublicId;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Brings the store's documents to the branch a working tree has switched to. A stored document
 * has no branch: its identity is the working tree and the path, and it reads as its newest
 * commit by time, so the commits of two branches in one document show whichever was written
 * last, not HEAD. The commits HEAD adds are written first, which is all a branch ahead of the
 * document needs. When the document then still differs from HEAD it is rebuilt from HEAD's
 * history alone. Nothing is lost: an ephemeral document is what git holds, and the commits of the
 * other branch stay in git. A document that is not ephemeral is left as it is and said so.
 */
final class DocumentBranch {

    private DocumentBranch() { }

    /**
     * A document to bring to HEAD.
     *
     * @param anchor the document's anchor
     * @param path   its file, relative to the working tree
     */
    record Member(PublicId anchor, String path) { }

    /**
     * What following a branch did.
     *
     * @param rebuilt the files whose documents were rebuilt from HEAD's history
     * @param absent  the files this branch does not hold, whose documents stand as they were
     * @param stale   the files whose documents still differ from HEAD, each with why
     */
    record Followed(List<String> rebuilt, List<String> absent, List<String> stale) {

        /** @return a sentence for the status line, empty when every document simply followed */
        String report() {
            List<String> parts = new ArrayList<>();
            if (!rebuilt.isEmpty()) {
                parts.add("Rebuilt from this branch's history: " + String.join(", ", rebuilt) + ".");
            }
            if (!absent.isEmpty()) {
                parts.add("Not on this branch, so shown as stored: " + String.join(", ", absent) + ".");
            }
            if (!stale.isEmpty()) {
                parts.add("Still differs from this branch: " + String.join("; ", stale) + ".");
            }
            return String.join(" ", parts);
        }
    }

    /**
     * Brings documents to HEAD, one at a time, so that one that cannot follow leaves the rest
     * following.
     *
     * @param root    the working tree
     * @param members the documents
     * @return what was done
     * @throws IOException if git or the repository cannot be read
     */
    static Followed follow(Path root, List<Member> members) throws IOException {
        List<String> rebuilt = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (Member member : members) {
            Path file = root.resolve(member.path());
            if (!GitOperations.committed(root, Path.of(member.path()))) {
                absent.add(member.path());
                continue;
            }
            GitDocumentMaterializer.materialize(root, file, true);
            if (matchesHead(member, root)) {
                continue;
            }
            try {
                DocumentScopes.rebuild(member.anchor(), root, file);
                if (matchesHead(member, root)) {
                    rebuilt.add(member.path());
                } else {
                    stale.add(member.path() + ": rebuilt, and it still differs");
                }
            } catch (IllegalStateException refused) {
                stale.add(member.path() + ": " + refused.getMessage());
            }
        }
        return new Followed(List.copyOf(rebuilt), List.copyOf(absent), List.copyOf(stale));
    }

    /** Whether the store's committed document is the file at HEAD. */
    private static boolean matchesHead(Member member, Path root) throws IOException {
        var exported = GitDocumentExporter.export(member.anchor(), false);
        return exported.isPresent()
                && GitDocumentExporter.compare(exported.get(), root).relation() == GitDocumentExporter.Relation.IDENTICAL_TO_HEAD;
    }
}
