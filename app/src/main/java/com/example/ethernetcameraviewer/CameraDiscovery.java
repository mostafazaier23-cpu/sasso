package com.example.ethernetcameraviewer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * WS-Discovery (ONVIF) Probe on the local LAN. Discovery yields device IPs,
 * NOT RTSP paths. Devices using proprietary P2P video may not answer.
 */
final class CameraDiscovery {
    private CameraDiscovery() {}

    static List<String> discover() throws IOException {
        String probe = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\""
                + " xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\""
                + " xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\">"
                + "<e:Header><w:MessageID>uuid:" + UUID.randomUUID() + "</w:MessageID>"
                + "<w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>"
                + "<w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>"
                + "</e:Header><e:Body><d:Probe/></e:Body></e:Envelope>";

        byte[] request = probe.getBytes(StandardCharsets.UTF_8);
        Set<String> ips = new LinkedHashSet<>();
        long until = System.currentTimeMillis() + 3600;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(500);
            socket.send(new DatagramPacket(
                    request, request.length,
                    InetAddress.getByName("239.255.255.250"), 3702));

            while (System.currentTimeMillis() < until && ips.size() < 24) {
                byte[] buffer = new byte[16384];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(response);
                    String xml = new String(response.getData(), response.getOffset(),
                            response.getLength(), StandardCharsets.UTF_8);
                    if (xml.contains("ProbeMatches") || xml.contains("ProbeMatch")) {
                        ips.add(response.getAddress().getHostAddress());
                    }
                } catch (SocketTimeoutException timeout) {
                    // Keep listening until the full discovery interval has passed.
                }
            }
        }
        return new ArrayList<>(ips);
    }
}
