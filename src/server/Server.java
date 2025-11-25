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
	private static HashMap<String, java.util.List<String>> backupTable; // {"owner:filename" -> ["peer:chunkID", ...]}
	private static int serverRqCounter = 0;
	
	private static int nextServerRq() {
		serverRqCounter = (serverRqCounter % 99) + 1;
		return serverRqCounter;
	}
	
    public static void main(String[] args) throws IOException {
    	peers = new HashMap<>();
    	backupTable = new HashMap<>();
    	
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
						continue;
					}

					if ("BACKUP_REQ".equalsIgnoreCase(cmd)){
						//validate msg length
						if (parts.length < 5) {
							int rq = parts.length > 1 ? safeInt(parts[1]) : 0;
							sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: Malformed", rq));
							receive = new byte[65535];
							continue;
						}
						int rq = safeInt(parts[1]);
						String fileName = parts[2];
						long fileSize = safeLong(parts[3]);
						long checksum = safeLong(parts[4]);
						// identify the owner
						String owner = null;
						for (PeerData pd : peers.values()) {
							if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
								owner = pd.getName();
								break;
							}
						}
						if (owner == null) {
							sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: NotRegistered", rq));
							receive = new byte[65535];
							continue;
						}
						//Select a storage peer not the owner tho
						PeerData chosen = null;
						for (PeerData pd : peers.values()) {
							if (!pd.getName().equals(owner) && !"OWNER".equalsIgnoreCase(pd.getRole())) {
								chosen = pd; break;
							}
						}
						if (chosen == null) {
							sendSimple(ds, dpReceive, String.format("BACKUP-DENIED %02d REASON: NoStoragePeer", rq));
							receive = new byte[65535];
							continue;
						}
					int chunkSize = 4096; //fixed size for now
					int chunkId = 0; 
					
				//Peer list
				String peerList = "[" + chosen.getName() + "]";
				String plan = String.format("BACKUP_PLAN %02d %s %s %d", rq, fileName, peerList, chunkSize);
				System.out.printf("BACKUP_REQ(rq=%02d file=%s size=%d checksum=%d owner=%s) -> %s%n", rq, fileName, fileSize, checksum, owner, plan);
				sendSimple(ds, dpReceive, plan);
				
				//Send STORE_REQ notification to selected storage peer
				int serverRq = nextServerRq();
				String storeReq = String.format("STORE_REQ %02d %s %d %s", serverRq, fileName, chunkId, owner);
				System.out.printf("Sending STORE_REQ to %s: %s%n", chosen.getName(), storeReq);
				byte[] d = storeReq.getBytes();
				ds.send(new DatagramPacket(d, d.length, chosen.getIp(), chosen.getUdpPort()));
				
				// Initialize backup table entry
				String backupKey = owner + ":" + fileName;
				backupTable.put(backupKey, new java.util.ArrayList<>());
				
				receive = new byte[65535];
				continue;
					}

				//CHUNK_OK / CHUNK_ERROR: Forward to owner peer
				if ("CHUNK_OK".equalsIgnoreCase(cmd) || "CHUNK_ERROR".equalsIgnoreCase(cmd)) {
					System.out.println(cmd + " received: " + msg);
					//Extract file name from message to find owner
					if (parts.length >= 3) {
						String fileNameAck = parts[2];
						//Find owner peer for this file
						PeerData ownerPeer = null;
						for (String key : backupTable.keySet()) {
							if (key.endsWith(":" + fileNameAck)) {
								String ownerName = key.substring(0, key.indexOf(":"));
								ownerPeer = peers.get(ownerName);
								break;
							}
						}
						//Forward message to owner
						if (ownerPeer != null) {
							byte[] fwdData = msg.getBytes();
							ds.send(new DatagramPacket(fwdData, fwdData.length, ownerPeer.getIp(), ownerPeer.getUdpPort()));
							System.out.printf("Forwarded %s to owner %s%n", cmd, ownerPeer.getName());
						}
					}
					receive = new byte[65535];
					continue;
				}
				
				//STORE_ACK RQ# File_Name Chunk_ID
				if ("STORE_ACK".equalsIgnoreCase(cmd)) {
					if (parts.length >= 4) {
						String fileNameAck = parts[2];
						int chunkIdAck = safeInt(parts[3]);
						//Identify which peer sent this
						String storagePeerName = null;
						for (PeerData pd : peers.values()) {
							if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
								storagePeerName = pd.getName();
								break;
							}
						}
						System.out.printf("STORE_ACK received: file=%s chunk=%d from peer=%s%n", fileNameAck, chunkIdAck, storagePeerName);
						//Update backup table: find matching owner:filename entry
						for (String key : backupTable.keySet()) {
							if (key.endsWith(":" + fileNameAck) && storagePeerName != null) {
								backupTable.get(key).add(storagePeerName + ":" + chunkIdAck);
								break;
							}
						}
					}
					receive = new byte[65535];
					continue;
				}
				
				//BACKUP_DONE RQ# File_Name
				if ("BACKUP_DONE".equalsIgnoreCase(cmd)) {
					if (parts.length >= 3) {
						String fileNameDone = parts[2];
						String ownerName = null;
						for (PeerData pd : peers.values()) {
							if (pd.getIp().equals(dpReceive.getAddress()) && pd.getUdpPort() == dpReceive.getPort()) {
								ownerName = pd.getName();
								break;
							}
						}
						System.out.printf("BACKUP_DONE received: file=%s from owner=%s%n", fileNameDone, ownerName);
					
					}
					receive = new byte[65535];
					continue;
				}		
				if ("RESTORE_REQ".equalsIgnoreCase(cmd)) {
    				if (parts.length < 3) {
        					int rq = parts.length > 1 ? safeInt(parts[1]) : 0;
        					sendSimple(ds, dpReceive,
                			String.format("RESTORE_FAIL %02d %s Malformed", rq,
                        	(parts.length > 2 ? parts[2] : "UNKNOWN")));
        					receive = new byte[65535];
        					continue;}	

    			int rq = safeInt(parts[1]);
    			String fileName = parts[2];

    			// find which peer is asking (owner)
    			String owner = null;
    			for (PeerData pd : peers.values()) {
        		if (pd.getIp().equals(dpReceive.getAddress())
                && pd.getUdpPort() == dpReceive.getPort()) {
            	owner = pd.getName();
            	break;}
			}

    	if (owner == null) {
        		sendSimple(ds, dpReceive,
                String.format("RESTORE_FAIL %02d %s NotRegistered", rq, fileName));
        		receive = new byte[65535];
        		continue;
    			}

    			String key = owner + ":" + fileName;
    			java.util.List<String> entries = backupTable.get(key);
    			if (entries == null || entries.isEmpty()) {
        		sendSimple(ds, dpReceive,
                String.format("RESTORE_FAIL %02d %s NoBackupFound", rq, fileName));
        		receive = new byte[65535];
        		continue;
    			}

    			// entries are like "peerName:chunkId"
    			StringBuilder peerList = new StringBuilder("[");
    			for (int i = 0; i < entries.size(); i++) {
        		String entry = entries.get(i);
        		String peerName = entry.split(":", 2)[0];
				if (i > 0) peerList.append(',');
				peerList.append(peerName);
				}
				peerList.append("]");

				String plan = String.format("RESTORE_PLAN %02d %s %s", rq, fileName, peerList);
				System.out.println("Sending: " + plan);
				sendSimple(ds, dpReceive, plan);
				receive = new byte[65535];
				continue;
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
	private static long safeLong(String s) {
		try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
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
