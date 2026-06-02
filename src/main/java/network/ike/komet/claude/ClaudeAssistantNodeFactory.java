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

import dev.ikm.komet.framework.KometNode;
import dev.ikm.komet.framework.KometNodeFactory;
import dev.ikm.komet.framework.activity.ActivityStream;
import dev.ikm.komet.framework.activity.ActivityStreamOption;
import dev.ikm.komet.framework.preferences.Reconstructor;
import dev.ikm.komet.framework.view.ObservableViewNoOverride;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.tinkar.common.id.PublicIdStringKey;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.ImmutableList;

/**
 * Contributes the {@link ClaudeAssistantNode} panel to Komet as a
 * {@link KometNodeFactory} service. Discovered via the {@code provides} clause
 * in {@code module-info}; ServiceLoader instantiates it through {@link #provider()}.
 *
 * <p>The assistant is not tied to an activity stream — it is driven entirely by
 * its own chat box — so it offers no stream choices.
 */
public final class ClaudeAssistantNodeFactory implements KometNodeFactory {

    protected static final String STYLE_ID = ClaudeAssistantNode.STYLE_ID;
    protected static final String TITLE = ClaudeAssistantNode.TITLE;

    /** ServiceLoader provider method (see {@code provides ... with} in module-info). */
    public static ClaudeAssistantNodeFactory provider() {
        return new ClaudeAssistantNodeFactory();
    }

    private ClaudeAssistantNodeFactory() {
        super();
    }

    @Override
    public void addDefaultNodePreferences(KometPreferences nodePreferences) {
        // No node-scoped defaults; configuration lives in user preferences.
    }

    @Override
    public ImmutableList<PublicIdStringKey<ActivityStream>> defaultActivityStreamChoices() {
        return Lists.immutable.empty();
    }

    @Override
    public ImmutableList<PublicIdStringKey<ActivityStreamOption>> defaultOptionsForActivityStream(
            PublicIdStringKey<ActivityStream> streamKey) {
        return Lists.immutable.empty();
    }

    @Reconstructor
    public static ClaudeAssistantNode reconstructor(ObservableViewNoOverride windowView,
                                                    KometPreferences nodePreferences) {
        return new ClaudeAssistantNode(
                windowView.makeOverridableViewProperties("ClaudeAssistantNodeFactory.reconstructor"),
                nodePreferences);
    }

    @Override
    public KometNode create(ObservableViewNoOverride windowView, KometPreferences nodePreferences) {
        return reconstructor(windowView, nodePreferences);
    }

    @Override
    public String getMenuText() {
        return TITLE;
    }

    @Override
    public String getStyleId() {
        return STYLE_ID;
    }

    @Override
    public Class<? extends KometNode> kometNodeClass() {
        return ClaudeAssistantNode.class;
    }
}
