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

import com.graphhopper.GHRequest;
import com.graphhopper.util.PMap;
import com.typesafe.config.Config;
import heigit.ors.exceptions.InternalServerException;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import heigit.ors.routing.graphhopper.extensions.HeavyVehicleAttributes;
import heigit.ors.routing.graphhopper.extensions.ORSGraphHopper;
import heigit.ors.routing.traffic.RealTrafficDataProvider;
import heigit.ors.routing.traffic.TrafficEdgeAnnotator;

public class GHRequestWrapper {
    private GHRequest ghRequest;
    private ORSGraphHopper graphHopper;
    private RouteProfileConfiguration profileConfig;

    /**
     * Constructor for initialising a GHRequest object with settings based on parameters passed.
     *
     * @param segmentProperties         Properties about the segment that is to be routed (start/end coordinates etc.)
     * @param routeProcessContext       A context for how the route should be processed
     * @param graphHopper               The GraphHopper instance that will be used for routing itself
     * @param profileConfig             Configuration for the specific profile that will be used
     * @throws InternalServerException  An error during the setting up of the GHRequest object
     */
    public GHRequestWrapper(RouteSegmentProperties segmentProperties, RouteProcessContext routeProcessContext, ORSGraphHopper graphHopper, RouteProfileConfiguration profileConfig) throws InternalServerException {
        ghRequest = new GHRequest(segmentProperties.getStartPoint(), segmentProperties.getEndPoint(), segmentProperties.getStartBearing(), segmentProperties.getDestinationBearing());
        this.graphHopper = graphHopper;
        this.profileConfig = profileConfig;

        if (segmentProperties.getRadii() != null)
            ghRequest.setMaxSearchDistance(segmentProperties.getRadii());

        RouteSearchParameters searchParams = routeProcessContext.getRouteSearchParams();
        int profileType = searchParams.getProfileType();

        if (RoutingProfileType.isDriving(profileType) && RealTrafficDataProvider.getInstance().isInitialized())
            ghRequest.setEdgeAnnotator(new TrafficEdgeAnnotator(graphHopper.getGraphHopperStorage()));

        RouteSearchContext searchCntx = new RouteSearchContext(graphHopper, searchParams);

        ghRequest.setVehicle(searchCntx.getEncoder().toString());

        PMap props = searchCntx.getProperties();
        if (props != null && props.size() > 0)
            ghRequest.getHints().merge(props);

        setupWeightingMethods(searchParams);

        ghRequest.setEdgeFilter(searchCntx.getEdgeFilter());
        ghRequest.setPathProcessor(routeProcessContext.getPathProcessor());

        setupAlgorithm(searchParams);
    }

    /**
     * Use the search parameters to identifiy and set the weighting method to be used for the routing request
     *
     * @param searchParams  The parameters that have been specified for the routing
     */
    private void setupWeightingMethods(RouteSearchParameters searchParams) {
        final String METHOD_STRING = "weighting_method";

        int weightingMethod = searchParams.getWeightingMethod();
        int profileType = searchParams.getProfileType();

        if (supportWeightingMethod(profileType)) {
            if (weightingMethod == WeightingMethod.FASTEST) {
                ghRequest.setWeighting("fastest");
                ghRequest.getHints().put(METHOD_STRING, "fastest");
            } else if (weightingMethod == WeightingMethod.SHORTEST) {
                ghRequest.setWeighting("shortest");
                ghRequest.getHints().put(METHOD_STRING, "shortest");
            } else if (weightingMethod == WeightingMethod.RECOMMENDED) {
                ghRequest.setWeighting("fastest");
                ghRequest.getHints().put(METHOD_STRING, "recommended");
            }
        }

        // MARQ24 for what ever reason after the 'weighting_method' hint have been set (based
        // on the given searchParameter Max have decided that's necessary 'patch' the hint
        // for certain profiles...
        // ...and BTW if the flexibleMode set to true, CH will be disabled!
        if (weightingMethod == WeightingMethod.RECOMMENDED && profileType == RoutingProfileType.DRIVING_HGV && HeavyVehicleAttributes.HGV == searchParams.getVehicleType()){
            ghRequest.setWeighting("fastest");
            ghRequest.getHints().put(METHOD_STRING, "recommended_pref");
        }
    }

    /**
     * Check for if the passed profile supports the weighting method
     *
     * @param profileType   The id of the profile to be checked
     * @return              Whether the specified profile supports the weighting method
     */
    private boolean supportWeightingMethod(int profileType) {
        return RoutingProfileType.isDriving(profileType) || RoutingProfileType.isCycling(profileType) || RoutingProfileType.isWalking(profileType) || profileType == RoutingProfileType.WHEELCHAIR;
    }

