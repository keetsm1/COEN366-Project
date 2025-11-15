package src.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

public class Server {
    public static void main(String[] args) throws IOException {
    	int serverPort = 1234;
        try (DatagramSocket ds = new DatagramSocket(1234)) {
            byte[] receive = new byte[65535];
            System.out.println("UDP server listening on port 1234...");

            while (true) {
                DatagramPacket dpReceive = new DatagramPacket(receive, receive.length);
                ds.receive(dpReceive);
                String msg = new String(dpReceive.getData(), 0, dpReceive.getLength()).trim();
                if ("bye".equalsIgnoreCase(msg)) {
                    System.out.println("Client sent bye.....EXITING");
                    break;
                }
                else {
                	//Reset the receive buffer in case another registration comes
                	receive = new byte[65535];
                	acceptRegistration(ds, dpReceive.getAddress(), dpReceive.getPort(), msg, 5678, 1024);
                }
            }
            ds.close();
        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }
    public static void acceptRegistration(DatagramSocket socket, InetAddress clientAddr, int serverPort, String name,
    int tcpPort, int storageCapacity) throws IOException {
    	int udpPort = socket.getLocalPort();
    	String response = "Registration Confirmed for:  " + name;
    	byte[] sendData = response.getBytes();
    	DatagramPacket dpSendResponse = new DatagramPacket(sendData, sendData.length, clientAddr, serverPort);
    	socket.send(dpSendResponse);
    }
}
