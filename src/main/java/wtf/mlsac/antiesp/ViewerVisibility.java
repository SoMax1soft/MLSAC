/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.antiesp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one viewer's client currently knows about the other players.
 *
 * <p>Written by the occlusion task on the server thread, read by the packet filter on netty
 * threads, so the fields the filter touches are volatile. The filter's first question is always
 * {@link #getHiddenCount()}: while a viewer hides nobody, every packet addressed to them can be
 * waved through without decoding anything at all.
 */
final class ViewerVisibility {

    private final UUID viewerId;
    private final Map<Integer, TargetLink> links = new ConcurrentHashMap<>();

    private volatile int hiddenCount;
    /** Positions of currently hidden players as x,y,z triples — used to mute their sounds. */
    private volatile double[] hiddenPositions = new double[0];
    private volatile boolean bypassing;

    ViewerVisibility(UUID viewerId) {
        this.viewerId = viewerId;
    }

    UUID getViewerId() {
        return viewerId;
    }

    Map<Integer, TargetLink> getLinks() {
        return links;
    }

    TargetLink link(int entityId) {
        return links.computeIfAbsent(entityId, id -> new TargetLink());
    }

    TargetLink peek(int entityId) {
        return links.get(entityId);
    }

    boolean isHidden(int entityId) {
        if (hiddenCount == 0) {
            return false;
        }
        TargetLink link = links.get(entityId);
        return link != null && link.hidden;
    }

    int getHiddenCount() {
        return hiddenCount;
    }

    void setHiddenCount(int hiddenCount) {
        this.hiddenCount = hiddenCount;
    }

    double[] getHiddenPositions() {
        return hiddenPositions;
    }

    void setHiddenPositions(double[] hiddenPositions) {
        this.hiddenPositions = hiddenPositions;
    }

    boolean isBypassing() {
        return bypassing;
    }

    void setBypassing(boolean bypassing) {
        this.bypassing = bypassing;
    }

    /** Per (viewer, target) pair state. */
    static final class TargetLink {
        /** True while the plugin is suppressing this entity for the viewer. */
        volatile boolean hidden;
        /** True while the viewer's client actually holds the entity. */
        volatile boolean clientHas;
        /** True while the server's entity tracker considers the viewer a tracker of the target. */
        volatile boolean serverTracked;

        /** When the target first became occluded in the current streak, 0 while visible. */
        long occludedSinceMs;
        /** Server tick of the last raytrace, used to age out the cached result. */
        int lastCheckTick;
        boolean lastVisible = true;
        /** Sample index that succeeded last time; retried first to keep the steady state at one ray. */
        int preferredSample;
        /**
         * Pass at which the gear should be sent once more after a reveal, or 0.
         *
         * <p>The reveal and the server's own equipment update can land in either order, and the
         * losing one is dropped — the client then keeps the armour it had at spawn time until the
         * player next changes something. One repeat closes that window.
         */
        int equipmentResyncTick;

        double lastViewerX, lastViewerY, lastViewerZ;
        double lastTargetX, lastTargetY, lastTargetZ;

        void rememberPositions(double viewerX, double viewerY, double viewerZ,
                               double targetX, double targetY, double targetZ) {
            this.lastViewerX = viewerX;
            this.lastViewerY = viewerY;
            this.lastViewerZ = viewerZ;
            this.lastTargetX = targetX;
            this.lastTargetY = targetY;
            this.lastTargetZ = targetZ;
        }

        /** True when neither endpoint has moved further than {@code threshold} since the last check. */
        boolean stillWithin(double viewerX, double viewerY, double viewerZ,
                            double targetX, double targetY, double targetZ, double threshold) {
            double thresholdSq = threshold * threshold;
            double dvx = viewerX - lastViewerX;
            double dvy = viewerY - lastViewerY;
            double dvz = viewerZ - lastViewerZ;
            if (dvx * dvx + dvy * dvy + dvz * dvz > thresholdSq) {
                return false;
            }
            double dtx = targetX - lastTargetX;
            double dty = targetY - lastTargetY;
            double dtz = targetZ - lastTargetZ;
            return dtx * dtx + dty * dty + dtz * dtz <= thresholdSq;
        }
    }
}
