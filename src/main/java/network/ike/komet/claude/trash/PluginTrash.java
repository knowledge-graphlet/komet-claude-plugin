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
package network.ike.komet.claude.trash;

import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import network.ike.komet.claude.stamp.DocumentScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.BackingStoreException;

/**
 * The plugin's Trash (KEC 2026-10-23): what a person deletes from an assistant card or an
 * Instruction Editor card is moved here first. A trashed item is hidden where it lived and
 * listed here, so it can be put back; it is deleted for good when the Trash is emptied, at once
 * or, when the person keeps the preference on, as Komet quits. Komet's journal shows it among
 * everything in its Trash, through {@link PluginTrashProvider}.
 *
 * <p>The Trash is a registry in the open store's configuration preferences, beside the cards it
 * serves, so it holds what each card needs to finish the deletion after the card is gone: where
 * the item is registered in its card's preferences, its payload file, and for a conversation its
 * journal in the knowledge base. Deleting a conversation deletes its journal's scope, the
 * instance data that recorded every exchange.
 */
public final class PluginTrash {

    private static final Logger LOG = LoggerFactory.getLogger(PluginTrash.class);

    private static final String REGISTRY = "network-ike-komet-claude-trash";
    private static final String KEY_KIND = "kind";
    private static final String KEY_NAME = "name";
    private static final String KEY_TRASHED_AT = "trashed-at";
    private static final String KEY_NODE = "node";
    private static final String KEY_FLAGS = "flags";
    private static final String KEY_PURGE_NODE = "purge-node";
    private static final String KEY_PURGE_PREFIX = "purge-prefix";
    private static final String KEY_FILE = "file";
    private static final String KEY_JOURNAL = "journal-anchor";

    /** What a trashed item is. */
    public enum Kind {
        /** An assistant card's conversation, with its journal in the knowledge base. */
        CONVERSATION("Conversation"),
        /** An Instruction Editor card's instruction set. */
        INSTRUCTION_SET("Instruction set");

        private final String word;

        Kind(String word) {
            this.word = word;
        }

        /** @return the kind in words, as the Trash lists it */
        public String word() {
            return word;
        }
    }

    /**
     * One item in the Trash.
     *
     * @param id            the item's identity (a conversation's or a set's id)
     * @param kind          what it is
     * @param name          its name, as the Trash lists it
     * @param trashedAt     when it was moved to the Trash, epoch milliseconds (0 when unknown)
     * @param node          the preferences node holding its registration, relative to the store's configuration preferences
     * @param flags         the keys on that node marking it trashed, removed when it is put back
     * @param purgeNode     whether deleting it removes the node itself (a conversation's index entry)
     * @param purgePrefix   otherwise, the prefix of the node's keys that deleting it removes (a set's registration)
     * @param file          its payload file, absolute, or empty
     * @param journalAnchor its journal's anchor in the knowledge base, a UUID, or empty
     */
    public record Entry(String id, Kind kind, String name, long trashedAt, String node, List<String> flags,
                        boolean purgeNode, String purgePrefix, String file, String journalAnchor) {

        public Entry {
            flags = List.copyOf(flags);
            purgePrefix = purgePrefix == null ? "" : purgePrefix;
            file = file == null ? "" : file;
            journalAnchor = journalAnchor == null ? "" : journalAnchor;
        }
    }

    /**
     * What emptying did.
     *
     * @param deleted  how many items were deleted
     * @param problems what could not be done, one line each; the items stay in the Trash
     */
    public record Emptied(int deleted, List<String> problems) {
    }

    private static final List<Consumer<Entry>> PUT_BACK = new CopyOnWriteArrayList<>();
    private static final List<Runnable> CHANGED = new CopyOnWriteArrayList<>();

    private PluginTrash() {
    }

    /**
     * Listens for items put back, so an open card can show one again.
     *
     * @param listener called on the JavaFX thread or the caller's, with the item put back
     */
    public static void onPutBack(Consumer<Entry> listener) {
        PUT_BACK.add(listener);
    }

