/*
 * Copyright 2016 wira.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bbi.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bbi.tools.FileEntry;
import org.bbi.tools.Log;

/**
 *
 * @author wira
 */
public class SockUDP {
    /**
     * Size of the buffer used to read file from disk
     */
    public static int FILE_READ_BUFFER_SIZE = 8192;
    
    /**
     * Maximum UDP payload size. Must be greater than FILE_READ_BUFFER_SIZE
     * for proper UDP PUT operation
     */
    public static final int UDP_MAX_DATAGRAM_SIZE = 16384;
    
    /**
     * Maximum buffer size for PUT to write through with single SEND call
     */
    public static int UDP_PUT_BUFFER_SIZE = 2*UDP_MAX_DATAGRAM_SIZE-2*4; 
    
    /**
     * Delay between broken up data pieces
     */
    public static int PIECE_SEND_DELAY_MS = 0;
    
    /**
     * Recursively transfer files to a client using a UDP socket. If the file
     * is a directory, the directory will be traversed and all files found
     * will be transferred. The remote host must use 
     * {@link #get(DatagramSocket, String, Progress) get} to receive the files.
     * 
     * @param s Socket handle to use
     * @param addr SocketAddress of the remote host
     * @param fileName File or directory to transfer
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs
     */    
    public static void put(DatagramSocket s, SocketAddress addr, String fileName,
            Progress p) throws IOException {
        Payload d;
        StringBuilder strBuf;
        List<FileEntry> fileList = new ArrayList<>();
        File file = new File(fileName);
        FileInputStream in;
        int putBufOffset;
        byte[] fileReadBuffer = new byte[FILE_READ_BUFFER_SIZE];
        int nr;
        try {
            long totalBytes = 0L;
            // generate and write file list (preamble)
            FileEntry.populateFileList(file.getParentFile(), file, fileList, true);
            strBuf = new StringBuilder();
            strBuf.append(String.valueOf(fileList.size()));
            strBuf.append("\n");
            for(FileEntry f : fileList) {
                strBuf.append(String.valueOf(f.getFile().length()));
                strBuf.append(" ");
                strBuf.append(f.getRelativePath());
                strBuf.append("\n");
                totalBytes += f.getFile().length();
            }
            strBuf.append(String.valueOf(totalBytes));
            strBuf.append("\n");
            // the preamble may be long, so we use put, not the string write
            put(s, addr, strBuf.toString().getBytes(StandardCharsets.UTF_8), null);
            if(p != null) {
                p.copiedTotalBytes = 0;
                p.totalFiles = fileList.size();
                p.totalBytes = totalBytes;
            }
            byte[] putBuf = new byte[UDP_PUT_BUFFER_SIZE];
            putBufOffset = 0;
            for(int i = 0; i < fileList.size(); i++) {
                FileEntry f = fileList.get(i);
                File fileHandle =  f.getFile();
                if(p != null) {
                    p.currentFileNumber = i + 1;
                    p.currentFileCopied = 0;
                    p.currentFileSize = fileHandle.length();
                    p.name = f.getRelativePath();
                }
                Log.d(1, "put " + String.format("[%1$15s]", 
                        NumberFormat.getIntegerInstance().format(fileHandle.length()))
                        + " " + f.getRelativePath());
                // transfer bytes
                in = new FileInputStream(fileHandle);
                while((nr = in.read(fileReadBuffer)) != -1) {
                    if(putBufOffset + nr < UDP_PUT_BUFFER_SIZE) {
                        System.arraycopy(fileReadBuffer, 0, putBuf, putBufOffset, nr);
                        putBufOffset += nr;
                    } else {                        
                        int lastBytes = UDP_PUT_BUFFER_SIZE - putBufOffset;
                        int nextBytes = nr - lastBytes;
                        Log.d(3, "udpput: flush pbOff=" + putBufOffset + " nr=" + nr +
                                " last=" + lastBytes + " next=" + nextBytes);
                        // flush
                        System.arraycopy(fileReadBuffer, 0, putBuf, putBufOffset, 
                                lastBytes);
                        send(s, addr, putBuf, null);
                        // reset
                        putBufOffset = 0 + nextBytes;
                        System.arraycopy(fileReadBuffer, lastBytes, putBuf, 0, 
                                nextBytes);
                        // sync with client
                        if(!(d = read(s)).utf8().equals("next")) {
                            Log.err("illegal chunk termination line: " + d.utf8());
                        }
                    }
                    if(p != null) {
                        p.currentFileCopied += nr;
                        p.copiedTotalBytes += nr;
                    }
                }
                in.close();
            }
            // check trailing bytes in putBuf
            if(putBufOffset > 0) {
                Log.d(3, "udpput: trail pbOff=" + putBufOffset);
                byte[] trailBuf = new byte[putBufOffset];
                System.arraycopy(putBuf, 0, trailBuf, 0, putBufOffset);
                send(s, addr, trailBuf, null);
            }
            if(!(d = read(s)).utf8().equals("done")) {
                Log.err("illegal put termination line: " + d.utf8());
            }
        } catch(IOException ioe) {
            Log.err("udpput: exception: " + ioe);
            write(s, addr, "-1");
        }
    }        
    
