// Make a map out of a voronoi graph
// Original Author: amitp@cs.stanford.edu
// License: MIT
package jMapGen;

import jMapGen.com.nodename.Delaunay.DelaunayUtil;
import jMapGen.com.nodename.Delaunay.Voronoi;
import jMapGen.com.nodename.geom.LineSegment;
import jMapGen.graph.Center;
import jMapGen.graph.Corner;
import jMapGen.graph.CornerElevationSorter;
import jMapGen.graph.Edge;
import jMapGen.graph.MoistureComparator;

import java.awt.Rectangle;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Random;
import java.util.Vector;

import za.co.iocom.math.MathUtil;


public class Map 
{
	public int NUM_POINTS = 4096*4;
	public int NUM_POINTS_SQ = (int) Math.sqrt(NUM_POINTS);

	// Passed in by the caller:
	public int SIZE;
	// Island shape is controlled by the islandRandom seed and the
	// type of island, passed in when we set the island shape. The
	// islandShape function uses both of them to determine whether any
	// point should be water or land.
	public IslandParameters islandParams;
	// Island details are controlled by this random generator. The
	// initial map upon loading is always deterministic, but
	// subsequent maps reset this random number generator with a
	// random seed.
	public Random mapRandom = new Random();

	// These store the graph data
	public Vector<Point> points;  // Only useful during map construction
	public Vector<Center> centers;
	public Vector<Corner> corners;
	public Vector<Edge> edges;
	public Vector<River> rivers;
	public Vector<Lake> lakes;
	public long seed;

	public Map(int size, long s) 
	{
		SIZE = size;
		seed = s;
	}

	// Random parameters governing the overall shape of the island
	public void newIsland(long seed) 
	{
		islandParams = new IslandParameters(seed, SIZE, 0.5);
		mapRandom.setSeed(seed);
		MathUtil.random = mapRandom;
	}

	public void newIsland(long seed, IslandParameters is) 
	{
		islandParams = is;
		mapRandom.setSeed(seed);
		MathUtil.random = mapRandom;
		NUM_POINTS = is.SIZE*4;
		NUM_POINTS_SQ = (int) Math.sqrt(NUM_POINTS);
	}

	public void go() 
	{
		points = new Vector<Point>();
		edges = new Vector<Edge>();
		centers = new Vector<Center>();
		corners = new Vector<Corner>();
		lakes = new Vector<Lake>();
		rivers = new Vector<River>();

		points = this.generateHexagon(SIZE);
		//System.out.println("Points: " + points.size());
		Rectangle R = new Rectangle();
		R.setFrame(0, 0, SIZE, SIZE);
		//System.out.println("Starting Creating map Voronoi...");
		Voronoi voronoi = new Voronoi(points, R);
		//System.out.println("Finished Creating map Voronoi...");
		buildGraph(points, voronoi);

		// Determine the elevations and water at Voronoi corners.
		assignCornerElevations();

		// Determine polygon and corner type: ocean, coast, land.
		assignOceanCoastAndLand();

		redistributeElevations(landCorners(corners));
		//fixElevations(landCorners(corners));

		// Assign elevations to non-land corners
		for(Iterator<Corner> i = corners.iterator(); i.hasNext();)
		{
			Corner q = (Corner)i.next();
			if (q.ocean || q.coast) 
			{
				q.elevation = 0.0;
			}
		}

		// Polygon elevations are the average of their corners
		assignPolygonElevations();

		assignLakeElevations(lakeCenters(centers));

		// Determine downslope paths.
		calculateDownslopesCenter();

		createCanyons();

		// Determine downslope paths.
		calculateDownslopesCenter();

		// Determine watersheds: for every center, where does it flow
		// out into the ocean? 
		calculateWatershedsCenter();

		// Create rivers.
		createRiversCenter();

		assignSlopedNoise();
		assignHillyNoise();

		calculateDownslopesCenter();

		assignMoisture();
		redistributeMoisture(landCenters(centers));

		sortClockwise();
		setupBiomeInfo();
	}

	private void assignHillyNoise() 
	{
		for(Iterator<Center> centerIter = centers.iterator(); centerIter.hasNext();)
		{
			Center center = (Center)centerIter.next();
			if(!center.isCoast() && this.mapRandom.nextInt(100) < 10 && !center.isWater() && center.getRiver() == 0)
			{
				Center highest = this.getHighestNeighbor(center);
				highest = this.getHighestNeighbor(highest);
				highest = this.getHighestNeighbor(highest);
				highest = this.getHighestNeighbor(highest);

				double diff = highest.elevation - center.elevation;
				center.elevation += diff * (0.5 + 0.5*mapRandom.nextDouble());

				for(Iterator<Center> centerIter2 = center.neighbors.iterator(); centerIter2.hasNext();)
				{
					Center center2 = (Center)centerIter2.next();
					if(!center2.isCoast() && center2.getRiver() == 0 && !center2.isWater())
					{
						center2.elevation += Math.max(0, (center.elevation - center2.elevation)*mapRandom.nextDouble());
					}
				}
			}
		}
	}

	private void assignSlopedNoise() 
	{
		for(Iterator<Center> centerIter = centers.iterator(); centerIter.hasNext();)
		{
			Center center = (Center)centerIter.next();
			if(!center.isCoast() && !center.isWater() && center.getRiver() == 0)
			{
				boolean nearWater = false;
				for(Iterator<Center> centerIter2 = center.neighbors.iterator(); centerIter2.hasNext();)
				{
					Center center2 = (Center)centerIter2.next();
					if(center2.isWater())
						nearWater  = true;
				}
				if(!nearWater && this.mapRandom.nextInt(100) < 50 && center.getRiver() < 3)
				{
					Center lowest = getLowestNeighbor(center);
					Center highest = getHighestNeighbor(center);

					if(this.mapRandom.nextInt(100) < 70)
						center.elevation -= mapRandom.nextDouble() * (center.elevation - lowest.elevation);
					else
						center.elevation += mapRandom.nextDouble() * (center.elevation - highest.elevation);
				}
			}
		}
	}

