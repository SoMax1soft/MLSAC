/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Modifications:
 *   - Modified by SoMax1soft for the MLSAC.NET project in 2026.
 */

package wtf.mlsac.vision;

/**
 * One MLS VISION event, as sent to {@code POST /api/v1/vision/events}. Mirrors the shape
 * the backend validates in routes/vision.js.
 *
 * <p>Convention: {@code playerA} is always the initiator (chest depositor, item dropper, /pay
 * sender, /tpa requester), {@code playerB} the counterpart. The backend's fan-out scoring
 * depends on this to tell "one player giving to many" apart from "many giving to one".
 */
public final class VisionEvent {
    public final String type;
    public final String playerA;
    public final String playerB;
    public final Integer valueTier;
    public final Double amount;
    public final String material;
    public final Double x;
    public final Double y;
    public final Double z;
    public final String world;
    public final String ip;
    public final String region;

    // Set only on ban_state; kept mutable because the factory fills them after construction rather
    // than widening the shared constructor for one event type.
    public boolean banned;
    public String banReason;
    public String removedBy;
    public boolean permanent;

    private VisionEvent(String type, String playerA, String playerB, Integer valueTier, Double amount,
            String material, Double x, Double y, Double z, String world, String ip, String region) {
        this.type = type;
        this.playerA = playerA;
        this.playerB = playerB;
        this.valueTier = valueTier;
        this.amount = amount;
        this.material = material;
        this.x = x;
        this.y = y;
        this.z = z;
        this.world = world;
        this.ip = ip;
        this.region = region;
    }

    public static VisionEvent chestTransfer(String depositor, String withdrawer, String world, double x, double y, double z) {
        return new VisionEvent("chest_transfer", depositor, withdrawer, null, null, null, x, y, z, world, null, null);
    }

    public static VisionEvent itemTransfer(String dropper, String picker, String material, String world, double x, double y, double z) {
        return new VisionEvent("item_transfer", dropper, picker, null, null, material, x, y, z, world, null, null);
    }

    public static VisionEvent deadDrop(String dropper, String picker, String material, String world, double x, double y, double z) {
        return new VisionEvent("dead_drop", dropper, picker, null, null, material, x, y, z, world, null, null);
    }

    public static VisionEvent tpaRequest(String requester, String target) {
        return new VisionEvent("tpa_request", requester, target, null, null, null, null, null, null, null, null, null);
    }

    public static VisionEvent messageMeta(String sender, String recipient) {
        return new VisionEvent("message_meta", sender, recipient, null, null, null, null, null, null, null, null, null);
    }

    public static VisionEvent economyTransfer(String sender, String recipient, double amount) {
        return new VisionEvent("economy_transfer", sender, recipient, null, amount, null, null, null, null, null, null, null);
    }

    public static VisionEvent ipSeen(String playerName, String ip) {
        return new VisionEvent("ip_seen", playerName, null, null, null, null, null, null, null, null, ip, null);
    }

    /**
     * {@code playerName} is on the region's owner or member list.
     *
     * <p>Only the roster matters, never where a player happens to stand: walking into a base proves
     * nothing, while being granted membership of it is a deliberate act of trust by whoever runs
     * the region.
     */
    public static VisionEvent regionRole(String playerName, String region, String world, boolean owner) {
        return new VisionEvent(owner ? "region_owner" : "region_member", playerName, null, null, null,
                null, null, null, null, world, null, region);
    }

    /** {@code playerName} is no longer on the region's roster. */
    public static VisionEvent regionLeft(String playerName, String region, String world) {
        return new VisionEvent("region_left", playerName, null, null, null, null, null, null, null, world, null, region);
    }

    /**
     * {@code actor} ran /rg addmember|addowner for {@code added}. Optional extra detail on top of
     * the roster scan: the scan sees that someone is a member, this says who put them there.
     */
    public static VisionEvent regionGrant(String actor, String added, String region) {
        return new VisionEvent("region_grant", actor, added, null, null, null, null, null, null, null, null, region);
    }

    /**
     * The player's real punishment state, read from the ban plugin rather than from what MLSAC
     * once did. {@code material} carries the primary group, {@code valueTier} the ban flags.
     */
    public static VisionEvent banState(String playerName, boolean banned, String reason,
            String bannedBy, String removedBy, boolean permanent, String privilege) {
        VisionEvent event = new VisionEvent("ban_state", playerName, bannedBy, banned ? 1 : 0,
                null, privilege, null, null, null, null, null, null);
        event.banned = banned;
        event.banReason = reason;
        event.removedBy = removedBy;
        event.permanent = permanent;
        return event;
    }

    /** {@code amount} carries this session's length in seconds, not a lifetime total — see the backend note in routes/vision.js. */
    public static VisionEvent playerStat(String playerName, long sessionPlaytimeSeconds, String ip) {
        return new VisionEvent("player_stat", playerName, null, null, (double) sessionPlaytimeSeconds, null, null, null, null, null, ip, null);
    }
}
