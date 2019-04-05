package channels;

import message.Message;
import utils.Globals;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import user_interface.UI;

public class TCPSender {

    private int port;
    private ConcurrentHashMap<InetAddress, Socket> sockets;
    private ExecutorService threadPool = Executors.newFixedThreadPool(Globals.MAX_TCP_SOCKET_THREADS);


    /**
      * Instantiates a new TCPSender
      *
      * @param port controller port
      */
    public TCPSender(int port) {
        this.port = port;
        sockets = new ConcurrentHashMap<>();
    }

    /**
      * Send a message
      *
      * @param message message to be sent
      * @param address destination address
      */
    public synchronized void sendMessage(Message message, InetAddress address) {
        threadPool.submit(() -> {
            Socket socket = null;

            if(sockets.containsKey(address)){
                socket = sockets.get(address);
                if(socket.isClosed()){
                    sockets.remove(address);
                    socket = null;
                }
            }

            if(socket == null){
                try {
                    socket = new Socket(address, port);
                    sockets.put(address, socket);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            ObjectOutputStream stream;
            try {
                stream = new ObjectOutputStream(socket.getOutputStream());
                stream.writeObject(message);
                UI.print("Sending CHUNK message " + message.getChunkNo() + " via TCP");
            } catch (IOException e) {
                UI.print("Closing TCP socket...");
                try {
                    socket.close();
                } catch (IOException e1) {
                    e.printStackTrace();
                }
            }
        });
    }
}