	private Center getHighestNeighbor(Center c)
	{
		Center highest = c;
		for(Iterator<Center> centerIter2 = c.neighbors.iterator(); centerIter2.hasNext();)
		{
			Center center2 = (Center)centerIter2.next();
			if(highest == null || center2.elevation > highest.elevation)
				highest = center2;
		}
		if(c.upriver != null)
		{
			highest = getLowestFromGroup(c.upriver);
		}
		return highest;
	}

	private Center getLowestNeighbor(Center c)
	{
		Center lowest = c;
		for(Iterator<Center> centerIter2 = c.neighbors.iterator(); centerIter2.hasNext();)
		{
			Center center2 = (Center)centerIter2.next();
			if(lowest == null || center2.elevation < lowest.elevation)
				lowest = center2;
		}
		if(c.downriver != null)
			lowest = c.downriver;
		return lowest;
	}

	private Center getLowestFromGroup(Vector<Center> group)
	{
		Center lowest = group.get(0);
		for(Iterator<Center> centerIter2 = group.iterator(); centerIter2.hasNext();)
		{
			Center center2 = (Center)centerIter2.next();
			if(lowest == null || center2.elevation < lowest.elevation)
				lowest = center2;
		}
		return lowest;
	}

	private Center getHighestFromGroup(Vector<Center> group)
	{
		Center highest = group.get(0);
		for(Iterator<Center> centerIter2 = group.iterator(); centerIter2.hasNext();)
		{
			Center center2 = (Center)centerIter2.next();
			if(highest == null || center2.elevation > highest.elevation)
				highest = center2;
		}
		return highest;
	}

	private void sortClockwise() 
	{
		for(Iterator<Center> centerIter = centers.iterator(); centerIter.hasNext();)
		{
			Center center = (Center)centerIter.next();
			Vector<Corner> sortedCorners = new Vector<Corner>();
			Vector<Center> sortedNeighbors = new Vector<Center>();
			Point zeroPoint = new Point(center.point.x, center.point.y+1);
			//Sort neighbors clockwise
			for(Iterator<Corner> iter = center.corners.iterator(); iter.hasNext();)
			{
				Corner c = (Corner)iter.next();
				if(sortedCorners.size() == 0)
					sortedCorners.add(c);
				else
				{
					boolean found = false;
					for(int i = 0; i < sortedCorners.size(); i++)
					{
						Corner c1 = sortedCorners.get(i);
						double c1angle = Math.atan2((c1.point.y - zeroPoint.y) , (c1.point.x - zeroPoint.x));
						double c2angle = Math.atan2((c.point.y - zeroPoint.y) , (c.point.x - zeroPoint.x));
						if(c2angle < c1angle)
						{
							sortedCorners.add(i, c);
							found = true;
							break;
						}
					}
					if(!found)
						sortedCorners.add(c);
				}
			}
			//Sort neighbors clockwise
			for(Iterator<Center> iter = center.neighbors.iterator(); iter.hasNext();)
			{
				Center c = (Center)iter.next();
				if(sortedNeighbors.size() == 0)
					sortedNeighbors.add(c);
				else
				{
					boolean found = false;
					for(int i = 0; i < sortedNeighbors.size(); i++)
					{
						Center c1 = sortedNeighbors.get(i);
						double c1angle = Math.atan2((c1.point.y - zeroPoint.y) , (c1.point.x - zeroPoint.x));
						double c2angle = Math.atan2((c.point.y - zeroPoint.y) , (c.point.x - zeroPoint.x));
						if(c2angle < c1angle)
						{
							sortedNeighbors.add(i, c);
							found = true;
							break;
						}
					}
					if(!found)
						sortedNeighbors.add(c);
				}
			}
			center.neighbors = sortedNeighbors;
			center.corners = sortedCorners;
		}
	}

	private void setupBiomeInfo() 
	{
		double edgeDistance = 0.10;
		double min = this.SIZE * edgeDistance;
		double max = this.SIZE * (1 -edgeDistance);
		for(Iterator<Center> centerIter = centers.iterator(); centerIter.hasNext();)
		{
			Center center = (Center)centerIter.next();

			//Assign biome information to each hex
			center.biome = getBiome(center);

			//If this hex is near the map border we want to count the number of hexes in the connected island.
			//If there are too few then we will delete this tiny island to make the islands look better
			if(!center.isWater() && (center.point.x < min || center.point.x > max ||
					center.point.y < min || center.point.y > max))
			{
				Vector<Center> island = countIsland(center, 25);
				if(island != null && island.size() > 0)
				{
					for(Center n : island)
					{
						n.setWater(true);
						n.setOcean(true);
						n.biome = BiomeType.OCEAN;
					}
				}
			}

			if(center.isCoastWater())
				center.elevation = -0.01 - mapRandom.nextDouble()*0.03;
			else if(center.isOcean())
				center.elevation = -0.1 - mapRandom.nextDouble()*0.25;


		}
	}

	/**
	 * @return May return null if the island is too big.
	 */
	public Vector<Center> countIsland(Center start, int maxSize) 
	{
		Vector<Center> outList = new Vector<Center>();
		LinkedList<Center> checkList = new LinkedList<Center>();

		outList.add(start);
		checkList.add(start);

		while(checkList.size() > 0)
		{
			Center c = checkList.pollFirst();
			for(Center n : c.neighbors)
			{
				if(!checkList.contains(n) && !outList.contains(n) && !n.isWater())
				{
					outList.add(n);
					checkList.addLast(n);
				}
			}

			if(outList.size() >= maxSize)
				return null;
		}


		return outList;
	}

	public Vector<Point> generateHexagon(int size) {

		Vector<Point> points = new Vector<Point>();
		int N = (int) Math.sqrt(NUM_POINTS);
		for (int x = 0; x < N; x++) {
			for (int y = 0; y < N; y++) {
				points.add(new Point((0.5 + x)/N * size, (0.25 + 0.5*x%2 + y)/N * size));
			}
		}
		return points;
	}

	/* Generate random points and assign them to be on the island or
	 in the water. Some water points are inland lakes; others are
	 ocean. We'll determine ocean later by looking at what's
	 connected to ocean.
	 */
	public Vector<Point> generateRandomPoints()
	{
		Point p; 
		int i; 
		Vector<Point> points = new Vector<Point>();

		for (i = 0; i < NUM_POINTS; i++) 
		{
			p = new Point(10 + mapRandom.nextDouble() * (SIZE-10),
					10 + mapRandom.nextDouble() * (SIZE-10));
			points.add(p);
		}
		return points;
	}

