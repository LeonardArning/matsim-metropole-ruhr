package org.matsim.prepare;

/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.Route;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.algorithms.TransportModeNetworkFilter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.algorithms.ParallelPersonAlgorithmUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.TripStructureUtils.Trip;
import org.matsim.core.router.costcalculators.OnlyTimeDependentTravelDisutility;
import org.matsim.core.router.speedy.SpeedyALTFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.trafficmonitoring.FreeSpeedTravelTime;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@CommandLine.Command(name = "scenario-cutout", description = "TODO")
public class ScenarioCutOut implements MATSimAppCommand {

	private static final Logger log = LogManager.getLogger(ScenarioCutOut.class);

	@CommandLine.Option(names = "--input", description = "Path to input population", required = true)
	private Path input;

	@CommandLine.Option(names = "--network", description = "Path to network", required = true)
	private Path networkPath;

	@CommandLine.Option(names = "--buffer", description = "Buffer around zones in meter", defaultValue = "1000")
	private double buffer;

	@CommandLine.Option(names = "--output-network", description = "Path to output network", required = true)
	private Path outputNetwork;

	@CommandLine.Option(names = "--output-population", description = "Path to output population", required = true)
	private Path outputPopulation;

	@CommandLine.Option(names = "--modes", description = "Modes to consider when cutting network", defaultValue = "car,bike,ride", split = ",")
	private Set<String> modes;

	@CommandLine.Option(names = "--keep-links-in-routes", description = "Keep all links in routes relevant to the area", defaultValue = "false")
	private boolean keepLinksInRoutes;

	@CommandLine.Option(names = "--use-router", description = "Use router on legs that don't have a route", defaultValue = "false")
	private boolean useRouter;

	@CommandLine.Mixin
	private CrsOptions crs;

	@CommandLine.Mixin
	private ShpOptions shp;

	private final ThreadLocal<LeastCostPathCalculator> routerCache = new ThreadLocal<>();

	public static void main(String[] args) {
		new ScenarioCutOut().execute(args);
	}

	@Override
	public Integer call() throws Exception {

		Network network = NetworkUtils.readNetwork(networkPath.toString());
		Population population = PopulationUtils.readPopulation(input.toString());

		if (crs.getInputCRS() == null) {
			log.error("Input CRS must be specified");
			return 2;
		}

		Geometry geom = shp.getGeometry().buffer(buffer);

		Set<Id<Link>> linksToDelete = new HashSet<>();
		Set<Id<Link>> linksToKeep = new HashSet<>();

		for (Link link : network.getLinks().values()) {

			if (geom.contains(MGC.coord2Point(link.getCoord()))
					|| geom.contains(MGC.coord2Point(link.getFromNode().getCoord()))
					|| geom.contains(MGC.coord2Point(link.getToNode().getCoord()))
					|| link.getAllowedModes().contains(TransportMode.pt)) {
				// keep the link
				linksToKeep.add(link.getId());
			} else {
				linksToDelete.add(link.getId());
			}
		}


		// additional links to include
		Set<Id<Link>> linksToInclude = ConcurrentHashMap.newKeySet();

		GeometryFactory gf = new GeometryFactory();

		Network carOnlyNetwork = useRouter ? filterNetwork(network) : null;
		if (useRouter) {
			keepLinksInRoutes = true;
		}

		// now delete irrelevant persons
		Set<Id<Person>> personsToDelete = ConcurrentHashMap.newKeySet();

		ParallelPersonAlgorithmUtils.run(population, Runtime.getRuntime().availableProcessors(), person -> {

			boolean keepPerson = false;

			List<Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());

			Set<Id<Link>> linkIds = new HashSet<>();

			for (Trip trip : trips) {

				// keep all agents starting or ending in area
				if (geom.contains(MGC.coord2Point(trip.getOriginActivity().getCoord())) || geom.contains(MGC.coord2Point(trip.getDestinationActivity().getCoord()))) {
					keepPerson = true;
				}

				LineString line = gf.createLineString(new Coordinate[]{
						MGC.coord2Coordinate(trip.getOriginActivity().getCoord()),
						MGC.coord2Coordinate(trip.getDestinationActivity().getCoord())
				});

				// also keep persons traveling through or close to area (beeline)
				if (line.intersects(geom)) {
					keepPerson = true;
				}


				for (Leg leg : trip.getLegsOnly()) {
					Route route = leg.getRoute();
					if (keepLinksInRoutes && route instanceof NetworkRoute) {

						linkIds.addAll(((NetworkRoute) route).getLinkIds());
						if (((NetworkRoute) route).getLinkIds().stream().anyMatch(linksToKeep::contains)) {
							keepPerson = true;
						}
					}
				}

				if (useRouter) {

					LeastCostPathCalculator router = createRouter(carOnlyNetwork);

					Node fromNode;
					Node toNode;

					Map<Id<Link>, ? extends Link> carLinks = carOnlyNetwork.getLinks();

					if (trip.getOriginActivity().getLinkId() != null && carLinks.get(trip.getOriginActivity().getLinkId()) != null) {
						fromNode = carLinks.get(trip.getOriginActivity().getLinkId()).getFromNode();
					} else {
						fromNode = NetworkUtils.getNearestLink(carOnlyNetwork, trip.getOriginActivity().getCoord()).getFromNode();
					}

					if (trip.getDestinationActivity().getLinkId() != null && carLinks.get(trip.getDestinationActivity().getLinkId()) != null) {
						toNode = carLinks.get(trip.getDestinationActivity().getLinkId()).getFromNode();
					} else {
						toNode = NetworkUtils.getNearestLink(carOnlyNetwork, trip.getDestinationActivity().getCoord()).getFromNode();
					}

					LeastCostPathCalculator.Path path = router.calcLeastCostPath(fromNode, toNode, 0, null, null);

					if (path != null) {
						// add all these links directly
						path.links.stream().map(Link::getId).forEach(linkIds::add);
						if (path.links.stream().map(Link::getId).anyMatch(linksToKeep::contains)) {
							keepPerson = true;
						}
					}
				}

				// activity link ids are reset, because it is not guaranteed they can be retained
				if (trip.getOriginActivity().getLinkId() != null) {
					linkIds.add(trip.getOriginActivity().getLinkId());
					trip.getOriginActivity().setLinkId(null);
				}

				if (trip.getDestinationActivity().getLinkId() != null) {
					linkIds.add(trip.getDestinationActivity().getLinkId());
					trip.getDestinationActivity().setLinkId(null);
				}

			}

			if (keepPerson) {
				linksToInclude.addAll(linkIds);
			} else {
				personsToDelete.add(person.getId());
			}

			PopulationUtils.resetRoutes(person.getSelectedPlan());

		});

