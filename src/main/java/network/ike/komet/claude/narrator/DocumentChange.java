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
package network.ike.komet.claude.narrator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What a stamped document's commit changed, for the narrator to describe (IKE-Network/ike-issues#1355): the
 * commit's message and its diff of the document, as git shows them.
 */
final class DocumentChange {

    /** Above this many characters the diff is cut, saying so: a narration is a short note. */
    static final int MAX_CHARACTERS = 12_000;

    private static final long TIMEOUT_SECONDS = 30;

    private DocumentChange() {
    }

    /**
     * @param root the repository's working tree
     * @param file the document, as git names it
     * @param sha  the commit
     * @return its message and its diff of the document
     * @throws IOException if git cannot be run
     * @throws InterruptedException if interrupted while git runs
     */
    static String of(Path root, String file, String sha) throws IOException, InterruptedException {
        Process git = new ProcessBuilder(List.of("git", "show", "--no-color", "--format=Commit message:%n%B%nDiff of " + file + ":",
                "--unified=2", sha, "--", file)).directory(root.toFile()).redirectErrorStream(true).start();
        byte[] out = git.getInputStream().readAllBytes();
        if (!git.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            git.destroyForcibly();
            throw new IOException("git show " + sha + " timed out");
        }
        String shown = new String(out, StandardCharsets.UTF_8);
        if (git.exitValue() != 0) {
            throw new IOException("git show " + sha + " exited " + git.exitValue() + ": " + shown.strip());
        }
        return cut(shown);
    }

    /**
     * @param shown what git showed
     * @return it, cut at {@value #MAX_CHARACTERS} characters with a note that it was
     */
    static String cut(String shown) {
        return shown.length() <= MAX_CHARACTERS ? shown
                : shown.substring(0, MAX_CHARACTERS) + "\n[… the rest of the diff, " + (shown.length() - MAX_CHARACTERS)
                        + " characters, is not shown]";
    }
}