	/*
	 * Although Lloyd relaxation improves the uniformity of polygon
	 sizes, it doesn't help with the edge lengths. Short edges can
	 be bad for some games, and lead to weird artifacts on
	 rivers. We can easily lengthen short edges by moving the
	 corners, but **we lose the Voronoi property**.  The corners are
	 moved to the average of the polygon centers around them. Short
	 edges become longer. Long edges tend to become shorter. The
	 polygons tend to be more uniform after this step.
	 */
	public void improveCorners() 
	{
		Vector<Point> newCorners = new Vector<Point>(corners.size());

		Point point; 
		int i; 
		Edge edge;

		// First we compute the average of the centers next to each corner.
		int count = 0;
		for(Corner q : corners)  
		{
			if (q.border) 
			{
				DelaunayUtil.setAtPosition(newCorners, q.index, q.point);
			} else {
				point = new Point(0.0, 0.0);
				for(Center r : q.touches)  
				{
					point.x += r.point.x;
					point.y += r.point.y;
				}
				point.x /= q.touches.size();
				point.y /= q.touches.size();
				DelaunayUtil.setAtPosition(newCorners, q.index, point);
				q.point = point;
			}
			count++;
		}

		// Move the corners to the new locations.
		for (i = 0; i < corners.size(); i++) {
			corners.get(i).point = newCorners.get(i);
		}

		// The edge midpoints were computed for the old corners and need
		// to be recomputed.
		for(i = 0; i < edges.size(); i++) 
		{
			edge = edges.get(i);
			if (edge.vCorner0 != null && edge.vCorner1 != null) 
			{
				edge.midpoint = edge.vCorner0.point != null && edge.vCorner1.point != null ? Point.interpolate(edge.vCorner0.point, edge.vCorner1.point, 0.5) : null;
			}
		}
	}

	// Create an array of corners that are on land only, for use by
	// algorithms that work only on land.  We return an array instead
	// of a vector because the redistribution algorithms want to sort
	// this array using Array.sortOn.
	public Vector<Corner> landCorners(Vector<Corner> corners) {
		Corner q; 
		Vector<Corner> locations = new Vector<Corner>();
		for (int i = 0; i < corners.size(); i++) {
			q = corners.get(i);
			if (!q.ocean && !q.coast) {
				locations.add(q);
			}
		}
		return locations;
	}

	public Vector<Center> landCenters(Vector<Center> centers) {
		Center q; 
		Vector<Center> locations = new Vector<Center>();
		for (int i = 0; i < centers.size(); i++) {
			q = centers.get(i);
			if (!q.isOcean() && !q.isCoast()) {
				locations.add(q);
			}
		}
		return locations;
	}

	public Vector<Center> lakeCenters(Vector<Center> centers2) {
		Center q; 
		Vector<Center> locations = new Vector<Center>();
		for (int i = 0; i < centers2.size(); i++) {
			q = centers2.get(i);
			if (!q.isOcean() && q.isWater()) {
				locations.add(q);
			}
		}
		return locations;
	}

	// Build graph data structure in 'edges', 'centers', 'corners',
	// based on information in the Voronoi results: point.neighbors
	// will be a list of neighboring points of the same type (corner
	// or center); point.edges will be a list of edges that include
	// that point. Each edge connects to four points: the Voronoi edge
	// edge.{v0,v1} and its dual Delaunay triangle edge edge.{d0,d1}.
	// For boundary polygons, the Delaunay edge will have one null
	// point, and the Voronoi edge may be null.
	public void buildGraph(Vector<Point> points, Voronoi voronoi) 
	{
		Center p; 
		Corner q; 
		Point point;
		Point other;

		Vector<jMapGen.com.nodename.Delaunay.Edge> libedges = voronoi.getEdges();
		HashMap<Point, Center> centerLookup = new HashMap<Point, Center>();

		//System.out.println("Starting buildGraph...");

		// Build Center objects for each of the points, and a lookup map
		// to find those Center objects again as we build the graph
		//System.out.println("Building Centers from " + points.size() + " total Points");
		for(int i = 0; i < points.size(); i++) 
		{
			point = points.get(i);
			p = new Center();
			p.index = centers.size();
			p.point = point;
			p.neighbors = new  Vector<Center>();
			p.borders = new Vector<Edge>();
			p.corners = new Vector<Corner>();
			centers.add(p);
			centerLookup.put(point, p);
		}

		// Workaround for Voronoi lib bug: we need to call region()
		// before Edges or neighboringSites are available
		for(int i = 0; i < centers.size(); i++) 
		{
			p = centers.get(i);
			voronoi.region(p.point);
		}


		// The Voronoi library generates multiple Point objects for
		// corners, and we need to canonicalize to one Corner object.
		// To make lookup fast, we keep an array of Points, bucketed by
		// x value, and then we only have to look at other Points in
		// nearby buckets. When we fail to find one, we'll create a new
		// Corner object.
		Vector<Vector<Corner>> _cornerMap = new Vector<Vector<Corner>>();
		_cornerMap.setSize((int)SIZE);

		for(int i = 0; i < libedges.size(); i++) 
		{
			jMapGen.com.nodename.Delaunay.Edge libedge = libedges.get(i);
			LineSegment dedge = libedge.delaunayLine();
			LineSegment vedge = libedge.voronoiEdge();

			// Fill the graph data. Make an Edge object corresponding to
			// the edge from the voronoi library.
			Edge edge = new Edge();
			edge.index = edges.size();
			edges.add(edge);
			edge.midpoint = vedge.p0 != null && vedge.p1 != null ? Point.interpolate(vedge.p0, vedge.p1, 0.5) : null;

			Corner c0 = makeCorner(vedge.p0, _cornerMap);
			Corner c1 = makeCorner(vedge.p1, _cornerMap);

			edge.setVoronoiEdge(c0, c1);
			edge.dCenter0 = centerLookup.get(dedge.p0);
			edge.dCenter1 = centerLookup.get(dedge.p1);

			// Centers point to edges. Corners point to edges.
			if (edge.dCenter0 != null) { edge.dCenter0.borders.add(edge); }
			if (edge.dCenter1 != null) { edge.dCenter1.borders.add(edge); }
			if (edge.vCorner0 != null) { edge.vCorner0.protrudes.add(edge); }
			if (edge.vCorner1 != null) { edge.vCorner1.protrudes.add(edge); }



			// Centers point to centers.
			if (edge.dCenter0 != null && edge.dCenter1 != null) 
			{
				addToCenterList(edge.dCenter0.neighbors, edge.dCenter1);
				addToCenterList(edge.dCenter1.neighbors, edge.dCenter0);
			}
			// Centers point to corners
			if (edge.dCenter0 != null) 
			{
				addToCornerList(edge.dCenter0.corners, edge.vCorner0);
				addToCornerList(edge.dCenter0.corners, edge.vCorner1);
			}
			if (edge.dCenter1 != null) 
			{
				addToCornerList(edge.dCenter1.corners, edge.vCorner0);
				addToCornerList(edge.dCenter1.corners, edge.vCorner1);
			}

			// Corners point to centers
			if (edge.vCorner0 != null) 
			{
				addToCenterList(edge.vCorner0.touches, edge.dCenter0);
				addToCenterList(edge.vCorner0.touches, edge.dCenter1);
			}
			if (edge.vCorner1 != null) 
			{
				addToCenterList(edge.vCorner1.touches, edge.dCenter0);
				addToCenterList(edge.vCorner1.touches, edge.dCenter1);
			}
		}

		//System.out.println("Finished buildGraph...");
	}

