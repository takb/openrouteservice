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

import com.graphhopper.util.CmdArgs;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import heigit.ors.routing.configuration.RouteProfileConfiguration;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Properties;

public class RoutingProfileTest {
    RouteProfileConfiguration config;

    @Before
    public void init() {
        config = new RouteProfileConfiguration();
        config.setGraphPath("graphLocation");
        config.setEncoderFlagsSize(4);
        config.setProfiles("driving-car");
    }

    @Test
    public void testBaseCmdArgGeneration() {
        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);

        Assert.assertEquals("test.osm", args.get("datareader.file", ""));
        Assert.assertEquals("graphLocation", args.get("graph.location", ""));
        Assert.assertEquals("4", args.get("graph.bytes_for_flags", ""));

        Assert.assertEquals("car-ors", args.get("graph.flag_encoders", ""));
    }

    @Test
    public void testEncoderOptionsCmdArgsGeneration() {
        config.setEncoderOptions("avoid_item:true");

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("car-ors|avoid_item:true", args.get("graph.flag_encoders", ""));

        config.setEncoderOptions("avoid_item:true|another_option:false");

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("car-ors|avoid_item:true|another_option:false", args.get("graph.flag_encoders", ""));
    }

    @Test
    public void testElevationCmdArgGeneration() {
        config.setElevationCacheClear(false);
        config.setElevationCachePath("elevationData");
        config.setElevationProvider("srtm");
        config.setElevationDataAccess("dataAccess");

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);

        Assert.assertEquals("elevationData", args.get("graph.elevation.cache_dir", ""));
        Assert.assertEquals("srtm", args.get("graph.elevation.provider", ""));
        Assert.assertEquals("dataAccess", args.get("graph.elevation.dataaccess", ""));
        Assert.assertEquals("false", args.get("graph.elevation.clear", ""));
    }

    @Test
    public void testInstructionsCmdArgGeneration() {
        config.setInstructions(false);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);

        Assert.assertEquals("false", args.get("instructions", ""));
    }

    @Test
    public void testPreparationStageCmdArgs() {
        Properties props = new Properties();
        props.put("min_network_size", "100");
        props.put("min_one_way_network_size", "101");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("100", args.get("prepare.min_network_size", ""));
        Assert.assertEquals("101", args.get("prepare.min_one_way_network_size", ""));

        props = new Properties();
        props.put("min_network_size", "150");
        props.put("min_one_way_network_size", "111");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("150", args.get("prepare.min_network_size", ""));
        Assert.assertEquals("111", args.get("prepare.min_one_way_network_size", ""));
    }

    @Test
    public void testPreparationStageCHCmdArgs() {
        Properties props = new Properties();
        props.put("methods.ch.enabled", "true");
        props.put("methods.ch.threads", "2");
        props.put("methods.ch.weightings", "test");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("2", args.get("prepare.ch.threads", ""));
        Assert.assertEquals("test", args.get("prepare.ch.weightings", ""));

        props = new Properties();
        props.put("methods.ch.enabled", "true");
        props.put("methods.ch.threads", "5");
        props.put("methods.ch.weightings", "test2");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("5", args.get("prepare.ch.threads", ""));
        Assert.assertEquals("test2", args.get("prepare.ch.weightings", ""));


        props = new Properties();
        props.put("methods.ch.enabled", "false");
        props.put("methods.ch.threads", "2");
        props.put("methods.ch.weightings", "test");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("no", args.get("prepare.ch.weightings", ""));
        Assert.assertFalse(args.has("prepare.ch.threads"));
    }

    @Test
    public void testPreparationStageLMCmdArgs() {
        Properties props = new Properties();
        props.put("methods.lm.enabled", "true");
        props.put("methods.lm.threads", "2");
        props.put("methods.lm.weightings", "test");
        props.put("methods.lm.landmarks", "20");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("2", args.get("prepare.lm.threads", ""));
        Assert.assertEquals("test", args.get("prepare.lm.weightings", ""));
        Assert.assertEquals("20", args.get("prepare.lm.landmarks", ""));

        props = new Properties();
        props.put("methods.lm.enabled", "true");
        props.put("methods.lm.threads", "5");
        props.put("methods.lm.weightings", "test2");
        props.put("methods.lm.landmarks", "21");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("5", args.get("prepare.lm.threads", ""));
        Assert.assertEquals("test2", args.get("prepare.lm.weightings", ""));
        Assert.assertEquals("21", args.get("prepare.lm.landmarks", ""));

        props = new Properties();
        props.put("methods.lm.enabled", "false");
        props.put("methods.lm.threads", "2");
        props.put("methods.lm.weightings", "test");
        props.put("methods.lm.landmarks", "20");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("no", args.get("prepare.lm.weightings", ""));
        Assert.assertFalse(args.has("prepare.lm.threads"));
        Assert.assertFalse(args.has("prepare.lm.landmarks"));
    }

    @Test
    public void testPreparationStageCoreAltCmdArgs() {
        Properties props = new Properties();
        props.put("methods.core.enabled", "true");
        props.put("methods.core.threads", "2");
        props.put("methods.core.weightings", "test");
        props.put("methods.core.lmsets", "10");
        props.put("methods.core.landmarks", "20");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("2", args.get("prepare.core.threads", ""));
        Assert.assertEquals("test", args.get("prepare.core.weightings", ""));
        Assert.assertEquals("10", args.get("prepare.corelm.lmsets", ""));
        Assert.assertEquals("20", args.get("prepare.corelm.landmarks", ""));

        props = new Properties();
        props.put("methods.core.enabled", "true");
        props.put("methods.core.threads", "5");
        props.put("methods.core.weightings", "test2");
        props.put("methods.core.lmsets", "15");
        props.put("methods.core.landmarks", "25");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("5", args.get("prepare.core.threads", ""));
        Assert.assertEquals("test2", args.get("prepare.core.weightings", ""));
        Assert.assertEquals("15", args.get("prepare.corelm.lmsets", ""));
        Assert.assertEquals("25", args.get("prepare.corelm.landmarks", ""));

        props = new Properties();
        props.put("methods.core.enabled", "false");
        props.put("methods.core.threads", "2");
        props.put("methods.core.weightings", "test");
        props.put("methods.core.lmsets", "10");
        props.put("methods.core.landmarks", "20");
        preparation = ConfigFactory.parseProperties(props);

        config.setPreparationOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("no", args.get("prepare.core.weightings", ""));
        Assert.assertFalse(args.has("prepare.core.threads"));
        Assert.assertFalse(args.has("prepare.corelm.landmarks"));
        Assert.assertFalse(args.has("prepare.corelm.lmsets"));
    }

    @Test
    public void testExecutionStageCHCmdArgs() {
        Properties props = new Properties();
        props.put("methods.ch.disabling_allowed", "true");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("true", args.get("routing.ch.disabling_allowed", ""));

        props = new Properties();
        props.put("methods.ch.disabling_allowed", "false");
        preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("false", args.get("routing.ch.disabling_allowed", ""));
    }

    @Test
    public void testExecutionStageCoreCmdArgs() {
        Properties props = new Properties();
        props.put("methods.core.disabling_allowed", "true");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("true", args.get("routing.core.disabling_allowed", ""));

        props = new Properties();
        props.put("methods.core.disabling_allowed", "false");
        preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("false", args.get("routing.core.disabling_allowed", ""));
    }

    @Test
    public void testExecutionStageLMCmdArgs() {
        Properties props = new Properties();
        props.put("methods.lm.disabling_allowed", "true");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("true", args.get("routing.lm.disabling_allowed", ""));

        props = new Properties();
        props.put("methods.lm.disabling_allowed", "false");
        preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("false", args.get("routing.lm.disabling_allowed", ""));
    }

    @Test
    public void testExecutionStageCoreLmCmdArgs() {
        Properties props = new Properties();
        props.put("methods.corelm.disabling_allowed", "true");
        props.put("methods.corelm.active_landmarks", "5");
        Config preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        CmdArgs args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("true", args.get("routing.lm.disabling_allowed", ""));
        Assert.assertEquals("5", args.get("routing.corelm.active_landmarks", ""));

        props = new Properties();
        props.put("methods.corelm.disabling_allowed", "false");
        props.put("methods.corelm.active_landmarks", "10");
        preparation = ConfigFactory.parseProperties(props);

        config.setExecutionOpts(preparation);

        args = RoutingProfile.createGHSettings("test.osm", config);
        Assert.assertEquals("false", args.get("routing.lm.disabling_allowed", ""));
        Assert.assertEquals("10", args.get("routing.corelm.active_landmarks", ""));
    }
}
