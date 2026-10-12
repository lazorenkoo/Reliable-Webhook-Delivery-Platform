package com.lazorenkoo.relayforge.endpoint;

import java.net.InetAddress;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PublicAddressPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0.0.0.0", "10.0.0.1", "100.64.0.0", "100.127.255.255", "127.0.0.1",
            "169.254.169.254", "172.16.0.0", "172.31.255.255", "192.0.0.1",
            "192.0.2.1", "192.88.99.1", "192.168.1.1", "198.18.0.0", "198.19.255.255",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fe80::1", "ff02::1", "64:ff9b::7f00:1",
            "2001::1", "2001:1ff:ffff::1", "2001:db8::1", "2002:7f00:1::1", "3fff::1",
            "::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254"
    })
    void rejectsPrivateAndSpecialAddresses(String address) throws Exception {
        assertFalse(PublicAddressPolicy.isPublic(InetAddress.getByName(address)), address);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.1.1.1", "8.8.8.8", "100.63.255.255", "100.128.0.0",
            "172.15.255.255", "172.32.0.0", "198.17.255.255", "198.20.0.0",
            "2001:200::1", "2001:4860:4860::8888", "2606:4700:4700::1111", "::ffff:8.8.8.8"
    })
    void acceptsPublicAddressesOutsideBlockedRanges(String address) throws Exception {
        assertTrue(PublicAddressPolicy.isPublic(InetAddress.getByName(address)), address);
    }
}
