package protocols;

import message.Message;
import peer.PeerState;
import channels.Channel;
import utils.Globals;
import utils.Utils;

import java.io.*;
import java.util.ArrayList;

public class BackupInitiator implements Runnable{

    private String filePath;
    private int replicationDegree;
    private int numberChunks;
    private ArrayList<Message> chunks;
    private String fileId;
    private File file;
    private PeerState peerState;
    private Channel channel;

    /**
     * Instantiates a new BackupChunk initiator.
     *
     * @param filePath          the file path
     * @param replicationDegree the replication degree
     * @param channel           the message
     */
    public BackupInitiator(PeerState peerState, String filePath, int replicationDegree, Channel channel) {
        this.peerState = peerState;
        this.channel = channel;
        this.filePath = filePath;
        this.replicationDegree = replicationDegree;

        file = new File(filePath);
        fileId = Utils.getFileID(filePath);

        numberChunks = (int) (file.length() / Globals.MAX_CHUNK_SIZE + 1);
        chunks = new ArrayList<>();
    }

    /**
      * Method executed when thread starts running. Executes the backup protocols as an initiator peer.
      */
    @Override
    public void run() {
        splitIntoChunks();

        int tries = 0;
        int waitTime = 500;

        for(Message chunk : chunks)
            peerState.listenForSTORED(chunk);

        do {
            tries++; waitTime *= 2;
            System.out.println("Sent " + filePath + " PUTCHUNK messages " + tries + " times");

            if(tries > Globals.MAX_PUTCHUNK_TRIES) {
                System.out.println("Aborting backup, attempt limit reached");
                return;
            }

            for(Message chunk : chunks){
                channel.sendMessage(chunk);
                System.out.println("Sent " + chunk.getMessageType() + " message: " + chunk.getChunkNo());
            }

        } while(!wereAllSTOREDReceived(waitTime));

        peerState.addBackedUpFile(filePath, fileId, numberChunks);
        System.out.println("File " + filePath + " backed up");
    }


    /**
     * Splits file in chunks
     */
    private void splitIntoChunks() {
        try {
            FileInputStream fileStream = new FileInputStream(file);
            BufferedInputStream bufferedFile = new BufferedInputStream(fileStream);

            for(int i = 0; i < numberChunks; i++) {
                byte[] body;
                byte[] aux = new byte[Globals.MAX_CHUNK_SIZE];
                int bytesRead = bufferedFile.read(aux);

                if(bytesRead == -1)
                    body = new byte[0];
                else if(bytesRead < Globals.MAX_CHUNK_SIZE)
                    body = new byte[bytesRead];
                else
                    body = new byte[Globals.MAX_CHUNK_SIZE];

                System.arraycopy(aux, 0, body, 0, body.length);
                chunks.add(new Message(peerState.getVersion(), peerState.getServerId(), fileId, body, Message.MessageType.PUTCHUNK, i, replicationDegree));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
      * After the given waitTime, checks if all chunks have achieved their desired replication degree, while removing those that have from the chunks container.
      * @param waitTime - the delay before starting to check
      * @return true if all the chunks achieved their desired replication degree and false if otherwise
      */
    private boolean wereAllSTOREDReceived(int waitTime) {
        try {
            Thread.sleep(waitTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        chunks.removeIf( chunk -> peerState.getBackedUpChunkRepDegree(chunk) >= chunk.getReplicationDeg());

        return chunks.isEmpty();
    }
}
