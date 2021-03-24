package org.matsim.prepare;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.contrib.accessibility.utils.MergeNetworks;
import org.matsim.contrib.bicycle.BicycleUtils;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.OsmBicycleReader;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryLogging;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.matsim.prepare.counts.CombinedCountsWriter;
import org.matsim.prepare.counts.LongTermCountsCreator;
import org.matsim.prepare.counts.RawDataVehicleTypes;
import org.matsim.prepare.counts.ShortTermCountsCreator;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.Vehicles;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class CreateSupply {

	// the following paths point to the publicSvn
	private static final Path osmData = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/original-data/osm/nordrhein-westfalen-2021-02-15.osm.pbf");
	private static final Path ruhrShape = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/original-data/shp-files/ruhrgebiet_boundary/ruhrgebiet_boundary.shp");
	
	private static final Path gtfsData1 = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/original-data/gtfs/2021_02_03_google_transit_verbundweit_inkl_spnv.zip");	
	private static final Path gtfsData2 = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/original-data/gtfs/gtfs-nwl-20210215.zip");
	private static final String gtfsDataDate = "2021-02-04";
	private static final String gtfsData1Prefix = "vrr";
	private static final String gtfsData2Prefix = "nwl";

	private static final Path bikeOnlyInfrastructureNetworkFile = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/original-data/bicycle-infrastructure/emscherweg-and-rsv.xml");
	private static final Path matsimInputDir = Paths.get("matsim/scenarios/countries/de/metropole-ruhr/metropole-ruhr-v1.0-25pct/input/");

	// the following paths point to the sharedSvn
	private static final Path longTermCountsRoot = Paths.get("projects/matsim-ruhrgebiet/original_data/counts/long_term_counts");
	private static final Path longTermCountsIdMapping = Paths.get("projects/matsim-ruhrgebiet/original_data/counts/mapmatching/countId-to-nodeId-long-term-counts.csv");
	private static final Path shortTermCountsRoot = Paths.get("projects/matsim-ruhrgebiet/original_data/counts/short_term_counts");
	private static final Path shortTermCountsIdMapping = Paths.get("projects/matsim-ruhrgebiet/original_data/counts/mapmatching/countId-to-nodeId-short-term-counts.csv");

	// we use UTM-32 as coord system
	private static final CoordinateTransformation transformation = TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, "EPSG:25832");
	private static final Logger logger = Logger.getLogger(CreateSupply.class);

	public static void main(String[] args) {

		String publicSvn = null;
		String sharedSvn = null;
		
		if (args.length <= 0) {
			logger.info("Please set the public svn and shared svn as arguments.");
		} else {
			publicSvn = args[0];
			sharedSvn = args[1];
		}
		
		new CreateSupply().run(Paths.get(publicSvn), Paths.get(sharedSvn));
	}

	private void run(Path publicSvn, Path sharedSvn) {

		// ----------------------------------------- Preparation ---------------------------------------

		checkInputFilesArePresent(publicSvn, sharedSvn);
		var outputDir = publicSvn.resolve(matsimInputDir);
		
		OutputDirectoryLogging.catchLogEntries();
		try {
			OutputDirectoryLogging.initLoggingWithOutputDirectory(outputDir.resolve("").toString());
		} catch (IOException e1) {
			e1.printStackTrace();
		}

		var geometries = ShapeFileReader.getAllFeatures(publicSvn.resolve(ruhrShape).toString()).stream()
				.map(feature -> (Geometry) feature.getDefaultGeometry())
				.collect(Collectors.toList());

		var nodeIdsToKeep = List.of(parseNodeIdsToKeep(sharedSvn.resolve(longTermCountsIdMapping)), parseNodeIdsToKeep(sharedSvn.resolve(shortTermCountsIdMapping))).stream()
				.flatMap(Collection::stream)
				.collect(Collectors.toSet());

		// ----------------------------------------- Create Network ----------------------------------------------------

		var network = new OsmBicycleReader.Builder()
				.setCoordinateTransformation(transformation)
				.setIncludeLinkAtCoordWithHierarchy((coord, level) -> isIncludeLink(coord, level, geometries))
				.setPreserveNodeWithId(nodeIdsToKeep::contains)
				.setAfterLinkCreated((link, tags, direction) -> onLinkCreated(link))
				.build()
				.read(publicSvn.resolve(osmData));

		var cleaner = new MultimodalNetworkCleaner(network);
		cleaner.run(Set.of(TransportMode.car));
		cleaner.run(Set.of(TransportMode.ride));
		cleaner.run(Set.of(TransportMode.bike));

		// --------------------------------------- Create Pt -----------------------------------------------------------

		var gtfsScenario1 = new TransitScheduleAndVehiclesFromGtfs().run(publicSvn.resolve(gtfsData1).toString(), gtfsDataDate, transformation, gtfsData1Prefix);
		var gtfsScenario2 = new TransitScheduleAndVehiclesFromGtfs().run(publicSvn.resolve(gtfsData2).toString(), gtfsDataDate, transformation, gtfsData2Prefix);

		new MatsimVehicleWriter(gtfsScenario1.getTransitVehicles()).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-vehicles-only-" + gtfsData1Prefix + ".xml.gz").toString());
		new MatsimVehicleWriter(gtfsScenario2.getTransitVehicles()).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-vehicles-only-" + gtfsData2Prefix + ".xml.gz").toString());
		new TransitScheduleWriter(gtfsScenario1.getTransitSchedule()).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-schedule-only-" + gtfsData1Prefix + ".xml.gz").toString());
		new TransitScheduleWriter(gtfsScenario2.getTransitSchedule()).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-schedule-only-" + gtfsData2Prefix + ".xml.gz").toString());

		Config config = ConfigUtils.createConfig();
		config.transit().setUseTransit(true);
		Scenario scenarioBase = ScenarioUtils.loadScenario(config);
		TransitSchedule baseTransitSchedule = scenarioBase.getTransitSchedule();
		Vehicles baseTransitVehicles = scenarioBase.getTransitVehicles();
		
		MergeTransitFiles.mergeVehicles(baseTransitVehicles, gtfsScenario1.getTransitVehicles());
		MergeTransitFiles.mergeVehicles(baseTransitVehicles, gtfsScenario2.getTransitVehicles());

		MergeTransitFiles.mergeSchedule(baseTransitSchedule, gtfsData1Prefix, gtfsScenario1.getTransitSchedule());
		MergeTransitFiles.mergeSchedule(baseTransitSchedule, gtfsData2Prefix, gtfsScenario2.getTransitSchedule());

		new MatsimVehicleWriter(baseTransitVehicles).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-vehicles.xml.gz").toString());
		new TransitScheduleWriter(baseTransitSchedule).writeFile(outputDir.resolve("metropole-ruhr-v1.0.transit-schedule.xml.gz").toString());
		
		MergeNetworks.merge(network, "", gtfsScenario1.getNetwork());
		MergeNetworks.merge(network, "", gtfsScenario2.getNetwork());

		new NetworkWriter(network).write(publicSvn.resolve(outputDir.resolve("metropole-ruhr-v1.0.network-onlyCarPt.xml.gz")).toString());

		// ----------------------------- Add bicycles and write network ------------------------------------------------

		var bikeNetwork = NetworkUtils.createNetwork();
		new MatsimNetworkReader(bikeNetwork).readFile(publicSvn.resolve(bikeOnlyInfrastructureNetworkFile).toString());
		new BikeNetworkMerger(network).mergeBikeHighways(bikeNetwork);
		new NetworkWriter(network).write(publicSvn.resolve(outputDir.resolve("metropole-ruhr-v1.0.network.xml.gz")).toString());

		// --------------------------------------- Create Counts -------------------------------------------------------

		var longTermCounts = new LongTermCountsCreator.Builder()
				.setLoggingFolder(outputDir.resolve("").toString())
				.withNetwork(network)
				.withRootDir(sharedSvn.resolve(longTermCountsRoot).toString())
				.withIdMapping(sharedSvn.resolve(longTermCountsIdMapping).toString())
				.withStationIdsToOmit(5002L, 50025L)
				.useCountsWithinGeometry(publicSvn.resolve(ruhrShape).toString())
				.build()
				.run();

		var shortTermCounts = new ShortTermCountsCreator.Builder()
				.setLoggingFolder(outputDir.resolve("").toString())
				.withNetwork(network)
				.withRootDir(sharedSvn.resolve(shortTermCountsRoot).toString())
				.withIdMapping(sharedSvn.resolve(shortTermCountsIdMapping).toString())
				.withStationIdsToOmit(5002L, 50025L)
				.useCountsWithinGeometry(publicSvn.resolve(ruhrShape).toString())
				.build()
				.run();

		CombinedCountsWriter.writeCounts(outputDir.resolve("metropole-ruhr-v1.0.counts.xml.gz"),
				longTermCounts.get(RawDataVehicleTypes.Pkw.toString()), shortTermCounts.get(RawDataVehicleTypes.Pkw.toString()));
	}

	private boolean isIncludeLink(Coord coord, int level, Collection<Geometry> geometries) {
		// include all streets wich are motorways to secondary streets
		if (level <= LinkProperties.LEVEL_SECONDARY) return true;

		// within shape include all other streets bigger than tracks
		return level <= LinkProperties.LEVEL_LIVING_STREET && geometries.stream().anyMatch(geometry -> geometry.contains(MGC.coord2Point(coord)));
	}

	private void onLinkCreated(Link link) {
		// all links which allow cars should also allow ride
		if (link.getAllowedModes().contains(TransportMode.car)) {
			var modes = new HashSet<>(link.getAllowedModes());
			modes.add(TransportMode.ride);
			link.setAllowedModes(modes);
		}

		// all regular bike links should have an infrastructure speed factor of 0.5
		if (link.getAllowedModes().contains(TransportMode.bike)) {
			link.getAttributes().putAttribute(BicycleUtils.BICYCLE_INFRASTRUCTURE_SPEED_FACTOR, 0.5);
		}
	}

	private Set<Long> parseNodeIdsToKeep(Path mapping) {

		Set<Long> result = new HashSet<>();
		try (FileReader reader = new FileReader(mapping.toString())) {
			for (CSVRecord record : CSVFormat.newFormat(';').withFirstRecordAsHeader().parse(reader)) {

				try {
					result.add(Long.parseLong(record.get(1)));
					result.add(Long.parseLong(record.get(2)));
				} catch (NumberFormatException e) {
					logger.error("Could not parse count-station: " + record.get(0));
				}
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return result;
	}

	private void checkInputFilesArePresent(Path publicSvn, Path sharedSvn) {

		if (Files.notExists(publicSvn.resolve(osmData)))
			throw new RuntimeException(publicSvn.resolve(osmData).toString() + " does not exist!");
		if (Files.notExists(publicSvn.resolve(ruhrShape)))
			throw new RuntimeException(publicSvn.resolve(ruhrShape).toString() + " does not exist!");
		if (Files.notExists(publicSvn.resolve(gtfsData1)))
			throw new RuntimeException(publicSvn.resolve(gtfsData1).toString() + " does not exist!");
		if (Files.notExists(publicSvn.resolve(bikeOnlyInfrastructureNetworkFile)))
			throw new RuntimeException(publicSvn.resolve(bikeOnlyInfrastructureNetworkFile).toString() + " does not exist!");
		if (Files.notExists(sharedSvn.resolve(longTermCountsRoot)))
			throw new RuntimeException(publicSvn.resolve(longTermCountsRoot).toString() + " does not exist!");
		if (Files.notExists(sharedSvn.resolve(longTermCountsIdMapping)))
			throw new RuntimeException(publicSvn.resolve(longTermCountsIdMapping).toString() + " does not exist!");
		if (Files.notExists(sharedSvn.resolve(shortTermCountsRoot)))
			throw new RuntimeException(publicSvn.resolve(shortTermCountsRoot).toString() + " does not exist!");
		if (Files.notExists(sharedSvn.resolve(shortTermCountsIdMapping)))
			throw new RuntimeException(publicSvn.resolve(shortTermCountsIdMapping).toString() + " does not exist!");
	}

}
