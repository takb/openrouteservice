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

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.*;
import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.CHGraph;
import com.graphhopper.storage.Graph;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.util.*;
import com.graphhopper.util.shapes.BBox;
import com.typesafe.config.Config;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import heigit.ors.exceptions.InternalServerException;
import heigit.ors.exceptions.StatusCodeException;
import heigit.ors.isochrones.IsochroneMap;
import heigit.ors.isochrones.IsochroneMapBuilderFactory;
import heigit.ors.isochrones.IsochroneSearchParameters;
import heigit.ors.isochrones.IsochronesErrorCodes;
import heigit.ors.mapmatching.MapMatcher;
import heigit.ors.mapmatching.RouteSegmentInfo;
import heigit.ors.mapmatching.hmm.HiddenMarkovMapMatcher;
import heigit.ors.matrix.*;
import heigit.ors.matrix.algorithms.MatrixAlgorithm;
import heigit.ors.matrix.algorithms.MatrixAlgorithmFactory;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import heigit.ors.routing.graphhopper.extensions.*;
import heigit.ors.routing.graphhopper.extensions.storages.GraphStorageUtils;
import heigit.ors.routing.traffic.RealTrafficDataProvider;
import heigit.ors.services.matrix.MatrixServiceSettings;
import heigit.ors.util.DebugUtility;
import heigit.ors.util.RuntimeUtility;
import heigit.ors.util.StringUtility;
import heigit.ors.util.TimeUtility;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

/**
 * This class generates {@link RoutingProfile} classes and is used by mostly all service classes e.g.
 * <p>
 * {@link heigit.ors.services.isochrones.requestprocessors.json.JsonIsochronesRequestProcessor}
 * <p>
 * {@link RoutingProfileManager} etc.
 *
 * @author Openrouteserviceteam
 * @author Julian Psotta, julian@openrouteservice.org
 */
public class RoutingProfile {
    private static final Logger LOGGER = Logger.getLogger(RoutingProfileManager.class.getName());
    private static int profileIdentifier = 0;
    private static final Object lockObj = new Object();

    private ORSGraphHopper graphHopper;
    private boolean useTrafficInfo;
    private Integer[] availableRoutingProfiles;
    private Integer graphhopperInstanceCount;
    private boolean isUpdateInProgress;
    private MapMatcher mapMatcher;

    private RouteProfileConfiguration profileConfig;

    public RoutingProfile(String osmFile, RouteProfileConfiguration rpc, RoutingProfilesCollection profiles, RoutingProfileLoadContext loadCntx) throws InternalServerException {
        availableRoutingProfiles = rpc.getProfilesTypes();
        graphhopperInstanceCount = 0;
        useTrafficInfo = false;
        if (hasCarPreferences())
            useTrafficInfo = rpc.getUseTrafficInformation();

        graphHopper = initGraphHopper(osmFile, rpc, profiles, loadCntx);

        profileConfig = rpc;
    }

    /**
     * Initiate GraphHopper instance.
     *
     * @param osmFilePath               Path to the file containing the OSM data
     * @param config                    The configuration options for generating and querying graphs
     * @param profiles                  The profiles that will have graphs generated
     * @param loadCntx                  Context for loading external data (i.e. elevation)
     * @return                          An instance of {@link ORSGraphHopper} with all active graphs loaded
     * @throws InternalServerException  Thrown when there was an error somewhere in the loading process
     */
    public static ORSGraphHopper initGraphHopper(String osmFilePath, RouteProfileConfiguration config, RoutingProfilesCollection profiles, RoutingProfileLoadContext loadCntx) throws InternalServerException {
        CmdArgs args = createGHSettings(osmFilePath, config);

        RoutingProfile refProfile = null;

        // TODO: Investigate if the reference profile code can be removed
        try {
            refProfile = profiles.getRouteProfile(RoutingProfileType.DRIVING_CAR);
        } catch (Exception ex) {
            // The refProfile was used for adding street names to things like paths and cycleways that are detached
            // from the road they follow.
            // Currently refProfile is always null
        }

        int profileId = 0;
        synchronized (lockObj) {
            profileIdentifier++;
            profileId = profileIdentifier;
        }

        long startTime = System.currentTimeMillis();

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(String.format("[%d] Profiles: '%s', location: '%s'.", profileId, config.getProfiles(), config.getGraphPath()));
        }