    /**
     * Listens for any change to the Trash's contents, so a Trash button can show its count.
     *
     * @param listener called after each change
     */
    public static void onChange(Runnable listener) {
        CHANGED.add(listener);
    }

    /**
     * Stops listening.
     *
     * @param listener a listener given to {@link #onPutBack} or {@link #onChange}
     */
    public static void stopListening(Object listener) {
        PUT_BACK.remove(listener);
        CHANGED.remove(listener);
    }

    /**
     * Records an item as in the Trash. The card has already hidden it, with the flags the entry
     * names.
     *
     * @param entry the item
     */
    public static void moveToTrash(Entry entry) {
        KometPreferences item = registry().node(entry.id());
        item.put(KEY_KIND, entry.kind().name());
        item.put(KEY_NAME, entry.name());
        item.putLong(KEY_TRASHED_AT, entry.trashedAt());
        item.put(KEY_NODE, entry.node());
        item.put(KEY_FLAGS, String.join(",", entry.flags()));
        item.putBoolean(KEY_PURGE_NODE, entry.purgeNode());
        item.put(KEY_PURGE_PREFIX, entry.purgePrefix());
        item.put(KEY_FILE, entry.file());
        item.put(KEY_JOURNAL, entry.journalAnchor());
        sync(registry());
        changed();
    }

