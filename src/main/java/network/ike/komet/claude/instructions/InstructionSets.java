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
package network.ike.komet.claude.instructions;

import dev.ikm.komet.preferences.KometPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The titled-instruction-set registration fabric ({@code IKE-Network/ike-issues#1044}, the
 * {@code #1042} core): each set is a payload file in the owning tile's preferences directory —
 * the portable Agent Skills form, YAML-ish frontmatter ({@code name}, {@code description}) over
 * a Markdown instruction body — registered by index entries in the tile's preferences node
 * ({@code instruction-set.<id>.name/.description/.file}). The entry IS the registration: no
 * scanned directories, and the payloads ride the preferences git sync. Sets are <em>roleless</em>
 * here — system-prompt versus skill is the attachment role at the use site, never a property of
 * the document.
 */
public final class InstructionSets {

    private static final Logger LOG = LoggerFactory.getLogger(InstructionSets.class);

    /** Index-entry key prefix within the owning tile's preferences node. */
    private static final String KEY_PREFIX = "instruction-set.";

    /**
     * One registered titled instruction set.
     *
     * @param id          the set's stable identity (a UUID string)
     * @param name        the title (the Agent Skills frontmatter {@code name})
     * @param description the one-line description (the progressive-disclosure surface)
     * @param category    the intended-use classification (never {@code null})
     * @param fileName    the payload file's name within the owning tile's directory
     */
    public record InstructionSet(String id, String name, String description,
                                 InstructionCategory category, String fileName) {
    }

    /**
     * A parsed instruction document: the frontmatter surface and the body beneath it.
     *
     * @param name        the frontmatter {@code name}, or {@code null} when absent
     * @param description the frontmatter {@code description}, or {@code null} when absent
     * @param category    the parsed {@code category:} classification (never {@code null})
     * @param body        the Markdown instruction body (never {@code null})
     */
    public record Frontmatter(String name, String description, InstructionCategory category,
                              String body) {
    }

    private final KometPreferences node;

    /**
     * Creates the store over an owning tile's preferences node (payloads live in its
     * {@linkplain KometPreferences#directory() directory}).
     *
     * @param node the owning tile's preferences node
     */
    public InstructionSets(KometPreferences node) {
        this.node = node;
    }

    /**
     * The registered sets, sorted by name.
     *
     * @return the sets (never {@code null}; empty when none are registered)
     */
    public List<InstructionSet> list() {
        List<InstructionSet> sets = new ArrayList<>();
        try {
            for (String key : node.keys()) {
                if (key.startsWith(KEY_PREFIX) && key.endsWith(".file")) {
                    String id = key.substring(KEY_PREFIX.length(), key.length() - ".file".length());
                    sets.add(new InstructionSet(id,
                            node.get(KEY_PREFIX + id + ".name", "Untitled"),
                            node.get(KEY_PREFIX + id + ".description", ""),
                            InstructionCategory.parse(node.get(KEY_PREFIX + id + ".category", "")),
                            node.get(key, "")));
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not enumerate instruction sets", e);
        }
        sets.sort(Comparator.comparing(InstructionSet::name, String.CASE_INSENSITIVE_ORDER));
        return sets;
    }

    /**
     * The registered set with the given id, if present.
     *
     * @param id the set id
     * @return the set, or empty when the id is not registered here
     */
    public Optional<InstructionSet> byId(String id) {
        return list().stream().filter(set -> set.id().equals(id)).findFirst();
    }

    /**
     * Creates and registers a new set: the payload written in the portable form (frontmatter +
     * body), the index entries recorded.
     *
     * @param name        the title
     * @param description the one-line description
     * @param body        the Markdown instruction body
     * @return the registered set, or empty when the tile has no payload directory
     */
    public Optional<InstructionSet> create(String name, String description,
                                           InstructionCategory category, String body) {
        String id = UUID.randomUUID().toString();
        InstructionSet set = new InstructionSet(id, name, description,
                category == null ? InstructionCategory.GENERAL : category,
                "instruction-set-" + id + ".md");
        return write(set, body) ? Optional.of(set) : Optional.empty();
    }

    /**
     * Rewrites a registered set's payload and index entries.
     *
     * @param set         the registered set
     * @param name        the (possibly changed) title
     * @param description the (possibly changed) description
     * @param body        the Markdown instruction body
     * @return the updated registration, or empty on a write failure
     */
    public Optional<InstructionSet> save(InstructionSet set, String name, String description,
                                         InstructionCategory category, String body) {
        InstructionSet updated = new InstructionSet(set.id(), name, description,
                category == null ? InstructionCategory.GENERAL : category, set.fileName());
        return write(updated, body) ? Optional.of(updated) : Optional.empty();
    }

    /** Writes the payload (portable form) and the index entries; sync is best-effort. */
    private boolean write(InstructionSet set, String body) {
        try {
            Optional<Path> dir = node.directory();
            if (dir.isEmpty()) {
                LOG.warn("No preferences directory; instruction set not persisted");
                return false;
            }
            Files.createDirectories(dir.get());
            Files.writeString(dir.get().resolve(set.fileName()),
                    withFrontmatter(set.name(), set.description(), set.category(), body),
                    StandardCharsets.UTF_8);
            node.put(KEY_PREFIX + set.id() + ".name", set.name() == null ? "Untitled" : set.name());
            node.put(KEY_PREFIX + set.id() + ".description",
                    set.description() == null ? "" : set.description());
            node.put(KEY_PREFIX + set.id() + ".category", set.category().display());
            node.put(KEY_PREFIX + set.id() + ".file", set.fileName());
            node.sync();
            return true;
        } catch (Exception e) {
            LOG.warn("Could not persist instruction set {}", set.id(), e);
            return false;
        }
    }

    /**
     * Imports a {@code SKILL.md}-form file: the payload is copied in unchanged (it is already
     * the portable form) and registered under the frontmatter's name and description — the
     * file's base name when the frontmatter carries none.
     *
     * @param source the file to import
     * @return the registered set, or empty when the copy or registration fails
     */
    public Optional<InstructionSet> importFile(Path source) {
        try {
            String raw = Files.readString(source, StandardCharsets.UTF_8);
            Frontmatter parsed = parseFrontmatter(raw);
            String name = parsed.name() != null ? parsed.name()
                    : source.getFileName().toString().replaceFirst("\\.md$", "");
            String id = UUID.randomUUID().toString();
            InstructionSet set = new InstructionSet(id, name,
                    parsed.description() == null ? "" : parsed.description(),
                    parsed.category(),
                    "instruction-set-" + id + ".md");
            Optional<Path> dir = node.directory();
            if (dir.isEmpty()) {
                return Optional.empty();
            }
            Files.createDirectories(dir.get());
            Files.copy(source, dir.get().resolve(set.fileName()), StandardCopyOption.REPLACE_EXISTING);
            node.put(KEY_PREFIX + id + ".name", set.name());
            node.put(KEY_PREFIX + id + ".description", set.description());
            node.put(KEY_PREFIX + id + ".category", set.category().display());
            node.put(KEY_PREFIX + id + ".file", set.fileName());
            node.sync();
            return Optional.of(set);
        } catch (Exception e) {
            LOG.warn("Could not import instruction set from {}", source, e);
            return Optional.empty();
        }
    }

    /**
     * The set's parsed document — frontmatter surface and body — from its payload file.
     *
     * @param set the registered set
     * @return the parsed document, or empty when the payload cannot be read
     */
    public Optional<Frontmatter> read(InstructionSet set) {
        try {
            Optional<Path> dir = node.directory();
            if (dir.isEmpty()) {
                return Optional.empty();
            }
            Path file = dir.get().resolve(set.fileName());
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(parseFrontmatter(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            LOG.warn("Could not read instruction set {}", set.id(), e);
            return Optional.empty();
        }
    }

    /**
     * Parses the portable form: a leading {@code ---} frontmatter block carrying {@code name:}
     * and {@code description:} (quotes tolerated), then the body. A document without frontmatter
     * is all body.
     *
     * @param markdown the document text; {@code null} is treated as empty
     * @return the parsed document (never {@code null})
     */
    public static Frontmatter parseFrontmatter(String markdown) {
        String text = markdown == null ? "" : markdown;
        String[] lines = text.split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        if (first >= lines.length || !lines[first].strip().equals("---")) {
            return new Frontmatter(null, null, InstructionCategory.GENERAL, text);
        }
        String name = null;
        String description = null;
        String category = null;
        int i = first + 1;
        for (; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.equals("---")) {
                i++;
                break;
            }
            if (line.startsWith("name:")) {
                name = unquote(line.substring("name:".length()));
            } else if (line.startsWith("description:")) {
                description = unquote(line.substring("description:".length()));
            } else if (line.startsWith("category:")) {
                category = unquote(line.substring("category:".length()));
            }
        }
        StringBuilder body = new StringBuilder();
        for (; i < lines.length; i++) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return new Frontmatter(name, description, InstructionCategory.parse(category),
                body.toString().stripLeading());
    }

    private static String unquote(String value) {
        String stripped = value.strip();
        if (stripped.length() >= 2
                && ((stripped.startsWith("\"") && stripped.endsWith("\""))
                        || (stripped.startsWith("'") && stripped.endsWith("'")))) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    /**
     * Composes the portable form: frontmatter over body.
     *
     * @param name        the title; {@code null} becomes {@code Untitled}
     * @param description the description; {@code null} becomes empty
     * @param body        the Markdown body; {@code null} becomes empty
     * @return the document text (never {@code null})
     */
    public static String withFrontmatter(String name, String description,
                                         InstructionCategory category, String body) {
        return "---\n"
                + "name: " + (name == null ? "Untitled" : name) + "\n"
                + "description: " + (description == null ? "" : description) + "\n"
                + "category: " + (category == null ? InstructionCategory.GENERAL : category).display() + "\n"
                + "---\n\n"
                + (body == null ? "" : body);
    }

    /**
     * The seed for a fresh skill-shaped document — the {@code SKILL.md} scaffold a "New skill…"
     * invocation opens on; a system-prompt invocation seeds from the card's active layer
     * instead. Seeding is invocation context, never an editor mode.
     *
     * @param name the new document's working title
     * @return the scaffold text
     */
    public static String skillScaffold(String name) {
        return """
                Use this skill when …

                ## Instructions

                - …
                """;
    }
}
