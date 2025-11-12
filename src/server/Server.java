package src.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

public class Server {
    public static void main(String[] args) {
        try (DatagramSocket ds = new DatagramSocket(1234)) {
            byte[] receive = new byte[65535];
            System.out.println("UDP server listening on port 1234...");

            while (true) {
                DatagramPacket dpReceive = new DatagramPacket(receive, receive.length);
                ds.receive(dpReceive);

                String msg = new String(dpReceive.getData(), 0, dpReceive.getLength()).trim();
                System.out.println("Client: " + msg);

                if ("bye".equalsIgnoreCase(msg)) {
                    System.out.println("Client sent bye.....EXITING");
                    break;
                }
            }

        } catch (SocketException e) {
            System.err.println("Socket error: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
        }
    }
}
