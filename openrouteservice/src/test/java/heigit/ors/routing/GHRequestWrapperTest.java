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

import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphBuilder;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.typesafe.config.ConfigFactory;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Polygon;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import heigit.ors.routing.graphhopper.extensions.GraphProcessContext;
import heigit.ors.routing.graphhopper.extensions.ORSDefaultFlagEncoderFactory;
import heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import heigit.ors.routing.graphhopper.extensions.flagencoders.FlagEncoderNames;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class GHRequestWrapperTest {
    ORSGraphHopper gh;
    final EncodingManager encodingManager = new EncodingManager(new ORSDefaultFlagEncoderFactory(), FlagEncoderNames.CAR_ORS, 4);
    RouteProfileConfiguration config;

    void initGraph(GraphHopperStorage graph) {
        NodeAccess na = graph.getNodeAccess();
        na.setNode(0, 42, 10);
        na.setNode(1, 42.1, 10.1);
        na.setNode(2, 42.1, 10.2);
        na.setNode(3, 42, 10.4);

        graph.edge(0, 1, 10, true);
        graph.edge(2, 3, 10, true);
    }

    @Before
    public void init() {
        GraphHopperStorage storage = new GraphBuilder(encodingManager).create();
        initGraph(storage);

        config = new RouteProfileConfiguration();

        config.setExecutionOpts(ConfigFactory.parseString(
                "  {\n" +
                        "    methods: {\n" +
                        "      astar: { approximation: test, epsilon: 10}," +
                        "      ch: {\n" +
                        "        disabling_allowed: true\n" +
                        "      },\n" +
                        "      lm: {\n" +
                        "        disabling_allowed: true,\n" +
                        "        active_landmarks: 8\n" +
                        "      },\n" +
                        "      core: {\n" +
                        "        disabling_allowed: true,\n" +
                        "        active_landmarks: 6\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }"
        ));

        try {
            GraphProcessContext cntx = new GraphProcessContext(config);
            gh = new ORSGraphHopper(cntx, false, null);
            gh.setStoreOnFlush(false);
            gh.setEncodingManager(encodingManager);
            //gh.setCHEnabled(false);
            gh.setGraphHopperStorage(storage);
        } catch (Exception e) {

        }
    }

    private void initWithoutCH() {
        GraphHopperStorage storage = new GraphBuilder(encodingManager).create();
        initGraph(storage);

        config = new RouteProfileConfiguration();

        try {
            GraphProcessContext cntx = new GraphProcessContext(config);
            gh = new ORSGraphHopper(cntx, false, null);
            gh.setStoreOnFlush(false);
            gh.setEncodingManager(encodingManager);
            gh.setCHEnabled(false);
            gh.setGraphHopperStorage(storage);

        } catch (Exception e) {

        }
    }

    private void initWithoutCHandCALT() {
        GraphHopperStorage storage = new GraphBuilder(encodingManager).create();
        initGraph(storage);

        config = new RouteProfileConfiguration();

        try {
            GraphProcessContext cntx = new GraphProcessContext(config);
            gh = new ORSGraphHopper(cntx, false, null);
            gh.setStoreOnFlush(false);
            gh.setEncodingManager(encodingManager);
            gh.setCHEnabled(false);
            gh.setCoreEnabled(false);
            gh.setCoreLMEnabled(false);
            gh.getLMFactoryDecorator().setEnabled(true);
            gh.setGraphHopperStorage(storage);

        } catch (Exception e) {

        }
    }



    private GHRequestWrapper createWrapper(RouteSearchParameters routeSearchParams)  throws Exception {
        RouteSegmentProperties segmentProperties = new RouteSegmentProperties(
                new Coordinate(42, 10),
                new Coordinate(42.1, 10.1),
                1
        );

        RouteProcessContext rpc = new RouteProcessContext(null, routeSearchParams);

        return new GHRequestWrapper(segmentProperties, rpc, gh, config);
    }

    @Test
    public void testWeightingMethod() throws Exception {
        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("fastest", wrapper.getRequestForGH().getWeighting());

        routeSearchParams.setWeightingMethod(WeightingMethod.SHORTEST);
        wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("shortest", wrapper.getRequestForGH().getWeighting());

        routeSearchParams.setWeightingMethod(WeightingMethod.RECOMMENDED);
        wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("fastest", wrapper.getRequestForGH().getWeighting());
        Assert.assertEquals("recommended", wrapper.getRequestForGH().getHints().get("weighting_method", ""));

        routeSearchParams.setWeightingMethod(WeightingMethod.FASTEST);
        wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("fastest", wrapper.getRequestForGH().getWeighting());
    }

    @Test
    public void testDynamicWeighting() throws Exception {
        initWithoutCHandCALT();

        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);
        routeSearchParams.setFlexibleMode(true);

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("astarbi", wrapper.getRequestForGH().getAlgorithm());
        Assert.assertTrue(wrapper.getRequestForGH().getHints().getBool("ch.disable", false));
        Assert.assertTrue(wrapper.getRequestForGH().getHints().getBool("core.disable", false));
        Assert.assertFalse(wrapper.getRequestForGH().getHints().getBool("lm.disable", true));
    }

    @Test
    public void testAlgorithmSetup() throws Exception {
        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("test", wrapper.getRequestForGH().getHints().get("astarbi.approximation", ""));
        Assert.assertEquals(10, wrapper.getRequestForGH().getHints().getDouble("astarbi.epsilon", 0), 0);
    }

    @Test
    public void testUseLandmarksWithAvoidAreas() throws Exception {
        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);
        GeometryFactory fact = new GeometryFactory();
        routeSearchParams.setAvoidAreas(new Polygon[] {
                fact.createPolygon(new Coordinate[]{
                        new Coordinate(0,0),
                        new Coordinate(0,1),
                        new Coordinate(1,1),
                        new Coordinate(0,0)})});

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("astarbi", wrapper.getRequestForGH().getAlgorithm());
    }

    @Test
    public void testFastRoutingWithoutCH() throws Exception {
        initWithoutCH();

        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("astarbi", wrapper.getRequestForGH().getAlgorithm());
        Assert.assertTrue(wrapper.getRequestForGH().getHints().getBool("ch.disable", false));
    }

    @Test
    public void testFastRoutingWithoutCHandCALT() throws Exception {
        initWithoutCHandCALT();

        RouteSearchParameters routeSearchParams = new RouteSearchParameters();

        routeSearchParams.setProfileType(1);

        GHRequestWrapper wrapper = createWrapper(routeSearchParams);
        Assert.assertEquals("astarbi", wrapper.getRequestForGH().getAlgorithm());
        Assert.assertTrue(wrapper.getRequestForGH().getHints().getBool("ch.disable", false));
        Assert.assertTrue(wrapper.getRequestForGH().getHints().getBool("core.disable", false));
        Assert.assertFalse(wrapper.getRequestForGH().getHints().getBool("lm.disable", true));
    }
}
