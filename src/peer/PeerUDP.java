package src.peer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Scanner;

public class PeerUDP{
    public static void main(String[] args) throws IOException{
        Scanner sc = new Scanner(System.in);
        DatagramSocket ds = new DatagramSocket();
        InetAddress ip = InetAddress.getLocalHost();
        byte buf[] = null;
        int serverPort = 1234;

        //Send registration message to server
        System.out.println("Write your name:");
        String name = sc.nextLine().trim();
        sendRegistration(ds, ip, serverPort, name, "BOTH", 5678, 1024);
        
        //Receive server response
        byte[] receiveBuffer = new byte[65535];
        DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        ds.receive(receivePacket);
        String response = new String(receivePacket.getData(), 0, receivePacket.getLength()).trim();
        System.out.println("Server response: " + response);
        
        //Responses you can get from the server
        if (response.startsWith("REGISTER-DENIED")){
            System.out.println("Registration denied by server. Exiting.");
            ds.close();
            sc.close();
            return;
        } else if (response.startsWith("REGISTERED") || response.startsWith("REGISTER-ACCEPTED")) {
            System.out.println("Registration accepted by server.");
        }

        System.out.println("Write messages to send to server (type 'bye' to exit):");
        System.out.println("Type 'de' to deregister.");
        System.out.println("Type 'list' to see registered peers.");
        while(true){
            String inp = sc.nextLine();

            if (inp.equalsIgnoreCase("de")){
                // Send de-registration and exit
                sendDeregistration(ds, ip, serverPort, name);
                break;
            }

            if (inp.equalsIgnoreCase("list")) {
                byte[] data = "LIST".getBytes();
                DatagramPacket pkt = new DatagramPacket(data, data.length, ip, serverPort);
                ds.send(pkt);
                byte[] bufList = new byte[4096];
                DatagramPacket resp = new DatagramPacket(bufList, bufList.length);
                ds.receive(resp);
                String listResp = new String(resp.getData(), 0, resp.getLength()).trim();
                System.out.println("Server: " + listResp);
                continue;
            }

            if (inp.equalsIgnoreCase("bye")) {
                break;
            }

            buf = inp.getBytes();
            DatagramPacket DpSend = new DatagramPacket(buf, buf.length, ip, serverPort);
            ds.send(DpSend);
        }
        sc.close();
        ds.close();
    }

	// RQ# generator: 01..99 then wraps
    private static int rqCounter = 0;

    private static int nextRq() {
        rqCounter = (rqCounter % 99) + 1; // 1..99
        return rqCounter;
    }

	public static String formatRegistration(String name, String role, InetAddress ipAddress, int udpPort, int tcpPort,
			long storageCapacity) {

        return String.format("REGISTER %d %s %s %s %d %d %dMB", nextRq(), name, role, ipAddress.getHostAddress(),
                udpPort, tcpPort, storageCapacity);

	}

	public static String formatDeregistration(String name) {
        return String.format("DE-REGISTER %d %s", nextRq(), name);
	}

    public static void sendRegistration(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                            String name, String role, int tcpPort, long storageCapacity) throws IOException {
        
        int udpPort = socket.getLocalPort();
        String msg = formatRegistration(name, role, InetAddress.getLocalHost(), udpPort, tcpPort, storageCapacity);
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
        System.out.println("Sent: " + msg );
    }

    public static void sendDeregistration(DatagramSocket socket, InetAddress serverAddr, int serverPort,
                                          String name) throws IOException {
        // Format: DE-REGISTER RQ# Name
        String msg = formatDeregistration(name);
        byte[] data = msg.getBytes();
        DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, serverPort);
        socket.send(packet);
        System.out.println("Sent: " + msg );
        // Wait for server response
        byte[] buf = new byte[1024];
        DatagramPacket resp = new DatagramPacket(buf, buf.length);
        socket.receive(resp);
        String r = new String(resp.getData(), 0, resp.getLength()).trim();
        System.out.println("Server response: " + r);
    }
}