	@SuppressWarnings("unchecked")
	public Corner makeCorner(Point point, Vector<Vector<Corner>> _cornerMap) 
	{
		Corner q;
		int bucket;

		if (point == null) 
			return null;

		int minBucket = (int)(point.x) - 1;
		int maxBucket = (int)(point.x) + 1;

		for (bucket = minBucket; bucket <= maxBucket; bucket++) 
		{
			Vector<Corner> cornermap = (Vector<Corner>) DelaunayUtil.getAtPosition(_cornerMap, bucket);
			for(int i = 0; cornermap != null && i < cornermap.size(); i++) 
			{
				q = cornermap.get(i);
				double dx = point.x - q.point.x;
				double dy = point.y - q.point.y;
				double dxdy = dx*dx + dy*dy;
				if (dxdy < 1E-6) 
				{
					return q;
				}
			}
		}

		bucket = (int)(point.x);
		if (_cornerMap.size() <= bucket || _cornerMap.get(bucket) == null)
		{
			DelaunayUtil.setAtPosition(_cornerMap, bucket, new Vector<Corner>());
		}
		q = new Corner();
		q.index = corners.size();
		corners.add(q);

		q.point = point;
		q.border = (point.x == 0 || point.x == SIZE
				|| point.y == 0 || point.y == SIZE);
		q.touches = new Vector<Center>();
		q.protrudes = new Vector<Edge>();
		q.adjacent = new Vector<Corner>();		

		_cornerMap.get(bucket).add(q);

		return q;

	}

	void addToCornerList(Vector<Corner> v, Corner x) 
	{
		if (x != null && !v.contains(x)) { v.add(x); }
	}

	void addToCenterList(Vector<Center> v, Center x) 
	{
		if (x != null && v.indexOf(x) < 0) { v.add(x); }
	}

	// Determine elevations and water at Voronoi corners. By
	// construction, we have no local minima. This is important for
	// the downslope vectors later, which are used in the river
	// construction algorithm. Also by construction, inlets/bays
	// push low elevation areas inland, which means many rivers end
	// up flowing out through them. Also by construction, lakes
	// often end up on river paths because they don't raise the
	// elevation as much as other terrain does.
	public void assignCornerElevations() 
	{
		Corner baseCorner, adjacentCorner;
		LinkedList<Corner> queue = new LinkedList<Corner>();

		/**
		 * First we check each corner to see if it is land or water
		 * */
		for(Corner c : corners)
		{
			c.water = !inside(c.point);

			if (c.border) 
			{
				c.elevation = 0;
				queue.add(c);
			}
		}

		/**
		 * Next we assign the borders to have 0 elevation and all other corners to have MAX_VALUE. We also add
		 * the border points to a queue which contains all start points for elevation distribution.
		 */

		// Traverse the graph and assign elevations to each point. As we
		// move away from the map border, increase the elevations. This
		// guarantees that rivers always have a way down to the coast by
		// going downhill (no local minima).
		while (queue.size() > 0) {
			baseCorner = queue.pollFirst();

			for(int i = 0; i < baseCorner.adjacent.size(); i++)
			{

				adjacentCorner = baseCorner.adjacent.get(i);

				if(!adjacentCorner.border)
				{
					// Every step up is epsilon over water or 1 over land. The
					// number doesn't matter because we'll rescale the
					// elevations later.				
					double newElevation = 0.000000001 + baseCorner.elevation;

					if (!baseCorner.water && !adjacentCorner.water && newElevation < 0.20) 
					{
						newElevation += 0.05;
					}
					else if (!baseCorner.water && !adjacentCorner.water) 
					{
						newElevation += 1;
					}
					// If this point changed, we'll add it to the queue so
					// that we can process its neighbors too.
					if (newElevation < adjacentCorner.elevation) 
					{
						adjacentCorner.elevation = newElevation;
						queue.add(adjacentCorner);
					}
				}
			}
		}
	}

	public Vector<Corner> sortElevation(Vector<Corner> locations)
	{
		Vector<Corner> locationsOut = new Vector<Corner>();
		for(Iterator<Corner> iter = locations.iterator(); iter.hasNext();)
		{
			Corner c = iter.next();
			for(int o = 0; o < locationsOut.size(); o++)
			{
				Corner cOut = locationsOut.get(o);
				if(cOut.elevation < c.elevation)
				{
					locationsOut.add(o, c);
					if(cOut.elevation < 0)
						cOut.elevation = 0;
					break;
				}
			}
		}
		return locationsOut;
	}

	public Vector<Corner> sortMoisture(Vector<Corner> locations)
	{
		Vector<Corner> locationsOut = new Vector<Corner>();
		for(Iterator<Corner> iter = locations.iterator(); iter.hasNext();)
		{
			Corner c = iter.next();
			for(int o = 0; o < locationsOut.size(); o++)
			{
				Corner cOut = locationsOut.get(o);
				if(cOut.moisture < c.moisture)
				{
					locationsOut.add(o, c);
					break;
				}
			}
		}
		return locationsOut;
	}

