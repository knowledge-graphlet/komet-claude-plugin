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
package network.ike.komet.claude.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The fenced blocks of a markdown text, each with where it sits, so one can be read out or
 * restated in place. A fence opens with three or more backticks or tildes, carries its info
 * string on the same line, and closes on a line of only fence characters at least as wide; a
 * fence left open runs to the end of the text, as CommonMark reads it.
 */
public final class FencedBlocks {

    /**
     * One fenced block.
     *
     * @param info  the info string after the opening fence, stripped
     * @param body  the lines between the fences, each ending with a newline
     * @param start the offset of the opening fence's line in the text
     * @param end   the offset just past the closing fence's line, or the text's end when the fence is left open
     * @param fence the fence characters the block opened with, as written
     */
    public record Fence(String info, String body, int start, int end, String fence) {
    }

    private FencedBlocks() {
    }

    /** Every fenced block of a text, in order. */
    public static List<Fence> scan(String markdown) {
        List<Fence> fences = new ArrayList<>();
        if (markdown == null) {
            return fences;
        }
        String[] lines = markdown.split("\n", -1);
        int[] offsets = new int[lines.length + 1];
        for (int i = 0; i < lines.length; i++) {
            offsets[i + 1] = offsets[i] + lines[i].length() + 1;
        }
        int i = 0;
        while (i < lines.length) {
            String stripped = lines[i].stripTrailing().stripLeading();
            char fence = stripped.startsWith("```") ? '`' : stripped.startsWith("~~~") ? '~' : 0;
            if (fence == 0) {
                i++;
                continue;
            }
            int width = 0;
            while (width < stripped.length() && stripped.charAt(width) == fence) {
                width++;
            }
            String info = stripped.substring(width).strip();
            StringBuilder body = new StringBuilder();
            int j = i + 1;
            boolean closed = false;
            for (; j < lines.length; j++) {
                String candidate = lines[j].strip();
                if (candidate.length() >= width && candidate.chars().allMatch(c -> c == fence)) {
                    closed = true;
                    break;
                }
                if (j == lines.length - 1 && lines[j].isEmpty()) {
                    break; // the empty piece after the text's final newline is no line of the body
                }
                body.append(lines[j]).append('\n');
            }
            int end = closed ? Math.min(offsets[j + 1], markdown.length()) : markdown.length();
            fences.add(new Fence(info, body.toString(), offsets[i], end, stripped.substring(0, width)));
            i = j + 1;
        }
        return fences;
    }

    /**
     * The text with the first fenced block carrying this info and body restated with another
     * info and body, keeping the fence as it was written; empty when no block matches.
     */
    public static Optional<String> replace(String markdown, String info, String body, String newInfo, String newBody) {
        for (Fence fence : scan(markdown)) {
            if (fence.info().equals(info == null ? "" : info.strip()) && fence.body().equals(body)) {
                String restated = fence.fence() + (newInfo == null || newInfo.isBlank() ? "" : newInfo.strip()) + "\n"
                        + (newBody.endsWith("\n") || newBody.isEmpty() ? newBody : newBody + "\n")
                        + fence.fence() + "\n";
                String tail = markdown.substring(fence.end());
                if (fence.end() == markdown.length() && !markdown.endsWith("\n")) {
                    restated = restated.substring(0, restated.length() - 1);
                }
                return Optional.of(markdown.substring(0, fence.start()) + restated + tail);
            }
        }
        return Optional.empty();
    }
}
