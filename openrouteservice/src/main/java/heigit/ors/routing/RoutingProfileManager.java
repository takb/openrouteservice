/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package heigit.ors.routing;

import com.graphhopper.GHResponse;
import com.graphhopper.routing.util.PathProcessor;
import com.graphhopper.util.Helper;
import com.graphhopper.util.PointList;
import com.vividsolutions.jts.geom.Coordinate;
import heigit.ors.exceptions.*;
import heigit.ors.isochrones.IsochroneMap;
import heigit.ors.isochrones.IsochroneSearchParameters;
import heigit.ors.mapmatching.MapMatchingRequest;
import heigit.ors.matrix.MatrixErrorCodes;
import heigit.ors.matrix.MatrixRequest;
import heigit.ors.matrix.MatrixResult;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import heigit.ors.routing.configuration.RoutingManagerConfiguration;
import heigit.ors.routing.pathprocessors.ExtraInfoProcessor;
import heigit.ors.routing.traffic.RealTrafficDataProvider;
import heigit.ors.services.routing.RoutingServiceSettings;
import heigit.ors.util.FormatUtility;
import heigit.ors.util.GeomUtility;
import heigit.ors.util.RuntimeUtility;
import heigit.ors.util.TimeUtility;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

public class RoutingProfileManager {
    private static final Logger LOGGER = Logger.getLogger(RoutingProfileManager.class.getName());

    private RoutingProfilesCollection routeProfileCollection;
    private RoutingProfilesUpdater profileUpdater;
    private static RoutingProfileManager mInstance;

    public static synchronized RoutingProfileManager getInstance() {
        if (mInstance == null) {
            mInstance = new RoutingProfileManager();
            mInstance.initialize(null);
        }

        return mInstance;
    }

    /**
     * Run the processes that prepare graphs
     *
     * @param propertiesFilePath    The path to the file that contains the properties that should be used for graph preparation
     */
    public void prepareGraphs(String propertiesFilePath) {
        long startTime = System.currentTimeMillis();

        try {
            RoutingManagerConfiguration rmc = RoutingManagerConfiguration.loadFromFile(propertiesFilePath);

            routeProfileCollection = new RoutingProfilesCollection();
            int nRouteInstances = rmc.Profiles.length;

            RoutingProfileLoadContext loadCntx = new RoutingProfileLoadContext(RoutingServiceSettings.getInitializationThreads());
            ExecutorService executor = Executors.newFixedThreadPool(RoutingServiceSettings.getInitializationThreads());
            ExecutorCompletionService<RoutingProfile> compService = new ExecutorCompletionService<>(executor);

            int nTotalTasks = 0;

            for (int i = 0; i < nRouteInstances; i++) {
                RouteProfileConfiguration rpc = rmc.Profiles[i];
                if (!rpc.getEnabled())
                    continue;

                Integer[] routeProfileTypes = rpc.getProfilesTypes();

                if (routeProfileTypes != null) {
                    Callable<RoutingProfile> task = new RoutingProfileLoader(RoutingServiceSettings.getSourceFile(), rpc,
                            routeProfileCollection, loadCntx);
                    compService.submit(task);
                    nTotalTasks++;
                }
            }

            LOGGER.info("               ");

            int nCompletedTasks = 0;
            while (nCompletedTasks < nTotalTasks) {
                Future<RoutingProfile> future = compService.take();

                try {
                    RoutingProfile rp = future.get();
                    nCompletedTasks++;
                    rp.close();
                    LOGGER.info("Graph preparation done.");
                } catch (InterruptedException e) {
                    LOGGER.error(e);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    LOGGER.error(e);
                }
            }

            executor.shutdown();
            loadCntx.releaseElevationProviderCache();


            LOGGER.info("Graphs were prepared in " + TimeUtility.getElapsedTime(startTime, true) + ".");
        } catch (Exception ex) {
            LOGGER.error("Failed to prepare graphs.", ex);
        }

        RuntimeUtility.clearMemory(LOGGER);
    }

