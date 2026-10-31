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
package network.ike.komet.claude.calculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import network.ike.komet.claude.block.Tokens;
import network.ike.komet.claude.decision.Bounds;

/**
 * Edits to a {@code [calculate]} block's text that a what-if makes ({@code dev-calculator-blocks}):
 * an assumption for an input is one {@code assume =} line, added or replaced, written with the
 * input's token as its {@code bind =} line writes it, so the diff is that one line and the
 * observed binding is untouched. Everything else in the block is left byte for byte.
 */
public final class CalculationEdits {

    private CalculationEdits() {
    }

    /**
     * The body with an assumption for the input whose {@code bind} line carries the identifier,
     * replacing any assumption it already has; empty when no line binds that input.
     *
     * @param body       the block's body
     * @param identifier the input's written identity, as {@code @id=} gives it
     * @param bounds     the assumed value
     * @param unit       the unit token to write after the interval, when one is known
     */
    public static Optional<String> withAssumption(String body, String identifier, Bounds bounds, Optional<String> unit) {
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        boolean trailingNewline = body.endsWith("\n");
        if (trailingNewline) {
            lines.remove(lines.size() - 1);
        }
        Optional<String> token = Optional.empty();
        String indent = "  ";
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("bind") || stripped.startsWith("assume")) {
                String rest = stripped.substring(stripped.indexOf('=') + 1).strip();
                List<String> words = Tokens.words(rest);
                if (words.size() >= 2 && words.get(0).startsWith("k:") && words.get(1).equals("@id=" + identifier)) {
                    if (stripped.startsWith("assume")) {
                        continue;   // replaced below
                    }
                    token = Optional.of(words.get(0) + " " + words.get(1));
                    indent = line.substring(0, line.length() - line.stripLeading().length());
                }
            }
            kept.add(line);
        }
        if (token.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder assume = new StringBuilder(indent).append("assume = ").append(token.get()).append(' ')
                .append(Tokens.interval(bounds.lower(), bounds.upper(), !bounds.lowerOpen(), !bounds.upperOpen()));
        unit.ifPresent(u -> assume.append(' ').append(u));
        // after the last line of the block that is a bind or an assume, so assumptions stay together at the end
        int at = kept.size();
        for (int i = kept.size() - 1; i >= 0; i--) {
            String stripped = kept.get(i).strip();
            if (stripped.startsWith("bind") || stripped.startsWith("assume")) {
                at = i + 1;
                break;
            }
        }
        kept.add(at, assume.toString());
        String result = String.join("\n", kept);
        return Optional.of(trailingNewline ? result + "\n" : result);
    }

    /** The body with the input's assumption removed; the body itself when it has none. */
    public static String withoutAssumption(String body, String identifier) {
        List<String> lines = new ArrayList<>(List.of(body.split("\n", -1)));
        boolean trailingNewline = body.endsWith("\n");
        if (trailingNewline) {
            lines.remove(lines.size() - 1);
        }
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.startsWith("assume")) {
                String rest = stripped.substring(stripped.indexOf('=') + 1).strip();
                List<String> words = Tokens.words(rest);
                if (words.size() >= 2 && words.get(1).equals("@id=" + identifier)) {
                    continue;
                }
            }
            kept.add(line);
        }
        String result = String.join("\n", kept);
        return trailingNewline ? result + "\n" : result;
    }
}
