package com.lazorenkoo.relayforge.endpoint;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class PublicAddressPolicy {

    private static final List<String> BLOCKED = List.of(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
            "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
            "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15", "198.51.100.0/24",
            "203.0.113.0/24", "224.0.0.0/3", "2001::/23", "2001:db8::/32",
            "2002::/16", "3fff::/20");

    public static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (bytes.length == 16 && (bytes[0] & 0xe0) != 0x20) {
            return false;
        }
        for (String range : BLOCKED) {
            if (matches(bytes, range)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(byte[] address, String range) {
        String[] parts = range.split("/");
        byte[] network;
        try {
            network = InetAddress.getByName(parts[0]).getAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Invalid address range", exception);
        }
        if (address.length != network.length) {
            return false;
        }
        int prefix = Integer.parseInt(parts[1]);
        for (int bit = 0; bit < prefix; bit++) {
            int mask = 1 << (7 - bit % 8);
            if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                return false;
            }
        }
        return true;
    }
}
