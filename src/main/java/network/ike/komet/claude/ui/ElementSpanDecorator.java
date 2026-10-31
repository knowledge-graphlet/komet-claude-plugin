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

import dev.ikm.komet.markdown.richtext.InlineDecorator;
import dev.ikm.komet.markdown.richtext.InlinePiece;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import javafx.scene.paint.Color;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;

/**
 * Draws a note's clinical elements in its text ({@code dev-anf-element-isolation}): each
 * element's span underlined in the element's colour, and, in a run that holds a span, the words
 * that support no element shaded. The stretches come from the surface's {@link ElementSpans};
 * each stretch then goes to the decorator beneath, so a concept identifier in a span still
 * becomes a chip.
 */
final class ElementSpanDecorator implements InlineDecorator {

    private static final Color SHADE = Color.web("#9a948a");

    private final InlineDecorator inner;
    private final ElementSpans spans;

    ElementSpanDecorator(InlineDecorator inner, ElementSpans spans) {
        this.inner = inner;
        this.spans = spans;
    }

    @Override
    public List<InlinePiece> decorate(String text, StyleAttributeMap style) {
        return split(text, style, inner::decorate);
    }

    @Override
    public List<InlinePiece> decorateForCell(String text, StyleAttributeMap style) {
        return split(text, style, inner::decorateForCell);
    }

    @Override
    public StyleAttributeMap linkStyle(String destination, StyleAttributeMap style) {
        return inner.linkStyle(destination, style);
    }

    private List<InlinePiece> split(String text, StyleAttributeMap style,
                                    BiFunction<String, StyleAttributeMap, List<InlinePiece>> next) {
        if (text == null || text.isEmpty() || !spans.hasSpans()) {
            return next.apply(text, style);
        }
        List<ElementSpans.Mark> marks = spans.marks(text);
        if (marks.size() == 1 && marks.getFirst().kind() == ElementSpans.Kind.PLAIN) {
            return next.apply(text, style);
        }
        List<InlinePiece> pieces = new ArrayList<>();
        for (ElementSpans.Mark mark : marks) {
            pieces.addAll(next.apply(text.substring(mark.start(), mark.end()), styled(style, mark)));
        }
        return pieces;
    }

    private static StyleAttributeMap styled(StyleAttributeMap style, ElementSpans.Mark mark) {
        StyleAttributeMap.Builder builder = StyleAttributeMap.builder();
        if (style != null) {
            builder.merge(style);
        }
        return switch (mark.kind()) {
            case SPAN -> builder.setUnderline(true).setTextColor(Color.web(mark.colour()).darker()).build();
            case UNCOVERED -> builder.setTextColor(SHADE).build();
            case PLAIN, NOT_CLINICAL -> style;
        };
    }
}
