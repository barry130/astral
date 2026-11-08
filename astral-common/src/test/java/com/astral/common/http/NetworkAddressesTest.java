package com.astral.common.http;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class NetworkAddressesTest {
    @Test
    void distinguishesReservedSubnetsFromAdjacentPublicAddresses() throws Exception {
        for (String ip : new String[] {"192.2.1.1", "192.0.3.1", "93.184.216.34"}) {
            assertTrue(NetworkAddresses.isPublic(InetAddress.getByName(ip)), ip);
        }
        for (String ip : new String[] {"192.0.0.1", "192.0.2.1", "192.168.1.1", "198.51.100.1", "203.0.113.1"}) {
            assertFalse(NetworkAddresses.isPublic(InetAddress.getByName(ip)), ip);
        }
    }
}
