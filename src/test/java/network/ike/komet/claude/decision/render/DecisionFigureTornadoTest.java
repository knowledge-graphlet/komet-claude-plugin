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
package network.ike.komet.claude.decision.render;

import network.ike.komet.claude.decision.render.DecisionFigure.Polyline;
import network.ike.komet.claude.decision.render.DecisionFigure.Text;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Bar;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Choice;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Segment;
import network.ike.komet.claude.decision.render.DecisionFigureTornado.Tornado;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests where {@link DecisionFigureTornado} puts a bar's captions on a net-value axis: a caption the
 * zero line would run through goes to the line's far side instead.
 */
class DecisionFigureTornadoTest {

    @Test
    void aCaptionTheLineWouldCrossMovesToItsFarSide() {
        // a caption from 100 to 160, the line at 130: left of the line it ends 4 short of it
        assertEquals(130 - 4 - 60, DecisionFigureTornado.clearOfLine(100, 60, 130, true));
        // right of the line it starts 4 past it
        assertEquals(130 + 4, DecisionFigureTornado.clearOfLine(100, 60, 130, false));
    }

    @Test
    void aCaptionClearOfTheLineStays() {
        assertEquals(100, DecisionFigureTornado.clearOfLine(100, 60, 90, true));
        assertEquals(100, DecisionFigureTornado.clearOfLine(100, 60, 170, false));
        assertEquals(100, DecisionFigureTornado.clearOfLine(100, 60, Double.NaN, true));
    }

    @Test
    void noCaptionOnANetAxisStraddlesTheZeroLine() {
        // short bars starting a little way either side of zero, nearer to it than their captions are wide,
        // beside a long bar that sets the scale
        Tornado tornado = new Tornado("Net value", true, "scan", 300, -6_000, 4_000, List.of(
                bar("wide", "$6,000", "$4,000", -6_000, 4_000),
                bar("just above zero", "$54,628", "$13,657", 300, 1_400),
                bar("just below zero", "$5,463", "$1,366", -1_400, -300)), List.of());
        DecisionFigure figure = DecisionFigureTornado.build(tornado, 640, amount -> "$" + Math.round(amount));

        double zeroX = figure.marks().stream()
                .filter(mark -> mark instanceof Polyline line && line.role().equals("grid zero"))
                .mapToDouble(mark -> ((Polyline) mark).points()[0])
                .findFirst().orElseThrow(() -> new AssertionError("a net axis draws its zero line"));
        List<Text> captions = figure.marks().stream()
                .filter(mark -> mark instanceof Text text && text.role().equals("caption"))
                .map(Text.class::cast).toList();
        assertEquals(6, captions.size(), "two captions on each of the three bars");
        for (Text caption : captions) {
            double w = DecisionFigureText.width(caption.text(), caption.size(), caption.bold());
            assertTrue(caption.x() + w < zeroX || caption.x() > zeroX,
                    () -> caption.text() + " spans " + caption.x() + ".." + (caption.x() + w)
                            + " across the zero line at " + zeroX);
        }
    }

    private static Bar bar(String name, String low, String high, double least, double greatest) {
        Choice choice = Choice.BASE;
        return new Bar(name, low, high, true, least, greatest, least, greatest,
                List.of(new Segment(least, greatest, choice)), List.of());
    }
}