    /**
     * Recursively receive multiple files over the socket. The server must use
     * {@link #put(DatagramSocket, SocketAddress, String, Progress) put}
     * to transfer the files.
     * 
     * @param s Socket handle to use
     * @param destDir Destination directory for the received files
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public static void get(DatagramSocket s, String destDir, Progress p)
            throws IOException {
        long startTime = System.nanoTime();
        String[] tokens;
        long currentFileCopiedBytes;
        long transferFrame;
        FileOutputStream out;
        int nr, remainingBytes, nextBytes;
        byte[] overflowBuffer = null;
        long totalCopiedBytes = 0;
        
        // wait for preamble from remote host's put
        Payload payload;
        payload = get(s, null);
        SocketAddress remote = payload.getRemote();
        String[] preambleLines = payload.utf8().split("\n");
        int numOfFiles = Integer.parseInt(preambleLines[0]);
        if(numOfFiles < 0) {
            System.err.println("server returned " + numOfFiles);
            return;
        }
        
        // get file names and sizes
        String[] fileNames = new String[numOfFiles];
        long[] fileSizes = new long[numOfFiles];
        for(int i = 0; i < numOfFiles; i++) {
            tokens = preambleLines[i+1].split(" ", 2);
            fileNames[i] = tokens[1];
            fileSizes[i] = Long.parseLong(tokens[0]);
        }
        
        // get total number of bytes so the user knows how big the incoming
        // transmission is
        long totalBytes = Long.parseLong(preambleLines[1+numOfFiles]);
        Log.d(0, "number of files to fetch: " + numOfFiles + " (" +
                NumberFormat.getIntegerInstance().format(totalBytes) +
                " bytes)");
        
        if(p != null) {
            p.copiedTotalBytes = 0;
            p.totalFiles = numOfFiles;
            p.totalBytes = totalBytes;
        }
        for(int i = 0; i < numOfFiles; i++) {
            if(p != null) {
                p.currentFileNumber = i + 1;
                p.currentFileCopied = 0;
                p.currentFileSize = fileSizes[i];
                p.name = fileNames[i];
            }
            File f = new File(destDir + File.separator + fileNames[i]);
            FileEntry.createParentDirectory(f.getParentFile());
            Log.d(0, "get " + String.format("[%1$15s]", 
                    NumberFormat.getIntegerInstance().format(fileSizes[i])) + " "
                    + destDir + File.separator + fileNames[i]);
            currentFileCopiedBytes = 0;
            transferFrame = 0;
            out = new FileOutputStream(f);
            if(overflowBuffer != null) {
                // overflow from last iteration and it's less than/equal to the
                // current file
                if(overflowBuffer.length <= fileSizes[i]) {
                    Log.d(3, "resume of=" + overflowBuffer.length);
                    out.write(overflowBuffer);
                    currentFileCopiedBytes = overflowBuffer.length;
                    overflowBuffer = null;
                
                // overflow and it contains all of current file
                } else {                    
                    remainingBytes = (int)(fileSizes[i]);
                    nextBytes = (int)(overflowBuffer.length - fileSizes[i]);
                    Log.d(3, "cutoff copied=" + remainingBytes);
                    out.write(overflowBuffer, 0, remainingBytes);
                    byte[] newOverflowBuffer = new byte[nextBytes];
                    System.arraycopy(overflowBuffer, remainingBytes,
                            newOverflowBuffer, 0, nextBytes);
                    overflowBuffer = newOverflowBuffer;
                    currentFileCopiedBytes = fileSizes[i];
                }                
            }
            while(currentFileCopiedBytes < fileSizes[i] && 
                    (payload = recv(s, null)) != null) {
                byte[] receiveBuffer = payload.get();
                nr = receiveBuffer.length;
                if(currentFileCopiedBytes + nr <= fileSizes[i]) {
                    Log.d(3, "------ nr=" + nr + " copied=" + currentFileCopiedBytes);
                    out.write(receiveBuffer, 0 , nr);
                    currentFileCopiedBytes += nr;
                } else {
                    // we're done with this file but there's a piece of the
                    // next one. put it in our overflow buffer for next iteration
                    remainingBytes = (int)(fileSizes[i] - currentFileCopiedBytes);
                    nextBytes = nr - remainingBytes;
                    Log.d(3, "cutoff nr=" + nr + 
                            " copied=" + currentFileCopiedBytes + " remainingBytes=" + 
                            remainingBytes);
                    out.write(receiveBuffer, 0, remainingBytes);
                    currentFileCopiedBytes = fileSizes[i];
                    overflowBuffer = new byte[nextBytes];
                    System.arraycopy(receiveBuffer, remainingBytes,
                            overflowBuffer, 0, nextBytes);
                }                
                totalCopiedBytes += nr;
                transferFrame += nr;
                if(transferFrame == UDP_PUT_BUFFER_SIZE) {
                    // we're ready for next PUT chunk
                    write(s, remote, "next");
                    transferFrame = 0;
                }
                if(p != null) {
                    p.currentFileCopied = currentFileCopiedBytes;
                    p.copiedTotalBytes = totalCopiedBytes;
                }
            }
            out.close();
        }
        write(s, remote, "done");
            
        if(totalCopiedBytes < totalBytes) {
            Log.err("missing bytes");
        }
        
        long duration = System.nanoTime() - startTime;
        double seconds = duration / 1000000000.0;
        double speed = (totalBytes / 1000.0) / seconds;
        Log.d(0, NumberFormat.getIntegerInstance().format(totalBytes) + " bytes in " +
                String.format("%.3f", seconds) + " seconds (" +
                String.format("%.2f", speed) + " KiB/s)");
    }
    
    /**
     * A wrapper for
     * {@link #send(DatagramSocket, SocketAddress, byte[], Progress) send}.
     * Sends data broken into chunks with size defined by 
     * <code>UDP_PUT_BUFFER_SIZE</code> and synchronizes with the remote host
     * after every chunk.
     * 
     * @param s Socket handle to use
     * @param addr SocketAddress of the remote host
     * @param data Data to transfer
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs
     */
    public static void put(DatagramSocket s, SocketAddress addr, byte[] data,
            Progress p) throws IOException {
        if(data.length == 0) {
            return;
        }
        Payload d;
        int n = (data.length-1) / UDP_PUT_BUFFER_SIZE + 1;
        write(s, addr, String.valueOf(data.length));
        int off = 0;
        for(int i = 0; i < n; i++) {
            int sendSize = (data.length - off >= UDP_PUT_BUFFER_SIZE) ?
                    UDP_PUT_BUFFER_SIZE : data.length - off;
            byte[] buf = new byte[sendSize];
            System.arraycopy(data, off, buf, 0, sendSize);
            send(s, addr, buf, p);
            off += sendSize;
            if(!(d = read(s)).utf8().equals("next")) {
                Log.err("illegal chunk termination line: " + d.utf8());
            }
        }
    }
    
