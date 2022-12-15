package edu.wisc.cs.sdn.simpledns;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

class EC2RegionInfo {
    private long ip;
    private long mask;
    private String location;

    EC2RegionInfo(String entry) {
        String[] tokens = entry.split("[,/]");
        try {
            this.ip = parseIp(tokens[0]);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        this.mask = parsePrefix(Integer.parseInt(tokens[1]));
        this.location = tokens[2];
    }

    public long getIp() {
        return this.ip;
    }

    public long getMask() {
        return this.mask;
    }

    public String getLoc() {
        return this.location;
    }

    private static long parseIp(String ipString) throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(ipString);
        long longRepresentation = ByteBuffer.wrap(addr.getAddress()).getInt();

        return longRepresentation;
        // String[] ipStringParts = ipString.split("\\.");
        // int[] ipValParts = new int[4];

        // for (int i = 0; i < ipValParts.length; i++) {
        //     ipValParts[i] = Integer.parseInt(ipStringParts[i]);
        // }

        // return ipValParts[0] * (2^24) + ipValParts[1] * (2^16) + ipValParts[2] * (2^8) + ipValParts[3];
    }

    // reference: https://stackoverflow.com/questions/2942299/converting-cidr-address-to-subnet-mask-and-network-address
    private static long parsePrefix(int prefix) {
        return prefix != 0 ? 0xffffffff << (32 - prefix) : 0;
    }
}
