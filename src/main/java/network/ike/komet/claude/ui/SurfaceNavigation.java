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
package network.ike.komet.claude.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

/**
 * Takes the reader to a block on the surface: every scroll pane that encloses the node scrolls so
 * its top shows, and the node flashes once so the eye finds it. Used by the links between trees
 * that fold one another in.
 */
final class SurfaceNavigation {

    private SurfaceNavigation() {
    }

    /** Scrolls every enclosing scroll pane to the node's top and flashes it. */
    static void reveal(Node node) {
        for (Parent parent = node.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollPane scroll && scroll.getContent() != null) {
                Node content = scroll.getContent();
                Bounds at = content.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
                double range = content.getBoundsInLocal().getHeight() - scroll.getViewportBounds().getHeight();
                if (at != null && range > 0) {
                    scroll.setVvalue(Math.clamp((at.getMinY() - 8) / range, 0, 1));
                }
            }
        }
        double was = node.getOpacity();
        new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(node.opacityProperty(), 0.35)),
                new KeyFrame(Duration.millis(450), new KeyValue(node.opacityProperty(), was))).play();
    }
}
