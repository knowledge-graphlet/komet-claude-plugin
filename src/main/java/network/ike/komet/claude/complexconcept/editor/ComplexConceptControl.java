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
package network.ike.komet.claude.complexconcept.editor;

import dev.ikm.komet.kview.controls.KLDiTreeControl;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Skin;

import java.util.List;

/**
 * Edits a complex concept's specification as a tree: the DiTree control with the complex concept
 * adaptor layer (IKE-Network/ike-issues#1312). Its value is the specification's tree, the single
 * DiTree field of the complex concept pattern; its skin, {@link ComplexConceptControlSkin}, extends
 * the axiom control's editing skin, so the expansions and their constituents render as the axiom
 * control's own rows, the definition is the axiom control itself, each prerequisite is the ANF
 * statement control, and structural move and undo come with it.
 *
 * <p>Like the axiom control, it edits only when a component search factory and a component item
 * resolver are set; without them it renders the specification read-only.</p>
 */
public class ComplexConceptControl extends KLDiTreeControl {

    private final ReadOnlyObjectWrapper<String> lastRefusal = new ReadOnlyObjectWrapper<>(this, "lastRefusal");
    private final ObservableList<String> missing = FXCollections.observableArrayList();

    /** Creates an empty complex concept control. */
    public ComplexConceptControl() {
        getStyleClass().add("complex-concept-control");
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new ComplexConceptControlSkin(this);
    }

    /**
     * The message of the last edit the complex concept builder refused, or null since the last
     * edit it accepted. A refused edit leaves the tree as it was.
     *
     * @return the refusal message property
     */
    public final ReadOnlyObjectProperty<String> lastRefusalProperty() {
        return lastRefusal.getReadOnlyProperty();
    }

    /** @return the message of the last refused edit, or null */
    public final String getLastRefusal() {
        return lastRefusal.get();
    }

    void setLastRefusal(String message) {
        lastRefusal.set(message);
    }

    /**
     * What the specification still lacks to be complete: a tree under construction is well formed
     * without these, and the control lists them under the tree.
     *
     * @return the missing parts, live
     */
    public final ObservableList<String> getMissing() {
        return FXCollections.unmodifiableObservableList(missing);
    }

    void setMissing(List<String> parts) {
        missing.setAll(parts);
    }
}
