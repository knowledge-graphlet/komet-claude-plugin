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
package network.ike.komet.claude.stamp.print;

import design.knowledge.stamp.adoc.dialect.Block;
import design.knowledge.stamp.adoc.dialect.Document;
import design.knowledge.stamp.adoc.dialect.IkeDialectParser;
import dev.ikm.tinkar.common.id.PublicIds;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredBlock;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredDocument;
import network.ike.komet.claude.stamp.GitDocumentMaterializer.StoredLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A {@link StoredDocument} built from AsciiDoc text the way the materializer stores one: the
 * dialect parser's blocks with their own lines and child blocks as members in document
 * order, every line a {@link StoredLine} with no STAMP. Line nids are the 1-based line
 * numbers; block nids count up from one million.
 */
final class StoredFixture {

    private long nextBlock = 1_000_000;

    private StoredFixture() { }

    static StoredDocument parse(String text) {
        return new StoredFixture().build(IkeDialectParser.parse(text));
    }

    private StoredDocument build(Document parsed) {
        List<StoredLine> lines = new ArrayList<>();
        for (int i = 0; i < parsed.lines().size(); i++) {
            lines.add(new StoredLine(i + 1, parsed.lines().get(i), null, null, List.of()));
        }
        Map<Long, StoredBlock> blocks = new LinkedHashMap<>();
        StoredBlock root = store(parsed.root(), blocks);
        List<String> attributes = new ArrayList<>();
        attributes.add(parsed.title() == null ? "" : parsed.title());
        return new StoredDocument(PublicIds.of(UUID.randomUUID()), "0000000", lines, 1, attributes, root, blocks, Map.of());
    }

    private StoredBlock store(Block block, Map<Long, StoredBlock> blocks) {
        long nid = nextBlock++;
        List<long[]> members = new ArrayList<>();
        for (int line : block.lines()) {
            members.add(new long[] {line, line});
        }
        for (Block child : block.children()) {
            StoredBlock stored = store(child, blocks);
            members.add(new long[] {child.firstLine(), stored.nid()});
        }
        members.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Long> memberNids = members.stream().map(m -> m[1]).toList();
        StoredBlock stored = new StoredBlock(nid, block.kind(), block.level(), block.title() == null ? "" : block.title(),
                block.parameters() == null ? "" : block.parameters(), memberNids, null);
        blocks.put(nid, stored);
        return stored;
    }
}
