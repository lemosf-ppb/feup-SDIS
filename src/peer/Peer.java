package peer;

import message.Message;
import channels.*;
import protocols.*;
import interfaces.RMIProtocol;
import user_interface.UI;

import java.io.*;
import java.net.InetAddress;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static utils.Utils.MAX_THREADS;
import static utils.Utils.SAVING_INTERVAL;
import static utils.Utils.parseRMI;

public class Peer implements RMIProtocol {

    private Channel MCChannel;
    private Channel MDBChannel;
    private Channel MDRChannel;
    private MessageHandler messageHandler;
    private TCPSender tcpSender;
    private int serverId;
    private String version;
    private PeerState controller;
    private ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(MAX_THREADS);
    private int MDRPort;
    private boolean isEnhanced = false;

    /**
     * Constructor. Initiates peer from CLI args
     *
     * @param args initialization arguments
     */
    private Peer(final String args[]) {
        UI.printBoot("------------------- Booting Peer " + args[1] + " -------------------");
        UI.nl();
        UI.printBoot("Protocols version " + args[0]);
        version = args[0];
        serverId = Integer.parseInt(args[1]);

        String[] serviceAccessPoint = parseRMI(args[2], false);
        if (serviceAccessPoint == null) {
            return;
        }

        initRMI(args[1]);

        if (!loadPeerController()) {
            controller = new PeerState(version, serverId);
        }

        UI.printBoot("------------- Booting Multicast Channels -------------");
        UI.nl();

        this.messageHandler = new MessageHandler(this);

        threadPool.scheduleAtFixedRate(this::saveController, 0, SAVING_INTERVAL, TimeUnit.SECONDS);

        MDRPort = Integer.parseInt(args[8]);
        initChannels(args[3], Integer.parseInt(args[4]), args[5], Integer.parseInt(args[6]), args[7], MDRPort);

        UI.nl();
        UI.printBoot("-------------------- Peer " + args[1] + " Ready --------------------");

        if(!version.equals("1.0")){
            isEnhanced = true;
            Message messageCONTROL = new Message(version,serverId, null, Message.MessageType.CONTROL);
            MCChannel.sendMessage(messageCONTROL);
            UI.printOK("Sending CONTROL message");
            UI.printInfo("------------------------------------------------------");
        }
    }

    public static void main(final String args[]) {
        if (args.length != 9) {
            UI.printError("Wrong input!");
            UI.printWarning("Please use: java peer.Peer" + " <protocol_version> <peer_id> <service_access_point>" +
                    " <MCReceiver_address> <MCReceiver_port> <MDBReceiver_address>" + " <MDBReceiver_port> <MDRReceiver_address> <MDRReceiver_port>");
            return;
        }
        new Peer(args);
    }

    /**
     * Initiates remote service.
     *
     * @param accessPoint the RMI access point
     */
    private void initRMI(String accessPoint) {
        try {
            RMIProtocol remoteService = (RMIProtocol) UnicastRemoteObject.exportObject(this, 0);

            // Get own registry, to rebind to correct remoteService
            Registry registry = LocateRegistry.getRegistry();
            registry.rebind(accessPoint, remoteService);

            UI.printBoot("Connection to RMI server established");
            UI.nl();
        } catch (Exception e) {
            UI.printError("Failed to connect to RMI server. \nReason: " + e.toString());
        }
    }

    /**
     * Loads the peer controller from non-volatile memory, if file is present, or starts a new one.
     *
     * @return true if controller successfully loaded from .ser file, false otherwise
     */
    private boolean loadPeerController() {
        try {
            FileInputStream fileInputStream = new FileInputStream("PeerState" + serverId + ".ser");
            ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
            controller = (PeerState) objectInputStream.readObject();
            controller.setVersion(version);
            objectInputStream.close();
            fileInputStream.close();
            return true;
        } catch (FileNotFoundException e) {
            UI.printWarning("No pre-existing PeerState found, starting new one");
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * Initiates fields not retrievable from non-volatile memory
     *
     * @param MCAddress  control channel address
     * @param MCPort     control channel port
     * @param MDBAddress backup channel address
     * @param MDBPort    backup channel port
     * @param MDRAddress restore channel address
     * @param MDRPort    restore channel port
     */
    public void initChannels(String MCAddress, int MCPort, String MDBAddress, int MDBPort, String MDRAddress, int MDRPort) {
        try {
            MCChannel = new Channel("MC", MCAddress, MCPort, messageHandler);
            MDBChannel = new Channel("MDB", MDBAddress, MDBPort, messageHandler);
            MDRChannel = new Channel("MDR", MDRAddress, MDRPort, messageHandler);

            new Thread(MCChannel).start();
            new Thread(MDBChannel).start();
            new Thread(MDRChannel).start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (isEnhanced) {
            tcpSender = new TCPSender(MDRPort);
        }
    }

    /**
     * Saves the controller state to non-volatile memory
     */
    private void saveController() {
        try {
            FileOutputStream controllerFile = new FileOutputStream("PeerState" + serverId + ".ser");
            ObjectOutputStream controllerObject = new ObjectOutputStream(controllerFile);
            controllerObject.writeObject(this.controller);
            controllerObject.close();
            controllerFile.close();
        } catch (FileNotFoundException e) {
            UI.printError("Failed to create PeerState"+serverId+".ser");
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public String getVersion() {
        return version;
    }


    public int getServerId() {
        return serverId;
    }


    public PeerState getController() {
        return controller;
    }

    public boolean isEnhanced() {
        return isEnhanced;
    }

    /**
     * Submits an initiator instance of the backup protocols to the thread pool
     *
     * @param filePath          filename of file to be backed up
     * @param replicationDeg desired replication degree
     */
    @Override
    public void backup(String filePath, int replicationDeg) {
        threadPool.submit(new BackupInitiator(controller, filePath, replicationDeg, MDBChannel));
    }

    /**
     * Submits an initiator instance of the restore protocols to the thread pool
     *
     * @param filePath filename of file to be restored
     */
    @Override
    public void restore(String filePath) {
        if (!version.equals("1.0")) {
            UI.printInfo("Enhanced restore protocols initiated  (v"+version+")");
            threadPool.submit(new TCPReceiver(MDRPort, messageHandler));
        }

        threadPool.submit(new RestoreInitiator(controller, filePath, MCChannel));
    }

    /**
     * Submits an initiator instance of the delete protocols to the thread pool
     *
     * @param filePath filename of file to be deleted
     */
    @Override
    public void delete(String filePath) {
        threadPool.submit(new DeleteInitiator(this, filePath, MCChannel));
    }

    /**
     * Submits an initiator instance of the reclaim protocols to the thread pool
     *
     * @param space new amount of reserved space for peer, in kB
     */
    @Override
    public void reclaim(long space) {
        threadPool.submit(new ReclaimInitiator(controller, space, MCChannel));
    }

    /**
     * Retrieves the peer's local state by printing out its controller
     */
    @Override
    public void state() {
        UI.printInfo("-------------------- Peer " + serverId + " State --------------------");
        UI.print(controller.getPeerState());
        UI.printInfo("------------------------------------------------------");
    }

    public Channel getMCChannel() {
        return MCChannel;
    }

    public Channel getMDBChannel() {
        return MDBChannel;
    }

    public void sendMessage(Message message, InetAddress sourceAddress) {
        if (isEnhanced && !message.getVersion().equals("1.0")) {
            //send chunk via tcp and send header to MDR
            tcpSender.sendMessage(message, sourceAddress);
            MDRChannel.sendMessage(message, false);
        } else
            MDRChannel.sendMessage(message);
    }
}
