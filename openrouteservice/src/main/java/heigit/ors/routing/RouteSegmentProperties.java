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

import com.graphhopper.util.shapes.GHPoint;
import com.vividsolutions.jts.geom.Coordinate;

public class RouteSegmentProperties {
    private Coordinate startCoordinate;
    private Coordinate destinationCoordinate;

    private double startBearing = Double.NaN;
    private double destinationBearing = Double.NaN;

    private double startSearchRadius = Double.NaN;
    private double destinationSearchRadius = Double.NaN;

    private boolean simplifyGeometry;
    private boolean isSkippedSegment;

    private int segmentIndex;

    public RouteSegmentProperties(Coordinate startCoordinate, Coordinate destinationCoordinate, int segmentIndex) {
        this.startCoordinate = startCoordinate;
        this.destinationCoordinate = destinationCoordinate;
        this.segmentIndex = segmentIndex;
    }

    public void setStartBearing(double bearing) {
        this.startBearing = bearing;
    }

    public void setDestinationBearing(double bearing) {
        this.destinationBearing = bearing;
    }

    public void setIsSkippedSegment(boolean skip) {
        this.isSkippedSegment = skip;
    }

    public void setStartSearchRadius(double radius) {
        this.startSearchRadius = radius;
    }

    public void setDestinationSearchRadius(double radius) {
        this.destinationSearchRadius = radius;
    }

    public void setSimplifyGeometry(boolean simplify) {
        this.simplifyGeometry = simplify;
    }

    public double[] getRadii() {
        return new double[] {startSearchRadius, destinationSearchRadius};
    }

    public double getStartBearing() {
        return startBearing;
    }

    public double getDestinationBearing() {
        return destinationBearing;
    }

    public boolean skippedSegment() {
        return isSkippedSegment;
    }

    public boolean simplifyGeometry() {
        return simplifyGeometry;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public int getSegmentNumber() {
        return segmentIndex + 1;
    }

    public Coordinate getStartCoordinate() {
        return startCoordinate;
    }

    public Coordinate getDestinationCoordinate() {
        return destinationCoordinate;
    }

    public GHPoint getStartPoint() {
        return new GHPoint(startCoordinate.y, startCoordinate.x);
    }

    public GHPoint getEndPoint() {
        return new GHPoint(destinationCoordinate.y, destinationCoordinate.x);
    }
}
