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

import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What changed in a paired document since the assistant last read it, a line per block
 * (ike-lab-documents topic {@code dev-decision-document}, "What the person changed"): the person
 * edits between turns, so a request says which blocks to read again rather than leave the
 * assistant reasoning from its last version. A snapshot is taken at the end of each of the
 * assistant's turns, so whatever differs from it is someone else's; a line's STAMP cannot say
 * whose, since a shared pending edit rewrites a line in place under its first author's.
 */
public final class DocumentChanges {

    private DocumentChanges() {
    }

    /**
     * What a document says now, block by block.
     *
     * @param document the document, its pending edit included
     * @return each block's text, by the block's nid
     */
    public static Map<Long, String> snapshot(StoredDocument document) {
        Map<Long, String> texts = new HashMap<>();
        for (DocumentOutline.Node node : new DocumentOutline(document).nodes()) {
            if (!node.lines().isEmpty()) {
                texts.put(node.block().nid(), text(node));
            }
        }
        return texts;
    }

    /**
     * The blocks that changed since a snapshot.
     *
     * @param seen     the snapshot
     * @param document the document now
     * @return a line per block changed, added or removed, in document order, removed ones last
     */
    public static List<String> since(Map<Long, String> seen, StoredDocument document) {
        List<String> lines = new ArrayList<>();
        Map<Long, String> remaining = new HashMap<>(seen);
        for (DocumentOutline.Node node : new DocumentOutline(document).nodes()) {
            if (node.lines().isEmpty()) {
                continue;
            }
            String before = remaining.remove(node.block().nid());
            if (before == null || !before.equals(text(node))) {
                lines.add("§" + node.path() + " " + (before == null ? "added" : "changed") + " since your last turn, not by you");
            }
        }
        if (!remaining.isEmpty()) {
            lines.add(remaining.size() + (remaining.size() == 1 ? " block" : " blocks") + " removed");
        }
        return lines;
    }

    private static String text(DocumentOutline.Node node) {
        return node.lines().stream().map(StoredLine::text).collect(Collectors.joining("\n"));
    }
}