	// Change the overall distribution of elevations so that lower
	// elevations are more common than higher
	// elevations. Specifically, we want elevation X to have frequency
	// (1-X).  To do this we will sort the corners, then set each
	// corner to its desired elevation.
	public void redistributeElevations(Vector<Corner> locations) {
		// SCALE_FACTOR increases the mountain area. At 1.0 the maximum
		// elevation barely shows up on the map, so we set it to 1.1.
		double SCALE_FACTOR = 1.0;

		Collections.sort(locations, new CornerElevationSorter());
		int locationsSize = locations.size();

		for (int i = 0; i < locationsSize; i++) {
			// Let y(x) be the total area that we want at elevation <= x.
			// We want the higher elevations to occur less than lower
			// ones, and set the area to be y(x) = 1 - (1-x)^2.
			double y = (double)i/(double)(locationsSize-1);
			// Now we have to solve for x, given the known y.
			//  *  y = 1 - (1-x)^2
			//  *  y = 1 - (1 - 2x + x^2)
			//  *  y = 2x - x^2
			//  *  x^2 - 2x + y = 0
			// From this we can use the quadratic equation to get:
			double sqrtScale = Math.sqrt(SCALE_FACTOR);
			double scale1Y = SCALE_FACTOR*(1-y);
			double sqrtscale1Y = Math.sqrt(scale1Y);

			double x = sqrtScale - sqrtscale1Y;
			if (x > 1.0) x = 1.0;  // TODO: does this break downslopes?
			locations.get(i).elevation = x;
		}
	}

	// Change the overall distribution of moisture to be evenly distributed.	
	public void redistributeMoisture(Vector<Center> locations) {
		int i;
		Collections.sort(locations, new MoistureComparator());
		Center c1;
		for (i = 0; i < locations.size(); i++) 
		{
			c1 = locations.get(i);
			double m = i/(double)(locations.size());
			c1.moisture = m;
		}
	}

	// Determine polygon and corner types: ocean, coast, land.
	public void assignOceanCoastAndLand() {
		// Compute polygon attributes 'ocean' and 'water' based on the
		// corner attributes. Count the water corners per
		// polygon. Oceans are all polygons connected to the edge of the
		// map. In the first pass, mark the edges of the map as ocean;
		// in the second pass, mark any water-containing polygon
		// connected an ocean as ocean.
		LinkedList<Center> queue = new LinkedList<Center>();
		Center p = null, r = null; 
		Corner q; 
		int numWater;

		for(int i = 0; i < centers.size(); i++)
		{
			p = centers.get(i);
			numWater = 0;
			for(int j = 0; j < p.corners.size(); j++)
			{
				q = p.corners.get(j);
				if (q.border) {
					p.setBorder(true);
					p.setOcean(true);
					q.water = true;
					queue.add(p);
				}
				if (q.water) {
					numWater += 1;
				}
			}
			p.setWater((p.isOcean() || numWater >= p.corners.size() * this.islandParams.lakeThreshold));
		}
		while (queue.size() > 0) 
		{
			p = queue.pop();

			for(int j = 0; j < p.neighbors.size(); j++)
			{
				r = p.neighbors.get(j);
				if (r.isWater() && !r.isOcean()) {
					r.setOcean(true);;
					queue.add(r);
				}
			}
		}

		int numOcean = 0;
		int numLand = 0;

		// Set the polygon attribute 'coast' based on its neighbors. If
		// it has at least one ocean and at least one land neighbor,
		// then this is a coastal polygon.
		for(int i = 0; i < centers.size(); i++)
		{
			p = centers.get(i);
			numOcean = 0;
			numLand = 0;

			for(int j = 0; j < p.neighbors.size(); j++)
			{
				r = p.neighbors.get(j);
				numOcean += (r.isOcean() ? 1 : 0);
				numLand += (!r.isWater() ? 1 : 0);
			}

			p.setCoast((numOcean > 0) && (numLand > 0));
			p.setCoastWater(p.isOcean() && (numLand > 0));
		}


		// Set the corner attributes based on the computed polygon
		// attributes. If all polygons connected to this corner are
		// ocean, then it's ocean; if all are land, then it's land;
		// otherwise it's coast.
		for(int j = 0; j < corners.size(); j++)
		{
			q = corners.get(j);
			numOcean = 0;
			numLand = 0;
			for(int i = 0; i < q.touches.size(); i++)
			{
				p = q.touches.get(i);
				numOcean += (p.isOcean() ? 1 : 0);
				numLand += (!p.isWater() ? 1 : 0);
			}
			q.ocean = (numOcean == q.touches.size());
			q.coast = (numOcean > 0) && (numLand > 0);
			q.water = q.border || ((numLand != q.touches.size()) && !q.coast);

		}
	}

	// Polygon elevations are the average of the elevations of their corners.
	public void assignPolygonElevations() 
	{
		Center p; 
		Corner q; 
		double sumElevation;
		for(int i = 0; i < centers.size(); i++)
		{
			p = centers.get(i);
			sumElevation = 0.0;
			for(int j = 0; j < p.corners.size(); j++)
			{
				q = p.corners.get(j);
				sumElevation += q.elevation;
			}
			p.elevation = sumElevation / p.corners.size();
		}
	}

	public void assignLakeElevations(Vector<Center> centers) 
	{
		for(Center c : centers)
		{
			//if there are current no lakes, or the current center doesnt exist in any lakes already
			Lake exists = centerInExistingLake(c);
			if(lakes.isEmpty() || exists == null)
			{
				//default the lakeElevation 1
				double lakeElev = 1;

				//Create a new lake
				Lake lake = new Lake();

				//contains a list of centers that need to check outward to find the bounds of the lake.
				LinkedList<Center> centersToCheck = new LinkedList<Center>();

				// add the current center to the centersToCheck list
				lake.addCenter(c);
				//Add the center to the queue for outward propagation
				centersToCheck.add(c);

				while (centersToCheck.size() > 0) 
				{
					Center baseCenter = centersToCheck.pollFirst();

					for(Center adj : baseCenter.neighbors)
					{
						if(!lake.hasCenter(adj) && adj.isWater() && !adj.isOcean())
						{
							lake.addCenter(adj);
							centersToCheck.add(adj);
						}
					}			
				}
				lakes.add(lake);
			}
		}
		for(int i = 0; i < lakes.size(); i++)
		{
			Lake lake = lakes.get(i);
			for(Center c : lake.centers)
			{
				c.elevation = lake.lowestCenter.elevation;
			}
		}
	}

