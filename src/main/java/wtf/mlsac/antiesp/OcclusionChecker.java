/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Occlusion raytracer for Anti-ESP.
 *
 * <p>Traversal uses a voxel DDA (Amanatides &amp; Woo): every block the segment passes through is
 * visited exactly once, so a 48 block ray costs at most ~150 block lookups instead of the ~240
 * fixed-size samples a step-based tracer needs — and it can never tunnel through a wall between
 * two samples.
 *
 * <p>All block access goes through a {@link ChunkAccessGuard}. Reading a block in an unloaded
 * chunk makes the server load it synchronously, which is exactly the kind of stall an anti-cheat
 * must never cause, so the default guard skips those chunks and the ray is reported as clear
 * (fail-open: players stay visible).
 */
public final class OcclusionChecker {

    /** Maximum number of sample points on the target hitbox. */
    public static final int MAX_SAMPLES = 9;

    /** Hard cap on visited voxels, so a degenerate ray can never spin. */
    private static final int MAX_VOXELS = 512;

    /** Horizontal offset of the shoulder/leg sample rays, in blocks. */
    private static final double LATERAL_OFFSET = 0.32;

    /** Per-{@link Material} memo of {@link Material#isOccluding()}: 0 = unknown, 1 = no, 2 = yes. */
    private static volatile byte[] occlusionMemo;

    private OcclusionChecker() {
    }

    /** Decides whether a block position may be read at all. */
    public interface ChunkAccessGuard {
        boolean canRead(World world, int chunkX, int chunkZ);
    }

    /** Default guard: only touch chunks that are already loaded. */
    public static final ChunkAccessGuard LOADED_ONLY = World::isChunkLoaded;

    /**
     * Checks if a target player is visible to a viewer player.
     *
     * @param viewer            the player viewing
     * @param target            the target player being checked
     * @param maxDistance       maximum distance to check visibility
     * @param proximityDistance safe distance within which the target is always visible
     * @return true if visible, false if occluded (hidden)
     */
    public static boolean isVisible(Player viewer, Player target, double maxDistance, double proximityDistance) {
        if (viewer == null || target == null || !viewer.isOnline() || !target.isOnline()) {
            return false;
        }
        if (viewer.getEntityId() == target.getEntityId()) {
            return true;
        }
        World world = viewer.getWorld();
        if (!world.equals(target.getWorld())) {
            return false;
        }

        Location eye = viewer.getEyeLocation();
        Location targetLoc = target.getLocation();
        double dx = targetLoc.getX() - eye.getX();
        double dy = targetLoc.getY() - eye.getY();
        double dz = targetLoc.getZ() - eye.getZ();
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > maxDistance * maxDistance) {
            return false;
        }
        if (distSq <= proximityDistance * proximityDistance) {
            return true;
        }

        Vector direction = eye.getDirection();
        double[] samples = new double[MAX_SAMPLES * 3];
        int count = buildSamples(targetLoc.getX(), targetLoc.getY(), targetLoc.getZ(),
                target.isSneaking(), direction.getX(), direction.getZ(), MAX_SAMPLES, samples);

