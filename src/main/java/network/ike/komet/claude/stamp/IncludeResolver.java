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

import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import design.knowledge.stamp.adoc.dialect.Reference;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where an assembly's {@code include::} directives point, as committed text alone can say
 * (IKE-Network/ike-issues#1307). An aggregator such as the ANF compendium names its topics
 * through an attribute — {@code include::{topics}/topics/anf/why-standardize.adoc[]} — whose
 * value is settled by the build ({@code :topics: {generated}/topics-asciidoc}, unpacked from a
 * sibling module at build time), so the text's own attribute entries resolve some includes and
 * not others. The resolver tries, in order:
 *
 * <ol>
 *   <li>the target with every {@code {name}} replaced by the document's own attribute entry,
 *       header or body, relative to the including file's directory (or absolute), when nothing
 *       is left unresolved;</li>
 *   <li>the repository conventions: a target under {@code {topics}} maps onto the topic
 *       library's source root, {@code topics/src/docs/asciidoc}, relative to the repository
 *       root;</li>
 *   <li>the target as written, relative to the including file's directory.</li>
 * </ol>
 *
 * <p>A candidate counts when the caller's {@code exists} test accepts it — committed at HEAD,
 * for the materializer — so a working-tree copy of a generated file never stands in for the
 * source. The resolver reads nothing itself; it is a pure function of the lines it is given.
 */
public final class IncludeResolver {

    /** A header or body attribute entry: {@code :name: value}. */
    private static final Pattern ATTRIBUTE_ENTRY = Pattern.compile("^:([A-Za-z0-9_][A-Za-z0-9_-]*)!?:(?:\\s+(.*))?$");
    /** An attribute reference in a target: {@code {name}}. */
    private static final Pattern ATTRIBUTE_REFERENCE = Pattern.compile("\\{([A-Za-z0-9_][A-Za-z0-9_-]*)}");

    /**
     * Repository conventions: the attribute an aggregator uses for a library's unpacked copy,
     * and where that library's sources live under the repository root.
     */
    static final Map<String, String> CONVENTIONS = Map.of("topics", "topics/src/docs/asciidoc");

    private IncludeResolver() { }

    /**
     * One include and where it resolved.
     *
     * @param reference the directive as parsed
     * @param resolved  the file it names, relative to the repository root, or empty
     * @param reason    why it did not resolve, empty when it did
     */
    public record Resolution(Reference reference, Optional<Path> resolved, String reason) {

        /** @return the target as written */
        public String target() {
            return reference.target();
        }

        /** @return the directive's 1-based line in the including file */
        public int line() {
            return reference.line();
        }
    }

    /**
     * The attribute entries of a document's text, header and body alike, in order: AsciiDoc
     * honors an entry wherever it appears before the lines that use it, and the aggregators set
     * their library attribute after a comment, outside what the dialect parser keeps as the
     * header.
     *
     * @param lines the committed lines
     * @return name to value, the last entry for a name winning
     */
    public static Map<String, String> attributeEntries(List<String> lines) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher m = ATTRIBUTE_ENTRY.matcher(line);
            if (m.matches()) {
                entries.put(m.group(1), m.group(2) == null ? "" : m.group(2).strip());
            }
        }
        return entries;
    }

    /**
     * The include directives of a document's text, in order.
     *
     * @param lines the committed lines
     * @return every {@code include::} reference the dialect parser finds
     */
    public static List<Reference> includes(List<String> lines) {
        Document document = IkeDialectParser.parse(lines);
        return document.references().stream().filter(r -> r.kind() == Reference.Kind.INCLUDE).toList();
    }

    /**
     * Resolve every include of one file.
     *
     * @param repoRoot  the repository root, for the conventions and for keeping every answer inside it
     * @param including the including file, relative to the repository root
     * @param lines     the including file's committed lines
     * @param exists    whether a candidate, relative to the repository root, is a file the caller accepts
     * @return one resolution per include directive, in document order
     */
    public static List<Resolution> resolve(Path repoRoot, Path including, List<String> lines, Predicate<Path> exists) {
        Map<String, String> attributes = attributeEntries(lines);
        List<Resolution> resolutions = new ArrayList<>();
        for (Reference include : includes(lines)) {
            resolutions.add(resolveOne(repoRoot, including, attributes, include, exists));
        }
        return resolutions;
    }

    private static Resolution resolveOne(Path repoRoot, Path including, Map<String, String> attributes, Reference include, Predicate<Path> exists) {
        String target = include.target().strip();
        if (target.isEmpty()) {
            return new Resolution(include, Optional.empty(), "empty target");
        }
        if (target.contains("://")) {
            return new Resolution(include, Optional.empty(), "a URI, not a file in this repository");
        }
        Path base = including.getParent() == null ? Path.of("") : including.getParent();
        List<String> tried = new ArrayList<>();

        String substituted = substitute(target, attributes);
        if (!ATTRIBUTE_REFERENCE.matcher(substituted).find()) {
            Optional<Path> candidate = inside(repoRoot, base, substituted);
            if (candidate.isEmpty()) {
                return new Resolution(include, Optional.empty(), "outside this repository: " + substituted);
            }
            if (exists.test(candidate.get())) {
                return new Resolution(include, candidate, "");
            }
            tried.add(candidate.get().toString());
        }

        Matcher leading = Pattern.compile("^\\{([A-Za-z0-9_][A-Za-z0-9_-]*)}/?(.*)$").matcher(target);
        if (leading.matches() && CONVENTIONS.containsKey(leading.group(1))) {
            String rest = substitute(leading.group(2), attributes);
            if (!ATTRIBUTE_REFERENCE.matcher(rest).find()) {
                Optional<Path> candidate = inside(repoRoot, Path.of(CONVENTIONS.get(leading.group(1))), rest);
                if (candidate.isPresent() && exists.test(candidate.get())) {
                    return new Resolution(include, candidate, "");
                }
                candidate.ifPresent(c -> tried.add(c.toString()));
            }
        }

        if (!target.equals(substituted) || ATTRIBUTE_REFERENCE.matcher(target).find()) {
            // as written, in case the braces are literal in the tree
            Optional<Path> candidate = inside(repoRoot, base, target);
            if (candidate.isPresent() && exists.test(candidate.get())) {
                return new Resolution(include, candidate, "");
            }
        }
        List<String> unresolved = new ArrayList<>();
        Matcher m = ATTRIBUTE_REFERENCE.matcher(substituted);
        while (m.find()) {
            unresolved.add(m.group(1));
        }
        String reason = unresolved.isEmpty()
                ? "not committed in this repository" + (tried.isEmpty() ? "" : " (tried " + String.join(", ", tried) + ")")
                : "attribute" + (unresolved.size() == 1 ? " " : "s ") + String.join(", ", unresolved) + " not set in the document"
                        + (tried.isEmpty() ? "" : "; tried " + String.join(", ", tried));
        return new Resolution(include, Optional.empty(), reason);
    }

    /** Replace every {@code {name}} the entries define, recursively, so {@code {a}/x} with {@code :a: {b}/y} settles when {@code b} is set. */
    static String substitute(String text, Map<String, String> attributes) {
        String current = text;
        for (int round = 0; round < 8; round++) {
            Matcher m = ATTRIBUTE_REFERENCE.matcher(current);
            StringBuilder out = new StringBuilder();
            boolean changed = false;
            while (m.find()) {
                String value = attributes.get(m.group(1));
                if (value != null) {
                    m.appendReplacement(out, Matcher.quoteReplacement(value));
                    changed = true;
                } else {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(out);
            current = out.toString();
            if (!changed) {
                break;
            }
        }
        return current;
    }

    /** A path, absolute or relative to {@code base}, normalized and relative to the root — or empty when it leaves the root. */
    private static Optional<Path> inside(Path repoRoot, Path base, String target) {
        Path path = Path.of(target);
        Path root = repoRoot.toAbsolutePath().normalize();
        Path absolute = path.isAbsolute() ? path.normalize() : root.resolve(base).resolve(path).normalize();
        if (!absolute.startsWith(root)) {
            return Optional.empty();
        }
        return Optional.of(root.relativize(absolute));
    }
}