	private Lake centerInExistingLake(Center center)
	{
		for(Lake lake : lakes)
		{
			if(lake.hasCenter(center))
				return lake;
		}
		return null;
	}

	private void fixLakeCorners(Vector<Center> lakeCenters)
	{
		for(Center c : lakeCenters)
		{
			for(Corner cn : c.corners)
			{
				if(!cn.isShoreline())
					cn.elevation = c.elevation;
			}
		}
	}

	// Calculate downslope pointers.  At every point, we point to the
	// point downstream from it, or to itself.  This is used for
	// generating rivers and watersheds.
	private Vector<Center> centerInExistingLake(Vector<Vector<Center>> Lakes, Center center)
	{
		for(Vector<Center> lake : Lakes)
		{
			if(lake.contains(center))
				return lake;
		}
		return null;
	}

	public void calculateDownslopes() 
	{
		Corner upCorner, tempCorner, downCorner;

		for(int j = 0; j < corners.size(); j++)
		{
			upCorner = corners.get(j);
			downCorner = upCorner;
			for(int i = 0; i < upCorner.adjacent.size(); i++)
			{
				tempCorner= upCorner.adjacent.get(i);
				if (tempCorner.elevation <= downCorner.elevation) 
				{
					downCorner = tempCorner;
				}
			}	
			upCorner.downslope = downCorner;
		}
	}

	public void calculateDownslopesCenter() 
	{
		Center upCorner, tempCorner, downCorner;

		for(int j = 0; j < centers.size(); j++)
		{
			upCorner = centers.get(j);
			downCorner = upCorner;
			for(int i = 0; i < upCorner.neighbors.size(); i++)
			{
				tempCorner= upCorner.neighbors.get(i);
				if (convertHeightToMC(tempCorner.elevation) <= convertHeightToMC(downCorner.elevation)) 
				{
					downCorner = tempCorner;
				}
			}	
			upCorner.downslope = downCorner;
		}
	}

	public void calculateWatershedsCenter() 
	{
		Center workCorner, tempCorner; int i; boolean changed;

		// Initially the watershed pointer points downslope one step.      
		for(int j = 0; j < centers.size(); j++)
		{
			workCorner = centers.get(j);
			workCorner.watershed = workCorner;
			if (!workCorner.isOcean() && !workCorner.isCoast()) 
			{
				workCorner.watershed = workCorner.downslope;
			}
		}
		// Follow the downslope pointers to the coast. Limit to 100
		// iterations although most of the time with NUM_POINTS=2000 it
		// only takes 20 iterations because most points are not far from
		// a coast.  TODO: can run faster by looking at
		// p.watershed.watershed instead of p.downslope.watershed.
		for (i = 0; i < 100; i++) {
			changed = false;
			for(int j = 0; j < centers.size(); j++)
			{
				workCorner = centers.get(j);
				if (!workCorner.isOcean() && !workCorner.isCoast() && !workCorner.watershed.isCoast()) {
					tempCorner = workCorner.downslope.watershed;
					if (!tempCorner.isOcean()) workCorner.watershed = tempCorner;
					changed = true;
				}
			}
			if (!changed) break;
		}
		// How big is each watershed?
		for(int j = 0; j < centers.size(); j++)
		{
			workCorner = centers.get(j);
			tempCorner = workCorner.watershed;
		}

	}

	private void createCanyons()
	{
		if(!this.islandParams.shouldGenCanyons())
			return;

		Vector<Center> possibleStarts = new Vector<Center>();

		for (int i = 0; i < SIZE/6; i++) 
		{
			possibleStarts.add(centers.get(mapRandom.nextInt(centers.size()-1)));
		}

		for(Center c : possibleStarts)
		{
			if(c.isWater())
				continue;
			Center curNode = c;
			Center nextNode = c;
			int count = 0;
			while (true)
			{
				if (c == null || count > 250 || curNode.isWater()) 
				{
					break;
				}
				count++;
				curNode = nextNode;
				//calculate the next rivernode
				nextNode = getNextCanyonNode(curNode);
				//set the downriver center for this node to the next center
				curNode.downriver = nextNode;
				curNode.setCanyon(true);

				//set the current working center to our next node before starting over
				curNode = nextNode;
			}
		}

		for(Center c : centers)
		{
			if(!c.isWater() && !c.isCanyon())
			{
				if(c.elevation > 0.05)
				{
					c.elevation = Math.min(c.elevation+(0.2*Math.min(c.elevation*2, 1.0)), c.elevation*2);
				}
			}

			/*if(c.isWater() && !c.isOcean() && c.elevation > 0.3)
			{
				c.setWater(false);
			}*/
		}
	}

	public Center getNextCanyonNode(Center center)
	{
		Center next = center.downslope;

		Vector<Center> possibles = new Vector<Center>();

		//The river will attempt to meander if we aren't propagating down an existing river
		if(center.getRiver() == 0)
		{
			//Go through each neighbor and find all possible hexes at the same elevation or lower
			for(Center n : center.neighbors)
			{
				//If the elevations are the same or lower then this might be an ok location
				if(convertHeightToMC(n.elevation) <= convertHeightToMC(center.elevation))
				{
					//If next to a water hex then we move to it instead of anything else
					if(n.isOcean() || n.isWater())
					{
						return n;
					}

					//If the elevation is <= our current cell elevation then we allow this cell to be selected
					possibles.add(n);
				}
			}
		}
		if(possibles.size() > 1)
		{
			Center p = possibles.get(mapRandom.nextInt(possibles.size()));
			return p;
		}
		else if(possibles.size() == 1)
			return possibles.get(0);

		return center.downslope;
	}

