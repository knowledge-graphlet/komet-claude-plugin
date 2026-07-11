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
package network.ike.komet.claude.doc.print.settings;

import dev.ikm.komet.layout.preferences.PropertyWithDefault;

/**
 * The {@link dev.ikm.komet.preferences.KometPreferences} keys under which a
 * {@link network.ike.komet.claude.doc.DocumentSurface}'s settings are persisted. Each constant is
 * simultaneously the preference key (an {@link Enum}), the default-value holder
 * ({@link PropertyWithDefault#defaultValue()}), and — via {@code ClassConceptBinding} — a
 * {@code publicId()}-bearing concept binding. Paired with its settings value type through
 * {@link dev.ikm.komet.layout.settings.ControlSettings}, it is the whole storage contract; there are
 * no string preference keys.
 *
 * <p>The constant name is part of the persisted key string, so it must not be renamed without a
 * migration.
 */
public enum DocumentSurfaceSettingKeys implements PropertyWithDefault {

    /** The paged print settings, stored whole as a {@link PrintSettings} value. */
    PRINT_SETTINGS(PrintSettings.DEFAULT);

    private final Object defaultValue;

    DocumentSurfaceSettingKeys(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    /**
     * The default value persisted under this key when nothing has been stored yet.
     *
     * @return the key's default settings value
     */
    @Override
    public Object defaultValue() {
        return defaultValue;
    }
}
