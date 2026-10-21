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

import design.knowledge.stamp.adoc.dialect.LineKinds;
import design.knowledge.stamp.adoc.dialect.Document;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.foundation.ike.bindings.IkeConcept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What each line of a parsed document is, as a semantic vertex (IKE-Network/ike-issues#1313;
 * design {@code design-2026-10-20-instance-data}): its meaning, a line concept of the starter
 * set's {@code DocumentVertexSet}, and the parts its markup carried as properties keyed by
 * concept. Every line carries its {@code Line source}, the line as written, so the file reads
 * back byte for byte whatever the other properties say.
 *
 * <p>What kind each line is comes from stamp-adoc core's {@link LineKinds}, which the sentence
 * segmentation reads too, so the two never disagree about which lines are prose; this class names
 * each kind with its concept and reads the parts the markup carried.
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
    private static final Pattern UNORDERED = Pattern.compile("^\\s*(\\*+|-)\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*(\\.+|\\d+\\.)\\s+(.*)$");
    private static final Pattern DESCRIPTION = Pattern.compile("^(?!\\s)(?![a-z]+::)(.+?)(::+)(?:\\s+(.*))?$");
    private static final Pattern BLOCK_MACRO = Pattern.compile("^(image|include|ifdef|ifndef|ifeval|endif|toc|video|audio)::([^\\[]*)\\[(.*)\\]$");

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
        List<LineKinds.Kind> kinds = LineKinds.of(document);
        List<Role> roles = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            roles.add(classify(lines.get(i), kinds.get(i)));
        }
        return roles;
    }

    private static Role classify(String s, LineKinds.Kind kind) {
        Map<ConceptFacade, Object> properties = new LinkedHashMap<>();
        properties.put(IkeConcept.LINE_SOURCE, s);
        switch (kind) {
            case BLANK -> {
                return new Role(IkeConcept.BLANK_LINE, properties);
            }
            case DELIMITER -> {
                return new Role(IkeConcept.DELIMITER_LINE, properties);
            }
            case COMMENT -> {
                return new Role(IkeConcept.COMMENT_LINE, properties);
            }
            case VERBATIM -> {
                return new Role(IkeConcept.VERBATIM_LINE, properties);
            }
            case HEADING -> {
                Matcher heading = HEADING.matcher(s);
                heading.matches();
                properties.put(IkeConcept.DOCUMENT_ELEMENT_LEVEL, heading.group(1).length() - 1);
                properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, heading.group(2));
                return new Role(IkeConcept.HEADING_LINE, properties);
            }
            case ANCHOR -> {
                Matcher anchor = ANCHOR.matcher(s);
                anchor.matches();
                properties.put(IkeConcept.ANCHOR_ID, anchor.group(1));
                return new Role(IkeConcept.ANCHOR_LINE, properties);
            }
            case BLOCK_TITLE -> {
                Matcher title = BLOCK_TITLE.matcher(s);
                title.matches();
                properties.put(IkeConcept.DOCUMENT_ELEMENT_TITLE, title.group(1).strip());
                return new Role(IkeConcept.BLOCK_TITLE_LINE, properties);
            }
            case BLOCK_ATTRIBUTE -> {
                properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, s);
                return new Role(IkeConcept.BLOCK_ATTRIBUTE_LINE, properties);
            }
            case ATTRIBUTE_ENTRY -> {
                Matcher entry = ATTRIBUTE_ENTRY.matcher(s);
                entry.matches();
                properties.put(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME, entry.group(1));
                properties.put(IkeConcept.ATTRIBUTE_VALUE, entry.group(2) == null ? "" : entry.group(2).strip());
                return new Role(IkeConcept.ATTRIBUTE_ENTRY_LINE, properties);
            }
            case INCLUDE -> {
                Matcher macro = BLOCK_MACRO.matcher(s);
                macro.matches();
                properties.put(IkeConcept.ASCIIDOC_REFERENCE_TARGET, macro.group(2).strip());
                properties.put(IkeConcept.DOCUMENT_ELEMENT_PARAMETERS, macro.group(3));
                return new Role(IkeConcept.ASCIIDOC_INCLUDE, properties);
            }
            case CONDITIONAL -> {
                Matcher macro = BLOCK_MACRO.matcher(s);
                macro.matches();
                properties.put(IkeConcept.ASCIIDOC_ATTRIBUTE_NAME, macro.group(2).strip());
                properties.put(IkeConcept.ASCIIDOC_NEGATED, macro.group(1).equals("ifndef"));
                return new Role(IkeConcept.ASCIIDOC_CONDITIONAL, properties);
            }
            case ITEM -> {
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
            default -> {
                return new Role(IkeConcept.PROSE_LINE, properties);
            }
        }
    }
}
