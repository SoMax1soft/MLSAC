/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.antiesp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ViewerVisibilityTest {

    private ViewerVisibility visibility;

    @BeforeEach
    void setUp() {
        visibility = new ViewerVisibility(UUID.randomUUID());
    }

    @Test
    @DisplayName("The hidden counter short-circuits the packet filter")
    void testHiddenCountShortCircuit() {
        // Every outgoing packet asks this question first, so it must answer without touching the
        // link table while the viewer hides nobody.
        visibility.link(42).hidden = true;

        assertFalse(visibility.isHidden(42), "A stale flag must not count while the counter is zero");

        visibility.setHiddenCount(1);

        assertTrue(visibility.isHidden(42));
        assertFalse(visibility.isHidden(43), "Unknown entities are never hidden");
    }

    @Test
    @DisplayName("Links are created once and reused")
    void testLinkIsStable() {
        ViewerVisibility.TargetLink first = visibility.link(7);
        ViewerVisibility.TargetLink second = visibility.link(7);

        assertSame(first, second);
        assertSame(first, visibility.peek(7));
        assertNull(visibility.peek(8), "peek must not create a link");
    }

    @Test
    @DisplayName("A fresh link starts visible and untracked")
    void testLinkDefaults() {
        ViewerVisibility.TargetLink link = visibility.link(1);

        assertFalse(link.hidden);
        assertFalse(link.clientHas);
        assertFalse(link.serverTracked);
        assertTrue(link.lastVisible, "Fail-open: an unchecked pair counts as visible");
        assertEquals(0L, link.occludedSinceMs);
    }

    @Test
    @DisplayName("The movement gate only holds while both players stay put")
    void testMovementGate() {
        ViewerVisibility.TargetLink link = visibility.link(1);
        link.rememberPositions(0, 64, 0, 10, 64, 0);

        assertTrue(link.stillWithin(0, 64, 0, 10, 64, 0, 0.3),
                "Nobody moved, the cached result is still good");
        assertTrue(link.stillWithin(0.1, 64, 0.1, 10, 64, 0, 0.3),
                "A twitch inside the threshold keeps the cache");
        assertFalse(link.stillWithin(1.0, 64, 0, 10, 64, 0, 0.3),
                "The viewer moved, the ray has to be redone");
        assertFalse(link.stillWithin(0, 64, 0, 11.0, 64, 0, 0.3),
                "The target moved, the ray has to be redone");
        assertFalse(link.stillWithin(0, 65, 0, 10, 64, 0, 0.3),
                "Vertical movement counts too");
    }

    @Test
    @DisplayName("A zero threshold forces a recheck on any movement")
    void testZeroThreshold() {
        ViewerVisibility.TargetLink link = visibility.link(1);
        link.rememberPositions(0, 64, 0, 10, 64, 0);

        assertTrue(link.stillWithin(0, 64, 0, 10, 64, 0, 0.0));
        assertFalse(link.stillWithin(0.01, 64, 0, 10, 64, 0, 0.0));
    }

    @Test
    @DisplayName("Hidden positions default to empty so the sound filter never sees null")
    void testHiddenPositionsDefault() {
        assertNotNull(visibility.getHiddenPositions());
        assertEquals(0, visibility.getHiddenPositions().length);
    }
}
