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

import dev.ikm.komet.layout.trash.KlTrash;
import dev.ikm.komet.layout.trash.KlTrashItem;
import dev.ikm.komet.layout.trash.KlTrashProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The plugin's part of Komet's Trash: the conversations and instruction sets its cards moved to
 * {@link PluginTrash}, listed, put back and deleted through the journal's Trash.
 */
public final class PluginTrashProvider implements KlTrashProvider {

    /** Creates the provider; called by the service loader. */
    public PluginTrashProvider() {
    }

    @Override
    public String sourceName() {
        return "IKE plugin";
    }

    @Override
    public List<KlTrashItem> items() {
        return PluginTrash.entries().stream()
                .map(entry -> new KlTrashItem(this, entry.id(), entry.kind().word(), entry.name(), entry.trashedAt()))
                .toList();
    }

    @Override
    public void putBack(List<KlTrashItem> items) {
        entriesOf(items).forEach(PluginTrash::putBack);
    }

    @Override
    public KlTrash.Deleted delete(List<KlTrashItem> items) {
        PluginTrash.Emptied emptied = PluginTrash.delete(entriesOf(items));
        return new KlTrash.Deleted(emptied.deleted(), emptied.problems());
    }

    @Override
    public void onChange(Runnable listener) {
        PluginTrash.onChange(listener);
    }

    @Override
    public void stopListening(Runnable listener) {
        PluginTrash.stopListening(listener);
    }

    private static List<PluginTrash.Entry> entriesOf(List<KlTrashItem> items) {
        Map<String, PluginTrash.Entry> byId = PluginTrash.entries().stream()
                .collect(Collectors.toMap(PluginTrash.Entry::id, Function.identity(), (a, b) -> a));
        return items.stream().map(item -> byId.get(item.id())).filter(java.util.Objects::nonNull).toList();
    }
}