	public void createRiversCenter() 
	{
		Center c;
		Center prev;

		Vector<Center> possibleStarts = new Vector<Center>();

		for (int i = 0; i < SIZE/6; i++) 
		{
			c = centers.get(mapRandom.nextInt(centers.size()-1));
			if(this.islandParams.shouldGenCanyons() && c.isCanyon())
				possibleStarts.add(c);
			else
				possibleStarts.add(c);
		}

		for (int i = 0; i < lakes.size(); i++) 
		{
			possibleStarts.add(lakes.get(i).lowestCenter);
			for(Center cen : lakes.get(i).lowestCenter.neighbors)
			{
				if(cen.isWater() && mapRandom.nextBoolean())
					possibleStarts.add(cen);
			}
		}

		for (int i = 0; i < possibleStarts.size(); i++) 
		{
			c = possibleStarts.get(i);

			if (c.isOcean() || c.elevation > 0.85 || c.getRiver() > 0) continue;

			River r = new River();
			RiverNode curNode = new RiverNode(c);
			r.addNode(curNode);
			RiverNode nextNode = curNode;
			int count = 0;
			while (true)
			{
				if (c == null || c == c.downslope || count > 250 || (curNode.center.isWater() && curNode != r.riverStart)) 
				{
					break;
				}
				count++;
				curNode = nextNode;
				//calculate the next rivernode
				nextNode = getNextRiverNode(r, curNode);
				//set the downriver center for this node to the next center
				curNode.setDownRiver(nextNode.center);
				nextNode.setUpRiver(curNode.center);
				//add the next node to the river graph
				r.addNode(nextNode);
				//If the current hex is water then we exit early unless this is the first node in the river
				if((c.isWater() && curNode != r.riverStart) && (curNode.downRiver == null || curNode.downRiver.isWater()))
					break;

				//Keep track of the length of a river before it joins another river or reaches its end
				if(nextNode.center.getRiver() == 0)
					r.lengthToMerge++;
				//set the current working center to our next node before starting over
				c = nextNode.center;
			}

			//If this river is long enough to be acceptable and it eventually empties into a water hex then we process the river into the map
			boolean isValid = false;
			if(r.riverStart != null && r.riverStart.center.isWater() && r.nodes.lastElement().center.isWater() &&(r.riverStart != r.nodes.lastElement()) &&
					r.nodes.lastElement().center.elevation < r.riverStart.center.elevation)
				isValid = true;
			if(r.lengthToMerge > 3 && r.nodes.lastElement().center.isWater())
				isValid = true;

			if(r.riverStart == null || r.riverStart.center.getRiver() != 0 || r.nodes.size() < 4)
				isValid = false;

			if(isValid)
			{
				if(r.riverStart.center.isWater())
					r.riverWidth = 4 - 3 * r.riverStart.center.elevation;
				//Add this river to the river collection
				rivers.add(r);
				curNode = r.nodes.get(0);
				nextNode = curNode;
				boolean cancelRiver = false;
				for (int j = 0; j < r.nodes.size() && !cancelRiver; j++) 
				{
					if(j == 0)
					{
						for(Center n :r.riverStart.center.neighbors)
						{
							if(n.getRiver() > 0)
							{
								rivers.remove(rivers.size()-1);
								cancelRiver = true;
								break;
							}
						}
					}
					else
					{
						nextNode = r.nodes.get(j);

						curNode.center.addRiver(r.riverWidth);
						curNode.center.downriver = nextNode.center;
						nextNode.center.addUpRiverCenter(curNode.center);
						curNode = nextNode;
					}
				}
			}
		}
	}

	public RiverNode getNextRiverNode(River river, RiverNode curNode)
	{
		Center next = curNode.center.downriver;
		if(next != null)
			return new RiverNode(next);

		Vector<Center> possibles = new Vector<Center>();

		//The river will attempt to meander if we aren't propagating down an existing river
		if(curNode.center.getRiver() == 0)
		{
			//Go through each neighbor and find all possible hexes at the same elevation or lower
			for(Center n : curNode.center.neighbors)
			{
				//Make sure that we aren't trying to flow backwards if the hexes are on the same level
				if(n == curNode.upRiver)
					continue;
				//We dont want our rivers to turn at very sharp angles so we check our previous node to make sure that it is not neighbors with this node
				if(n.neighbors.contains(curNode.upRiver))
					continue;

				//If the elevations are the same or lower then this might be an ok location
				if(convertHeightToMC(n.elevation) <= convertHeightToMC(curNode.center.elevation))
				{
					//If next to a water hex then we move to it instead of anything else
					if(n.isOcean() || n.isWater())
					{
						//Unless we are dealing with a lake tile and this is the first River node
						if(river.riverStart == curNode && !n.isOcean())
							continue;
						return new RiverNode(n);
					}

					//If one of the neighbors is also a river then we want to join it
					if(n.getRiver() > 0)
						return new RiverNode(n);

					//If the elevation is <= our current cell elevation then we allow this cell to be selected
					possibles.add(n);
				}
			}
		}
		if(possibles.size() > 1)
		{
			Center p = possibles.get(mapRandom.nextInt(possibles.size()));
			return new RiverNode(p);
		}
		else if(possibles.size() == 1)
			return new RiverNode(possibles.get(0));

		return new RiverNode(curNode.center.downslope);
	}

	private int convertHeightToMC(double d)
	{
		return (int)Math.floor(this.islandParams.islandMaxHeight * d);
	}

	// Calculate moisture. Freshwater sources spread moisture: rivers
	// and lakes (not oceans). Saltwater sources have moisture but do
	// not spread it (we set it at the end, after propagation).
	public void assignMoisture() 
	{
		LinkedList<Center> queue = new LinkedList<Center>();
		// Fresh water
		for(Center cr : centers)
		{
			if ((cr.isWater() || cr.getRiver() > 0) && !cr.isOcean()) {
				cr.moisture = cr.getRiver() > 0? Math.min(3.0, (0.1 * cr.getRiver())) : 1.0;
				queue.push(cr);
			} else {
				cr.moisture = 0.0;
			}
		}
		while (queue.size() > 0) 
		{
			Center q = queue.pop();

			for(Center adjacent : q.neighbors)
			{
				double newMoisture = q.moisture * 0.9;
				if (newMoisture > adjacent.moisture) {
					adjacent.moisture = newMoisture;
					queue.push(adjacent);
				}
			}
		}
		// Salt water
		for(Center cr : centers)
		{
			if (cr.isOcean() || cr.isCoast()) 
			{
				cr.moisture = 1.0;
			}
		}
	}