    /**
     * Add properties to the GHRequest regarding the algorithm that should be used for routing
     *
     * @param searchParams  Parameters that have been declared for searching for a route
     */
    private void setupAlgorithm(RouteSearchParameters searchParams) {
        ghRequest.setAlgorithm("dijkstrabi");

        setupSearchMethod(searchParams);

        if (searchParams.getProfileType() == RoutingProfileType.DRIVING_EMERGENCY) {
            ghRequest.getHints().put("custom_weightings", true);
            ghRequest.getHints().put("weighting_#acceleration#", true);
        }

        Config optsExecute = profileConfig.getExecutionOpts();
        if (optsExecute != null) {
            if (optsExecute.hasPath("methods.astar.approximation")) {
                String astarApproximation = optsExecute.getString("methods.astar.approximation");
                if (astarApproximation != null)
                    ghRequest.getHints().put("astarbi.approximation", astarApproximation);
            }
            if (optsExecute.hasPath("methods.astar.epsilon")) {
                Double astarEpsilon = Double.parseDouble(optsExecute.getString("methods.astar.epsilon"));
                if (astarEpsilon != null)
                    ghRequest.getHints().put("astarbi.epsilon", astarEpsilon);
            }
        }
    }

    /**
     * Define the search method that should be applied to the routing.
     *
     * @param searchParams  Parameters for the searching process.
     */
    private void setupSearchMethod(RouteSearchParameters searchParams) {

        boolean flexibleMode = needsFlexibleRouting(searchParams.getWeightingMethod(), searchParams);

        if (searchParams.requiresDynamicWeights() || flexibleMode) {
            setupDynamicSearchMethod(searchParams);
        } else {
            setupFastSearchMethod(searchParams);
        }

        //cannot use CH or CoreALT with avoid areas. Need to fallback to ALT with beeline approximator or Dijkstra
        if (ghRequest.getHints() != null && ghRequest.getHints().getBool("avoid_areas", false)){
            enableLandmarks();
        }
    }

    /**
     * Determine if the request needs to use flexible routing or not
     *
     * @param weightingMethod   The weighting method (fastest, shortest etc.) to be applied
     * @param searchParams      Parameters defining the search characteristics
     *
     * @return                  Whether flexible routing needs to be used or not
     */
    private boolean needsFlexibleRouting(int weightingMethod, RouteSearchParameters searchParams) {
        boolean flexibleMode = searchParams.getFlexibleMode();

        if (weightingMethod == WeightingMethod.SHORTEST || weightingMethod ==WeightingMethod.RECOMMENDED) {
            flexibleMode = true;
        }

        if(searchParams.getProfileType() == RoutingProfileType.WHEELCHAIR) {
            flexibleMode = true;
        }

        return flexibleMode;
    }

    /**
     * Enable the various dynamic search methods that can be used for determining a route
     *
     * @param searchParams  Parameters defining the search process
     */
    private void setupDynamicSearchMethod(RouteSearchParameters searchParams) {
        if (graphHopper.isCHEnabled()) {
            // We don't want to use contraction hierarchies in anything other than fast routing
            toggleContractionHierarchies(true);
        }
        if (graphHopper.getLMFactoryDecorator().isEnabled()) {
            enableLandmarks();
        }
        if (graphHopper.isCoreEnabled() && searchParams.getOptimized()) {
            enableCALT();
        }
    }

    /**
     * Set up the methods to be used for quickly generating routes
     *
     * @param searchParams  Parameters defining the search process
     */
    private void setupFastSearchMethod(RouteSearchParameters searchParams) {
        if (graphHopper.isCHEnabled()) {
            enableCH();
        } else {
            if (graphHopper.isCoreEnabled() && searchParams.getOptimized()) {
                enableCALT();
            }
            else {
                enableLandmarks();
            }
        }
    }

    /**
     * Enable the Core-ALT algorithm
     */
    private void enableCALT() {
        ghRequest.setAlgorithm("astarbi");
        toggleCoreAlt(false);
        toggleLandmarks(true);
        toggleContractionHierarchies(true);
    }

    /**
     * Enable the landmark based routing algorithm
     */
    private void enableLandmarks() {
        ghRequest.setAlgorithm("astarbi");
        toggleLandmarks(false);
        toggleCoreAlt(true);
        toggleContractionHierarchies(true);
    }

    /**
     * Enable the Contraction Hierarchies routing algorithm
     */
    private void enableCH() {
        ghRequest.setAlgorithm("dijkstrabi");
        toggleContractionHierarchies(false);
        toggleLandmarks(true);
        toggleCoreAlt(true);

    }

    /**
     * Enable or disable the contraction hierarchies algorithm
     * @param disableCH Disable CH or not
     */
    private void toggleContractionHierarchies(boolean disableCH) {
        ghRequest.getHints().put("ch.disable", disableCH);
    }

    /**
     * Enable or disable the landmark based routing algorithm
     * @param disableLM Disable the LM algorithm or not
     */
    private void toggleLandmarks(boolean disableLM) {
        ghRequest.getHints().put("lm.disable", disableLM);
    }

    /**
     * Enable or disable the core-alt algorithm
     * @param disableCALT Disable CH or not
     */
    private void toggleCoreAlt(boolean disableCALT) {
        ghRequest.getHints().put("core.disable", disableCALT);
    }

    public void setVehicle(String encoder) {
        ghRequest.setVehicle(encoder);
    }

    public GHRequest getRequestForGH() {
        return ghRequest;
    }
}
