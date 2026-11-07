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
package network.ike.komet.claude.terminology;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Catches terms in the assistant's reply that misname IKE's own things, so the reply can be
 * rewritten before it stands (knowledge-graphlet/komet-claude-plugin#2). A prompt instruction alone
 * does not reliably keep the model from calling the knowledge base an "ontology"; this is the
 * in-app analogue of the operator's terminology hook.
 *
 * <p>The terms are read from {@code flagged-terms.txt} beside this class, so flagging another term
 * is a data change. Matching is case-insensitive at word boundaries, so an identifier such as
 * {@code ontologyView} does not match, only the standalone word. Text in code spans and fenced
 * blocks is not scanned: it quotes code or source rather than speaking in the assistant's voice. A
 * guard whose resource is missing finds nothing rather than breaking the conversation.
 */
public final class TerminologyGuard {

    /**
     * A flagged term: the word forms to catch and the IKE terms to use instead.
     *
     * @param name           the term's name, its first word form
     * @param pattern        the compiled case-insensitive, word-boundary pattern
     * @param replacements   the IKE terms to use instead
     * @param externalEscape {@code true} when the word is right for an external system (so the
     *                       assistant may keep it for that), {@code false} when it never names an
     *                       IKE thing correctly
     * @param note           a short reason, or empty
     */
    public record FlaggedTerm(String name, Pattern pattern, List<String> replacements,
                              boolean externalEscape, String note) {
    }

    /**
     * One match of a flagged term.
     *
     * @param matched the text that matched, in its original case
     * @param term    the flagged term it matched
     */
    public record Hit(String matched, FlaggedTerm term) {
    }

    private static final String RESOURCE = "flagged-terms.txt";

    /** Fenced blocks (``` or ~~~) and inline code spans, which quote rather than speak. */
    private static final Pattern CODE = Pattern.compile(
            "(?ms)^(```|~~~).*?^\\1[ \\t]*$|`[^`\\n]*`");

    private final List<FlaggedTerm> terms;

    private TerminologyGuard(List<FlaggedTerm> terms) {
        this.terms = List.copyOf(terms);
    }

    /**
     * Loads the guard from the bundled {@code flagged-terms.txt}.
     *
     * @return a guard over the bundled terms; one that finds nothing if the resource is absent
     */
    public static TerminologyGuard fromDefaultResource() {
        List<FlaggedTerm> parsed = new ArrayList<>();
        try (InputStream in = TerminologyGuard.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    FlaggedTerm term = parseLine(line);
                    if (term != null) {
                        parsed.add(term);
                    }
                }
            }
        } catch (IOException e) {
            // A guard that cannot read its terms finds nothing rather than breaking the conversation.
        }
        return new TerminologyGuard(parsed);
    }

    /**
     * Creates a guard over the given terms.
     *
     * @param terms the flagged terms
     * @return a guard over them
     */
    public static TerminologyGuard of(List<FlaggedTerm> terms) {
        return new TerminologyGuard(terms);
    }

    /**
     * The terms this guard flags.
     *
     * @return the terms, unmodifiable
     */
    public List<FlaggedTerm> terms() {
        return terms;
    }

    /**
     * Finds the flagged terms in a reply, outside code spans and fenced blocks.
     *
     * @param text the reply; null or blank finds nothing
     * @return the matches, term by term in the order the terms are listed
     */
    public List<Hit> scan(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return hits;
        }
        String prose = CODE.matcher(text).replaceAll(" ");
        for (FlaggedTerm term : terms) {
            Matcher matcher = term.pattern().matcher(prose);
            while (matcher.find()) {
                hits.add(new Hit(matcher.group(), term));
            }
        }
        return hits;
    }

    /**
     * The instruction for the rewrite turn: each distinct flagged term with the terms to use instead.
     *
     * @param hits the matches from {@link #scan(String)}
     * @return the instruction, or an empty string when there is nothing to rewrite
     */
    public static String correctionInstruction(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        List<FlaggedTerm> distinct = new ArrayList<>();
        for (Hit hit : hits) {
            if (distinct.stream().noneMatch(t -> t.name().equals(hit.term().name()))) {
                distinct.add(hit.term());
            }
        }
        StringBuilder sb = new StringBuilder(
                "Your previous reply used terms that misname things in this knowledge base. Rewrite it: ");
        for (int i = 0; i < distinct.size(); i++) {
            FlaggedTerm term = distinct.get(i);
            if (i > 0) {
                sb.append("; ");
            }
            sb.append("instead of \"").append(term.name()).append("\" use ")
                    .append(String.join(" / ", term.replacements()));
            if (term.externalEscape()) {
                sb.append(" (keep it only where you mean an external system)");
            }
        }
        sb.append(". Keep everything else the same, every block included, and call no tools.");
        return sb.toString();
    }

    /**
     * Parses one line of the form {@code forms || external|internal || replacement, ... || note},
     * where {@code forms} is a pipe-separated list of word forms.
     *
     * @param rawLine the line
     * @return the term, or null for a blank, comment or malformed line
     */
    private static FlaggedTerm parseLine(String rawLine) {
        String line = rawLine.strip();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\s*\\|\\|\\s*", -1);
        if (parts.length < 3) {
            return null;
        }
        String forms = parts[0].strip();
        if (forms.isEmpty()) {
            return null;
        }
        boolean externalEscape = "external".equalsIgnoreCase(parts[1].strip());
        List<String> replacements = Arrays.stream(parts[2].split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        String note = (parts.length > 3) ? parts[3].strip() : "";
        Pattern pattern = Pattern.compile("\\b(?:" + forms + ")\\b", Pattern.CASE_INSENSITIVE);
        String name = forms.split("\\|")[0].strip();
        return new FlaggedTerm(name, pattern, replacements, externalEscape, note);
    }
}
