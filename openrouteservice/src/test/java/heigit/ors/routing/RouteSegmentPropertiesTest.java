/*
 * This file is part of Openrouteservice.
 *
 * Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this library;
 * if not, see <https://www.gnu.org/licenses/>.
 */

package heigit.ors.routing;

import com.vividsolutions.jts.geom.Coordinate;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class RouteSegmentPropertiesTest {
    private Coordinate start = new Coordinate(1,2);
    private Coordinate end = new Coordinate(3,4);
    private RouteSegmentProperties props;

    @Before
    public void init() {
        props = new RouteSegmentProperties(
                start,
                end,
                0
        );
    }

    @Test
    public void testInitialisation() {
        Assert.assertEquals(1, props.getStartCoordinate().x, 0.0);
        Assert.assertEquals(2, props.getStartCoordinate().y, 0.0);
        Assert.assertEquals(3, props.getDestinationCoordinate().x, 0.0);
        Assert.assertEquals(4, props.getDestinationCoordinate().y, 0.0);

        Assert.assertEquals(1, props.getSegmentNumber());
    }

    @Test
    public void testSegmentIndex() {
        Assert.assertEquals(0, props.getSegmentIndex());
    }

    @Test
    public void testPoints() {
        Assert.assertEquals(1, props.getStartPoint().lon, 0.0);
        Assert.assertEquals(2, props.getStartPoint().lat, 0.0);
        Assert.assertEquals(3, props.getEndPoint().lon, 0.0);
        Assert.assertEquals(4, props.getEndPoint().lat, 0.0);
    }

    @Test
    public void testBearings() {
        Assert.assertTrue(Double.isNaN(props.getStartBearing()));
        Assert.assertTrue(Double.isNaN(props.getDestinationBearing()));

        props.setStartBearing(50);
        props.setDestinationBearing(100);

        Assert.assertEquals(50, props.getStartBearing(), 0.0);
        Assert.assertEquals(100, props.getDestinationBearing(), 0.0);
    }

    @Test
    public void testSearchRadii() {
        Assert.assertTrue(Double.isNaN(props.getRadii()[0]));
        Assert.assertTrue(Double.isNaN(props.getRadii()[1]));

        props.setStartSearchRadius(20);
        props.setDestinationSearchRadius(150);
        Assert.assertEquals(20, props.getRadii()[0], 0);
        Assert.assertEquals(150, props.getRadii()[1], 0);
    }

    @Test
    public void testSkippedSegment() {
        Assert.assertFalse(props.skippedSegment());

        props.setIsSkippedSegment(true);
        Assert.assertTrue(props.skippedSegment());

        props.setIsSkippedSegment(false);
        Assert.assertFalse(props.skippedSegment());
    }
}
