//package heigit.ors.fastisochrones;
//
//import com.graphhopper.routing.Dijkstra;
//import com.graphhopper.routing.EdgeIteratorStateHelper;
//import com.graphhopper.routing.util.TraversalMode;
//import com.graphhopper.routing.weighting.Weighting;
//import com.graphhopper.storage.Graph;
//import com.graphhopper.storage.SPTEntry;
//import com.graphhopper.util.EdgeExplorer;
//import com.graphhopper.util.EdgeIterator;
//
//import java.util.HashMap;
//import java.util.Map;
//
//
//public class OneWaySweepDijkstra extends Dijkstra {
//
//    private Map<Integer, Integer> oneWayMap;
//
//
//    public OneWaySweepDijkstra(Graph graph, Weighting weighting, TraversalMode traversalMode) {
//        super(graph, weighting, traversalMode);
//
//        this.oneWayMap = new HashMap<>();
//    }
//
//
//    public OneWaySweepDijkstra indivReset() {
//        step = "";
//        oneWayMap.clear();
//        return this;
//    }
//
//    @Override
//    public void runAlgo() {
//        EdgeExplorer explorer = outEdgeExplorer;
//        while (true) {
//            visitedNodes++;
//            if (isMaxVisitedNodesExceeded() || finished())
//                break;
//
//            int startNode = currEdge.adjNode;
//            EdgeIterator iter = explorer.setBaseNode(startNode);
//            while (iter.next()) {
//                if (!accept(iter, currEdge.edge))
//                    continue;
//
//                // ORS-GH MOD START
//                // ORG CODE START
//                //int traversalId = traversalMode.createTraversalId(iter, false);
//                //double tmpWeight = weighting.calcWeight(iter, false, currEdge.edge) + currEdge.weight;
//                // ORIGINAL END
//                // TODO: MARQ24 WHY the heck the 'reverseDirection' is not used also for the traversal ID ???
//                int traversalId = traversalMode.createTraversalId(iter, false);
//                // Modification by Maxim Rylov: use originalEdge as the previousEdgeId
//                double tmpWeight = weighting.calcWeight(iter, reverseDirection, currEdge.originalEdge) + currEdge.weight;
//                // ORS-GH MOD END
//                if (Double.isInfinite(tmpWeight))
//                    continue;
//
//                SPTEntry nEdge = fromMap.get(traversalId);
//                if (nEdge == null) {
//                    nEdge = new SPTEntry(iter.getEdge(), iter.getAdjNode(), tmpWeight);
//                    nEdge.parent = currEdge;
//                    // ORS-GH MOD START
//                    // Modification by Maxim Rylov: Assign the original edge id.
//                    nEdge.originalEdge = EdgeIteratorStateHelper.getOriginalEdge(iter);
//                    // ORS-GH MOD END
//                    fromMap.put(traversalId, nEdge);
//                    fromHeap.add(nEdge);
//                } else if (nEdge.weight > tmpWeight) {
//                    fromHeap.remove(nEdge);
//                    nEdge.edge = iter.getEdge();
//                    // ORS-GH MOD START
//                    nEdge.originalEdge = EdgeIteratorStateHelper.getOriginalEdge(iter);
//                    // ORS-GH MOD END
//                    nEdge.weight = tmpWeight;
//                    nEdge.parent = currEdge;
//                    fromHeap.add(nEdge);
//                } else
//                    continue;
//
//                updateBestPath(iter, nEdge, traversalId);
//            }
//
//            if (fromHeap.isEmpty())
//                break;
//
//            currEdge = fromHeap.poll();
//            if (currEdge == null)
//                throw new AssertionError("Empty edge cannot happen");
//        }
//        while (!finished()) {
//            int baseNode = pollQueue();
//            if (statusNodeSettled(baseNode))
//                continue;
//
//            edgeIter = edgeExpl.setBaseNode(baseNode);
//            while (edgeIter.next()) {
//                //>> outgoing Edges of baseNode
//                if (!edgeFilterSeq.accept(edgeIter))
//                    continue;
//
//                toggleOneWayEdgeIds(edgeIter.getEdge(), baseNode);
//            }
//        }
//        return this;
//    }
//
//    private synchronized void toggleOneWayEdgeIds(int edgeId, int node) {
//        if (oneWayMap.containsKey(edgeId))
//            oneWayMap.put(edgeId, -1);
//        else
//            oneWayMap.put(edgeId, node);
//    }
//
//
//    /**
//     * G-E-T
//     **/
//    public Map<Integer, Integer> getOneWayMap() {
//        return oneWayMap;
//    }
//}