		log.info("Persons to delete: {}", personsToDelete.size());
		for (Id<Person> personId : personsToDelete) {
			population.removePerson(personId);
		}

		log.info("Persons in the scenario: {}", population.getPersons().size());

		PopulationUtils.writePopulation(population, outputPopulation.toString());

		if (keepLinksInRoutes && linksToInclude.isEmpty()) {
			log.warn("Keep links in routes is activated, but no links have been kept. Probably no routes are present.");
		}

		log.info("Links to add: " + linksToKeep.size());

		if (keepLinksInRoutes) {
			log.info("Additional links from routes to include: {}", linksToInclude.size());
		}


		log.info("number of links in original network: {}", network.getLinks().size());

		for (Id<Link> linkId : linksToDelete) {
			if (!linksToInclude.contains(linkId))
				network.removeLink(linkId);
		}

		// clean the network
		log.info("number of links before cleaning: {}", network.getLinks().size());
		log.info("number of nodes before cleaning: {}", network.getNodes().size());

		MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(network);
		cleaner.removeNodesWithoutLinks();

		for (String m : modes) {
			log.info("Cleaning mode {}", m);
			cleaner.run(Set.of(m));
		}

		log.info("number of links after cleaning: {}", network.getLinks().size());
		log.info("number of nodes after cleaning: {}", network.getNodes().size());

		NetworkUtils.writeNetwork(network, outputNetwork.toString());


		return 0;
	}

	private Network filterNetwork(Network network) {
		TransportModeNetworkFilter filter = new TransportModeNetworkFilter(network);

		Network carOnlyNetwork = NetworkUtils.createNetwork();
		filter.filter(carOnlyNetwork, Set.of(TransportMode.car));
		return carOnlyNetwork;
	}

	private LeastCostPathCalculator createRouter(Network network) {

		LeastCostPathCalculator c = routerCache.get();
		if (c == null) {
			FreeSpeedTravelTime travelTime = new FreeSpeedTravelTime();
			LeastCostPathCalculatorFactory fastAStarLandmarksFactory = new SpeedyALTFactory();

			OnlyTimeDependentTravelDisutility travelDisutility = new OnlyTimeDependentTravelDisutility(travelTime);

			c = fastAStarLandmarksFactory.createPathCalculator(network, travelDisutility, travelTime);
			routerCache.set(c);
		}

		return c;
	}
}




