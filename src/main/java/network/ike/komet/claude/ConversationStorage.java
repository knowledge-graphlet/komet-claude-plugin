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
package network.ike.komet.claude;

import dev.ikm.komet.kview.storage.StorageGestures;
import dev.ikm.komet.layout.storage.KlStorage;
import dev.ikm.komet.layout.storage.KlStorageOffer;
import dev.ikm.komet.layout.storage.KlStorageProvider;
import dev.ikm.komet.layout.storage.KlStoredGroup;
import dev.ikm.komet.preferences.JournalWindowSettings;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.PreferencesService;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.PatternEntity;
import dev.ikm.tinkar.entity.boundary.DataKind;
import dev.ikm.tinkar.entity.scope.Scope;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import javafx.application.Platform;
import network.ike.foundation.ike.bindings.IkePattern;
import network.ike.komet.claude.json.Json;
import network.ike.komet.claude.trash.PluginTrash;
import network.ike.komet.document.ComponentText;
import network.ike.komet.document.surface.JournalStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;

/**
 * The conversations with the assistant as groups of local data (IKE-Network/ike-issues#1313; steps 2
 * and 3 of {@code plan-2026-11-22-remove-export-import-data}). A conversation is two things: its
 * journal in the knowledge base, which records every exchange, and a file in an assistant card's
 * preferences folder, with the titles of its exchanges and the messages the assistant is sent. An
 * export has both. An import gives the conversation to an open assistant card, or keeps it for the
 * next one opened.
 */
