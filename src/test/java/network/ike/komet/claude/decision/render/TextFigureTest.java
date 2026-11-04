/*
 * Copyright © 2025 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.komet.claude.decision.render;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A block of words drawn for a page: inside its width, its table's words whole, a title above it. */
class TextFigureTest {

    private static List<DecisionFigure.Text> texts(DecisionFigure figure) {
        return figure.marks().stream().filter(DecisionFigure.Text.class::isInstance).map(DecisionFigure.Text.class::cast).toList();
    }

    @Test
    void wordsStayInsideTheFigureAndWhole() {
        String wide = "a concept named at such length that its cell cannot hold it on one line of the table at all, not nearly";
        DecisionFigure figure = TextFigure.of("complex-concept", Optional.of("A name"), List.of(wide + " " + wide),
                List.of(List.of("given", "value"), List.of(wide, "present"), List.of("short", wide)));
        for (DecisionFigure.Text text : texts(figure)) {
            assertTrue(text.x() + DecisionFigureText.width(text.text(), text.size(), text.bold()) <= TextFigure.WIDTH + 1,
                    "past the edge: " + text.text());
            assertTrue(text.top() < figure.height(), "beneath the figure: " + text.text());
            assertTrue(List.of(wide.split(" ")).containsAll(List.of(text.text().split(" "))) || text.role().equals("name")
                    || text.role().equals("table-head") || text.text().equals("present") || text.text().equals("short"),
                    "a word was broken: " + text.text());
        }
    }

    @Test
    void aTitleStandsAboveAndMovesTheFigureDown() {
        DecisionFigure figure = TextFigure.of("anf", Optional.of("A name"), List.of(), List.of());
        DecisionFigure titled = TextFigure.titled("The block's title", figure);
        assertEquals("The block's title", texts(titled).getFirst().text());
        assertTrue(titled.height() > figure.height());
        assertTrue(texts(titled).get(1).top() > texts(figure).getFirst().top());
    }
}
