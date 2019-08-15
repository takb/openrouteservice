package heigit.ors.routing.graphhopper.extensions.flagencoders;

import com.graphhopper.reader.ReaderWay;
import com.graphhopper.util.PMap;

public class EmergencyOverrideFlagEncoder extends CarFlagEncoder {
    private int averageSpeed = 0;

    public EmergencyOverrideFlagEncoder() {
        this(5, 5, 0);
    }

    public EmergencyOverrideFlagEncoder(PMap properties) {
        this((int) properties.getLong("speed_bits", 5),
                properties.getDouble("speed_factor", 5),
                properties.getBool("turn_costs", false) ? 1 : 0);
        this.properties = properties;
        this.averageSpeed = properties.getInt("ave_speed", 0);
        this.setBlockFords(properties.getBool("block_fords", true));
        this.setBlockByDefault(properties.getBool("block_barriers", true));



        this.useAcceleration = properties.getBool("use_acceleration", false);
    }

    public EmergencyOverrideFlagEncoder(String propertiesStr) {
        this(new PMap(propertiesStr));
    }

    public EmergencyOverrideFlagEncoder (int speedBits, double speedFactor, int maxTurnCosts) {
        super(speedBits, speedFactor, maxTurnCosts);

    }

    @Override
    public long handleWayTags(ReaderWay way, long allowed, long relationFlags) {
        if (this.averageSpeed > 0) {
            if (!isAccept(allowed))
                return 0;

            long flags = 0;
            if (!isFerry(allowed)) {
                flags = setSpeed(flags, averageSpeed);

                boolean isRoundabout = way.hasTag("junction", "roundabout");

                if (isRoundabout)
                {
                    flags = setBool(flags, K_ROUNDABOUT, true);
                }

                if (isOneway(way) || isRoundabout) {
                    if (isBackwardOneway(way))
                        flags |= backwardBit;

                    if (isForwardOneway(way))
                        flags |= forwardBit;
                } else
                    flags |= directionBitMask;

            } else {
                double ferrySpeed = getFerrySpeed(way, _speedLimitHandler.getSpeed("living_street"), _speedLimitHandler.getSpeed("service"), _speedLimitHandler.getSpeed("residential"));
                flags = setSpeed(flags, ferrySpeed);
                flags |= directionBitMask;
            }

            return flags;
        } else {
            return super.handleWayTags(way,allowed,relationFlags);
        }
    }

    @Override
    public int getVersion() {
        return 1;
    }

    @Override
    public String toString() {
        return FlagEncoderNames.EMERGENCY_OVERRIDE;
    }
}
