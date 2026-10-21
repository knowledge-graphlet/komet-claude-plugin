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

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Document;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each line of a parsed document is, as a semantic vertex (IKE-Network/ike-issues#1313;
 * design {@code design-2026-10-20-instance-data}): its meaning, a line concept of the starter
 * set's {@code DocumentVertexSet}, and the parts its markup carried as properties keyed by
 * concept. Every line carries its {@code Line source}, the line as written, so the file reads
 * back byte for byte whatever the other properties say.
 *
 * <p>The classification reads the parser's block structure, which owns every line but the
 * blank ones, and the markup the parser itself recognizes; it is a pure function of the text.
 */
public final class LineRoles {

    /**
     * One line's role.
     *
     * @param meaning    the line concept
     * @param properties the properties, Line source first, keyed by concept
     */
    public record Role(ConceptFacade meaning, Map<ConceptFacade, Object> properties) { }

    private static final Pattern HEADING = Pattern.compile("^(={1,6})\\s+(\\S.*?)\\s*$");
    private static final Pattern ANCHOR = Pattern.compile("^\\[\\[([^\\[\\],]+)(?:,[^\\]]*)?\\]\\]$");
    private static final Pattern BLOCK_TITLE = Pattern.compile("^\\.([^\\s.].*)$");
    private static final Pattern ATTRIBUTE_LINE = Pattern.compile("^\\[(?!\\[)([^\\]]*)\\]$");
    private static final Pattern ATTRIBUTE_ENTRY = Pattern.compile("^:([A-Za-z0-9_][A-Za-z0-9_-]*!?):(?:\\s+(.*))?$");
    private static final Pattern DELIMITER = Pattern.compile("^(-{4,}|\\.{4,}|={4,}|_{4,}|\\*{4,}|--|\\|===|/{4,}|\\+{4,})$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*(\\*+|-)\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\.+|\\d+\\.)\\s+(.*)$");
    private static final Pattern DESCRIPTION = Pattern.compile("^(?!\\s)(?![a-z]+::)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern BLOCK_MACRO = Pattern.compile("^(image|include|ifdef|ifndef|ifeval|endif|toc|video|audio)::([^\\[]*)\\[(.*)\\]$");

    /** Blocks whose body is kept exactly as written. */
    private static final Set<Block.Kind> VERBATIM = Set.of(Block.Kind.LISTING, Block.Kind.LITERAL, Block.Kind.DIAGRAM);

    private LineRoles() {
    }

    /**
     * Classifies every line of a parsed document.
     *
     * @param document the parse
     * @return each line's role, line 1 first
     */
    public static List<Role> of(Document document) {
        List<String> lines = document.lines();
        Map<Integer, Block> owner = new HashMap<>();
        for (Block block : document.blocks()) {
            for (int line : block.lines()) {
                owner.put(line, block);
            }
        }
        Map<Block, int[]> fences = new HashMap<>();
        List<Role> roles = new ArrayList<>(lines.size());
        for (int i = 1; i <= lines.size(); i++) {
            Block block = owner.get(i);
            int[] fence = block == null ? null : fences.computeIfAbsent(block, b -> fences(b, lines));
            roles.add(classify(lines.get(i - 1), i, block, fence, lines));
        }
        return roles;
    }

    private static Role classify(String s, int line, Block block, int[] fence, List<String> lines) {
        Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
        properties.put(IkeConcept.LINE_SOURCE, s);
        if (s.isBlank()) {
            return new Role(IkeConcept.BLANK_LINE, properties);
        }
        if (block != null && fence[0] > 0 && (VERBATIM.contains(block.kind()) || block.kind() == Block.Kind.OPAQUE)) {
            if (line == fence[0] || line == fence[1]) {
                return new Role(IkeConcept.DELIMITER_LINE, properties);
            }
            if (line > fence[0] && (fence[1] == 0 || line < fence[1])) {
                boolean comment = lines.get(fence[0] - 1).startsWith("//");
                return new Role(comment ? IkeConcept.COMMENT_LINE : IkeConcept.VERBATIM_LINE, properties);
            }
        }
        if (DELIMITER.matcher(s).matches() || s.strip().equals("+")) {
            return new Role(IkeConcept.DELIMITER_LINE, properties);
        }
        if (s.startsWith("//")) {
            return new Role(IkeConcept.COMMENT_LINE, properties);
        }
        Matcher heading = HEADING.matcher(s);
        if (heading.matches() && block != null && block.headingLine() == line
                && (block.kind() == Block.Kind.SECTION || block.kind() == Block.Kind.DOCUMENT)) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_LEVEL, heading.group(1).length() - 1);
            properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, heading.group(2));
            return new Role(IkeConcept.HEADING_LINE, properties);
        }
        Matcher anchor = ANCHOR.matcher(s);
        if (anchor.matches()) {
            properties.put(IkeConcept.ANCHOR_ID, anchor.group(1));
            return new Role(IkeConcept.ANCHOR_LINE, properties);
        }
        Matcher title = BLOCK_TITLE.matcher(s);
        if (title.matches()) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, title.group(1).strip());
            return new Role(IkeConcept.BLOCK_TITLE_LINE, properties);
        }
        if (ATTRIBUTE_LINE.matcher(s).matches()) {
            properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, s);
            return new Role(IkeConcept.BLOCK_ATTRIBUTE_LINE, properties);
        }
        Matcher entry = ATTRIBUTE_ENTRY.matcher(s);
        if (entry.matches()) {
            properties.put(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME, entry.group(1));
            properties.put(IkeConcept.ATTRIBUTE_VALUE, entry.group(2) == null ? "" : entry.group(2).strip());
            return new Role(IkeConcept.ATTRIBUTE_ENTRY_LINE, properties);
        }
        Matcher macro = BLOCK_MACRO.matcher(s);
        if (macro.matches()) {
            String name = macro.group(1);
            String target = macro.group(2).strip();
            switch (name) {
                case "include" -> {
                    properties.put(IkeConcept.ASCIIDOC_REFERENCE_TARGET, target);
                    properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, macro.group(3));
                    return new Role(IkeConcept.ASCIIDOC_INCLUDE, properties);
                }
                case "ifdef", "ifndef", "endif" -> {
                    properties.put(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME, target);
                    properties.put(IkeConcept.ASCIIDOC_NEGATED, name.equals("ifndef"));
                    return new Role(IkeConcept.ASCIIDOC_CONDITIONAL, properties);
                }
                default -> {
                    return new Role(IkeConcept.VERBATIM_LINE, properties);   // image, toc and the media macros, kept as written
                }
            }
        }
        if (block != null && block.kind() == Block.Kind.LIST_ITEM && opensItem(block, line, lines)) {
            Matcher unordered = UNORDERED.matcher(s);
            Matcher ordered = ORDERED.matcher(s);
            Matcher description = DESCRIPTION.matcher(s);
            if (unordered.matches()) {
                properties.put(IkeConcept.LIST_MARKER, unordered.group(1));
                properties.put(IkeConcept.LIST_DEPTH, unordered.group(1).equals("-") ? 1 : unordered.group(1).length());
            } else if (ordered.matches()) {
                String marker = ordered.group(1);
                properties.put(IkeConcept.LIST_MARKER, marker);
                properties.put(IkeConcept.LIST_DEPTH, Character.isDigit(marker.charAt(0)) ? 1 : marker.length());
            } else if (description.matches()) {
                properties.put(IkeConcept.LIST_MARKER, description.group(2));
                properties.put(IkeConcept.LIST_DEPTH, description.group(2).length() - 1);
                properties.put(IkeConcept.ASCIIDOC_TERM_TEXT, description.group(1).strip());
            }
            return new Role(IkeConcept.ITEM_LINE, properties);
        }
        return new Role(IkeConcept.PROSE_LINE, properties);
    }

    /** Whether a line is the first line of a list item after its metadata: the line its marker opens. */
    private static boolean opensItem(Block item, int line, List<String> lines) {
        for (int owned : item.lines()) {
            String text = lines.get(owned - 1);
            if (ANCHOR.matcher(text).matches() || BLOCK_TITLE.matcher(text).matches() || ATTRIBUTE_LINE.matcher(text).matches()) {
                continue;
            }
            return owned == line;
        }
        return false;
    }

    /**
     * The opening and closing delimiter lines of a delimited block: the first line it owns that
     * is a delimiter, and the last it owns with the same text, 0 when it has none.
     */
    private static int[] fences(Block block, List<String> lines) {
        int open = 0;
        int close = 0;
        for (int owned : block.lines()) {
            String text = lines.get(owned - 1);
            if (open == 0) {
                if (DELIMITER.matcher(text).matches()) {
                    open = owned;
                }
            } else if (text.equals(lines.get(open - 1))) {
                close = owned;
            }
        }
        return new int[] {open, close};
    }
}
