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

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.storage.GraphStorage;
import com.graphhopper.util.PMap;
import heigit.ors.exceptions.InternalServerException;
import heigit.ors.routing.graphhopper.extensions.edgefilters.*;
import heigit.ors.routing.parameters.ProfileParameters;
import heigit.ors.routing.parameters.VehicleParameters;
import heigit.ors.routing.parameters.WheelchairParameters;
import heigit.ors.routing.traffic.RealTrafficDataProvider;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

public class RouteSearchContext {
	private GraphHopper graphHopper;
	private EdgeFilter edgeFilter;
	private FlagEncoder encoder;
	
	private PMap properties;

	public RouteSearchContext(GraphHopper gh, EdgeFilter edgeFilter, FlagEncoder encoder)
	{
		this.graphHopper = gh;
		this.edgeFilter = edgeFilter;
		this.encoder = encoder;
	}

	/**
	 * Create a search context based on the search parameters passed in and the instance of GraphHopper being used.
	 *
	 * @param searchParams              The parameters that should be used to generate the {@link RouteSearchContext}
	 * @throws InternalServerException  When an error occurs in the context creation or an invalid encoder name is used
	 */
	public RouteSearchContext(GraphHopper graphHopper, RouteSearchParameters searchParams) throws InternalServerException {

		int profileType = searchParams.getProfileType();
		String encoderName = RoutingProfileType.getEncoderName(profileType);

		if ("UNKNOWN".equals(encoderName))
			throw new InternalServerException(RoutingErrorCodes.UNKNOWN, "unknown vehicle profile.");

		if (!graphHopper.getEncodingManager().supports(encoderName)) {
			throw new InternalServerException("Vehicle " + encoderName + " unsupported. " + "Supported are: "
					+ graphHopper.getEncodingManager());
		}

		FlagEncoder flagEncoder = graphHopper.getEncodingManager().getEncoder(encoderName);
		GraphStorage gs = graphHopper.getGraphHopperStorage();

		EdgeFilterSequence edgeFilters = setupEdgeFilters(searchParams, flagEncoder, gs);

		this.graphHopper = graphHopper;
		this.edgeFilter = edgeFilters;
		this.encoder = flagEncoder;

		properties = generateProperties(searchParams);
	}

	/**
	 * Generate an {@link PMap} object to be used by GraphHopper based on the search parameters
	 *
	 * @param searchParams  The items to use in the route request
	 * @return              An {@link PMap} object containing the various properties needed for route calculation
	 */
	private PMap generateProperties(RouteSearchParameters searchParams) {
		PMap props = new PMap();

		if (searchParams.hasAvoidAreas()) {
			props.put("avoid_areas", true);
		}

		if (searchParams.hasAvoidFeatures()) {
			props.put("avoid_features", searchParams.getAvoidFeatureTypes());
		}

		if (searchParams.hasAvoidCountries()) {
			props.put("avoid_countries", Arrays.toString(searchParams.getAvoidCountries()));
		}

		ProfileParameters profileParams = searchParams.getProfileParameters();
		if (profileParams != null && profileParams.hasWeightings()) {
			props.put("custom_weightings", true);
			Iterator<ProfileWeighting> iterator = profileParams.getWeightings().getIterator();
			while (iterator.hasNext()) {
				ProfileWeighting weighting = iterator.next();
				if (!weighting.getParameters().isEmpty()) {
					String name = ProfileWeighting.encodeName(weighting.getName());
					for (Map.Entry<String, String> kv : weighting.getParameters().getMap().entrySet())
						props.put(name + kv.getKey(), kv.getValue());
				}
			}
		}

		int profileType = searchParams.getProfileType();

		/* Live traffic filter - currently disabled */
		if (searchParams.getConsiderTraffic()) {
			RealTrafficDataProvider trafficData = RealTrafficDataProvider.getInstance();
			if (RoutingProfileType.isDriving(profileType) && searchParams.getWeightingMethod() != WeightingMethod.SHORTEST && trafficData.isInitialized()) {
				props.put("weighting_traffic_block", true);
			}
		}

		return props;
	}

