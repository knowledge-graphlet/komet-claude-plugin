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
package network.ike.komet.claude.narrator;

import dev.ikm.tinkar.common.service.LifecyclePhase;
import dev.ikm.tinkar.common.service.ServiceLifecycle;
import dev.ikm.tinkar.common.service.ServiceLifecyclePhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts the {@link CommitNarrator} headlessly at application startup, after the datastore is
 * loaded, via the {@link ServiceLifecycle} SPI (discovered cross-layer by the lifecycle manager
 * during {@code PrimitiveData.start()}). Runs whether or not any assistant window is ever opened.
 * <p>
 * Startup order matters: the narrator identity concepts are seeded <em>before</em> subscribing, so
 * the seed commits are not themselves delivered to (and narrated by) the narrator. The whole
 * service is skipped when no Anthropic API key is configured ({@link #shouldActivate()}), so a
 * Komet install that never uses Claude incurs no narrator subscription and no graph mutation.
 */
@LifecyclePhase(value = ServiceLifecyclePhase.APPLICATION_SERVICES, subPriority = 50)
public class CommitNarratorLifecycle implements ServiceLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(CommitNarratorLifecycle.class);

    private CommitNarrator narrator;

    public CommitNarratorLifecycle() {}

    @Override
    public boolean shouldActivate() {
        return CommitNarrator.isConfigured();
    }

    @Override
    public void startup() {
        // ServiceLifecycle contract: a thrown exception aborts application startup. Narration is
        // non-essential, so isolate any failure here and leave Komet otherwise fully functional.
        try {
            NarratorIdentity.seedIfAbsent();
            narrator = new CommitNarrator();
            narrator.subscribe();
            LOG.info("Commit narrator started (subscribed to COMMIT_TOPIC)");
        } catch (Throwable t) {
            LOG.error("Commit narrator failed to start; commit narration disabled", t);
        }
    }

    @Override
    public void shutdown() {
        if (narrator != null) {
            narrator.shutdown();
        }
    }
}