public final class ConversationStorage implements KlStorageProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationStorage.class);

    /** The name of a conversation's own file in an export. */
    static final String PAYLOAD = "conversation.json";

    /** The preferences node of imported conversations no card has taken in yet. */
    private static final String WAITING_NODE = "network-ike-komet-claude-imported";
    private static final int MAX_DEPTH = 8;

    /** Creates the provider; called by the service loader. */
    public ConversationStorage() {
    }

    /**
     * A conversation an assistant card's index lists.
     *
     * @param card    the card's preferences node
     * @param id      the conversation's id
     * @param title   its title, as the card's list shows it
     * @param anchor  its journal's anchor, or {@code null} when nothing of it is in the knowledge base
     * @param payload its file, or {@code null} when the knowledge base keeps no preferences folder
     * @param place   the card and the journal it is in, in words
     * @param trashed whether it is in the Trash
     */
    record Indexed(KometPreferences card, String id, String title, PublicId anchor, Path payload, String place, boolean trashed) { }

    /**
     * An imported conversation: its file's contents.
     *
     * @param id    the conversation's id
     * @param title its title, as a card's list will show it
     * @param json  its file's contents
     */
    record Waiting(String id, String title, String json) { }

    // ── what is stored ───────────────────────────────────────────────────────

    @Override
    public String sourceName() {
        return "Conversations";
    }

    @Override
    public List<KlStoredGroup> groups() {
        List<KlStoredGroup> groups = new ArrayList<>();
        for (Indexed conversation : indexed()) {
            if (conversation.anchor() == null || !PrimitiveData.get().hasPublicId(conversation.anchor())
                    || EntityHandle.get(PrimitiveData.nid(conversation.anchor())).entity().isEmpty()) {
                continue;                                        // nothing of it is in the knowledge base
            }
            List<KlStorageOffer> offers = new ArrayList<>();
            offers.add(export(conversation.anchor(), conversation.title()));
            if (!conversation.trashed()) {
                offers.add(new KlStorageOffer("storageTrash", "Move to Trash", "Move this conversation to the Trash; Put Back in the Trash returns it",
                        (node, edit, done) -> {
                            boolean trashed = ClaudeCard.moveToTrash(conversation);
                            StorageGestures.report(node, "Move to Trash", trashed, trashed ? "Moved “" + conversation.title() + "” to the Trash."
                                    : "Could not move “" + conversation.title() + "” to the Trash.");
                            done.run();
                        }));
            }
            groups.add(new KlStoredGroup(this, KlStorage.rootOf(conversation.anchor()), DataKind.LOCAL, conversation.title(),
                    conversation.trashed() ? "In the Trash" : conversation.place(), true, offers));
        }
        for (Waiting conversation : waiting()) {
            ClaudeCard.ConversationDto dto = Json.parse(conversation.json(), ClaudeCard.ConversationDto.class);
            PublicId anchor = PublicIds.of(UUID.fromString(dto.journalAnchor()));
            if (PrimitiveData.get().hasPublicId(anchor) && EntityHandle.get(PrimitiveData.nid(anchor)).entity().isPresent()) {
                groups.add(new KlStoredGroup(this, KlStorage.rootOf(anchor), DataKind.LOCAL, conversation.title(),
                        "Imported; the next assistant card opened lists it", true, List.of(export(anchor, conversation.title()))));
            }
        }
        return groups;
    }

    private static KlStorageOffer export(PublicId anchor, String title) {
        return new KlStorageOffer("storageExport", "Export…", "Write this conversation to a file that another knowledge base can import",
                (node, edit, done) -> StorageGestures.export(node, anchor, title, edit, done));
    }

    /** A journal whose conversation is gone. */
    @Override
    public Optional<KlStoredGroup> describe(Scope leftover) {
        long root = leftover.roots().getFirst();
        Optional<PublicId> anchor = journal(PrimitiveData.publicId(root));
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        String title = ComponentText.name(Calculators.View.Default(), root);
        String uuid = anchor.get().asUuidArray()[0].toString();
        boolean trashed = PluginTrash.entries().stream().anyMatch(entry -> uuid.equals(entry.journalAnchor()));
        List<KlStorageOffer> offers = new ArrayList<>();
        offers.add(export(anchor.get(), title));
        if (!trashed) {
            offers.add(new KlStorageOffer("storageTrash", "Move to Trash", "Move this journal to the Trash; Put Back in the Trash returns it",
                    (node, edit, done) -> {
                        PluginTrash.moveToTrash(new PluginTrash.Entry("journal-" + uuid, PluginTrash.Kind.CONVERSATION, title,
                                System.currentTimeMillis(), "", List.of(), false, "", "", uuid));
                        StorageGestures.report(node, "Move to Trash", true, "Moved “" + title + "” to the Trash.");
                        done.run();
                    }));
        }
        return Optional.of(new KlStoredGroup(this, PrimitiveData.publicId(root), leftover.kind(), title,
                trashed ? "In the Trash" : "A conversation's journal; no assistant card has the conversation", false, offers));
    }

    /**
     * The journal a group is.
     *
     * @param root the group's root
     * @return its anchor, when it is a conversation's journal
     */
    private static Optional<PublicId> journal(PublicId root) {
        if (!PrimitiveData.get().hasPublicId(root)) {
            return Optional.empty();
        }
        long nid = PrimitiveData.nid(root);
        return switch (EntityHandle.get(nid).entity().orElse(null)) {
            case PatternEntity<?> _ -> {
                List<PublicId> found = new ArrayList<>();
                EntityService.get().forEachSemanticOfPattern(nid, element -> {
                    if (element.referencedComponentNid() == nid && JournalStore.isJournal(element.publicId())) {
                        found.add(element.publicId());
                    }
                });
                yield found.stream().findFirst();
            }
            case ConceptEntity<?> _ -> PrimitiveData.get().hasPublicId(IkePattern.JOURNAL_MANIFEST_PATTERN.publicId())
                    && EntityService.get().semanticsForComponentOfPattern(nid, IkePattern.JOURNAL_MANIFEST_PATTERN.nid()).findAny().isPresent()
                    ? Optional.of(root) : Optional.empty();
            case null, default -> Optional.empty();
        };
    }

    /** Every conversation the open knowledge base's assistant cards list, the trashed ones included. */
    static List<Indexed> indexed() {
        List<Indexed> found = new ArrayList<>();
        walk(PreferencesService.configurationPreferences(), "", 0, found);
        return found;
    }

    private static void walk(KometPreferences node, String journal, int depth, List<Indexed> found) {
        try {
            if (ClaudeCard.CARD_TYPE_VALUE.equals(node.get(ClaudeCard.CARD_TYPE_KEY, ""))) {
                read(node, journal, found);
                return;
            }
            if (depth == MAX_DEPTH || node.name().startsWith("network")) {
                return;                                          // a plugin's own registry, which has no cards
            }
            String title = node.get(JournalWindowSettings.JOURNAL_TITLE, journal);
            for (KometPreferences child : node.children()) {
                walk(child, title, depth + 1, found);
            }
        } catch (BackingStoreException | RuntimeException e) {
            LOG.warn("Could not read the conversations beneath {}", node.name(), e);
        }
    }

    private static void read(KometPreferences card, String journal, List<Indexed> found) throws BackingStoreException {
        if (!card.nodeExists(ClaudeCard.INDEX_NODE)) {
            return;
        }
        KometPreferences index = card.node(ClaudeCard.INDEX_NODE);
        String label = card.get(ClaudeCard.TILE_LABEL_KEY, "Assistant");
        String place = journal.isBlank() ? label : label + " · " + journal;
        for (String id : index.childrenNames()) {
            KometPreferences entry = index.node(id);
            String file = entry.get(ClaudeCard.KEY_FILE, "");
            if (!entry.get(ClaudeCard.KEY_MOVED_TO, "").isBlank() || file.isBlank()) {
                continue;
            }
            Path payload = card.directory().map(dir -> dir.resolve(file)).filter(Files::isRegularFile).orElse(null);
            String anchor = entry.get(ClaudeCard.KEY_ANCHOR, "");
            boolean trashed = !entry.get(ClaudeCard.KEY_TRASHED, "").isBlank() || "true".equals(entry.get(ClaudeCard.KEY_HIDDEN, ""));
            found.add(new Indexed(card, id, title(entry, payload), anchor.isBlank() ? null : PublicIds.of(UUID.fromString(anchor)),
                    payload, place, trashed));
        }
    }

    /** A conversation's title as its card lists it: its first exchange's, or its name. */
    private static String title(KometPreferences entry, Path payload) {
        String name = entry.get(ClaudeCard.KEY_NAME, "");
        if (payload != null) {
            try {
                ClaudeCard.ConversationDto dto = Json.parse(Files.readString(payload, StandardCharsets.UTF_8), ClaudeCard.ConversationDto.class);
                return title(dto, name);
            } catch (IOException | RuntimeException unread) {
                LOG.warn("Could not read {}", payload, unread);
            }
        }
        return name;
    }

    private static String title(ClaudeCard.ConversationDto dto, String name) {
        String first = dto.titles() == null ? null : dto.titles().get("0");
        return first != null ? first : dto.name() != null ? dto.name() : name;
    }

    // ── export ───────────────────────────────────────────────────────────────

    @Override
    public Map<String, byte[]> exportEntries(PublicId root) {
        for (Indexed conversation : indexed()) {
            if (conversation.anchor() != null && conversation.payload() != null && PrimitiveData.get().hasPublicId(conversation.anchor())
                    && PublicId.equals(KlStorage.rootOf(conversation.anchor()), root)) {
                try {
                    return Map.of(PAYLOAD, Files.readAllBytes(conversation.payload()));
                } catch (IOException e) {
                    LOG.warn("Could not read {}; the export has the journal alone", conversation.payload(), e);
                }
            }
        }
        return Map.of();
    }

    // ── import ───────────────────────────────────────────────────────────────

    @Override
    public Optional<String> imported(ScopeOperations.Imported imported, Map<String, byte[]> entries) {
        for (PublicId root : imported.roots()) {
            Optional<PublicId> anchor = journal(root);
            if (anchor.isPresent()) {
                Waiting conversation = conversation(anchor.get(),
                        ComponentText.name(Calculators.View.Default(), PrimitiveData.nid(root)), entries.get(PAYLOAD));
                takeIn(conversation);
                return Optional.of("the conversation “" + conversation.title() + "”");
            }
        }
        return Optional.empty();
    }

    /** The conversation of an imported journal: its own file when the export had one, else one made from the journal. */
    static Waiting conversation(PublicId anchor, String name, byte[] payload) {
        if (payload != null) {
            String json = new String(payload, StandardCharsets.UTF_8);
            try {
                ClaudeCard.ConversationDto dto = Json.parse(json, ClaudeCard.ConversationDto.class);
                if (dto.id() != null && UUID.fromString(dto.id()) != null && anchor.asUuidArray()[0].toString().equals(dto.journalAnchor())) {
                    return new Waiting(dto.id(), title(dto, name), json);
                }
                LOG.warn("The exported conversation's file is of another journal; it is made from the journal");
            } catch (RuntimeException unread) {
                LOG.warn("The exported conversation's file cannot be read; it is made from the journal", unread);
            }
        }
        List<Map<String, Object>> turns = new ArrayList<>();
        new JournalStore(Calculators.View::Default).load(anchor).ifPresent(records -> records.forEach(turn -> {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", turn.assistantAuthored() ? "assistant" : "user");
            message.put("content", turn.markdown());
            turns.add(message);
        }));
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", UUID.randomUUID().toString());
        dto.put("name", name);
        dto.put("turns", turns);
        dto.put("journalAnchor", anchor.asUuidArray()[0].toString());
        return new Waiting((String) dto.get("id"), name, Json.stringify(dto));
    }

    /**
     * Gives an imported conversation to the card that has it, when one does, and otherwise keeps it
     * for an assistant card to take in: an open one now, or the next one opened.
     */
    private static void takeIn(Waiting conversation) {
        Optional<Indexed> here = indexed().stream().filter(indexed -> indexed.id().equals(conversation.id()) && indexed.payload() != null).findFirst();
        if (here.isPresent()) {
            try {
                Files.writeString(here.get().payload(), conversation.json(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            // What was replaced may be in the Trash, which is emptied as Komet quits.
            PluginTrash.entries().stream().filter(entry -> entry.id().equals(conversation.id())).forEach(PluginTrash::putBack);
            Platform.runLater(() -> ClaudeCard.reload(conversation.id(), here.get().payload()));
            return;
        }
        keep(conversation);
        Platform.runLater(ClaudeCard::takeInImported);
    }

    /** Conversations waiting in a knowledge base that keeps no preferences folder. */
    private static final Map<String, Waiting> IN_MEMORY = new LinkedHashMap<>();

    private static synchronized void keep(Waiting conversation) {
        KometPreferences waiting = PreferencesService.configurationPreferences().node(WAITING_NODE);
        Optional<Path> dir = waiting.directory();
        if (dir.isEmpty()) {
            IN_MEMORY.put(conversation.id(), conversation);
            return;
        }
        try {
            Files.createDirectories(dir.get());
            Files.writeString(dir.get().resolve(conversation.id() + ".json"), conversation.json(), StandardCharsets.UTF_8);
            waiting.put(conversation.id(), conversation.title());
            waiting.sync();
        } catch (IOException | BackingStoreException e) {
            LOG.warn("Could not keep the imported conversation {} for an assistant card", conversation.id(), e);
            IN_MEMORY.put(conversation.id(), conversation);
        }
    }

    /** @return the imported conversations no card has taken in yet */
    private static synchronized List<Waiting> waiting() {
        List<Waiting> found = new ArrayList<>(IN_MEMORY.values());
        try {
            if (!PreferencesService.configurationPreferences().nodeExists(WAITING_NODE)) {
                return found;
            }
            KometPreferences waiting = PreferencesService.configurationPreferences().node(WAITING_NODE);
            Optional<Path> dir = waiting.directory();
            for (String id : dir.isEmpty() ? new String[0] : waiting.keys()) {
                Path file = dir.get().resolve(id + ".json");
                if (Files.isRegularFile(file)) {
                    found.add(new Waiting(id, waiting.get(id, ""), Files.readString(file, StandardCharsets.UTF_8)));
                }
            }
        } catch (IOException | BackingStoreException | RuntimeException e) {
            LOG.warn("Could not read the imported conversations", e);
        }
        return found;
    }

    /**
     * Takes the imported conversations that are waiting; each is given once.
     *
     * @return the conversations
     */
    static synchronized List<Waiting> takeWaiting() {
        List<Waiting> taken = waiting();
        IN_MEMORY.clear();
        try {
            if (PreferencesService.configurationPreferences().nodeExists(WAITING_NODE)) {
                KometPreferences waiting = PreferencesService.configurationPreferences().node(WAITING_NODE);
                for (Waiting conversation : taken) {
                    if (waiting.directory().isPresent()) {
                        Files.deleteIfExists(waiting.directory().get().resolve(conversation.id() + ".json"));
                    }
                    waiting.remove(conversation.id());
                }
                waiting.sync();
            }
        } catch (IOException | BackingStoreException | RuntimeException e) {
            LOG.warn("Could not let go of the imported conversations", e);
        }
        return taken;
    }
}
