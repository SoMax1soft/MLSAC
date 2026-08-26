/*
 * Copyright (C) 2026 MLSAC Team
 * MLSAC is a GPLv3 licensed fork of a Minecraft anti-cheat system.
 */

package wtf.mlsac.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EndpointRouterTest {

    private static final String PRIMARY = "https://api.mlsac.net";
    private static final String RESERVE = "https://ruapi.mlsac.net";

    @Test
    @DisplayName("Without a reserve there is only ever one candidate")
    void testNoReserve() {
        EndpointRouter router = new EndpointRouter(PRIMARY, "");

        assertFalse(router.hasReserve());
        assertEquals(List.of(PRIMARY), router.candidates());
        assertEquals(PRIMARY, router.current());
        assertFalse(router.isOnReserve());
    }

    @Test
    @DisplayName("The primary is always tried first")
    void testPrimaryFirst() {
        EndpointRouter router = new EndpointRouter(PRIMARY, RESERVE);

        assertEquals(List.of(PRIMARY, RESERVE), router.candidates());

        // Even after failing over, the next connection starts from the configured endpoint, so an
        // outage does not pin the server to the reserve for good.
        router.select(RESERVE);
        assertTrue(router.isOnReserve());
        assertEquals(List.of(PRIMARY, RESERVE), router.candidates());
    }

    @Test
    @DisplayName("Requests follow whichever host the connection succeeded on")
    void testSelectionSticksBetweenConnections() {
        EndpointRouter router = new EndpointRouter(PRIMARY, RESERVE);

        assertEquals(PRIMARY, router.current());
        router.select(RESERVE);
        assertEquals(RESERVE, router.current());
        router.select(PRIMARY);
        assertEquals(PRIMARY, router.current());
        assertFalse(router.isOnReserve());
    }

    @Test
    @DisplayName("Trailing slashes never produce a double slash in a URL")
    void testNormalisation() {
        EndpointRouter router = new EndpointRouter("https://api.mlsac.net///", "https://ruapi.mlsac.net/");

        assertEquals(PRIMARY, router.current());
        assertEquals(RESERVE, router.reserve());
        assertEquals(PRIMARY + "/api/v1/init", router.current() + "/api/v1/init");
    }

    @Test
    @DisplayName("A reserve equal to the primary is not a reserve")
    void testSameAddressIsNotAReserve() {
        EndpointRouter router = new EndpointRouter(PRIMARY, PRIMARY + "/");

        assertFalse(router.hasReserve(), "Failing over to the same host would just repeat the failure");
        assertEquals(1, router.candidates().size());
    }

    @Test
    @DisplayName("Null addresses do not blow up")
    void testNullSafety() {
        EndpointRouter router = new EndpointRouter(PRIMARY, null);

        assertFalse(router.hasReserve());
        assertEquals(PRIMARY, router.current());
    }

    @Test
    @DisplayName("Only an unreachable host triggers failover")
    void testUnreachableClassification() {
        assertTrue(EndpointRouter.isUnreachable(new SocketTimeoutException("connect timed out")));
        assertTrue(EndpointRouter.isUnreachable(new ConnectException("Connection refused")));
        assertTrue(EndpointRouter.isUnreachable(new UnknownHostException("api.mlsac.net")));
        // Wrapped by an outer exception on the way up the stack.
        assertTrue(EndpointRouter.isUnreachable(
                new RuntimeException("wrapped", new SocketTimeoutException("connect timed out"))));

        // An answer, even a bad one, is not an outage: another host would answer the same.
        assertFalse(EndpointRouter.isUnreachable(new IOException("unexpected end of stream")));
        assertFalse(EndpointRouter.isUnreachable(new IllegalStateException("bad json")));
        assertFalse(EndpointRouter.isUnreachable(null));
    }

    @Test
    @DisplayName("A self-referencing cause does not loop forever")
    void testSelfReferencingCause() {
        // Some libraries hand back an exception whose cause is itself.
        RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertFalse(EndpointRouter.isUnreachable(loop));
    }
}