	// Polygon moisture is the average of the moisture at corners
	public void assignPolygonMoisture() {
		double sumMoisture;
		for(Center p : centers)
		{
			sumMoisture = 0.0;
			for(Corner q : p.corners)
			{
				if (q.moisture > 1.0) q.moisture = 1.0;
				sumMoisture += q.moisture;
			}
			p.moisture = sumMoisture / p.corners.size();
		}
	}

	// Assign a biome type to each polygon. If it has
	// ocean/coast/water, then that's the biome; otherwise it depends
	// on low/high elevation and low/medium/high moisture. This is
	// roughly based on the Whittaker diagram but adapted to fit the
	// needs of the island map generator.
	static public BiomeType getBiome(Center p) {
		if (p.isOcean()) {
			return BiomeType.OCEAN;
		} else if (p.isWater()) {
			if (p.elevation < 0.1) return BiomeType.MARSH;
			if (p.elevation > 0.8) return BiomeType.ICE;
			return BiomeType.LAKE;
		} else if (p.isCoast()) {
			return BiomeType.BEACH;
		} else if (p.elevation > 0.8) {
			if (p.moisture > 0.50) return BiomeType.SNOW;
			else if (p.moisture > 0.33) return BiomeType.TUNDRA;
			else if (p.moisture > 0.16) return BiomeType.BARE;
			else return BiomeType.SCORCHED;
		} else if (p.elevation > 0.6) {
			if (p.moisture > 0.66) return BiomeType.TAIGA;
			else if (p.moisture > 0.33) return BiomeType.SHRUBLAND;
			else return BiomeType.TEMPERATE_DESERT;
		} else if (p.elevation > 0.3) {
			if (p.moisture > 0.83) return BiomeType.TEMPERATE_RAIN_FOREST;
			else if (p.moisture > 0.50) return BiomeType.TEMPERATE_DECIDUOUS_FOREST;
			else if (p.moisture > 0.16) return BiomeType.GRASSLAND;
			else return BiomeType.TEMPERATE_DESERT;
		} else {
			if (p.moisture > 0.66) return BiomeType.TROPICAL_RAIN_FOREST;
			else if (p.moisture > 0.33) return BiomeType.TROPICAL_SEASONAL_FOREST;
			else if (p.moisture > 0.16) return BiomeType.GRASSLAND;
			else return BiomeType.SUBTROPICAL_DESERT;
		}
	}

	// Look up a Voronoi Edge object given two adjacent Voronoi
	// polygons, or two adjacent Voronoi corners
	public Edge lookupEdgeFromCenter(Center p, Center r) {
		for(int j = 0; j < p.borders.size(); j++)
		{
			Edge edge = p.borders.get(j);

			if (edge.dCenter0 == r || edge.dCenter1 == r) return edge;
		}
		return null;
	}

	public Edge lookupEdgeFromCorner(Corner q, Corner s) 
	{
		for(int j = 0; j < q.protrudes.size(); j++)
		{
			Edge edge = q.protrudes.get(j);
			if (edge.vCorner0 == s || edge.vCorner1 == s) return edge;
		}
		return null;
	}

	// Determine whether a given point should be on the island or in the water.
	public Boolean inside(Point p) 
	{
		return islandParams.insidePerlin(p);
	}

	double elevationBucket(Center p) 
	{
		if (p.isOcean()) return -1;
		else return Math.floor(p.elevation*10);
	}

	public Center getClosestCenter(Point p)
	{
		Center closest = null;
		double distance = Double.MAX_VALUE;

		for (int i = 1; i < centers.size(); i++)
		{
			double newDist = p.distanceSq(centers.get(i).point);
			if(newDist < distance)
			{
				distance = newDist;
				closest = centers.get(i);
			}
		}
		if(closest == null)
			System.out.println("Failed center check");
		return closest;
	}

	/**
	 * @return nearest Center point for the contianing hex
	 */
	public Center getSelectedHexagon(Point p)
	{
		//First we place the point in a local grid between 0 and the map width
		p.x = p.x % SIZE;
		p.y = p.y % SIZE;

		//If the point has any negative numbers, we add the map width to make it positive and get the correct location
		if(p.x < 0)
			p.x += SIZE;
		if(p.y < 0)
			p.y += SIZE;

		//Form the best guess coordinates
		int x = (int)Math.floor((p.x /(SIZE/NUM_POINTS_SQ)));
		int y = (int)Math.floor((p.y /(SIZE/NUM_POINTS_SQ)));

		Center orig = this.centers.get( NUM_POINTS_SQ*x+y);
		//Get the inCircle radius
		double r = 0;
		if(orig.corners.size() > 0)
		{
			r = Math.sqrt(3)/2*(orig.borders.get(0).midpoint.distanceSq(orig.point));
		}
		Center bestGuess = orig;
		double dist = p.distanceSq(orig.point);
		//Perform a quick test to see if the point is within the inCircle. If it is then we can skip the rest of the method and return the Best Guess
		if(dist < r)
			return bestGuess;

		for (int i = 0; i < orig.neighbors.size(); i++)
		{
			Center guess = orig.neighbors.get(i);
			double newDist = p.distanceSq(guess.point);
			if(newDist < dist)
			{
				dist = newDist;
				bestGuess = guess;
				if(dist < r)
					return bestGuess;
			}
			for (int j = 0; j < guess.neighbors.size(); j++)
			{
				Center guess2 = guess.neighbors.get(j);
				double newDist2 = p.distanceSq(guess2.point);
				if(newDist2 < dist)
				{
					dist = newDist2;
					bestGuess = guess2;
					if(dist < r)
						return bestGuess;
				}
			}
		}

		return bestGuess;
	}

	public Corner getClosestCorner(Point p)
	{
		Corner closest = corners.get(0);
		double distance = p.distance(corners.get(0).point);

		for (int i = 1; i < corners.size(); i++)
		{
			double newDist = p.distance(corners.get(i).point);
			if(newDist < distance)
			{
				distance = newDist;
				closest = corners.get(i);
			}
		}
		return closest;
	}
}