    /**
     * Run processes that build or load graphs into memory.
     *
     * @param managerConfiguration  The configuration that should be used for generating the graphs
     */
    private void loadGraphs(RoutingManagerConfiguration managerConfiguration) {
        long startTime = System.currentTimeMillis();

        routeProfileCollection = new RoutingProfilesCollection();
        int nRouteInstances = managerConfiguration.Profiles.length;

        RoutingProfileLoadContext loadCntx = new RoutingProfileLoadContext(RoutingServiceSettings.getInitializationThreads());
        ExecutorService executor = Executors.newFixedThreadPool(RoutingServiceSettings.getInitializationThreads());
        ExecutorCompletionService<RoutingProfile> compService = new ExecutorCompletionService<>(executor);

        int nTotalTasks = 0;

        for (int i = 0; i < nRouteInstances; i++) {
            RouteProfileConfiguration rpc = managerConfiguration.Profiles[i];
            if (!rpc.getEnabled())
                continue;

            Integer[] routeProfiles = rpc.getProfilesTypes();

            if (routeProfiles != null) {
                Callable<RoutingProfile> task = new RoutingProfileLoader(RoutingServiceSettings.getSourceFile(), rpc,
                        routeProfileCollection, loadCntx);
                compService.submit(task);
                nTotalTasks++;
            }
        }

        LOGGER.info("               ");

        int nCompletedTasks = 0;
        while (nCompletedTasks < nTotalTasks) {
            try {
                Future<RoutingProfile> future = compService.take();
                RoutingProfile rp = future.get();
                nCompletedTasks++;
                if (!routeProfileCollection.add(rp))
                    LOGGER.warn("Routing profile has already been added.");
            } catch (InterruptedException e) {
                LOGGER.error(e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOGGER.error(e);
            }
        }

        executor.shutdown();
        loadCntx.releaseElevationProviderCache();

        LOGGER.info("Total time: " + TimeUtility.getElapsedTime(startTime, true) + ".");
        LOGGER.info("========================================================================");
    }

    /**
     * Initialise all of the routing profiles for the service.
     *
     * @param propertiesFilePath    The path of the file that should be read for obtaining the properties
     */
    public void initialize(String propertiesFilePath) {
        RuntimeUtility.printRAMInfo("", LOGGER);

        LOGGER.info("      ");

        if (RoutingServiceSettings.getEnabled()) {
            RoutingManagerConfiguration rmc = null;
            try {
                rmc = RoutingManagerConfiguration.loadFromFile(propertiesFilePath);
            } catch (InternalServerException e) {
                LOGGER.error("Could not load configuration.", e);
            }

            if(rmc != null) {
                LOGGER.info(String.format("====> Initializing profiles from '%s' (%d threads) ...", RoutingServiceSettings.getSourceFile(), RoutingServiceSettings.getInitializationThreads()));
                LOGGER.info("                              ");

                if ("preparation".equalsIgnoreCase(RoutingServiceSettings.getWorkingMode())) {
                    prepareGraphs(propertiesFilePath);
                } else {
                    loadGraphs(rmc);

                    if (rmc.TrafficInfoConfig != null && rmc.TrafficInfoConfig.Enabled) {
                        RealTrafficDataProvider.getInstance().initialize(rmc, routeProfileCollection);
                    }

                    if (rmc.UpdateConfig != null && rmc.UpdateConfig.Enabled) {
                        profileUpdater = new RoutingProfilesUpdater(rmc.UpdateConfig, routeProfileCollection);
                        profileUpdater.start();
                    }
                }

                RoutingProfileManagerStatus.setReady(true);
            }
        }

        RuntimeUtility.clearMemory(LOGGER);

        if (LOGGER.isInfoEnabled())
            routeProfileCollection.printStatistics(LOGGER);
    }

    public void destroy() {
        if (profileUpdater != null)
            profileUpdater.destroy();

        if (RealTrafficDataProvider.getInstance().isInitialized())
            RealTrafficDataProvider.getInstance().destroy();

        routeProfileCollection.destroy();
    }

    public RoutingProfilesCollection getProfiles() {
        return routeProfileCollection;
    }

    public boolean updateEnabled() {
        return profileUpdater != null;
    }

    public Date getNextUpdateTime() {
        return profileUpdater == null ? new Date() : profileUpdater.getNextUpdate();
    }

    public String getUpdatedStatus() {
        return profileUpdater == null ? null : profileUpdater.getStatus();
    }

    public RouteResult matchTrack(MapMatchingRequest req)  {
        // Map matching is disabled
        return null;
    }

    /**
     * Compute a route between the way points specified. A multi waypoint route is broken down into individual segments
     * and the the route is calculated for each segment before being reconstructed into a singe RouteResult object.
     *
     * @param request               The information about the route to be generated
     * @return                      A single RouteResult object containing all route segments merged into one route
     * @throws StatusCodeException  An error relating to computing the route (e.g. cannot find a start/end point, no
     *                              valid route etc.)
     */
    public RouteResult computeRoute(RoutingRequest request) throws StatusCodeException {
        List<Integer> skipSegments = request.getSkipSegments();
        List<GHResponse> routeSegments = new ArrayList<>();

        RoutingProfile rp = getRouteProfile(request);
        RouteSearchParameters searchParams = request.getSearchParameters();
        PathProcessor pathProcessor = new ExtraInfoProcessor(rp.getGraphhopper(), request);

        Coordinate[] coords = request.getCoordinates();
        Coordinate segmentStartCoordinate = coords[0];
        Coordinate segmentEndCoordinate;
        int nSegments = coords.length - 1;
        RouteProcessContext routeProcCntx = new RouteProcessContext(pathProcessor, searchParams);
        GHResponse prevResp = null;
        boolean hasBearings = (request.getContinueStraight() || searchParams.getBearings() != null);
        int profileType = request.getSearchParameters().getProfileType();

        for (int i = 1; i <= nSegments; ++i) {
            segmentEndCoordinate = coords[i];
            RouteSegmentProperties segmentProperties = new RouteSegmentProperties(segmentStartCoordinate, segmentEndCoordinate, i-1);

            pathProcessor.setSegmentIndex(i - 1, nSegments);

            if (hasBearings) {
                double leaveBearing = Double.NaN;
                double entryBearing = Double.NaN;

                if (i>1 && request.getContinueStraight()) {
                    leaveBearing = getHeadingDirection(prevResp);
                }

                if (searchParams.getBearings() != null) {
                    leaveBearing = searchParams.getBearings()[i-1];

                    if (i == nSegments && searchParams.getBearings().length == nSegments + 1)
                        entryBearing = searchParams.getBearings()[i];

                }

                segmentProperties.setStartBearing(leaveBearing);
                segmentProperties.setDestinationBearing(entryBearing);
            }

            segmentProperties.setIsSkippedSegment(skipSegments.contains(i));
            segmentProperties.setSimplifyGeometry(request.getGeometrySimplify());

            if (searchParams.getMaximumRadiuses() != null) {
                segmentProperties.setStartSearchRadius(searchParams.getMaximumRadiuses()[i-1]);
                segmentProperties.setDestinationSearchRadius(searchParams.getMaximumRadiuses()[i]);
            } else {
                try {
                    int maxSnappingRadius = routeProfileCollection.getRouteProfile(profileType).getConfiguration().getMaximumSnappingRadius();

                    segmentProperties.setStartSearchRadius(maxSnappingRadius);
                    segmentProperties.setDestinationSearchRadius(maxSnappingRadius);
                } catch (InternalServerException exception) {
                    LOGGER.debug(exception.getMessage());
                }

            }
            GHResponse gr = rp.computeRouteSegment(segmentProperties, routeProcCntx);

            Throwable error = generateErrors(gr, segmentProperties);
            if (error != null) {
                throw (StatusCodeException) error;
            }

            prevResp = gr;
            routeSegments.add(gr);
            segmentStartCoordinate = segmentEndCoordinate;
        }
        routeSegments = enrichDirectRoutesTime(routeSegments);
        return new RouteResultBuilder().createMergedRouteResultFromBestPaths(routeSegments, request, (pathProcessor != null && (pathProcessor instanceof ExtraInfoProcessor)) ? ((ExtraInfoProcessor) pathProcessor).getExtras() : null);
    }

    /**
     * Method that throws ors friendly exceptions if any errors were encountered during the route calculation.
     *
     * @param routeResponse             The response from the GraphHopper routing request
     * @param segmentProps              Properties for the segment that the route is being calculated for
     * @return                          A formatted ORS exception dependant on what went wrong in GH
     */
    private Throwable generateErrors(GHResponse routeResponse, RouteSegmentProperties segmentProps) {
        Throwable error = null;

        if (routeResponse.hasErrors()) {
            if (routeResponse.getErrors().isEmpty() || routeResponse.getErrors().get(0) instanceof com.graphhopper.util.exceptions.ConnectionNotFoundException) {
                error =  new RouteNotFoundException(
                        RoutingErrorCodes.ROUTE_NOT_FOUND,
                        String.format("Unable to find a route between points %d (%s) and %d (%s).",
                                segmentProps.getSegmentNumber(),
                                FormatUtility.formatCoordinate(segmentProps.getStartCoordinate()),
                                segmentProps.getSegmentNumber() + 1,
                                FormatUtility.formatCoordinate(segmentProps.getDestinationCoordinate()))
                );
            } else if (routeResponse.getErrors().get(0) instanceof com.graphhopper.util.exceptions.PointNotFoundException) {
                StringBuilder message = new StringBuilder();
                for(Throwable errorGenerated: routeResponse.getErrors()) {
                    if(message.length() != 0)
                        message.append("; ");
                    message.append(errorGenerated.getMessage());
                }
                error = new PointNotFoundException(message.toString());
            } else {
                error = new InternalServerException(RoutingErrorCodes.UNKNOWN, routeResponse.getErrors().get(0).getMessage());
            }
        }

        return error;
    }

    /**
     * This will enrich all direct routes with an approximated travel time that is being calculated from the real graphhopper
     * results. The routes object should contain all routes, so the function can maintain and return the proper order!
     *
     * @param routes Should hold all the routes that have been calculated, not only the direct routes.
     * @return will return routes object with enriched direct routes if any we're found in the same order as the input object.
     */
    private List<GHResponse> enrichDirectRoutesTime(List<GHResponse> routes) {
        List<GHResponse> graphhopperRoutes = new ArrayList<>();
        List<GHResponse> directRoutes = new ArrayList<>();
        long graphHopperTravelTime = 0;
        double graphHopperTravelDistance = 0;
        double averageTravelTimePerMeter;

        for (GHResponse ghResponse : routes) {
            if (!ghResponse.getHints().has("skipped_segment")) {
                graphHopperTravelDistance += ghResponse.getBest().getDistance();
                graphHopperTravelTime += ghResponse.getBest().getTime();
                graphhopperRoutes.add(ghResponse);
            } else {
                directRoutes.add(ghResponse);
            }
        }

        if (graphhopperRoutes.isEmpty() || directRoutes.isEmpty()) {
            return routes;
        }

        if (graphHopperTravelDistance == 0) {
            return routes;
        }

        averageTravelTimePerMeter = graphHopperTravelTime / graphHopperTravelDistance;
        for (GHResponse ghResponse : routes) {
            if (ghResponse.getHints().has("skipped_segment")) {
                double directRouteDistance = ghResponse.getBest().getDistance();
                ghResponse.getBest().setTime(Math.round(directRouteDistance * averageTravelTimePerMeter));
                double directRouteInstructionDistance = ghResponse.getBest().getInstructions().get(0).getDistance();
                ghResponse.getBest().getInstructions().get(0).setTime(Math.round(directRouteInstructionDistance * averageTravelTimePerMeter));
            }
        }

        return routes;
    }

    /**
     * Calculate the bearing of travel (from true north) at the end of the route contained in the route response
     *
     * @param routeResponse     The GraphHopper route response containing the route that we want the direction for
     * @return                  The bearing in degrees (from true north) that the traveller entered the end point at
     */
    private double getHeadingDirection(GHResponse routeResponse) {
        if (routeResponse == null) {
            return Double.NaN;
        }

        PointList points = routeResponse.getBest().getPoints();
        int nPoints = points.size();
        if (nPoints > 1) {
            double lon1 = points.getLon(nPoints - 2);
            double lat1 = points.getLat(nPoints - 2);
            double lon2 = points.getLon(nPoints - 1);
            double lat2 = points.getLat(nPoints - 1);
            // For some reason, GH may return a response where the last two points are identical
            if (lon1 == lon2 && lat1 == lat2 && nPoints > 2) {
                lon1 = points.getLon(nPoints - 3);
                lat1 = points.getLat(nPoints - 3);
            }
            return Helper.ANGLE_CALC.calcAzimuth(lat1, lon1, lat2, lon2);
        } else
            return Double.NaN;
    }

    /**
     * Get the RoutingProfile to use for generating a route based on the information passed in the request.
     *
     * @param request               The information to use for identifying the routing profile to use
     * @return                      A RoutingProfile object containing information needed to generate a route
     * @throws StatusCodeException  An error thrown indicating that a valid routing profile could not be obtained
     */
    public RoutingProfile getRouteProfile(RoutingRequest request) throws StatusCodeException {
        RouteSearchParameters searchParams = request.getSearchParameters();
        int profileType = searchParams.getProfileType();

        boolean dynamicWeights = searchParams.requiresDynamicWeights();

        RoutingProfile rp = routeProfileCollection.getRouteProfile(profileType, !dynamicWeights);

        if (rp == null && !dynamicWeights)
            rp = routeProfileCollection.getRouteProfile(profileType, false);

        if (rp == null)
            throw new InternalServerException(RoutingErrorCodes.UNKNOWN, "Unable to get an appropriate route profile for RoutePreference = " + RoutingProfileType.getName(request.getSearchParameters().getProfileType()));

        RouteProfileConfiguration config = rp.getConfiguration();

        Coordinate[] coords = request.getCoordinates();
        int nCoords = coords.length;
        if (config.getMaximumWayPoints() > 0 && nCoords > config.getMaximumWayPoints()) {
            throw new ServerLimitExceededException(RoutingErrorCodes.REQUEST_EXCEEDS_SERVER_LIMIT, "The specified number of waypoints must not be greater than " + config.getMaximumWayPoints() + ".");
        }

        validateMaximumDistances(config, searchParams, coords);

        return rp;
    }

    /**
     * Validate the estimated distance between the coordinates making up waypoints of a route against limits applied in
     * config settings.
     *
     * @param config        The config information for the routing profile
     * @param searchParams  Parameters that will be applied to the routing request
     * @param coords        The coordinates that make up the waypints of the route
     * @throws ServerLimitExceededException Indication that a maximum distance has been exceeded
     */
    private void validateMaximumDistances(RouteProfileConfiguration config, RouteSearchParameters searchParams, Coordinate[] coords) throws ServerLimitExceededException {
        boolean hasAvoidAreas = searchParams.hasAvoidAreas();
        boolean dynamicWeights = searchParams.requiresDynamicWeights();

        if (config.getMaximumDistance() > 0
                || (dynamicWeights && config.getMaximumDistanceDynamicWeights() > 0)
                || (hasAvoidAreas && config.getMaximumDistanceAvoidAreas() > 0)) {

            double totalDist = GeomUtility.calculateDistance(coords);

            if (config.getMaximumDistance() > 0 && totalDist > config.getMaximumDistance())
                throw new ServerLimitExceededException(RoutingErrorCodes.REQUEST_EXCEEDS_SERVER_LIMIT, "The approximated route distance must not be greater than " + config.getMaximumDistance() + " meters.");

            if (dynamicWeights && config.getMaximumDistanceDynamicWeights() > 0 && totalDist > config.getMaximumDistanceDynamicWeights())
                throw new ServerLimitExceededException(RoutingErrorCodes.REQUEST_EXCEEDS_SERVER_LIMIT, "By dynamic weighting, the approximated distance of a route segment must not be greater than " + config.getMaximumDistanceDynamicWeights() + " meters.");
            if (hasAvoidAreas && config.getMaximumDistanceAvoidAreas() > 0 && totalDist > config.getMaximumDistanceAvoidAreas())
                throw new ServerLimitExceededException(RoutingErrorCodes.REQUEST_EXCEEDS_SERVER_LIMIT, "With areas to avoid, the approximated route distance must not be greater than " + config.getMaximumDistanceAvoidAreas() + " meters.");
        }
    }

    /**
     * This function sends the {@link IsochroneSearchParameters} together with the Attributes to the {@link RoutingProfile}.
     *
     * @param parameters The input is a {@link IsochroneSearchParameters}
     * @return Return is a {@link IsochroneMap} holding the calculated data plus statistical data if the attributes where set.
     * @throws InternalServerException
     */
    public IsochroneMap buildIsochrone(IsochroneSearchParameters parameters) throws InternalServerException {

        int profileType = parameters.getRouteParameters().getProfileType();
        RoutingProfile rp = routeProfileCollection.getRouteProfile(profileType, false);

        return rp.buildIsochrone(parameters);
    }

    public MatrixResult computeMatrix(MatrixRequest req) throws StatusCodeException {
        RoutingProfile rp = routeProfileCollection.getRouteProfile(req.getProfileType(), !req.getFlexibleMode());

        if (rp == null)
            throw new InternalServerException(MatrixErrorCodes.UNKNOWN, "Unable to find an appropriate routing profile.");

        return rp.computeMatrix(req);
    }
}
