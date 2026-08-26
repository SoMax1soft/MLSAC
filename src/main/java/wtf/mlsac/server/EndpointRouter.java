/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 * This project is community-maintained and not affiliated with any single upstream repository.
 */

package wtf.mlsac.server;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Picks which backend address to talk to: the configured endpoint, or the reserve when the first
 * one cannot be reached at all.
 *
 * <p>Only a failure to reach the host counts — a timeout, a refused connection, a DNS failure. An
 * HTTP status is an answer, not an outage: failing over on 401 would just re-send an invalid API
 * key somewhere else, and failing over on 500 would hide a backend problem behind a second host.
 *
 * <p>Selection is never sticky by itself. {@link #candidates()} always lists the primary first, so
 * every fresh connection attempt starts from the configured endpoint and a server does not stay
 * pinned to the reserve once the outage is over; only after the primary fails again does the
 * reserve get used. Between connections, {@link #current()} keeps returning whatever last worked so
 * heartbeats and predictions follow the same host.
 */
public final class EndpointRouter {

    private final String primary;
    private final String reserve;

    /** The address that last connected. Read from request threads, written on (re)connect. */
    private volatile String selected;

    public EndpointRouter(String primary, String reserve) {
        this.primary = normalize(primary);
        String cleanedReserve = normalize(reserve);
        // A reserve identical to the primary is not a reserve; treat it as absent so the logs do
        // not claim a failover that changes nothing.
        this.reserve = cleanedReserve.isEmpty() || cleanedReserve.equals(this.primary) ? "" : cleanedReserve;
        this.selected = this.primary;
    }

    private static String normalize(String address) {
        if (address == null) {
            return "";
        }
        String trimmed = address.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The address currently in use for outgoing requests. */
    public String current() {
        return selected;
    }

    public String primary() {
        return primary;
    }

    public String reserve() {
        return reserve;
    }

    public boolean hasReserve() {
        return !reserve.isEmpty();
    }

    public boolean isOnReserve() {
        return !primary.equals(selected);
    }

    /** Addresses to try for a new connection, best first. */
    public List<String> candidates() {
        List<String> candidates = new ArrayList<>(2);
        candidates.add(primary);
        if (hasReserve()) {
            candidates.add(reserve);
        }
        return candidates;
    }

    /** Records the address a connection succeeded on, so later requests go to the same host. */
    public void select(String address) {
        this.selected = normalize(address);
    }

    /**
     * Whether this failure means the host could not be reached, as opposed to answering with an
     * error.
     */
    public static boolean isUnreachable(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException
                    || cause instanceof InterruptedIOException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return hasReserve() ? primary + " (reserve: " + reserve + ")" : primary;
    }
}
