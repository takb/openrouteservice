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
package heigit.ors.isochrones;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import heigit.ors.common.TravelRangeType;
import heigit.ors.exceptions.InternalServerException;
import heigit.ors.isochrones.statistics.StatisticsProvider;
import heigit.ors.isochrones.statistics.StatisticsProviderConfiguration;
import heigit.ors.isochrones.statistics.StatisticsProviderFactory;
import heigit.ors.services.isochrones.IsochronesServiceSettings;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IsochroneMap {
	private static final Logger LOGGER = Logger.getLogger(IsochroneMap.class.getName());
	private int travellerId;
	private Envelope envelope;
	private List<Isochrone> isochrones;
	private Coordinate center;

    public IsochroneMap(int travellerId, Coordinate center)
	{
		this.travellerId = travellerId;
		this.center = center;
		isochrones = new ArrayList<>();
		envelope = new Envelope();
	}

	/**
	 * Add the various attributes to an isochrone based on the {@link IsochroneSearchParameters} that were requested
	 *
	 * @param parameters				The parameters that are being requested for the isochrone
	 * @throws InternalServerException	An error whilst calculating one of the property values
	 */
	public void addAttributes(IsochroneSearchParameters parameters) throws InternalServerException {
		if (parameters.hasAttribute(IsochroneSearchParameters.POPULATION_ATTR)) {
			addPopulation();
		}

		if (parameters.hasAttribute("reachfactor") || parameters.hasAttribute("area")) {

			for (Isochrone isochrone : this.getIsochrones()) {

				String units = parameters.getUnits();
				String areaUnits = parameters.getAreaUnits();

				if (areaUnits != null)
					units = areaUnits;

				double area = isochrone.calcArea(units);

				if (parameters.hasAttribute("area")) {
					isochrone.setArea(area);
				}

				if (parameters.hasAttribute("reachfactor") && parameters.getRangeType() == TravelRangeType.Time) {
					isochrone.setReachfactor(isochrone.calcReachfactor(units));
				}
			}
		}
	}

	/**
	 * Add population information to the isochrone.
	 *
	 * @throws InternalServerException	A problem relating to accessing or calculating population information.
	 */
	private void addPopulation() throws InternalServerException {
		try {

			Map<StatisticsProviderConfiguration, List<String>> mapProviderToAttrs = new HashMap<>();

			StatisticsProviderConfiguration provConfig = IsochronesServiceSettings.getStatsProviders().get(IsochroneSearchParameters.POPULATION_ATTR);

			if (provConfig != null) {
				List<String> attrList = new ArrayList<>();
				attrList.add(IsochroneSearchParameters.POPULATION_ATTR);
				mapProviderToAttrs.put(provConfig, attrList);
			}

			for (Map.Entry<StatisticsProviderConfiguration, List<String>> entry : mapProviderToAttrs.entrySet()) {
				provConfig = entry.getKey();
				StatisticsProvider provider = StatisticsProviderFactory.getProvider(provConfig.getName(), provConfig.getParameters());
				String[] provAttrs = provConfig.getMappedProperties(entry.getValue());

				for (Isochrone isochrone : this.getIsochrones()) {
					double[] attrValues = provider.getStatistics(isochrone, provAttrs);
					isochrone.setAttributes(entry.getValue(), attrValues, provConfig.getAttribution());
				}
			}
		} catch (Exception ex) {
			LOGGER.error(ex);
			throw new InternalServerException(IsochronesErrorCodes.UNKNOWN, "Unable to compute isochrone " + IsochroneSearchParameters.POPULATION_ATTR + " attribute.");
		}
	}
	
	public int getTravellerId()
	{
		return travellerId;
	}

	public boolean isEmpty()
	{
		return isochrones.isEmpty();
	}

	public Coordinate getCenter() 
	{
		return center;
	}


    public Iterable<Isochrone> getIsochrones()
	{
		return isochrones;
	}
	
	public int getIsochronesCount()
	{
		return isochrones.size();
	}

	public Isochrone getIsochrone(int index)
	{
		return isochrones.get(index);
	}

	public void addIsochrone(Isochrone isochrone)
	{
		isochrones.add(isochrone);
		envelope.expandToInclude(isochrone.getGeometry().getEnvelopeInternal());
	}

	public Envelope getEnvelope()
	{
		return envelope;
	}
}