	/**
	 * Generate an {@link EdgeFilterSequence} object that contains all of the different edge filters needed for the route search.
	 * These {@link EdgeFilter}s are then use din the routing process to filter out specific edges from the graph.
	 *
	 * @param searchParams              Various parameters to use in the search process.
	 * @param flagEncoder               The {@link FlagEncoder} that the route request is to be made against
	 * @param graphStorage              The {@link GraphStorage} object that contains the full graph
	 * @return                          An {@link EdgeFilterSequence} containing all the {@link EdgeFilter}s that should
	 *                                  be applied to the graph
	 * @throws InternalServerException  An error in the determination of edge filters.
	 */
	private EdgeFilterSequence setupEdgeFilters(RouteSearchParameters searchParams, FlagEncoder flagEncoder, GraphStorage graphStorage) throws InternalServerException {
		int profileType = searchParams.getProfileType();
		ProfileParameters profileParams = searchParams.getProfileParameters();
		/* Initialize an edge filter sequence using a default edge filter */

		EdgeFilterSequence edgeFilters = new EdgeFilterSequence(new DefaultEdgeFilter(flagEncoder));

		/* Heavy vehicle filter */
		if (RoutingProfileType.isHeavyVehicle(profileType) && searchParams.hasParameters(VehicleParameters.class)) {
			VehicleParameters vehicleParams = (VehicleParameters) profileParams;

			if (vehicleParams.hasAttributes()) {
				if (profileType == RoutingProfileType.DRIVING_HGV)
					edgeFilters.add(new HeavyVehicleEdgeFilter(flagEncoder, searchParams.getVehicleType(), vehicleParams, graphStorage));
				else if (profileType == RoutingProfileType.DRIVING_EMERGENCY)
					edgeFilters.add(new EmergencyVehicleEdgeFilter(vehicleParams, graphStorage));
			}
		}

		/* Wheelchair filter */
		if (profileType == RoutingProfileType.WHEELCHAIR && searchParams.hasParameters(WheelchairParameters.class)) {
			edgeFilters.add(new WheelchairEdgeFilter((WheelchairParameters) profileParams, graphStorage));
		}

		/* Avoid areas */
		if (searchParams.hasAvoidAreas()) {
			edgeFilters.add(new AvoidAreasEdgeFilter(searchParams.getAvoidAreas()));
		}

		/* Avoid features */
		if (searchParams.hasAvoidFeatures()) {
			edgeFilters.add(new AvoidFeaturesEdgeFilter(profileType, searchParams, graphStorage));
		}

		/* Avoid borders of some form */
		if ((searchParams.hasAvoidBorders() || searchParams.hasAvoidCountries()) &&
				(RoutingProfileType.isDriving(profileType) || RoutingProfileType.isCycling(profileType))) {
			edgeFilters.add(new AvoidBordersEdgeFilter(searchParams, graphStorage));
		}

		if (searchParams.getConsiderTraffic()) {
			RealTrafficDataProvider trafficData = RealTrafficDataProvider.getInstance();
			if (RoutingProfileType.isDriving(profileType) && searchParams.getWeightingMethod() != WeightingMethod.SHORTEST && trafficData.isInitialized()) {
				edgeFilters.add(new BlockedEdgesEdgeFilter(flagEncoder, trafficData.getBlockedEdges(graphStorage), trafficData.getHeavyVehicleBlockedEdges(graphStorage)));
			}
		}

		return edgeFilters;
	}

	public FlagEncoder getEncoder() {
		return encoder;
	}

	public EdgeFilter getEdgeFilter() {
		return edgeFilter;
	}

	public GraphHopper getGraphHopper() {
		return graphHopper;
	}
	
	public PMap getProperties()
	{
		return properties;
	}
	
	public void setProperties(PMap value)
	{
		properties = value;
	}
}
