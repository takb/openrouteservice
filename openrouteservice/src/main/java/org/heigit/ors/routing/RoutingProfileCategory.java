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
package org.heigit.ors.routing;

import com.graphhopper.routing.util.EncodingManager;

public class RoutingProfileCategory {
	public static final int UNKNOWN = 0;
	public static final int DRIVING = 1;
	public static final int CYCLING = 2;
	public static final int WALKING = 4;
	public static final int WHEELCHAIR = 8;
	
	public static int getFromRouteProfile(int profileType) {
		if (RoutingProfileType.isDriving(profileType))
			return RoutingProfileCategory.DRIVING;

		if (RoutingProfileType.isCycling(profileType))
			return RoutingProfileCategory.CYCLING;
		
		if (RoutingProfileType.isWalking(profileType))
			return RoutingProfileCategory.WALKING;
		
		if (RoutingProfileType.WHEELCHAIR == profileType)
			return RoutingProfileCategory.WHEELCHAIR;
		
		return RoutingProfileCategory.UNKNOWN;
	}

	public static int getFromEncoder(EncodingManager encodingManager) {
		if (encodingManager.hasEncoder("car-ors") || encodingManager.hasEncoder("heavyvehicle"))
			return RoutingProfileCategory.DRIVING;

		if (encodingManager.hasEncoder("bike") || encodingManager.hasEncoder("mtb") || encodingManager.hasEncoder("racingbike")
		 || encodingManager.hasEncoder("safetybike") || encodingManager.hasEncoder("cycletourbike") || encodingManager.hasEncoder("electrobike"))
			return RoutingProfileCategory.CYCLING;

		if (encodingManager.hasEncoder("foot") || encodingManager.hasEncoder("hiking"))
			return RoutingProfileCategory.WALKING;

		if (encodingManager.hasEncoder("wheelchair"))
			return RoutingProfileCategory.WHEELCHAIR;

		return RoutingProfileCategory.UNKNOWN;
	}

	private RoutingProfileCategory() {}
}
