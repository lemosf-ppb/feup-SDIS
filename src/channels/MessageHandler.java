package channels;

import peer.PeerState;
import storage.ChunkInfo;
import message.Message;
import storage.FileChunk;
import peer.Peer;
import protocols.BackupChunk;
import storage.FileInfo;
import utils.Globals;
import utils.Utils;
import user_interface.UI;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.*;

public class MessageHandler {

    private final int MAX_DISPATCHER_THREADS = 50;
    private PeerState controller;
    private Peer peer;

    private ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(MAX_DISPATCHER_THREADS);

    /**
     * Instantiates a new MessageHandler.
     *
     */
    public MessageHandler(Peer peer) {
        this.peer = peer;
        this.controller = peer.getController();
    }

    /**
      * Handles a message and sends it to the thread pool.
      * Ignores messages sent my itself.
      *
      * @param message message to be handled
      * @param address address used in GETCHUNK message (TCP address). Unless the peer is enhanced, this field is always
      * null.
      */
    void handleMessage(Message message, InetAddress address) {

        if(message.getMessageType()!= Message.MessageType.REMOVED && message.getMessageType()!= Message.MessageType.STORED &&
                message.getSenderId().equals(peer.getServerId()))
            return;

        int randomWait;
        switch(message.getMessageType()) {
            case PUTCHUNK:
                if(!message.getVersion().equals("1.0")) {
                    controller.listenForSTORED_ENH(message);
                    randomWait = Utils.getRandomBetween(0, Globals.MAX_BACKUP_ENH_WAIT_TIME);
                }
                else
                    randomWait = 0;

                threadPool.schedule(() -> handlePUTCHUNK(message), randomWait, TimeUnit.MILLISECONDS);
                break;
            case STORED:
                threadPool.submit(() -> handleSTORED(message));
                break;
            case GETCHUNK:
                controller.listenForCHUNK(message);
                randomWait = Utils.getRandomBetween(0, Globals.MAX_CHUNK_WAITING_TIME);
                threadPool.schedule(() -> handleGETCHUNK(message, address), randomWait, TimeUnit.MILLISECONDS);
                break;
            case CHUNK:
                threadPool.submit(() -> handleCHUNK(message));
                break;
            case DELETE:
                threadPool.submit(() -> handleDELETE(message));
                break;
            case REMOVED:
                threadPool.submit(() -> handleREMOVED(message));
                break;
            default:
                UI.printError("No valid type");
        }

    }

    /**
     * Handles a PUTCHUNK message
     *
     * @param message the message
     */
    private void handlePUTCHUNK(Message message) {
        UI.printBoot("------------- Received Putchunk Message: "+message.getChunkNo()+" -----------");

        //System.out.println("Received Putchunk: " + message.getChunkNo());

        String fileId = message.getFileId();
        int chunkNo = message.getChunkNo();

        //Ignora putchunks dum fichieor que ele esta a fazer backup
        ConcurrentHashMap<String, FileInfo> backedUpFiles = controller.getBackedUpFiles();
        for (Map.Entry<String, FileInfo> entry : backedUpFiles.entrySet()) {
            FileInfo fileInfo = entry.getValue();
            if(fileInfo.getFileId().equals(fileId)){
                return;
            }
        }

        if(controller.isBackupEnhancement() && !message.getVersion().equals("1.0")) {
            FileChunk key = new FileChunk(fileId, chunkNo);
            ConcurrentHashMap<FileChunk, ChunkInfo> storedRepliesInfo = controller.getStoredChunks_ENH();

            if(storedRepliesInfo.containsKey(key)) {
                if(storedRepliesInfo.get(key).achievedDesiredRepDeg()) {
                    UI.printWarning("Received enough STORED messages for " + message.getChunkNo() + " meanwhile, ignoring request");
                    UI.printBoot("------------------------------------------------------");
                    return;
                }
            }
        }

        controller.startStoringChunks(message);
        ConcurrentHashMap<String, ArrayList<Integer>> storedChunksByFileId = controller.getStoredChunksByFileId();

        if(storedChunksByFileId.get(fileId).contains(message.getChunkNo())) {
            UI.printWarning("Already stored chunk, sending STORED anyway.");
        }
        else {
            if (!controller.getStorageManager().saveChunk(message)) {
                UI.printError("Not enough space to save chunk " + message.getChunkNo() + " of file " + message.getFileId());
                UI.printBoot("------------------------------------------------------");
                return;
            }
            controller.addStoredChunk(message);
        }

        Message storedMessage = new Message(message.getVersion(), peer.getServerId(), message.getFileId(), null, Message.MessageType.STORED, message.getChunkNo());
        peer.getMCChannel().sendWithRandomDelay(0, Globals.MAX_STORED_WAITING_TIME, storedMessage);

        UI.printOK("Sent Stored Message: " + storedMessage.getChunkNo());
        UI.printBoot("------------------------------------------------------");
    }