        return firstClearSample(world, eye.getX(), eye.getY(), eye.getZ(), samples, count, 0, LOADED_ONLY) >= 0;
    }

    /**
     * Fills {@code out} with up to {@code sampleCount} points on the target's hitbox as
     * {@code x, y, z} triples, ordered from most to least likely to be exposed.
     *
     * @param lookX x component of the viewer's look direction, used to place the lateral samples
     * @param lookZ z component of the viewer's look direction
     * @return the number of points written
     */
    public static int buildSamples(double x, double y, double z, boolean sneaking,
                                   double lookX, double lookZ, int sampleCount, double[] out) {
        int count = Math.max(1, Math.min(MAX_SAMPLES, sampleCount));

        double eyeY = y + (sneaking ? 1.27 : 1.62);
        double chestY = y + 0.90;
        double waistY = y + 0.55;
        double kneeY = y + 0.35;
        double feetY = y + 0.15;

        // Perpendicular to the viewer's look direction, so the lateral samples straddle the target
        // across the viewer's screen rather than along the line of sight.
        double rightX = -lookZ;
        double rightZ = lookX;
        double len = Math.sqrt(rightX * rightX + rightZ * rightZ);
        if (len < 1.0E-4) {
            rightX = LATERAL_OFFSET;
            rightZ = 0.0;
        } else {
            double scale = LATERAL_OFFSET / len;
            rightX *= scale;
            rightZ *= scale;
        }

        int i = 0;
        i = put(out, i, x, chestY, z);
        if (i / 3 < count) i = put(out, i, x, eyeY, z);
        if (i / 3 < count) i = put(out, i, x, waistY, z);
        if (i / 3 < count) i = put(out, i, x, feetY, z);
        if (i / 3 < count) i = put(out, i, x, kneeY, z);
        if (i / 3 < count) i = put(out, i, x - rightX, chestY, z - rightZ);
        if (i / 3 < count) i = put(out, i, x + rightX, chestY, z + rightZ);
        if (i / 3 < count) i = put(out, i, x - rightX, kneeY, z - rightZ);
        if (i / 3 < count) i = put(out, i, x + rightX, kneeY, z + rightZ);
        return i / 3;
    }

    private static int put(double[] out, int i, double x, double y, double z) {
        out[i] = x;
        out[i + 1] = y;
        out[i + 2] = z;
        return i + 3;
    }

    /**
     * Returns the index of the first sample point reachable from the eye, or -1 when every sample
     * is occluded.
     *
     * <p>{@code preferred} is tried first. Feeding back the index that succeeded last tick turns
     * the steady-state "still visible" case into a single ray.
     */
    public static int firstClearSample(World world, double eyeX, double eyeY, double eyeZ,
                                       double[] samples, int count, int preferred,
                                       ChunkAccessGuard guard) {
        if (count <= 0) {
            return -1;
        }
        if (preferred > 0 && preferred < count) {
            int base = preferred * 3;
            if (isRayUnobstructed(world, eyeX, eyeY, eyeZ, samples[base], samples[base + 1], samples[base + 2], guard)) {
                return preferred;
            }
        }
        for (int i = 0; i < count; i++) {
            if (i == preferred) {
                continue;
            }
            int base = i * 3;
            if (isRayUnobstructed(world, eyeX, eyeY, eyeZ, samples[base], samples[base + 1], samples[base + 2], guard)) {
                return i;
            }
        }
        return -1;
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
     * Checks whether the look direction {@code (lookX, lookY, lookZ)} covers the offset
     * {@code (dx, dy, dz)} within {@code cosHalfFov}. Allocation-free variant of
     * {@link #isInFov(Location, Location, float)}.
     */
    public static boolean isInFov(double lookX, double lookY, double lookZ,
                                  double dx, double dy, double dz, double cosHalfFov) {
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1.0E-6) {
            return true;
        }
        double dot = lookX * dx + lookY * dy + lookZ * dz;
        if (dot <= 0) {
            return cosHalfFov <= 0;
        }
        return dot * dot >= cosHalfFov * cosHalfFov * lenSq;
    }

    public static boolean isRayUnobstructed(World world, double x1, double y1, double z1,
                                            double x2, double y2, double z2) {
        return isRayUnobstructed(world, x1, y1, z1, x2, y2, z2, LOADED_ONLY);
    }

    /**
     * Voxel-DDA raytrace looking for an occluding block strictly between the two endpoints.
     *
     * <p>The voxel containing the eye is deliberately not tested — a viewer whose head is clipped
     * into a block must not have the whole world vanish. The voxel around the target is tested, so
     * a player pressed flat against the far side of a wall stays hidden.
     */
    public static boolean isRayUnobstructed(World world, double x1, double y1, double z1,
                                            double x2, double y2, double z2, ChunkAccessGuard guard) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0E-4) {
            return true;
        }

        double invLength = 1.0 / length;
        double ndx = dx * invLength;
        double ndy = dy * invLength;
        double ndz = dz * invLength;

        int x = floor(x1);
        int y = floor(y1);
        int z = floor(z1);
        final int endX = floor(x2);
        final int endY = floor(y2);
        final int endZ = floor(z2);

        int stepX = ndx > 0 ? 1 : (ndx < 0 ? -1 : 0);
        int stepY = ndy > 0 ? 1 : (ndy < 0 ? -1 : 0);
        int stepZ = ndz > 0 ? 1 : (ndz < 0 ? -1 : 0);

        double tMaxX = boundaryDistance(x, x1, ndx, stepX);
        double tMaxY = boundaryDistance(y, y1, ndy, stepY);
        double tMaxZ = boundaryDistance(z, z1, ndz, stepZ);

        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(ndx);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(ndy);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(ndz);

        int loadedChunkX = Integer.MIN_VALUE;
        int loadedChunkZ = Integer.MIN_VALUE;

        for (int visited = 0; visited < MAX_VOXELS; visited++) {
            double entered;
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                entered = tMaxX;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                entered = tMaxY;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                entered = tMaxZ;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (entered > length) {
                return true;
            }

            int chunkX = x >> 4;
            int chunkZ = z >> 4;
            if (chunkX != loadedChunkX || chunkZ != loadedChunkZ) {
                if (guard != null && !guard.canRead(world, chunkX, chunkZ)) {
                    return true;
                }
                loadedChunkX = chunkX;
                loadedChunkZ = chunkZ;
            }

            if (isOccluding(world.getBlockAt(x, y, z).getType())) {
                return false;
            }
            if (x == endX && y == endY && z == endZ) {
                return true;
            }
        }
        return true;
    }

    /** Distance along the ray from {@code origin} to the first voxel boundary on this axis. */
    private static double boundaryDistance(int voxel, double origin, double normal, int step) {
        if (step == 0) {
            return Double.MAX_VALUE;
        }
        double edge = step > 0 ? (voxel + 1) - origin : origin - voxel;
        return edge / Math.abs(normal);
    }

    /**
     * Memoized {@link Material#isOccluding()}. The raytracer calls this for every visited voxel and
     * the underlying lookup is not free on modern server versions.
     */
    public static boolean isOccluding(Material material) {
        if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR) {
            return false;
        }
        byte[] memo = occlusionMemo;
        int ordinal = material.ordinal();
        if (memo == null || memo.length <= ordinal) {
            memo = new byte[Material.values().length];
            occlusionMemo = memo;
        }
        byte cached = memo[ordinal];
        if (cached != 0) {
            return cached == 2;
        }
        boolean occluding;
        try {
            occluding = material.isOccluding();
        } catch (Throwable ignored) {
            occluding = material.isSolid();
        }
        memo[ordinal] = (byte) (occluding ? 2 : 1);
        return occluding;
    }

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }
}
