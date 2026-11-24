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
package network.ike.komet.claude.storage;

import dev.ikm.komet.kview.storage.StorageGestures;
import dev.ikm.komet.layout.storage.KlStorageOffer;
import dev.ikm.komet.terms.KometConcept;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.entity.scope.ScopeOperations;
import dev.ikm.tinkar.terms.ConceptFacade;
import network.ike.komet.claude.anthropic.AnthropicTool;
import network.ike.komet.document.DocumentStorage;
import network.ike.komet.document.GitDocumentMaterializer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The assistant's one tool for the actions on whole groups of data: it offers one, and the person
 * performs it (IKE-Network/ike-issues#1313; step 4 of {@code plan-2026-11-22-remove-export-import-data}).
 * Only a person removes, shares, exports or imports such data, until the policy on patient data in
 * data kept only in a knowledge base is settled (KEC, 2026-11-22, decision 7). An offer is a button
 * in the conversation; selecting it makes the gesture the person would make themselves, with the
 * question that gesture asks.
 */
public final class StorageOfferTools {

    private StorageOfferTools() {
    }

    /** The conversation the tool acts for. */
    public interface Host {

        /** A host with no conversation. */
        Host NONE = new Host() {
            @Override
            public void offer(KlStorageOffer offer) {
                throw new IllegalStateException("there is no conversation to make an offer in");
            }

            @Override
            public KlStorageOffer.Perform export() {
                throw new IllegalStateException("there is no conversation to export");
            }
        };

        /**
         * Shows an offer to the person.
         *
         * @param offer the offer
         */
        void offer(KlStorageOffer offer);

        /** @return the gesture that exports the conversation, as the person would make it */
        KlStorageOffer.Perform export();
    }

    /**
     * Makes the tool.
     *
     * @param host the conversation it acts for
     * @return the tool
     */
    public static AnthropicTool offerTool(Host host) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("action", Map.of("type", "string", "enum", List.of("remove_from_knowledge_base", "share_document", "make_document_local",
                "delete_document", "export_conversation", "import_from_file"), "description", "What to offer."));
        properties.put("documents", Map.of("type", "array", "items", Map.of("type", "string"), "description",
                "For an action on documents: each document's name or recorded path, as the document tools show it."));
        properties.put("file", Map.of("type", "string", "description", "For import_from_file: the full path of the file the person named."));
        return new Offer("""
                Offer the person an action on whole documents or conversations: remove_from_knowledge_base (a document \
                saved in a file leaves the knowledge base; its file stays), share_document (everyone who syncs the \
                knowledge base gets it, with the documents it includes), make_document_local (a shared one is taken \
                away from everyone else), delete_document (a shared one is deleted for everyone, or one kept only in \
                this knowledge base), export_conversation (this conversation, to a file another knowledge base can \
                import) or import_from_file (a file Komet's Export wrote). Call it only when the person asked for that. \
                You never perform these: the person sees a button above the message box and selects it, or not. \
                Nothing has been done when this returns.""",
                Map.of("type", "object", "properties", properties, "required", List.of("action")), host);
    }

    private record Offer(String description, Map<String, Object> inputSchema, Host host) implements AnthropicTool {

        @Override
        public String name() {
            return "offer_storage_action";
        }

        @Override
        public String execute(Map<String, Object> input) {
            try {
                KlStorageOffer offer = switch (String.valueOf(input.get("action"))) {
                    case "remove_from_knowledge_base" -> documents(KometConcept.REMOVE_FROM_KNOWLEDGE_BASE, input);
                    case "share_document" -> documents(KometConcept.SHARE_DOCUMENT, input);
                    case "make_document_local" -> documents(KometConcept.MAKE_DOCUMENT_LOCAL, input);
                    case "delete_document" -> documents(KometConcept.DELETE_DOCUMENT, input);
                    case "export_conversation" -> export();
                    case "import_from_file" -> importFile(input);
                    default -> throw new IllegalArgumentException("no such action: " + input.get("action"));
                };
                host.offer(offer);
                return "Offered: " + offer.label() + " The person sees it as a button above the message box. Nothing has been done, and"
                        + " nothing is until they select it; tell them what it would do.";
            } catch (RuntimeException e) {
                return "Not offered: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }

        private static KlStorageOffer documents(ConceptFacade action, Map<String, Object> input) {
            if (!(input.get("documents") instanceof List<?> names) || names.isEmpty()) {
                throw new IllegalArgumentException("documents is required: the documents' names");
            }
            Set<PublicId> anchors = new LinkedHashSet<>();
            for (Object name : names) {
                anchors.add(find(String.valueOf(name).strip()));
            }
            return DocumentStorage.offer(action, List.copyOf(anchors));
        }

        /** The stored document of a name or a recorded path. */
        private static PublicId find(String wanted) {
            PublicId named = GitDocumentMaterializer.storedDocuments().get(wanted);
            if (named != null) {
                return named;
            }
            List<PublicId> found = new ArrayList<>();
            GitDocumentMaterializer.storedDocumentsByPath().forEach((path, anchors) -> {
                if (path.equals(wanted) || path.endsWith("/" + wanted)) {
                    found.addAll(anchors);
                }
            });
            if (found.isEmpty()) {
                throw new IllegalArgumentException("the knowledge base has no document named " + wanted);
            }
            if (found.size() > 1) {
                throw new IllegalArgumentException("more than one document is named " + wanted + "; give its whole path");
            }
            return found.getFirst();
        }

        private KlStorageOffer export() {
            return new KlStorageOffer("assistantOfferExport", "Export this conversation…", "Write this conversation to a file that another knowledge base can import",
                    host.export());
        }

        private static KlStorageOffer importFile(Map<String, Object> input) {
            if (!(input.get("file") instanceof String location) || location.isBlank()) {
                throw new IllegalArgumentException("file is required: the path of the file the person named");
            }
            Path file = Path.of(location.strip());
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("there is no file " + file);
            }
            if (!ScopeOperations.exportedScope(file.toFile())) {
                throw new IllegalArgumentException(file.getFileName() + " is not a file that Komet's Export wrote");
            }
            return new KlStorageOffer("assistantOfferImport", "Import " + file.getFileName() + "…", "Read this file into the knowledge base, after checking it",
                    (node, edit, done) -> StorageGestures.importFile(node, file.toFile(), edit, done));
        }
    }
}