    /**
     * Handles a STORED message
     *
     * @param message the message
     */
    private void handleSTORED(Message message) {
        UI.printBoot("-------------- Received Stored Message: "+ message.getChunkNo() +" ------------");

        //System.out.println("Received Stored Message: " + message.getChunkNo());

        FileChunk key = new FileChunk(message.getFileId(), message.getChunkNo());
        controller.updateChunkInfo(key,message);
        UI.printBoot("------------------------------------------------------");
    }

    /**
     * Handles a GETCHUNK message. If a CHUNK message for this chunk is received while handling GETCHUNK, the operation
     * is aborted. If the peer does not have any CHUNK for this file or this CHUNK No, the operation is aborted.
     *
     * @param message the message
     * @param sourceAddress address used for TCP connection in enhanced version of protocols
     */
    private void handleGETCHUNK(Message message, InetAddress sourceAddress) {
        UI.printBoot("------------ Received GetChunk Message: "+message.getChunkNo()+" ------------");

        //System.out.println("Received GetChunk Message: " + message.getChunkNo());

        String fileId = message.getFileId();
        int chunkNo = message.getChunkNo();
        FileChunk fileChunk = new FileChunk(fileId, chunkNo);

        ConcurrentHashMap<FileChunk, Boolean> isBeingRestoredChunkMap = controller.getIsBeingRestoredChunkMap();
        if(isBeingRestoredChunkMap.containsKey(fileChunk)) {
            if(isBeingRestoredChunkMap.get(fileChunk)) {
                controller.removeChunk(fileChunk);
                UI.printWarning("CHUNK " + chunkNo + " is already being restored, ignoring request");
                UI.printBoot("------------------------------------------------------");
                return;
            }
        }

        ConcurrentHashMap<String, ArrayList<Integer>> storedChunksByFileId = controller.getStoredChunksByFileId();
        if(!storedChunksByFileId.containsKey(fileId) || !storedChunksByFileId.get(fileId).contains(chunkNo)) {
            UI.printBoot("------------------------------------------------------");
            return;
        }

        Message chunk = controller.getStorageManager().loadChunk(fileId, chunkNo);
        peer.sendMessage(chunk,sourceAddress);
        UI.printOK("Sent CHUNK Message: " + message.getChunkNo());
        UI.printBoot("------------------------------------------------------");
    }

    /**
     * Handles a CHUNK message
     *
     * @param message the message
     */
    private void handleCHUNK(Message message) {
        UI.printBoot("-------------- Received Chunk Message: "+ message.getChunkNo() +" -------------");
        //System.out.println("Received Chunk Message: " + message.getChunkNo());

        String fileId = message.getFileId();
        FileChunk fileChunk = new FileChunk(fileId, message.getChunkNo());

        ConcurrentHashMap<FileChunk, Boolean> isBeingRestoredChunkMap = controller.getIsBeingRestoredChunkMap();
        if(isBeingRestoredChunkMap.containsKey(fileChunk)) {
            controller.setIsBeingRestored(fileChunk);
            UI.printOK("Marked Chunk " + message.getChunkNo() + "as being restored");
        }

        ConcurrentHashMap<String, ConcurrentSkipListSet<Message>> chunksByRestoredFile = controller.getRestoredChunks();
        if(!chunksByRestoredFile.containsKey(fileId)) {
            UI.printBoot("------------------------------------------------------");
            return;
        }

        // if an enhanced chunk message is sent via multicast
        // channel, it only contains a header, don't restore
        //TODO: this verification isn't right
        if(!message.getVersion().equals("1.0") && !message.hasBody()) {
            UI.printBoot("------------------------------------------------------");
            return;
        }

        controller.addRestoredFileChunks(message);

        if(controller.hasRestoredAllChunks(fileId)) {
            controller.saveFileToRestoredFolder(fileId);
            controller.stopRestoringFile(fileId);
        }
        UI.printBoot("------------------------------------------------------");
    }

