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
package org.heigit.ors.routing.graphhopper.extensions;

import com.graphhopper.GraphHopper;
import com.graphhopper.routing.util.EncodingManager;
import org.heigit.ors.routing.graphhopper.extensions.storages.builders.GraphStorageBuilder;
import org.apache.log4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.graphhopper.routing.weighting.Weighting;
import com.graphhopper.storage.GHDirectory;
import com.graphhopper.storage.GraphExtension;
import com.graphhopper.storage.ExtendedStorageSequence;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.GraphStorageFactory;
import com.graphhopper.storage.TurnCostExtension;

public class ORSGraphStorageFactory implements GraphStorageFactory {

	private static final Logger LOGGER = Logger.getLogger(ORSGraphStorageFactory.class.getName());

	private List<GraphStorageBuilder> graphStorageBuilders;

	public ORSGraphStorageFactory(List<GraphStorageBuilder> graphStorageBuilders) {
		this.graphStorageBuilders = graphStorageBuilders;
	}

	@Override
	public GraphHopperStorage createStorage(GHDirectory dir, GraphHopper gh) {
		EncodingManager encodingManager = gh.getEncodingManager();
		GraphExtension geTurnCosts = null;
		ArrayList<GraphExtension> graphExtensions = new ArrayList<>();

		if (encodingManager.needsTurnCostsSupport()) {
			Path path = Paths.get(dir.getLocation(), "turn_costs");
			File fileEdges  = Paths.get(dir.getLocation(), "edges").toFile();
			File fileTurnCosts = path.toFile();

			// First we need to check if turncosts are available. This check is required when we introduce a new feature, but an existing graph does not have it yet.
			if ((!hasGraph(gh) && !fileEdges.exists()) || (fileEdges.exists() && fileTurnCosts.exists()))
				geTurnCosts =  new TurnCostExtension();
		}

		if (graphStorageBuilders != null) {
			for(GraphStorageBuilder builder : graphStorageBuilders) {
				try {
					GraphExtension ext = builder.init(gh);
					if (ext != null)
						graphExtensions.add(ext);
				} catch(Exception ex) {
					LOGGER.error(ex);
				}
			}
		}

		GraphExtension graphExtension = null;

		if (geTurnCosts == null && graphExtensions.isEmpty())
			graphExtension = new GraphExtension.NoOpExtension();
		else if (geTurnCosts != null && !graphExtensions.isEmpty()) {
			ArrayList<GraphExtension> seq = new ArrayList<>();
			seq.add(geTurnCosts);
			seq.addAll(graphExtensions);

			graphExtension = getExtension(seq);
		} else if (geTurnCosts != null) {
			graphExtension = geTurnCosts;
		} else {
			graphExtension = getExtension(graphExtensions);
		}

		if(gh instanceof ORSGraphHopper) {
			if (((ORSGraphHopper) gh).isCoreEnabled())
				((ORSGraphHopper) gh).initCoreAlgoFactoryDecorator();
			if (((ORSGraphHopper) gh).isCoreLMEnabled())
				((ORSGraphHopper) gh).initCoreLMAlgoFactoryDecorator();
		}

		if (gh.getLMFactoryDecorator().isEnabled())
			gh.initLMAlgoFactoryDecorator();

		if (gh.getCHFactoryDecorator().isEnabled())
			gh.initCHAlgoFactoryDecorator();

		List<Weighting> nodeBasedWeightings = new ArrayList<>();
		List<Weighting> edgeBasedWeightings = new ArrayList<>();
		List<String> nodeBasedTypes = new ArrayList<>();
		List<String> edgeBasedTypes = new ArrayList<>();
		if (gh.isCHEnabled()) {
			nodeBasedWeightings.addAll(gh.getCHFactoryDecorator().getNodeBasedWeightings());
			String[] types = new String[gh.getCHFactoryDecorator().getNodeBasedWeightings().size()];
			Arrays.fill(types, "ch");
			nodeBasedTypes.addAll(Arrays.asList(types));
		}
		if (((ORSGraphHopper)gh).isCoreEnabled()) {
			nodeBasedWeightings.addAll(((ORSGraphHopper) gh).getCoreFactoryDecorator().getWeightings());
			String[] types = new String[((ORSGraphHopper) gh).getCoreFactoryDecorator().getWeightings().size()];
			Arrays.fill(types, "core");
			nodeBasedTypes.addAll(Arrays.asList(types));
		}
		if (!nodeBasedWeightings.isEmpty())
			return new GraphHopperStorage(nodeBasedWeightings, edgeBasedWeightings, dir, encodingManager,
				gh.hasElevation(), graphExtension, nodeBasedTypes, edgeBasedTypes);
		else
			return new GraphHopperStorage(dir, encodingManager, gh.hasElevation(), graphExtension);
	}

	private GraphExtension getExtension(ArrayList<GraphExtension> graphExtensions) {
		if (graphExtensions.size() > 1) {
			ArrayList<GraphExtension> seq = new ArrayList<>(graphExtensions);
			return new ExtendedStorageSequence(seq);
		}
		else
			return graphExtensions.isEmpty() ? new GraphExtension.NoOpExtension() : graphExtensions.get(0);
	}

	private boolean hasGraph(GraphHopper gh) {
		try {
			gh.getGraphHopperStorage();
			return true;
		} catch (IllegalStateException ex){
			// do nothing
		} catch(Exception ex) {
			LOGGER.error(ex.getStackTrace());
		}
		return false;
	}
}
