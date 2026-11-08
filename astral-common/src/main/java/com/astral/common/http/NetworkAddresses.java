package com.astral.common.http;

import java.net.InetAddress;

/** Address classification shared by outbound HTTP policies. */
public final class NetworkAddresses {
    private NetworkAddresses() {}

    public static boolean isPublic(InetAddress address) {
        if (address == null
                || address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        int first = raw[0] & 0xff;
        int second = raw[1] & 0xff;
        if (raw.length == 4) {
            return first != 0
                    && first != 10
                    && first != 127
                    && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254)
                    && !(first == 172 && second >= 16 && second <= 31)
                    && !(first == 192
                            && (second == 168 || (second == 0 && ((raw[2] & 0xff) == 0 || (raw[2] & 0xff) == 2))))
                    && !(first == 198 && (second == 18 || second == 19 || (second == 51 && (raw[2] & 0xff) == 100)))
                    && !(first == 203 && second == 0 && (raw[2] & 0xff) == 113);
        }
        // Permit only global unicast 2000::/3, excluding documentation and transition tunnels.
        return raw.length == 16
                && (first & 0xe0) == 0x20
                && !(first == 0x20 && second == 0x02)
                && !(first == 0x20 && second == 0x01 && (raw[2] & 0xff) == 0 && (raw[3] & 0xff) == 0)
                && !(first == 0x20 && second == 0x01 && (raw[2] & 0xff) == 0x0d && (raw[3] & 0xff) == 0xb8);
    }

    /** Cloud metadata endpoints remain forbidden even for explicitly allowed private networks. */
    public static boolean isMetadata(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xff;
            int second = raw[1] & 0xff;
            int third = raw[2] & 0xff;
            int fourth = raw[3] & 0xff;
            return (first == 169 && second == 254 && ((third == 169 && fourth == 254) || (third == 170 && fourth == 2)))
                    || (first == 100 && second == 100 && third == 100 && fourth == 200);
        }
        return java.util.Arrays.equals(
                raw, new byte[] {(byte) 0xfd, 0, 0x0e, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0x54});
    }
}