    /**
     * A wrapper for {@link #recv(DatagramSocket, Progress) recv}. 
     * Counterpart of
     * {@link #put(DatagramSocket, SocketAddress, byte[], Progress) put}.
     * 
     * @param s DatagramSocket handle to use
     * @param p Progress handle to use (can be null)
     * @return Received data
     * @throws IOException if an I/O exception occurs
     */
    public static Payload get(DatagramSocket s, Progress p) 
            throws IOException {
        Payload payload = read(s);
        SocketAddress source = payload.getRemote();
        int len = Integer.parseInt(payload.utf8());
        byte[] data = new byte[len];
        int n = (len-1) / UDP_PUT_BUFFER_SIZE + 1;
        int off = 0;
        if(p != null) {
            p.totalBytes = len;
        }
        for(int i = 0; i < n; i++) {
            payload = recv(s, p);
            len = payload.get().length;
            System.arraycopy(payload.get(), 0, data, off, len);
            off += len;
            write(s, source, "next");
        }
        return new Payload(data, source);
    }
    
    /**
     * <p>Send data through UDP by breaking the byte array into UDP packets. Each
     * packet will have a header that allows
     * {@link #recv(DatagramSocket, Progress) recv} to reconstuct the data as 
     * UDP packets may be received not in the original transmission order.</p>
     * 
     * <p>The maximum number of packets is 65535 and each packet has a 4 byte
     * overhead. This results in maximum data size of:
     * 65535 x <code>UDP_MAX_DATAGRAM_SIZE-4</code> or 2^31-1 (largest Java
     * positive integer value), whichever is smaller. If bigger data needs
     * to be transmitted, it will have to be broken into multiple calls to
     * this method.</p>
     * 
     * <p>Note that there is no synchronization between this method and its
     * receiving counterpart. Sending too big of data broken into many UDP
     * packets may result in dropped packets on the remote host!</p>
     * 
     * @param s DatagramSocket handle to use
     * @param addr Address and port to transmit the data to
     * @param data Byte array containing the data to write
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public static void send(DatagramSocket s, SocketAddress addr,
            byte[] data, Progress p) throws IOException {       
        if(data.length == 0) {
            return;
        }
        int n = ((data.length-1) / (UDP_MAX_DATAGRAM_SIZE-4)) + 1;
        int bytesSent = 0;
        if(n >= 256*256) {
            Log.err("udpsend: too big");
            return;
        }
        int i;
        Log.d(3, "udpsend: dataLen=" + data.length + " n=" + n);
        for(i = 0; i < n; i++) {
            byte[] header = new byte[4];
            // order
            header[0] = (byte) ((i+1) / 256);
            header[1] = (byte) ((i+1) % 256);
            // total number of packets
            header[2] = (byte) (n / 256);
            header[3] = (byte) (n % 256);
            boolean lastChunk = data.length-bytesSent+4 <= UDP_MAX_DATAGRAM_SIZE;
            int sendSize = !lastChunk ? 
                    UDP_MAX_DATAGRAM_SIZE : data.length-bytesSent+4;
            byte[] sendBuffer = new byte[sendSize];
            System.arraycopy(header, 0, sendBuffer, 0, 4);
            System.arraycopy(data, bytesSent, sendBuffer, 4, sendSize-4);
            bytesSent += sendSize-4;
            DatagramPacket packet = new DatagramPacket(sendBuffer, sendSize, 
                    addr);
            Log.d(3, "udpsend: i=" + i + " bytesSent=" + bytesSent);
            s.send(packet);
            if(PIECE_SEND_DELAY_MS > 0) {
                try {
                    Thread.sleep(PIECE_SEND_DELAY_MS);
                } catch(Exception e) {
                    
                }
            }
            if(p != null) {
                p.copiedTotalBytes += sendSize;
            }
        }
    }
    
    /**
     * Receive UDP packets carrying data encoded by 
     * {@link #send(DatagramSocket, SocketAddress, byte[]) send}. The received 
     * data is then enclosed in a Payload object.
     * 
     * @param s DatagramSocket handle to use
     * @return Payload containing reconstructed data
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public static Payload recv(DatagramSocket s,
            Progress p) throws IOException {
        SocketAddress source = null;
        int packetsReceived = 0;
        int totalPackets = -1;
        int nr = 0;
        Map<Integer, Payload> buf = new HashMap<>();
        while(totalPackets < 0 || packetsReceived < totalPackets) {
            byte[] receiveBuffer = new byte[UDP_MAX_DATAGRAM_SIZE];
            DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            s.receive(packet);
            source = packet.getSocketAddress();
            byte[] data = packet.getData();
            byte[] payload = new byte[packet.getLength()-4];
            int order = ((int)(data[0] << 8) & 0xff00) +
                        ((int)(data[1])      & 0x00ff);
            if(totalPackets < 0) {
                totalPackets = ((int)(data[2] << 8) & 0xff00) +
                               ((int)(data[3])      & 0x00ff);
            }
            Log.d(3, "udprecv: " + packet.getAddress().getHostAddress() + " " +
                     "header: " + 
                     String.format("%02X %02X %02X %02X", 
                     data[0], data[1], data[2], data[3]) +
                     " pieces: " + order + "/" + totalPackets + " " +
                     payload.length + " bytes");
            System.arraycopy(data, 4, payload, 0, payload.length);
            buf.put(order, new Payload(payload, source));
            packetsReceived++;
            nr += payload.length;
            if(p != null) {
                p.copiedTotalBytes += payload.length;
            }
        }
        
        // reconstruct data
        byte[] ret = new byte[nr];
        for(int i = 0; i < totalPackets; i++) {
            byte[] data = buf.get(i+1).get();
            System.arraycopy(data, 0, ret, i*(UDP_MAX_DATAGRAM_SIZE-4), data.length);
        }
        return new Payload(ret, source);
    }
    
    /**
     * Send UTF-8 string through a <b>single</b> UDP packet to be received with
     * {@link #read(DatagramSocket) read}. If the data is greater than
     * <code>UDP_MAX_DATAGRAM_SIZE</code> it will be truncated.
     * 
     * @param s DatagramSocket handle to use
     * @param addr Address and port to transmit the data to
     * @param utf8 UTF-8 encoded string to write
     * @throws IOException if an I/O exception occurs 
     */
    public static void write(DatagramSocket s, SocketAddress addr, 
            String utf8) throws IOException {        
        byte[] utf8Bytes = utf8.getBytes(StandardCharsets.UTF_8);
        Log.d(2, "udpsend: \"" + utf8 + "\"");
        s.send(new DatagramPacket(utf8Bytes, utf8Bytes.length, addr));
    }    
    
    /**
     * Receive a UDP packet carrying a UTF-8 string data encoded by
     * {@link #write(DatagramSocket, SocketAddress, String) write}
     * 
     * @param s DatagramSocket handle to use
     * @return Payload containing received UTF-8 string
     * @throws IOException if an I/O exception occurs 
     */
    public static Payload read(DatagramSocket s) throws IOException {
        byte[] receiveBuffer = new byte[UDP_MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        s.receive(packet);
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(receiveBuffer, 0, data, 0, data.length);
        Payload payload = new Payload(data, packet.getSocketAddress());
        Log.d(2, "udprecv: \"" + payload.utf8() + "\"");
        return payload;
    }    
}