        GraphProcessContext gpc = new GraphProcessContext(config);

        ORSGraphHopper gh = new ORSGraphHopper(gpc, config.getUseTrafficInformation(), refProfile);

        ORSDefaultFlagEncoderFactory flagEncoderFactory = new ORSDefaultFlagEncoderFactory();
        gh.setFlagEncoderFactory(flagEncoderFactory);

        gh.init(args);

        // MARQ24: make sure that we only use ONE instance of the ElevationProvider across the multiple vehicle profiles
        // so the caching for elevation data will/can be reused across different vehicles. [the loadCntx is a single
        // Object that will shared across the (potential) multiple running instances]
        if(loadCntx.getElevationProvider() != null) {
            gh.setElevationProvider(loadCntx.getElevationProvider());
        }else {
            loadCntx.setElevationProvider(gh.getElevationProvider());
        }
        gh.setGraphStorageFactory(new ORSGraphStorageFactory(gpc.getStorageBuilders()));
        gh.setWeightingFactory(new ORSWeightingFactory(RealTrafficDataProvider.getInstance()));

        gh.importOrLoad();

        if (LOGGER.isInfoEnabled()) {
            EncodingManager encodingMgr = gh.getEncodingManager();
            GraphHopperStorage ghStorage = gh.getGraphHopperStorage();
            // MARQ24 MOD START
            // Same here as for the 'gh.getCapacity()' below - the 'encodingMgr.getUsedBitsForFlags()' method requires
            // the EncodingManager to be patched - and this is ONLY required for this logging line... which is IMHO
            // not worth it (and since we are not sharing FlagEncoders for mutiple vehicles this info is anyhow
            // obsolete
            LOGGER.info(String.format("[%d] FlagEncoders: %s, bits used [UNKNOWN]/%d.", profileId, encodingMgr.fetchEdgeEncoders().size(), encodingMgr.getBytesForFlags() * 8));
            // the 'getCapacity()' impl is the root cause of having a copy of the gh 'com.graphhopper.routing.lm.PrepareLandmarks'
            // class (to make the store) accessible (getLandmarkStorage()) - IMHO this is not worth it!
            // so gh.getCapacity() will be removed!
            LOGGER.info(String.format("[%d] Capacity: [UNKNOWN]. (edges - %s, nodes - %s)", profileId, ghStorage.getEdges(), ghStorage.getNodes()));
            // MARQ24 MOD END
            LOGGER.info(String.format("[%d] Total time: %s.", profileId, TimeUtility.getElapsedTime(startTime, true)));
            LOGGER.info(String.format("[%d] Finished at: %s.", profileId, new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())));
            LOGGER.info("                              ");
        }

        // Make a stamp which help tracking any changes in the size of OSM file.
        File osmFile = new File(osmFilePath);
        Path timeStampFilePath = Paths.get(config.getGraphPath(), "stamp.txt");
        File stampFile = timeStampFilePath.toFile();
        if (!stampFile.exists()) {
            try {
                Files.write(timeStampFilePath, Long.toString(osmFile.length()).getBytes());
            } catch (IOException e) {
                throw new InternalServerException("Cannot write stamp file");
            }
        }

        return gh;
    }

    public long getCapacity() {
        GraphHopperStorage graph = graphHopper.getGraphHopperStorage();
        return graph.getCapacity() + GraphStorageUtils.getCapacity(graph.getExtension());
    }

    /**
     * Generated the {@link CmdArgs} that are required by GraphHopper for initialising the GH engine
     *
     * @param sourceFile    Path to the source data (e.g. osm pbf file) that will be used for generating graphs
     * @param config        The configuration data used for setting up GraphHopper
     * @return              A {@link CmdArgs} object containing the properties needed to start GH
     */
    protected static CmdArgs createGHSettings(String sourceFile, RouteProfileConfiguration config) {
        CmdArgs args = new CmdArgs();
        args.put("graph.dataaccess", "RAM_STORE");
        args.put("datareader.file", sourceFile);
        args.put("graph.location", config.getGraphPath());
        args.put("graph.bytes_for_flags", config.getEncoderFlagsSize());

        if (!config.getInstructions())
            args.put("instructions", false);
        if (config.getElevationProvider() != null && config.getElevationCachePath() != null) {
            args.put("graph.elevation.provider", StringUtility.trimQuotes(config.getElevationProvider()));
            args.put("graph.elevation.cache_dir", StringUtility.trimQuotes(config.getElevationCachePath()));
            args.put("graph.elevation.dataaccess", StringUtility.trimQuotes(config.getElevationDataAccess()));
            args.put("graph.elevation.clear", config.getElevationCacheClear());
        }

        args.put(generatePreparationStageProperties(config));

        args.put(generateExecutionStageProperties(config));

        String[] encoderOpts = !Helper.isEmpty(config.getEncoderOptions()) ? config.getEncoderOptions().split(",") : null;
        Integer[] profiles = config.getProfilesTypes();

        StringBuilder flagEncoders = new StringBuilder();
        for (int i = 0; i < profiles.length; i++) {
            if (encoderOpts == null)
                flagEncoders.append(RoutingProfileType.getEncoderName(profiles[i]));
            else
                flagEncoders.append(RoutingProfileType.getEncoderName(profiles[i])).append("|").append(encoderOpts[i]);
            if (i < profiles.length - 1)
                flagEncoders.append(",");
        }

        args.put("graph.flag_encoders", flagEncoders.toString().toLowerCase());

        args.put("index.high_resolution", 500);

        return args;
    }

    /**
     * Generate the settings needed by GraphHopper for preparing graphs.
     *
     * @param config    Config settings for the whole GH process
     * @return          An {@link PMap} containing the properties needed by GH for the graph preparation stage
     */
    protected static PMap generatePreparationStageProperties(RouteProfileConfiguration config) {
        Config opts = config.getPreparationOpts();
        PMap props = new PMap();

        props.put("prepare.ch.weightings", "no");
        props.put("prepare.lm.weightings", "no");
        props.put("prepare.core.weightings", "no");

        if (opts != null) {
            if (opts.hasPath("min_network_size"))
                props.put("prepare.min_network_size", opts.getInt("min_network_size"));
            if (opts.hasPath("min_one_way_network_size"))
                props.put("prepare.min_one_way_network_size", opts.getInt("min_one_way_network_size"));

            boolean prepareCH = false;

            if (opts.hasPath("methods")) {
                if (opts.hasPath("methods.ch")) {
                    prepareCH = true;
                    props.put(generateCHPreparationProperties(opts));
                }
                if (opts.hasPath("methods.lm")) {
                    props.put(generateLMPreparationOptions(opts));
                }
                if (opts.hasPath("methods.core")) {
                    props.put(generateCorePreparationOptions(opts));
                }
            }

            if (config.getOptimize() && !prepareCH)
                props.put("graph.do_sort", true);
        }

        return props;
    }

    /**
     * Generate the properties for Contraction Hierarchies based on the values passed in the configuration.
     *
     * @param opts  The graph preparation configuration options
     * @return      An {@link PMap} containing the configuration properties for the CH preparation stage
     */
    private static PMap generateCHPreparationProperties(Config opts) {
        PMap props = new PMap();
        boolean prepareCH = true;
        Config chOpts = opts.getConfig("methods.ch");

        if (chOpts.hasPath("enabled")) {
            prepareCH = chOpts.getBoolean("enabled");
            if (!prepareCH)
                props.put("prepare.ch.weightings", "no");
        }

        if (prepareCH) {
            if (chOpts.hasPath("threads"))
                props.put("prepare.ch.threads", chOpts.getInt("threads"));
            if (chOpts.hasPath("weightings"))
                props.put("prepare.ch.weightings", StringUtility.trimQuotes(chOpts.getString("weightings")));
        }

        return props;
    }

    /**
     * Generate the properties for Landmarks (ALT) based on the values passed in the configuration.
     *
     * @param opts  The graph preparation configuration options
     * @return      An {@link PMap} containing the configuration properties for the ALT preparation stage
     */
    private static PMap generateLMPreparationOptions(Config opts) {
        PMap props = new PMap();
        boolean prepareLM = true;
        Config lmOpts = opts.getConfig("methods.lm");

        if (lmOpts.hasPath("enabled")) {
            prepareLM = lmOpts.getBoolean("enabled");
            if (!prepareLM)
                props.put("prepare.lm.weightings", "no");
        }

        if (prepareLM) {
            if (lmOpts.hasPath("threads"))
                props.put("prepare.lm.threads", lmOpts.getInt("threads"));
            if (lmOpts.hasPath("weightings"))
                props.put("prepare.lm.weightings", StringUtility.trimQuotes(lmOpts.getString("weightings")));
            if (lmOpts.hasPath("landmarks"))
                props.put("prepare.lm.landmarks", lmOpts.getInt("landmarks"));
        }

        return props;
    }

    /**
     * Generate the properties for the Core (Core-ALT) based on the values passed in the configuration.
     *
     * @param opts  The graph preparation configuration options
     * @return      An {@link PMap} containing the configuration properties for the Core-ALT preparation stage
     */
    private static PMap generateCorePreparationOptions(Config opts) {
        PMap props = new PMap();
        boolean prepareCore = true;
        Config coreOpts = opts.getConfig("methods.core");

        if (coreOpts.hasPath("enabled")) {
            prepareCore = coreOpts.getBoolean("enabled");
            if (!prepareCore)
                props.put("prepare.ch.weightings", "no");
        }

        if (prepareCore) {
            if (coreOpts.hasPath("threads"))
                props.put("prepare.core.threads", coreOpts.getInt("threads"));
            if (coreOpts.hasPath("weightings"))
                props.put("prepare.core.weightings", StringUtility.trimQuotes(coreOpts.getString("weightings")));
            if (coreOpts.hasPath("lmsets"))
                props.put("prepare.corelm.lmsets", StringUtility.trimQuotes(coreOpts.getString("lmsets")));
            if (coreOpts.hasPath("landmarks"))
                props.put("prepare.corelm.landmarks", coreOpts.getInt("landmarks"));
        }

        return props;
    }

    /**
     * Generate the settings needed by GraphHopper for executing route generation.
     *
     * @param config    Config settings for the whole GH process
     * @return          An {@link PMap} containing the properties needed by GH for the routing stage
     */
    private static PMap generateExecutionStageProperties(RouteProfileConfiguration config) {
        PMap props = new PMap();
        Config opts = config.getExecutionOpts();
        if (opts != null) {
            if (opts.hasPath("methods.ch")) {
                props.put(disablingOfMethodArgument(opts, "ch"));
            }
            if (opts.hasPath("methods.core")) {
                props.put(disablingOfMethodArgument(opts, "core"));
            }
            if (opts.hasPath("methods.lm")) {
                props.put(disablingOfMethodArgument(opts, "lm"));
                props.put(activeLandmarksProperty(opts, "lm"));
            }
            if (opts.hasPath("methods.corelm")) {
                props.put(disablingOfMethodArgument(opts, "corelm"));
                props.put(activeLandmarksProperty(opts, "corelm"));
            }
        }

        return props;
    }

    /**
     * Generate a property for the given method that disables the method based on the values in the configuration.
     *
     * @param opts      Execution options for the GH instance
     * @param method    The string representation of the method to be checked
     * @return          Properties with the disabling of the method set to the value indicated in the configuration
     */
    private static PMap disablingOfMethodArgument(Config opts, String method) {
        PMap props = new PMap();
        Config methodOptions = opts.getConfig("methods." + method);

        if(methodOptions.hasPath("disabling_allowed")) {
            if ("corelm".equals(method))
                method = "lm";
            props.put("routing." + method + ".disabling_allowed", methodOptions.getBoolean("disabling_allowed"));
        }

        return props;
    }

    /**
     * Determine the active landmarks that should be used in the routing process.
     *
     * @param opts      Execution configuraiton options for GH
     * @param method    String representation of the method whoe active landmarks should be obtained
     * @return          Properties containing the GH settings for the active landmarks for the specified method
     */
    private static PMap activeLandmarksProperty(Config opts, String method) {
        PMap props = new PMap();
        Config methodOptions = opts.getConfig("methods." + method);
        if (methodOptions.hasPath("active_landmarks"))
            props.put("routing." + method + ".active_landmarks", methodOptions.getInt("active_landmarks"));

        return props;
    }

    /**
     * Reinitialise the graphHopper instance
     *
     * @param graphHopperInstance       A instance of GraphHopper to use as the base for the new initialisation
     * @throws InternalServerException  An error occuring during the reinitialising process
     */
    public void updateGH(GraphHopper graphHopperInstance) throws InternalServerException {
        if (graphHopperInstance == null)
            throw new InternalServerException("GraphHopper instance is null.");

        try {
            isUpdateInProgress = true;
            while (true) {
                if (!isGHUsed()) {
                    GraphHopper ghOld = graphHopper;

                    ghOld.close();
                    ghOld.clean();

                    graphHopperInstance.close();

                    RuntimeUtility.clearMemory(LOGGER);

                    // Change the content of the graph folder
                    String oldLocation = ghOld.getGraphHopperLocation();
                    File dstDir = new File(oldLocation);
                    File srcDir = new File(graphHopperInstance.getGraphHopperLocation());
                    FileUtils.copyDirectory(srcDir, dstDir, true);
                    FileUtils.deleteDirectory(srcDir);

                    RoutingProfileLoadContext loadCntx = new RoutingProfileLoadContext();

                    graphHopper = initGraphHopper(ghOld.getDataReaderFile(), profileConfig, RoutingProfileManager.getInstance().getProfiles(), loadCntx);

                    loadCntx.releaseElevationProviderCache();

                    break;
                }

                Thread.sleep(2000);
            }
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }

        isUpdateInProgress = false;
    }

    /**
     * Method to keep the thread sleeping whilst an update is in progress.
     *
     * @throws InternalServerException  Thrown when an update is taking too long or a sleep process fails
     */
    private void waitForUpdateCompletion() throws InternalServerException {
        if (isUpdateInProgress) {
            long startTime = System.currentTimeMillis();

            while (isUpdateInProgress) {
                long curTime = System.currentTimeMillis();
                if (curTime - startTime > 600000) {
                    throw new InternalServerException("The route profile is currently being updated.");
                }

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InternalServerException("The route profile is currently being updated.");
                }
            }
        }
    }

    /**
     * Compute a matrix based on the request parameters passed in.
     *
     * @param req                   The parameters to use for generating the matrix
     * @return                      A {@link MatrixResult} object containing all of the information generated for the matrix
     * @throws StatusCodeException  Thrown when an error occurs within the matrix calculation process
     */
    public MatrixResult computeMatrix(MatrixRequest req) throws StatusCodeException {
        MatrixResult mtxResult;

        GraphHopper gh = getGraphhopper();
        String encoderName = RoutingProfileType.getEncoderName(req.getProfileType());
        FlagEncoder flagEncoder = gh.getEncodingManager().getEncoder(encoderName);

        MatrixAlgorithm alg = MatrixAlgorithmFactory.createAlgorithm(req, gh, flagEncoder);

        if (alg == null)
            throw new InternalServerException("Unable to create an algorithm to for computing distance/duration matrix.");

        try {
            String weightingStr = Helper.isEmpty(req.getWeightingMethod()) ? WeightingMethod.getName(WeightingMethod.FASTEST) : req.getWeightingMethod();
            Graph graph;
            if (!req.getFlexibleMode() && gh.getCHFactoryDecorator().isEnabled() && gh.getCHFactoryDecorator().getWeightingsAsStrings().contains(weightingStr))
                graph = gh.getGraphHopperStorage().getGraph(CHGraph.class);
            else
                graph = gh.getGraphHopperStorage().getBaseGraph();

            MatrixSearchContextBuilder builder = new MatrixSearchContextBuilder(gh.getLocationIndex(), new DefaultEdgeFilter(flagEncoder), req.getResolveLocations());
            MatrixSearchContext mtxSearchCntx = builder.create(graph, req.getSources(), req.getDestinations(), MatrixServiceSettings.getMaximumSearchRadius());

            HintsMap hintsMap = new HintsMap();
            hintsMap.setWeighting(weightingStr);
            Weighting weighting = new ORSWeightingFactory(RealTrafficDataProvider.getInstance()).createWeighting(hintsMap, gh.getTraversalMode(), flagEncoder, graph, null, gh.getGraphHopperStorage());

            alg.init(req, gh, mtxSearchCntx.getGraph(), flagEncoder, weighting);

            mtxResult = alg.compute(mtxSearchCntx.getSources(), mtxSearchCntx.getDestinations(), req.getMetrics());
        } catch (StatusCodeException e) {
            LOGGER.error(e);
            throw e;
        } catch (Exception e) {
            LOGGER.error(e);
            throw new InternalServerException(MatrixErrorCodes.UNKNOWN, "Unable to compute a distance/duration matrix.");
        }

        return mtxResult;
    }

    /**
     * @deprecated Map matching does not work when graphs are built individually.
     */
    @Deprecated
    public RouteSegmentInfo[] getMatchedSegments(Coordinate[] locations, double searchRadius, boolean bothDirections)
            throws InternalServerException {
        RouteSegmentInfo[] rsi = null;

        waitForUpdateCompletion();

        beginUseGH();

        try {
            rsi = getMatchedSegmentsInternal(locations, searchRadius, null, bothDirections);

            endUseGH();
        } catch (Exception ex) {
            endUseGH();

            throw ex;
        }

        return rsi;
    }

    /**
     * @deprecated Map matching does not work when graphs are built individually.
     */
    @Deprecated
    private RouteSegmentInfo[] getMatchedSegmentsInternal(Coordinate[] locations,
                                                          double searchRadius, EdgeFilter edgeFilter, boolean bothDirections) {
        if (mapMatcher == null) {
            mapMatcher = new HiddenMarkovMapMatcher();
            mapMatcher.setGraphHopper(graphHopper);
        }

        mapMatcher.setSearchRadius(searchRadius);
        mapMatcher.setEdgeFilter(edgeFilter);

        return mapMatcher.match(locations, bothDirections);
    }

    /**
     * Compute a route segment (a route between two waypoints) for this routing profile based on the parameters provided
     * @param segmentProperties         The properties for this particular route segment
     * @param routeProcessContext       The {@link RouteProcessContext} object that contains items needed for the route generation as a whole
     * @return                          An {@link GHResponse} contianing the result of the routing request as obtained from GraphHopper
     * @throws InternalServerException  Thrown when there is an error in the route generation process
     */
    public GHResponse computeRouteSegment(RouteSegmentProperties segmentProperties, RouteProcessContext routeProcessContext)
            throws InternalServerException {

        GHResponse resp;

        waitForUpdateCompletion();

        beginUseGH();

        try {
            GHRequest routingRequest = new GHRequestWrapper(segmentProperties, routeProcessContext, graphHopper, profileConfig).getRequestForGH();

            if (segmentProperties.skippedSegment()) {
                resp = graphHopper.constructFreeHandRoute(routingRequest);
            } else {
                graphHopper.setSimplifyResponse(segmentProperties.simplifyGeometry());
                resp = graphHopper.route(routingRequest);
            }
            if (DebugUtility.isDebug() && !segmentProperties.skippedSegment()) {
                LOGGER.info("visited_nodes.average - " + resp.getHints().get("visited_nodes.average", ""));
            }
            if (DebugUtility.isDebug() && segmentProperties.skippedSegment()) {
                LOGGER.info("skipped segment - " + resp.getHints().get("skipped_segment", ""));
            }
            endUseGH();
        } catch (Exception ex) {
            endUseGH();

            LOGGER.error(ex);

            throw new InternalServerException(RoutingErrorCodes.UNKNOWN, "Unable to compute a route");
        }

        return resp;
    }

    /**
     * This function creates the actual {@link IsochroneMap}.
     * So the first step in the function is a checkup on that.
     *
     * @param parameters                The input are {@link IsochroneSearchParameters}
     * @return                          The return will be an {@link IsochroneMap}
     * @throws InternalServerException  A problem occured in building the isochrone
     */
    public IsochroneMap buildIsochrone(IsochroneSearchParameters parameters) throws InternalServerException {

        IsochroneMap result;
        waitForUpdateCompletion();

        beginUseGH();

        try {
            RouteSearchContext searchCntx = new RouteSearchContext(graphHopper, parameters.getRouteParameters());

            IsochroneMapBuilderFactory isochroneMapBuilderFactory = new IsochroneMapBuilderFactory(searchCntx);
            result = isochroneMapBuilderFactory.buildMap(parameters);

            endUseGH();
        } catch (Exception ex) {
            endUseGH();

            LOGGER.error(ex);

            throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to build an isochrone map.");
        }

        if (result.getIsochronesCount() > 0) {
            result.addAttributes(parameters);
        }

        return result;
    }

    public Geometry getEdgeGeometry(int edgeId, int mode, int adjnodeid) {
        EdgeIteratorState iter = graphHopper.getGraphHopperStorage().getEdgeIteratorState(edgeId, adjnodeid);
        PointList points = iter.fetchWayGeometry(mode);
        if (points.size() > 1) {
            Coordinate[] coords = new Coordinate[points.size()];
            for (int i = 0; i < points.size(); i++) {
                double x = points.getLon(i);
                double y = points.getLat(i);
                coords[i] = new Coordinate(x, y);
            }
            return new GeometryFactory().createLineString(coords);
        }
        return null;
    }

    public Map<Integer, Long> getTmcEdges() {
        return graphHopper.getTmcGraphEdges();
    }

    public Map<Long, ArrayList<Integer>> getOsmId2edgeIds() {
        return graphHopper.getOsmId2EdgeIds();
    }

    public ORSGraphHopper getGraphhopper() {
        return graphHopper;
    }

    public BBox getBounds() {
        return graphHopper.getGraphHopperStorage().getBounds();
    }

    public StorableProperties getGraphProperties() {
        return graphHopper.getGraphHopperStorage().getProperties();
    }

    public String getGraphLocation() {
        return graphHopper == null ? null : graphHopper.getGraphHopperStorage().getDirectory().toString();
    }

    public RouteProfileConfiguration getConfiguration() {
        return profileConfig;
    }

    public Integer[] getPreferences() {
        return availableRoutingProfiles;
    }

    public boolean hasCarPreferences() {
        for (int profileType : availableRoutingProfiles) {
            if (RoutingProfileType.isDriving(profileType))
                return true;
        }

        return false;
    }

    public boolean isCHEnabled() {
        return graphHopper != null && graphHopper.isCHEnabled();
    }

    public boolean useTrafficInformation() {
        return useTrafficInfo;
    }

    public void close() {
        graphHopper.close();
    }

    private synchronized boolean isGHUsed() {
        return graphhopperInstanceCount > 0;
    }

    private synchronized void beginUseGH() {
        graphhopperInstanceCount++;
    }

    private synchronized void endUseGH() {
        graphhopperInstanceCount--;
    }

    public int hashCode() {
        return graphHopper.getGraphHopperStorage().getDirectory().getLocation().hashCode();
    }
}
