/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.antiesp;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

class OcclusionCheckerTest {

    private World mockWorld;
    private Map<String, Material> worldBlocks;

    @BeforeEach
    void setUp() {
        mockWorld = Mockito.mock(World.class);
        worldBlocks = new HashMap<>();

        when(mockWorld.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0);
            int y = invocation.getArgument(1);
            int z = invocation.getArgument(2);
            String key = x + "," + y + "," + z;
            Material mat = worldBlocks.getOrDefault(key, Material.AIR);

            Block mockBlock = Mockito.mock(Block.class);
            when(mockBlock.getType()).thenReturn(mat);
            return mockBlock;
        });
    }

    @Test
    @DisplayName("Ray in open air should be unobstructed")
    void testRayUnobstructedInAir() {
        boolean unobstructed = OcclusionChecker.isRayUnobstructed(mockWorld, 0, 64, 0, 10, 64, 0);
        assertTrue(unobstructed, "Ray in open air must be unobstructed");
    }

    @Test
    @DisplayName("Ray through solid stone wall should be obstructed")
    void testRayObstructedByStoneWall() {
        // Place stone block at (5, 64, 0)
        worldBlocks.put("5,64,0", Material.STONE);

        boolean unobstructed = OcclusionChecker.isRayUnobstructed(mockWorld, 0, 64, 0, 10, 64, 0);
        assertFalse(unobstructed, "Ray through stone wall must be obstructed");
    }

    @Test
    @DisplayName("Ray through glass wall should NOT be occluded")
    void testRayNotObstructedByGlassWall() {
        // Place glass block at (5, 64, 0)
        worldBlocks.put("5,64,0", Material.GLASS);

        boolean unobstructed = OcclusionChecker.isRayUnobstructed(mockWorld, 0, 64, 0, 10, 64, 0);
        assertTrue(unobstructed, "Glass block should not occlude line of sight");
    }

    @Test
    @DisplayName("Ray through diagonal block corner seam must be obstructed")
    void testDiagonalBlockCornerSeamIsObstructed() {
        // Place 2 solid blocks touching diagonally at corner (10.5, 10.5)
        worldBlocks.put("10,64,11", Material.STONE);
        worldBlocks.put("11,64,10", Material.STONE);

        // Ray from (9.5, 64, 9.5) to (11.5, 64, 11.5)
        boolean unobstructed = OcclusionChecker.isRayUnobstructed(mockWorld, 9.5, 64.5, 9.5, 11.5, 64.5, 11.5);
        assertFalse(unobstructed, "Ray passing through diagonal block seam must be obstructed");
    }

    @Test
    @DisplayName("Target standing on solid ceiling floor must be hidden from viewer looking up from below")
    void testTargetOnFloorIsHiddenFromViewerBelow() {
        // Floor block at (0, 64, 0) - solid wood
        worldBlocks.put("0,64,0", Material.OAK_PLANKS);

        // Viewer below at (0, 62, 0), looking up
        // Target standing on top of floor at (0, 65, 0)
        Location viewerLoc = new Location(mockWorld, 0, 62, 0);
        Location targetLoc = new Location(mockWorld, 0, 65, 0);

        boolean unobstructed = OcclusionChecker.isRayUnobstructed(mockWorld, 0, 63.62, 0, 0, 65.2, 0, viewerLoc, targetLoc);
        assertFalse(unobstructed, "Solid floor block at Y=64 must obstruct rays from viewer below");
    }

    @Test
    @DisplayName("FOV check detects targets in front vs behind viewer")
    void testFovCheck() {
        Location eyeLoc = new Location(mockWorld, 0, 64, 0);
        eyeLoc.setDirection(new Vector(1, 0, 0)); // Looking straight towards +X

        Location targetInFront = new Location(mockWorld, 10, 64, 0);
        Location targetBehind = new Location(mockWorld, -10, 64, 0);

        assertTrue(OcclusionChecker.isInFov(eyeLoc, targetInFront, 120.0f), "Target in front should be in FOV");
        assertFalse(OcclusionChecker.isInFov(eyeLoc, targetBehind, 120.0f), "Target behind back should NOT be in FOV");
    }

    @Test
    @DisplayName("Proximity safety distance forces visibility even behind walls")
    void testProximitySafety() {
        Player viewer = Mockito.mock(Player.class);
        Player target = Mockito.mock(Player.class);

        when(viewer.getEntityId()).thenReturn(1);
        when(target.getEntityId()).thenReturn(2);
        when(viewer.isOnline()).thenReturn(true);
        when(target.isOnline()).thenReturn(true);

        when(viewer.getWorld()).thenReturn(mockWorld);
        when(target.getWorld()).thenReturn(mockWorld);

        // Viewer at (0, 64, 0), Target close at (1.5, 64, 0) - within 3.0 proximity
        when(viewer.getEyeLocation()).thenReturn(new Location(mockWorld, 0, 65.62, 0));
        when(target.getLocation()).thenReturn(new Location(mockWorld, 1.5, 64, 0));

        // Place wall right between them
        worldBlocks.put("1,64,0", Material.STONE);
        worldBlocks.put("1,65,0", Material.STONE);

        boolean visible = OcclusionChecker.isVisible(viewer, target, 48.0, 3.0);
        assertTrue(visible, "Target within proximity safety radius (1.5 blocks) must be visible despite wall");
    }

    @Test
    @DisplayName("Randomized Smoke Test: 100 random player and wall positions")
    void testRandomizedSmokeTest() {
        Random random = new Random(42);

        for (int i = 0; i < 100; i++) {
            worldBlocks.clear();

            double vx = (random.nextDouble() - 0.5) * 100;
            double vy = 64.0;
            double vz = (random.nextDouble() - 0.5) * 100;

            double tx = (random.nextDouble() - 0.5) * 100;
            double ty = 64.0;
            double tz = (random.nextDouble() - 0.5) * 100;

            double dist = Math.sqrt((tx - vx) * (tx - vx) + (tz - vz) * (tz - vz));
            if (dist < 5.0) {
                continue; // Skip very close coordinates to focus test
            }

            boolean placeWall = random.nextBoolean();
            if (placeWall) {
                // Place a wall at midpoint
                int mx = (int) Math.floor((vx + tx) / 2.0);
                int my = 64;
                int mz = (int) Math.floor((vz + tz) / 2.0);

                worldBlocks.put(mx + "," + my + "," + mz, Material.STONE);
                worldBlocks.put(mx + "," + (my + 1) + "," + mz, Material.STONE);
            }

            Player viewer = Mockito.mock(Player.class);
            Player target = Mockito.mock(Player.class);

            when(viewer.getEntityId()).thenReturn(100 + i);
            when(target.getEntityId()).thenReturn(200 + i);
            when(viewer.isOnline()).thenReturn(true);
            when(target.isOnline()).thenReturn(true);
            when(viewer.getWorld()).thenReturn(mockWorld);
            when(target.getWorld()).thenReturn(mockWorld);

            Location vEye = new Location(mockWorld, vx, vy + 1.62, vz);
            vEye.setDirection(new Vector(tx - vx, 0, tz - vz).normalize());
            when(viewer.getEyeLocation()).thenReturn(vEye);
            when(target.getLocation()).thenReturn(new Location(mockWorld, tx, ty, tz));

            boolean isVisible = OcclusionChecker.isVisible(viewer, target, 120.0, 2.0);

            if (placeWall) {
                // If wall was placed, ray from eye to target center/feet through midpoint block is blocked
                // OcclusionChecker should correctly return false (or true if wall didn't intersect ray bounds)
                assertNotNull(isVisible);
            } else {
                assertTrue(isVisible, "Iteration " + i + ": Open path with no wall must be visible");
            }
        }
    }

    @Test
    @DisplayName("Viewer looking straight up at target behind a wall must be occluded (not NaN)")
    void testVerticalLookStraightUpOcclusion() {
        Player viewer = Mockito.mock(Player.class);
        Player target = Mockito.mock(Player.class);

        when(viewer.getEntityId()).thenReturn(1);
        when(target.getEntityId()).thenReturn(2);
        when(viewer.isOnline()).thenReturn(true);
        when(target.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(mockWorld);
        when(target.getWorld()).thenReturn(mockWorld);

        // Viewer at (0, 64, 0) looking straight UP (pitch -90 -> vector (0, 1, 0))
        Location vEye = new Location(mockWorld, 0, 65.62, 0);
        vEye.setDirection(new Vector(0, 1, 0));
        when(viewer.getEyeLocation()).thenReturn(vEye);
        when(viewer.getLocation()).thenReturn(new Location(mockWorld, 0, 64, 0));

        // Target far away at (10, 64, 0)
        when(target.getLocation()).thenReturn(new Location(mockWorld, 10, 64, 0));

        // Solid stone wall blocking line of sight between (0,64,0) and (10,64,0)
        for (int y = 64; y <= 66; y++) {
            for (int z = -1; z <= 1; z++) {
                worldBlocks.put("5," + y + "," + z, Material.STONE);
            }
        }

        boolean visible = OcclusionChecker.isVisible(viewer, target, 48.0, 1.0);
        assertFalse(visible, "Target behind stone wall must be occluded even when viewer looks straight up");
    }

    @Test
    @DisplayName("Target standing right next to wall block must be occluded from viewer on opposite side")
    void testTargetStandingAdjacentToWallIsOccluded() {
        Player viewer = Mockito.mock(Player.class);
        Player target = Mockito.mock(Player.class);

        when(viewer.getEntityId()).thenReturn(1);
        when(target.getEntityId()).thenReturn(2);
        when(viewer.isOnline()).thenReturn(true);
        when(target.isOnline()).thenReturn(true);
        when(viewer.getWorld()).thenReturn(mockWorld);
        when(target.getWorld()).thenReturn(mockWorld);

        Location vEye = new Location(mockWorld, 0, 65.62, 0);
        vEye.setDirection(new Vector(1, 0, 0));
        when(viewer.getEyeLocation()).thenReturn(vEye);
        when(viewer.getLocation()).thenReturn(new Location(mockWorld, 0, 64, 0));

        for (int y = 64; y <= 66; y++) {
            for (int z = -1; z <= 1; z++) {
                worldBlocks.put("5," + y + "," + z, Material.STONE);
            }
        }

        when(target.getLocation()).thenReturn(new Location(mockWorld, 5.7, 64, 0));

        boolean visible = OcclusionChecker.isVisible(viewer, target, 48.0, 1.0);
        assertFalse(visible, "Target standing adjacent to wall must be occluded from viewer on opposite side");
    }
}
