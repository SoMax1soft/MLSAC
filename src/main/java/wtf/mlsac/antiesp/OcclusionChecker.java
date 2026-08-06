/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Fast multi-ray occlusion raytracer for Anti-ESP.
 * Determines if a target player is visible to a viewer player based on
 * solid block collisions, proximity safety distance, and optional FOV frustum.
 */
public class OcclusionChecker {

    /**
     * Checks if a target player is visible to a viewer player.
     *
     * @param viewer            The player viewing
     * @param target            The target player being checked
     * @param maxDistance       Maximum distance to check visibility
     * @param proximityDistance Safe distance within which target is always visible
     * @return true if visible, false if occluded (hidden)
     */
    public static boolean isVisible(Player viewer, Player target, double maxDistance, double proximityDistance) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) {
            return false;
        }

        if (viewer.getEntityId() == target.getEntityId()) {
            return true;
        }

        World vWorld = viewer.getWorld();
        World tWorld = target.getWorld();
        if (!vWorld.equals(tWorld)) {
            return false;
        }

        Location vEye = viewer.getEyeLocation();
        Location tLoc = target.getLocation();

        double distSq = vEye.distanceSquared(tLoc);
        if (distSq > maxDistance * maxDistance) {
            return false;
        }

        // Proximity safety: players within close range are always visible (prevents corner peek latency)
        if (distSq <= proximityDistance * proximityDistance) {
            return true;
        }

        // Multi-ray test to target bounding box
        // Points: Target Eye, Target Chest/Waist, Target Feet, Left Shoulder, Right Shoulder
        double tx = tLoc.getX();
        double ty = tLoc.getY();
        double tz = tLoc.getZ();

        Location vLoc = viewer.getLocation();
        double eyeY = ty + (target.isSneaking() ? 1.27 : 1.62);
        double chestY = ty + 0.9;
        double waistY = ty + 0.55;
        double kneeY = ty + 0.35;
        double feetY = ty + 0.15;

        // Eye ray
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), tx, eyeY, tz, vLoc, tLoc)) {
            return true;
        }

        // Chest ray
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), tx, chestY, tz, vLoc, tLoc)) {
            return true;
        }

        // Waist ray
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), tx, waistY, tz, vLoc, tLoc)) {
            return true;
        }

        // Knee ray
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), tx, kneeY, tz, vLoc, tLoc)) {
            return true;
        }

        // Feet ray
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), tx, feetY, tz, vLoc, tLoc)) {
            return true;
        }

        // Left/Right shoulder & leg offset rays (+/- 0.35)
        Vector dir = vEye.getDirection().normalize();
        Vector right;
        if (Math.abs(dir.getY()) > 0.95) {
            right = new Vector(1, 0, 0).multiply(0.35);
        } else {
            right = new Vector(-dir.getZ(), 0, dir.getX()).normalize().multiply(0.35);
        }

        double leftX = tx - right.getX();
        double leftZ = tz - right.getZ();
        if (isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), leftX, chestY, leftZ, vLoc, tLoc) ||
            isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), leftX, kneeY, leftZ, vLoc, tLoc)) {
            return true;
        }

        double rightX = tx + right.getX();
        double rightZ = tz + right.getZ();
        return isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), rightX, chestY, rightZ, vLoc, tLoc) ||
               isRayUnobstructed(vWorld, vEye.getX(), vEye.getY(), vEye.getZ(), rightX, kneeY, rightZ, vLoc, tLoc);
    }

    /**
     * Checks if a point is within the viewer's FOV (in degrees).
     */
    public static boolean isInFov(Location viewerEye, Location targetPos, float fovDegrees) {
        Vector lookDir = viewerEye.getDirection().normalize();
        Vector toTarget = targetPos.toVector().subtract(viewerEye.toVector()).normalize();
        double dot = lookDir.dot(toTarget);
        double threshold = Math.cos(Math.toRadians(fovDegrees / 2.0));
        return dot >= threshold;
    }

    /**
     * Fast step-based raytrace checking for solid occluding blocks between (x1,y1,z1) and (x2,y2,z2).
     */
    public static boolean isRayUnobstructed(World world, double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0.001) {
            return true;
        }

        // Step by ~0.2 blocks for precision
        double steps = Math.ceil(distance / 0.2);
        double stepX = dx / steps;
        double stepY = dy / steps;
        double stepZ = dz / steps;
        int lastBlockX = Integer.MIN_VALUE;
        int lastBlockY = Integer.MIN_VALUE;
        int lastBlockZ = Integer.MIN_VALUE;

        for (int i = 0; i <= steps; i++) {
            double curX = x1 + stepX * i;
            double curY = y1 + stepY * i;
            double curZ = z1 + stepZ * i;

            int bx = (int) Math.floor(curX);
            int by = (int) Math.floor(curY);
            int bz = (int) Math.floor(curZ);

            if (bx == lastBlockX && by == lastBlockY && bz == lastBlockZ) {
                continue;
            }

            // Diagonal corner seam check
            if (lastBlockX != Integer.MIN_VALUE && lastBlockZ != Integer.MIN_VALUE) {
                if (bx != lastBlockX && bz != lastBlockZ) {
                    int c1X = lastBlockX;
                    int c1Z = bz;
                    int c2X = bx;
                    int c2Z = lastBlockZ;

                    if (world.getBlockAt(c1X, by, c1Z).getType().isOccluding() ||
                        world.getBlockAt(c2X, by, c2Z).getType().isOccluding()) {
                        return false; // Obstructed by diagonal corner block
                    }
                }
            }

            lastBlockX = bx;
            lastBlockY = by;
            lastBlockZ = bz;

            Block block = world.getBlockAt(bx, by, bz);
            Material type = block.getType();
            if (type.isOccluding()) {
                return false; // Obstructed by solid block between viewer and target
            }
        }
        return true;
    }

    public static boolean isRayUnobstructed(World world, double x1, double y1, double z1, double x2, double y2, double z2, Location viewerLoc, Location targetLoc) {
        return isRayUnobstructed(world, x1, y1, z1, x2, y2, z2);
    }
}
