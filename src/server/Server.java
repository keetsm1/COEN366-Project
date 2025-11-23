package src.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;
import src.peer.PeerData;

public class Server {
	private static HashMap<String, PeerData> peers;
    public static void main(String[] args) throws IOException {
    	peers = new HashMap<>();
    	
        try (DatagramSocket ds = new DatagramSocket(1234)) {
            byte[] receive = new byte[65535];
            System.out.println("UDP server listening on port 1234...");

            while (true) {
                DatagramPacket dpReceive = new DatagramPacket(receive, receive.length);
                ds.receive(dpReceive);
                String msg = new String(dpReceive.getData(), 0, dpReceive.getLength()).trim();
				System.out.printf("Server received: '%s' from %s:%d%n", msg, dpReceive.getAddress().getHostAddress(), dpReceive.getPort());
                if ("bye".equalsIgnoreCase(msg)) {
                    System.out.println("Client sent bye.....EXITING");
                    break;
                }
                else {
                	//Read the message and add a new peer to the hashmap so that the server can *track* peers.
                    String[] parts = msg.split("\\s+");

					if (parts.length == 0) continue;
					String cmd = parts[0].toUpperCase();

					//query to see what's in the registry
					if ("LIST".equals(cmd)) {
						StringBuilder sb = new StringBuilder("PEERS ").append(peers.size());
						for (PeerData pd : peers.values()) {
							sb.append(' ') // name ip udp tcp
							  .append(pd.getName()).append(' ')
							  .append(pd.getIp().getHostAddress()).append(' ')
							  .append(pd.getUdpPort()).append(' ')
							  .append(pd.getTcpPort());
						}
						sendSimple(ds, dpReceive, sb.toString());
						receive = new byte[65535];
						continue;
					}

					if("DE-REGISTER".equalsIgnoreCase(cmd)) {
						if (parts.length< 3){
							sendSimple(ds,dpReceive, "DE-REGISTER-DENIED 00 REASON: Malformed");
						}else{
							int rq= safeInt(parts[1]);
							String name= parts[2];
							PeerData removed = peers.remove(name);
							if (removed == null){
								sendSimple(ds,dpReceive, "DE-REGISTER-DENIED "+rq+" REASON: NotRegistered");
							} else {
								System.out.printf("Peer '%s' deregistered. (remaining=%d)%n", name, peers.size());
								sendSimple(ds,dpReceive, "DE-REGISTERED "+rq);
							}
						}
						receive = new byte[65535];
						continue; // avoid falling through to REGISTER parsing
					}
                	int rq = Integer.parseInt(parts[1]);
                	String name = parts[2];
                	String role = parts[3];
                	InetAddress ip = InetAddress.getByName(parts[4]);
                	int udpPort = Integer.parseInt(parts[5]);
                	int tcpPort = Integer.parseInt(parts[6]);
                	String storage = parts[7]; 
                	//Reset the receive buffer in case another registration comes
					receive = new byte[65535];
					//If hashmap does not currently have this peer stored, add it and accept the registration
					if (!peers.containsKey(name)) {
						PeerData newPeer = new PeerData(name, role, ip, udpPort, tcpPort, storage);
						peers.put(name, newPeer);
						System.out.printf("Accepting registration: name=%s role=%s udpPort=%d tcpPort=%d storage=%s (total peers=%d)%n", name, role, udpPort, tcpPort, storage, peers.size());
						acceptRegistration(ds, dpReceive.getAddress(), dpReceive.getPort(), msg, 5678, 1024, rq);
						System.out.println("Current peers: " + peers.keySet());
					}
					//Hashmap DOES already have this peer stored, don't add it to map and deny registration
					else {
						System.out.printf("Denying registration for existing peer name=%s (total peers=%d)%n", name, peers.size());
						denyRegistration(ds, dpReceive.getAddress(), dpReceive.getPort(), msg, 5678, 1024, rq,
								"REASON: Peer registered in server");
					}

				}
			}
            ds.close();
        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
	}

    private static int safeInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
    private static void sendSimple(DatagramSocket ds, DatagramPacket req, String text) throws IOException {
        byte[] d = text.getBytes();
        ds.send(new DatagramPacket(d, d.length, req.getAddress(), req.getPort()));
    }

	public static void acceptRegistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
			int tcpPort, int storageCapacity, int rq) throws IOException {
		//int udpPort = socket.getLocalPort();
		String response = formatAcceptResponse(rq);
		byte[] sendData = response.getBytes();
		DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
		socket.send(dpSendResponse);
	}

	public static void denyRegistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
			int tcpPort, int storageCapacity, int rq, String reason) throws IOException {
		String response = formatDenyResponse(rq, reason);
		byte[] sendData = response.getBytes();
		DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
		socket.send(dpSendResponse);
	}

	public static void acceptDeregistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
			int tcpPort, int storageCapacity, int rq, String reason) throws IOException {
		String response = "Deregistration accepted.";
		byte[] sendData = response.getBytes();
		DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
		socket.send(dpSendResponse);
	}

    public static String formatAcceptResponse(int rq) {
		//Response from server that project expects to be sent
		return String.format("REGISTERED %d", rq);
	}
	public static String formatDenyResponse(int rq, String reason) {
		//Response from server that project expects to be sent
		return String.format("REGISTER-DENIED %d %s", rq, reason);

	}
}
