package peer;

import java.net.InetAddress;

public class PeerData {
    private String name;
    private InetAddress ip;
    private int udpPort;
    private int tcpPort;
    private String role;
    private String storage;

    public PeerData(String name, String role, InetAddress ip, int udpPort, int tcpPort, String storage) {
        this.name = name;
        this.role = role;
        this.ip = ip;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.storage = storage;
    }

    // getters
    public String getName() { return name; }
    public InetAddress getIp() { return ip; }
    public int getUdpPort() { return udpPort; }
    public int getTcpPort() { return tcpPort; }
    public String getRole() { return role; }
    public String getStorage() { return storage; }
}