    /** @return the items in the Trash, most recently trashed first */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        try {
            KometPreferences registry = registry();
            for (String id : registry.childrenNames()) {
                read(registry.node(id), id).ifPresent(entries::add);
            }
        } catch (BackingStoreException | RuntimeException e) {
            LOG.warn("Could not read the Trash", e);
        }
        entries.sort(Comparator.comparingLong(Entry::trashedAt).reversed().thenComparing(Entry::name));
        return entries;
    }

    /**
     * @param id an item's identity
     * @return whether the item is in the Trash
     */
    public static boolean contains(String id) {
        try {
            return registry().nodeExists(id);
        } catch (BackingStoreException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Takes an item out of the Trash and shows it again where it lived.
     *
     * @param entry the item
     */
    public static void putBack(Entry entry) {
        resolve(entry.node()).ifPresent(node -> {
            for (String flag : entry.flags()) {
                node.remove(flag);
            }
            sync(node);
        });
        unregister(entry.id());
        for (Consumer<Entry> listener : PUT_BACK) {
            try {
                listener.accept(entry);
            } catch (RuntimeException e) {
                LOG.warn("A put-back listener failed for {}", entry.id(), e);
            }
        }
        changed();
    }

    /**
     * Deletes items for good: each one's file, its registration, and a conversation's journal.
     * An item whose deletion fails stays in the Trash, named in the result.
     *
     * @param entries the items
     * @return what was done
     */
    public static Emptied delete(Collection<Entry> entries) {
        int deleted = 0;
        List<String> problems = new ArrayList<>();
        for (Entry entry : entries) {
            Optional<String> problem = purge(entry);
            if (problem.isEmpty()) {
                deleted++;
            } else {
                problems.add(entry.kind().word() + " “" + entry.name() + "”: " + problem.get());
            }
        }
        if (!entries.isEmpty()) {
            changed();
        }
        return new Emptied(deleted, List.copyOf(problems));
    }

    /**
     * Empties the Trash.
     *
     * @return what was done
     */
    public static Emptied empty() {
        return delete(entries());
    }

    /**
     * The path of a card's preferences node relative to the store's configuration preferences,
     * as an entry records it.
     *
     * @param node the node
     * @return its relative path
     */
    public static String relativePath(KometPreferences node) {
        String root = PreferencesService.configurationPreferences().absolutePath();
        String path = node.absolutePath();
        if (!"/".equals(root) && path.startsWith(root)) {
            path = path.substring(root.length());
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Deletes a file and a journal, the parts of an item that live outside the preferences.
     *
     * @param file          the payload file, or null
     * @param journalAnchor the journal's anchor, or null
     * @return why it could not, or empty when it did
     */
    static Optional<String> deletePayload(Path file, PublicId journalAnchor) {
        if (journalAnchor != null && PrimitiveData.running() && PrimitiveData.get().hasPublicId(journalAnchor)) {
            try {
                DocumentScopes.delete(journalAnchor);
            } catch (RuntimeException e) {
                return Optional.of("its journal in the knowledge base could not be deleted: " + message(e));
            }
        }
        if (file != null) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                return Optional.of("its file could not be deleted: " + message(e));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> purge(Entry entry) {
        PublicId anchor = null;
        if (!entry.journalAnchor().isBlank()) {
            try {
                anchor = PublicIds.of(UUID.fromString(entry.journalAnchor()));
            } catch (IllegalArgumentException e) {
                LOG.warn("Trash entry {} names a journal that is not a UUID: {}", entry.id(), entry.journalAnchor());
            }
        }
        Optional<String> problem = deletePayload(entry.file().isBlank() ? null : Path.of(entry.file()), anchor);
        if (problem.isPresent()) {
            return problem;
        }
        Optional<KometPreferences> node = resolve(entry.node());
        try {
            if (node.isPresent()) {
                if (entry.purgeNode()) {
                    KometPreferences parent = node.get().parent();
                    node.get().removeNode();
                    sync(parent);
                } else if (!entry.purgePrefix().isBlank()) {
                    for (String key : node.get().keys()) {
                        if (key.startsWith(entry.purgePrefix())) {
                            node.get().remove(key);
                        }
                    }
                    sync(node.get());
                }
            }
        } catch (BackingStoreException | RuntimeException e) {
            return Optional.of("its registration could not be removed: " + message(e));
        }
        unregister(entry.id());
        return Optional.empty();
    }

    private static Optional<Entry> read(KometPreferences item, String id) {
        try {
            Kind kind = Kind.valueOf(item.get(KEY_KIND, ""));
            String flags = item.get(KEY_FLAGS, "");
            return Optional.of(new Entry(id, kind, item.get(KEY_NAME, id), item.getLong(KEY_TRASHED_AT, 0L),
                    item.get(KEY_NODE, ""),
                    flags.isBlank() ? List.of() : Arrays.asList(flags.split(",")),
                    item.getBoolean(KEY_PURGE_NODE, false), item.get(KEY_PURGE_PREFIX, ""),
                    item.get(KEY_FILE, ""), item.get(KEY_JOURNAL, "")));
        } catch (IllegalArgumentException e) {
            LOG.warn("Ignoring an unreadable Trash entry {}", id);
            return Optional.empty();
        }
    }

    private static Optional<KometPreferences> resolve(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        try {
            KometPreferences root = PreferencesService.configurationPreferences();
            return root.nodeExists(path) ? Optional.of(root.node(path)) : Optional.empty();
        } catch (BackingStoreException | RuntimeException e) {
            LOG.warn("Could not find preferences node {}", path, e);
            return Optional.empty();
        }
    }

    private static void unregister(String id) {
        try {
            KometPreferences registry = registry();
            if (registry.nodeExists(id)) {
                registry.node(id).removeNode();
                sync(registry);
            }
        } catch (BackingStoreException | RuntimeException e) {
            LOG.warn("Could not remove Trash entry {}", id, e);
        }
    }

    private static KometPreferences registry() {
        return PreferencesService.configurationPreferences().node(REGISTRY);
    }

    private static void changed() {
        for (Runnable listener : CHANGED) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                LOG.warn("A Trash change listener failed", e);
            }
        }
    }

    private static void sync(KometPreferences node) {
        try {
            node.sync();
        } catch (BackingStoreException e) {
            LOG.warn("Could not save preferences node {}", node.absolutePath(), e);
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
