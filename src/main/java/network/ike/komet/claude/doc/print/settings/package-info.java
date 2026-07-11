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
 * The persisted settings model for the paged {@link network.ike.komet.claude.doc.DocumentSurface}
 * print rendering, and the first consumer of the reusable per-control settings mechanism in
 * {@link dev.ikm.komet.layout.settings}.
 *
 * <p>{@link network.ike.komet.claude.doc.print.settings.PrintSettings} is the whole
 * {@link dev.ikm.tinkar.common.binary.Encodable} value; every field is a concept-identity value
 * enum ({@code PageSize}, {@code PageOrientation}, {@code MarginPreset}, {@code DocumentTheme},
 * {@code FurnitureVisibility}, {@code PageNumberPlacement}) with a pinned, freeze-tested
 * {@code publicId()} — no string constants. {@link
 * network.ike.komet.claude.doc.print.settings.DocumentSurfaceSettingKeys} is the
 * {@link dev.ikm.komet.layout.preferences.PropertyWithDefault} preference key that carries the
 * default and the settings' own concept identity.
 */
package network.ike.komet.claude.doc.print.settings;
