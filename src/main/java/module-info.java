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

/**
 * Komet Claude Plugin — an in-Komet assistant panel that drives a Claude
 * dialog whose read-only tools execute in-process over the live knowledge
 * graph.
 *
 * <p>Skeleton iteration: declares the Komet framework dependency and the
 * module boundary. The next iteration adds
 * {@code provides dev.ikm.komet.framework.KometNodeFactory with ...} and the
 * panel, the hand-rolled Anthropic client, and the read-only graph tools.
 */
module komet.claude {
    requires dev.ikm.komet.framework;
    requires dev.ikm.tinkar.entity;
    requires dev.ikm.tinkar.common;
    requires dev.ikm.tinkar.provider.search;
    requires java.net.http;

    // Vendored json4j references java.beans.Introspector (java.desktop) and
    // java.sql.Timestamp (java.sql) in serializer code paths we don't exercise
    // (we use records + Maps only). Candidate to trim so these can be dropped.
    requires java.desktop;
    requires java.sql;

    exports network.ike.komet.claude;
}