    /**
     * Handles a DELETE message. If the peer does not have the chunk, the message is ignored.
     *
     * @param message the message
     */
    private void handleDELETE(Message message) {
        UI.printBoot("-------------- Received Delete Message ---------------");

        //System.out.println("Received Delete Message");

        String fileId = message.getFileId();

        ConcurrentHashMap<String, ArrayList<Integer>> storedChunksByFileId = controller.getStoredChunksByFileId();
        if(!storedChunksByFileId.containsKey(fileId)) {
            UI.printBoot("------------------------------------------------------");
            return;
        }

        ArrayList<Integer> storedChunks = storedChunksByFileId.get(fileId);
        while(!storedChunks.isEmpty()) {
            controller.deleteChunk(fileId, storedChunks.get(0), false);
        }

        //controller.removeStoredChunksFile(fileId);
        UI.printOK("Delete Success: file deleted");
        UI.printBoot("------------------------------------------------------");
    }

    /**
     * Handles a REMOVED message. If this action leads to an unsatisfied replication degree, a new backup protocols for
     * the chunk must be initiated. However, it must wait a random interval of [0-400]ms to check if the degree was
     * satisfied before taking action.
     *
     * @param message the message
     */
    private void handleREMOVED(Message message) {
        UI.printBoot("------------- Received Removed Message: "+message.getChunkNo()+" ------------");

        //System.out.println("Received Removed Message: " + message.getChunkNo());

        FileChunk fileChunk = new FileChunk(message.getFileId(), message.getChunkNo());
        ConcurrentHashMap<FileChunk, ChunkInfo> storedChunks = controller.getStoredChunks();
        ConcurrentHashMap<FileChunk, ChunkInfo> reclaimedChunks = controller.getChunksReclaimed();

        if(storedChunks.containsKey(fileChunk)) {
            ChunkInfo chunkInfo = storedChunks.get(fileChunk);
            chunkInfo.decreaseCurrentRepDeg();
            System.out.println("Have storedchunk");

            if(!chunkInfo.achievedDesiredRepDeg()) {
                System.out.println("Replication degree of Chunk " + message.getChunkNo() + " is no longer being respected");
                Message chunk = controller.getStorageManager().loadChunk(message.getFileId(), message.getChunkNo());

                threadPool.schedule( new BackupChunk(controller, chunk, chunkInfo.getDesiredReplicationDeg(), peer.getMDBChannel()),
                        Utils.getRandomBetween(0, Globals.MAX_REMOVED_WAITING_TIME), TimeUnit.MILLISECONDS);
            }
        } else if(reclaimedChunks.containsKey(fileChunk)){
            ChunkInfo chunkInfo = reclaimedChunks.get(fileChunk);

            System.out.println("Replication degree of Chunk " + message.getChunkNo() + " is no longer being respected");
            Message PUTCHUNK = new Message(peer.getVersion(), -1, message.getFileId(), chunkInfo.getBody(),
                    Message.MessageType.PUTCHUNK, message.getChunkNo(), chunkInfo.getDesiredReplicationDeg());

            threadPool.schedule( new BackupChunk(controller, PUTCHUNK, peer.getMDBChannel()),
                    Utils.getRandomBetween(0, Globals.MAX_REMOVED_WAITING_TIME), TimeUnit.MILLISECONDS);

            controller.removeReclaimedChunk(fileChunk);
        }
        UI.printBoot("------------------------------------------------------");
    }
}
