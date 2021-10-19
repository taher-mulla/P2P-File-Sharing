import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;

public class peerProcess extends Thread {
    private static int peersWithCompleteFile = 0;
    private static int thisPeerId;
    private static LinkedHashMap<Integer, PeerInfo> peers;
    private static int connectedPeers = 0;

    public static void main(String[] args) {
        try {
//            thisPeerId = Integer.parseInt(args[0]);
            thisPeerId = 1001;
            System.out.println("This peer - " + thisPeerId);
            peers = new LinkedHashMap<>();

            //Read PeerInfo.cfg and set properties for all peers
            setPeerInfo(peers);
            PeerInfo thisPeer = peers.get(thisPeerId);

            //Read Common.cfg and set the common properties
            CommonProperties commonProperties = new CommonProperties();
            commonProperties.setCommonProperties();
            int fileSize = commonProperties.getFileSize();
            int pieceSize = commonProperties.getPieceSize();
            int numberOfPieces = (int) Math.ceil((double) fileSize / pieceSize);
            int[] bitField = new int[numberOfPieces];
            byte[][] filePieces = new byte[numberOfPieces][];

            //Set pieces of file if this peer has the full file
            if (thisPeer.isHasFile()) {

                //Bitfield will have all 1s if this peer has the full file
                Arrays.fill(bitField, 1);
                thisPeer.setBitField(bitField);

                //Increment this variable by 1 so that total peers with the full file can be tracked
                peersWithCompleteFile += 1;

                //Read the file using stream and assign to fileBytes
                BufferedInputStream file = new BufferedInputStream(new FileInputStream(commonProperties.getFileName()));
                byte[] fileBytes = new byte[fileSize];
                file.read(fileBytes);
                file.close();
                int part = 0;

                //Assigning file pieces to filePieces
                for (int counter = 0; counter < fileSize; counter += pieceSize) {

                    //Fill the filePieces for the part bytes from range counter to counter + pieceSize
                    if (counter + pieceSize <= fileSize)
                        filePieces[part] = Arrays.copyOfRange(fileBytes, counter, counter + pieceSize);

                        //Else will be used for the final few bytes left which is less than the piece size
                    else
                        filePieces[part] = Arrays.copyOfRange(fileBytes, counter, fileSize);
                    part += 1;
                    thisPeer.setNumberOfPieces();
                }
            } else {
                Arrays.fill(bitField, 0);
                thisPeer.setBitField(bitField);
            }

            //Starting all threads to start the protocol
            ConnectToPeers connectToPeers = new ConnectToPeers();
            AcceptConnectionsFromPeers acceptConnectionsFromPeers = new AcceptConnectionsFromPeers();
            connectToPeers.start();
            acceptConnectionsFromPeers.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static void setPeerInfo(HashMap<Integer, PeerInfo> peers) {
        try {
            BufferedReader peerInfo =
                    new BufferedReader(new FileReader("Config Files/PeerInfo.cfg"));
            Object[] peerInfoLines = peerInfo.lines().toArray();
            for (var peerInfoLine : peerInfoLines) {
                int peerId = Integer.parseInt(peerInfoLine.toString().split(" ")[0]);
                String hostName = peerInfoLine.toString().split(" ")[1];
                int portNumber = Integer.parseInt(peerInfoLine.toString().split(" ")[2]);
                boolean hasFile = Integer.parseInt(peerInfoLine.toString().split(" ")[3]) == 1;
                PeerInfo peer = new PeerInfo(peerId, hostName, portNumber, hasFile);
                peers.put(peerId, peer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class ConnectToPeers extends Thread {
        @Override
        public void run() {
            byte[] inputData = new byte[32];
            try {
                byte[] handShakeMessage = Messages.getHandShakeMessage(thisPeerId);

                //Iterate through the peers hashmap
                for (int peerId : peers.keySet()) {

                    //We break here because we only want to connect to peers started before this peer. If this peer is
                    // 1003 we only want to connect to 1001 and 1002. When the loop reaches 1003 we break
                    if (peerId == thisPeerId)
                        break;

                    //Writing the handshake on the output stream
                    Socket socket = new Socket(peers.get(peerId).getHostName(), peers.get(peerId).getPortNumber());
                    DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream());
                    outputStream.write(handShakeMessage);

                    //The other peer sends a handshake message which is retrieved here
                    DataInputStream dataInputStream = new DataInputStream(socket.getInputStream());
                    dataInputStream.readFully(inputData);
                    int receivedPeerId = ByteBuffer.wrap(Arrays.copyOfRange(inputData, 28, 32)).getInt();

                    //This is the check that is mentioned in the project description. If the peer id is different
                    //from the one we connected to , we close the socket
                    if (receivedPeerId != peerId)
                        socket.close();
                    else {
                        StringBuilder handshakeMsg = new StringBuilder();
                        handshakeMsg.append(new String(Arrays.copyOfRange(inputData, 0, 28)));
                        handshakeMsg.append(receivedPeerId);
                        System.out.println(handshakeMsg + " - Connected to " + receivedPeerId);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static class AcceptConnectionsFromPeers extends Thread {
        @Override
        public void run() {
            byte[] data = new byte[32];
            try {
                byte[] handShakeMessage = Messages.getHandShakeMessage(thisPeerId);

                ServerSocket serverSocket = new ServerSocket(peers.get(thisPeerId).getPortNumber());

                //While loop runs peers.size() - 1 times because we want to connect to all other peers
                while (connectedPeers <= peers.size() - 1) {
                    Socket socket = serverSocket.accept();
                    DataInputStream input = new DataInputStream(socket.getInputStream());
                    input.readFully(data);
                    StringBuilder handshakeMsg = new StringBuilder();
                    int peerId = ByteBuffer.wrap(Arrays.copyOfRange(data, 28, 32)).getInt();
                    handshakeMsg.append(new String(Arrays.copyOfRange(data, 0, 28)));
                    handshakeMsg.append(peerId);
                    System.out.println(handshakeMsg + " - Connected to " + peerId);

                    //Sending handshake message to connected peer
                    DataOutputStream dataOutputStream = new DataOutputStream(socket.getOutputStream());
                    dataOutputStream.write(handShakeMessage);
                    connectedPeers += 1;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    static class Messages {
        public static byte[] getHandShakeMessage(int peerId) {
            byte[] handShakeMessage = new byte[32];
            byte[] header = "P2PFILESHARINGPROJ".getBytes(StandardCharsets.UTF_8);
            byte[] zeroBits = "0000000000".getBytes(StandardCharsets.UTF_8);
            byte[] thisPeer = ByteBuffer.allocate(4).putInt(peerId).array();

            int index = 0;
            for (var b : header) {
                handShakeMessage[index] = b;
                index += 1;
            }

            for (var b : zeroBits) {
                handShakeMessage[index] = b;
                index += 1;
            }

            for (var b : thisPeer) {
                handShakeMessage[index] = b;
                index += 1;
            }
            return handShakeMessage;
        }
    }
}

class CommonProperties {
    private int preferredNeighbors;
    private int unchokingInterval;
    private int optimisticUnchokingInterval;
    private String fileName;
    private int fileSize;
    private int pieceSize;

    public int getPreferredNeighbors() {
        return preferredNeighbors;
    }

    public void setPreferredNeighbors(int preferredNeighbors) {
        this.preferredNeighbors = preferredNeighbors;
    }

    public int getUnchokingInterval() {
        return unchokingInterval;
    }

    public void setUnchokingInterval(int unchokingInterval) {
        this.unchokingInterval = unchokingInterval;
    }

    public int getOptimisticUnchokingInterval() {
        return optimisticUnchokingInterval;
    }

    public void setOptimisticUnchokingInterval(int optimisticUnchokingInterval) {
        this.optimisticUnchokingInterval = optimisticUnchokingInterval;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public int getFileSize() {
        return fileSize;
    }

    public void setFileSize(int fileSize) {
        this.fileSize = fileSize;
    }

    public int getPieceSize() {
        return pieceSize;
    }

    public void setPieceSize(int pieceSize) {
        this.pieceSize = pieceSize;
    }

    public void setCommonProperties() {
        try {
            BufferedReader commonInfo =
                    new BufferedReader(new FileReader("Config Files/Common.cfg"));

            Object[] commonLines = commonInfo.lines().toArray();
            this.setPreferredNeighbors(Integer.parseInt(commonLines[0].toString().split(" ")[1]));
            this.setUnchokingInterval(Integer.parseInt(commonLines[1].toString().split(" ")[1]));
            this.setOptimisticUnchokingInterval(Integer.parseInt(commonLines[2].toString().split(" ")[1]));
            this.setFileName(commonLines[3].toString().split(" ")[1]);
            this.setFileSize(Integer.parseInt(commonLines[4].toString().split(" ")[1]));
            this.setPieceSize(Integer.parseInt(commonLines[5].toString().split(" ")[1]));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class PeerInfo {
    private int peerId;
    private String hostName;
    private int portNumber;
    private boolean hasFile;
    private int[] bitField;
    private int numberOfPieces = 0;

    public PeerInfo(int peerId, String hostName, int portNumber, boolean hasFile) {
        this.peerId = peerId;
        this.hostName = hostName;
        this.portNumber = portNumber;
        this.hasFile = hasFile;
    }

    public int getPeerId() {
        return peerId;
    }

    public void setPeerId(int peerId) {
        this.peerId = peerId;
    }

    public String getHostName() {
        return hostName;
    }

    public void setHostName(String hostName) {
        this.hostName = hostName;
    }

    public int getPortNumber() {
        return portNumber;
    }

    public void setPortNumber(int portNumber) {
        this.portNumber = portNumber;
    }

    public boolean isHasFile() {
        return hasFile;
    }

    public void setHasFile(boolean hasFile) {
        this.hasFile = hasFile;
    }

    public int[] getBitField() {
        return bitField;
    }

    public void setBitField(int[] bitField) {
        this.bitField = bitField;
    }

    public int getNumberOfPieces() {
        return numberOfPieces;
    }

    public void setNumberOfPieces() {

        //Bitfield length is the total number of pieces in the file. If total number of downloaded pieces is equal to
        // bitField.length it means that the peer has the complete file.
        this.numberOfPieces += 1;
        if (this.numberOfPieces == bitField.length)
            this.hasFile = true;
    }
}
