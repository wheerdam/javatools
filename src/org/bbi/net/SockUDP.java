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
import java.util.Iterator;
import java.util.List;
import org.bbi.tools.FileEntry;
import org.bbi.tools.Log;

/**
 * Some tools to transfer data and files over UDP
 *
 * @author wira
 */
public class SockUDP {
    /**
     * The UDP socket we're working on
     */
    private DatagramSocket s;
    
    /**
     * The last time the buffer was accessed
     */
    private long lastBufferAccess;
    
    /**
     * Size of the buffer used to read file from disk
     */
    public static int FILE_READ_BUFFER_SIZE = 8192;
    
    /**
     * Maximum UDP payload size. Must be greater than FILE_READ_BUFFER_SIZE
     * for proper UDP PUT operation. The built-in value is 16384 bytes
     */
    public static final int UDP_MAX_DATAGRAM_SIZE = 16384;
    
    /**
     * Maximum buffer size for PUT to write through with single SEND call
     */
    private static int UDP_PUT_BUFFER_SIZE = 2*(UDP_MAX_DATAGRAM_SIZE-2); 
    
    /**
     * Delay between broken up data pieces
     */
    public static int PIECE_SEND_DELAY_MS = 0;
    
    /**
     * Temporary buffer of unclaimed UDP packets
     */
    private final List<DatagramPacket> RECV_BUFFER = new ArrayList<>();
    
    /**
     * Provide a socket handle to initiate the class
     * 
     * @param s socket handle to use
     */
    public SockUDP(DatagramSocket s) {
        this.s = s;
        lastBufferAccess = System.currentTimeMillis();
    }

    /**
     * <p>Modify the buffer size for PUT call. The buffer is set to be multiples
     * of the maximum datagram size minus the SEND/RECV header so the data
     * chunks will mostly fit inside a UDP packet</p>
     * 
     * <p>Setting a large value will cause many UDP packets to be sent at once
     * <em>without</em> synchronization. The default value is 2, which means
     * that synchronization happens after every 2 UDP packets sent when PUT
     * and GET are used</p>
     * 
     * @param m buffer size in multiples of <code>UDP_MAX_DATAGRAM_SIZE-4</code>
     */
    public static void setPutBufferSize(int m) {
        UDP_PUT_BUFFER_SIZE = m*(UDP_MAX_DATAGRAM_SIZE-4);
    }
    
    /**
     * Get the current value for the PUT buffer size in BYTES
     * 
     * @return buffer size
     */
    public static int getPutBufferSize() {
        return UDP_PUT_BUFFER_SIZE;
    }
    
    /**
     * Inspect arrived packets to see if it came from the given source. If
     * no previous source-matched packets were found, block and receive on the
     * socket and check again. If the new packet still does not match, return
     * <code>null</code> to signify the caller that its packet has not arrived
     * but should check again in the future. Returning <code>null</code>
     * also allows waiting threads to access the buffer
     * 
     * @param addr host address to match to
     * @return first matching payload in the buffer, or <code>null</code> if
     * none is found
     * @throws IOException if an I/O exception occurs
     */
    private synchronized DatagramPacket inspect(SocketAddress addr) 
            throws IOException {        
        Log.d(5, "--> inspect: looking for packets from " + UDPHost.sockAddress(addr));
        String sockAddress = UDPHost.sockAddress(addr);
        DatagramPacket p = consume(addr);
        if(p != null) {
            Log.d(5, "<-- inspect: found by consume");
            return p;
        }
        // let other threads the chance to consume the buffer
        if(!RECV_BUFFER.isEmpty()) {
            Log.d(5, "<-- inspect: yield (buffer empty)");
            return null;
        }
        
        // buffer is actually empty, block and receive. At this point if 
        // multiple threads are waiting a packet, they will be blocked
        Log.d(5, "    inspect: block");
        p = read();
        lastBufferAccess = System.currentTimeMillis();
        
        // check if this is the one before giving up
        if(UDPHost.sockAddress(p.getSocketAddress()).equals(sockAddress)) {
            Log.d(5, "<-- inspect: fresh off the wire");
            return p;
        }
        
        // if not, yield and try again later
        Log.d(5, "    --- RECV_BUFFER.add(" + RECV_BUFFER.size() + "): " + 
                 UDPHost.sockAddress(addr) + " " +
                 (new Payload(p)).decode());
        RECV_BUFFER.add(p);       
        Log.d(5, "<-- inspect: nope, to buffer you go");
        return null;
    }
    
    /**
     * See if there is a packet in the buffer that matches the source address.
     * If there is, return the oldest packet. If there is none, return 
     * <code>null</code>
     * 
     * @param addr host address to match to
     * @return first matching payload in the buffer, or <code>null</code> if
     * none is found
     */
    private synchronized DatagramPacket consume(SocketAddress addr) {
        Log.d(5, "--> consume: looking for packets from " + UDPHost.sockAddress(addr));
        String sockAddress = UDPHost.sockAddress(addr);
        lastBufferAccess = System.currentTimeMillis();
        Iterator<DatagramPacket> it = RECV_BUFFER.iterator();
        while(it.hasNext()) {
            DatagramPacket pp = it.next();
            if(UDPHost.sockAddress(pp.getSocketAddress()).equals(sockAddress)) {
                it.remove();
                Log.d(5, "    --- RECV_BUFFER.remove(" + RECV_BUFFER.size() + "): " + 
                         UDPHost.sockAddress(addr) + " " +
                         (new Payload(pp)).decode());
                Log.d(5, "<-- consume: found one"); 
                return pp;
            }
        }
        Log.d(5, "<-- consume: nope");
        return null;
    }
    
    /**
     * Listen for a packet not coming from the listed addresses (i.e. a new
     * connection). Packets coming from known hosts will be added to the buffer
     * and <code>null</code> will be returned
     * 
     * @param addresses list of socket addresses to ignore (<code>null</code> to
     * capture a packet from <em>any</em> source)
     * @return a packet belonging to a new host or <code>null</code>
     * @throws IOException if an I/O exception occurs
     */
    public synchronized DatagramPacket listen(List<SocketAddress> addresses)
            throws IOException {
        DatagramPacket p;
        boolean fetch = RECV_BUFFER.isEmpty();
        Log.d(3, "--> listen: port " + s.getLocalPort() + " bufsize=" + 
                RECV_BUFFER.size());
        if(fetch) {
            Log.d(3, "    listen: block");
            p = read();
            lastBufferAccess = System.currentTimeMillis();
            if(addresses != null) {
                for(SocketAddress addr : addresses) {
                    if(UDPHost.sockAddress(addr).equals(
                            UDPHost.sockAddress(p.getSocketAddress()))) {
                        Log.d(5, "    --- RECV_BUFFER.add(" + RECV_BUFFER.size() + "): " + 
                                 UDPHost.sockAddress(addr) + " " +
                                 (new Payload(p)).decode());
                        RECV_BUFFER.add(p);
                        Log.d(5, "<-- listen: nope, to buffer");
                        return null;
                    }
                }
            }
            Log.d(5, "<-- listen: fresh off the wire!");
            return p;
        } else {
            lastBufferAccess = System.currentTimeMillis();
            Iterator<DatagramPacket> it = RECV_BUFFER.iterator();
            while(it.hasNext()) {
                p = it.next();
                if(addresses != null) {
                    for(SocketAddress addr : addresses) {
                        if(UDPHost.sockAddress(addr).equals(
                                UDPHost.sockAddress(p.getSocketAddress()))) {
                            Log.d(5, "<-- listen: nope, to buffer");
                            return null;
                        }
                    }
                }
                Log.d(5, "    --- RECV_BUFFER.remove(" + RECV_BUFFER.size() + "): " + 
                         UDPHost.sockAddress(p.getSocketAddress()) + " " +
                         (new Payload(p)).decode());
                it.remove();
                Log.d(5, "<-- listen: found one");
                return p;
            }
            Log.d(5, "<-- listen: nope");
            return null;
        }
    }
    
    /**
     * Return the timestamp the last time the buffer was accessed. Can be
     * useful to clear the buffer when there are lingering packets that just
     * don't get consumed
     * 
     * @return timestamp in milliseconds
     */
    public synchronized long getTimeLastBufferAccess() {
        return lastBufferAccess;
    }
    
    /**
     * Get a list of current buffer contents
     * 
     * @return list of contents in string array
     */
    public synchronized String[] dumpBuffer() {
        String[] ret = new String[RECV_BUFFER.size()];
        int i = 0;
        for(DatagramPacket p : RECV_BUFFER) {
            ret[i] = UDPHost.sockAddress(p.getSocketAddress()) + " len=" +
                                         p.getLength();
            i++;
        }
        return ret;
    }
    
    /**
     * Reset receive buffer state if it has not been accessed for the specified
     * amount of time
     * 
     * @param time duration in milliseconds where the buffer has not been
     * touched
     * @return true if buffer is cleared, false otherwise
     */
    public synchronized boolean clearBuffer(long time) {
        if(System.currentTimeMillis() - lastBufferAccess > time) {
            Log.d(5, "    --- RECV_BUFFER.clear");
            RECV_BUFFER.clear();
            return true;
        }
        return false;
    }
    
    /**
     * Recursively transfer files to a client using a UDP socket. If the file
     * is a directory, the directory will be traversed and all files found
     * will be transferred. The remote host must use 
     * {@link #getf(DatagramSocket, String, Progress) getf} to receive the files
     * 
     * @param addr <code>SocketAddress</code> of the remote host
     * @param fileName file or directory to transfer
     * @param p progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs
     */    
    public void putf(SocketAddress addr, 
                     String fileName, 
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
            // the preamble may be long, so we use put, not write
            put(addr, strBuf.toString().getBytes(StandardCharsets.UTF_8), null);
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
                        Log.d(3, "udpputf: flush pbOff=" + putBufOffset + " nr=" + nr +
                                " last=" + lastBytes + " next=" + nextBytes);
                        // flush
                        System.arraycopy(fileReadBuffer, 0, putBuf, putBufOffset, 
                                lastBytes);
                        send(addr, putBuf, null);
                        // reset
                        putBufOffset = 0 + nextBytes;
                        System.arraycopy(fileReadBuffer, lastBytes, putBuf, 0, 
                                nextBytes);
                        // sync with client
                        if(!(d = recv(addr, null)).decode().equals("next")) {
                            Log.err("illegal chunk termination line: " + d.decode());
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
                Log.d(3, "udpputf: trail pbOff=" + putBufOffset);
                byte[] trailBuf = new byte[putBufOffset];
                System.arraycopy(putBuf, 0, trailBuf, 0, putBufOffset);
                send(addr, trailBuf, null);
            }
            if(!(d = recv(addr, null)).decode().equals("done")) {
                Log.err("illegal put termination line: " + d.decode());
            }
        } catch(IOException ioe) {
            Log.err("udpputf: exception: " + ioe);
            sendUTF8(addr, "-1");
        }
    }        
    
    /**
     * Recursively receive multiple files over the socket. The server must use
     * {@link #putf(DatagramSocket, SocketAddress, String, Progress) putf}
     * to transfer the files
     * 
     * @param destDir destination directory for the received files
     * @param source source address to match, <code>null</code> to match all
     * packets (dangerous)
     * @param p progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */    
    public void getf(SocketAddress source, String destDir, Progress p)
            throws IOException {       
        long startTime = System.nanoTime();
        String[] tokens;
        long currentFileCopiedBytes;
        long transferFrame;
        FileOutputStream out;
        int nr, remainingBytes, nextBytes;
        byte[] overflowBuffer = null;
        long totalCopiedBytes = 0;
        Log.d(3, "udpgetf: " + UDPHost.sockAddress(source));
        
        // wait for preamble from remote host's putf
        Payload payload;
        payload = get(source, null);
        SocketAddress remote = payload.getRemote();
        String[] preambleLines = payload.decode().split("\n");
        int numOfFiles = Integer.parseInt(preambleLines[0]);
        if(numOfFiles < 0) {
            System.err.println("server returned " + numOfFiles);
            return;
        }
        
        // getf file names and sizes
        String[] fileNames = new String[numOfFiles];
        long[] fileSizes = new long[numOfFiles];
        for(int i = 0; i < numOfFiles; i++) {
            tokens = preambleLines[i+1].split(" ", 2);
            fileNames[i] = tokens[1];
            fileSizes[i] = Long.parseLong(tokens[0]);
        }
        
        // getf total number of bytes so the user knows how big the incoming
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
                    Log.d(3, "udpgetf: resume of=" + overflowBuffer.length);
                    out.write(overflowBuffer);
                    currentFileCopiedBytes = overflowBuffer.length;
                    overflowBuffer = null;
                
                // overflow and it contains all of current file
                } else {                    
                    remainingBytes = (int)(fileSizes[i]);
                    nextBytes = (int)(overflowBuffer.length - fileSizes[i]);
                    Log.d(3, "udpgetf: cutoff copied=" + remainingBytes);
                    out.write(overflowBuffer, 0, remainingBytes);
                    byte[] newOverflowBuffer = new byte[nextBytes];
                    System.arraycopy(overflowBuffer, remainingBytes,
                            newOverflowBuffer, 0, nextBytes);
                    overflowBuffer = newOverflowBuffer;
                    currentFileCopiedBytes = fileSizes[i];
                }                
            }
            while(currentFileCopiedBytes < fileSizes[i] && 
                    (payload = recv(source, null)) != null) {
                byte[] receiveBuffer = payload.get();
                nr = receiveBuffer.length;
                if(currentFileCopiedBytes + nr <= fileSizes[i]) {
                    Log.d(3, "udpgetf: nr=" + nr + " copied=" + currentFileCopiedBytes);
                    out.write(receiveBuffer, 0 , nr);
                    currentFileCopiedBytes += nr;
                } else {
                    // we're done with this file but there's a piece of the
                    // next one. putf it in our overflow buffer for next iteration
                    remainingBytes = (int)(fileSizes[i] - currentFileCopiedBytes);
                    nextBytes = nr - remainingBytes;
                    Log.d(3, "udpgetf: cutoff nr=" + nr + 
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
                    sendUTF8(remote, "next");
                    transferFrame = 0;
                }
                if(p != null) {
                    p.currentFileCopied = currentFileCopiedBytes;
                    p.copiedTotalBytes = totalCopiedBytes;
                }
            }
            out.close();
        }
        sendUTF8(remote, "done");
            
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
     * after every chunk
     * 
     * @param addr <code>SocketAddress</code> of the remote host
     * @param data data to transfer
     * @param p progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs
     */
    public void put(SocketAddress addr, byte[] data, Progress p) 
            throws IOException {
        if(data.length == 0) {
            return;
        }
        Payload d;
        int n = (data.length-1) / UDP_PUT_BUFFER_SIZE + 1;
        Log.d(3, "udpput: " + UDPHost.sockAddress(addr) + 
                " dataLen=" + data.length + " n=" + n);
        sendUTF8(addr, String.valueOf(data.length));
        int off = 0;
        for(int i = 0; i < n; i++) {
            int sendSize = (data.length - off >= UDP_PUT_BUFFER_SIZE) ?
                    UDP_PUT_BUFFER_SIZE : data.length - off;
            byte[] buf = new byte[sendSize];
            System.arraycopy(data, off, buf, 0, sendSize);
            send(addr, buf, p);
            off += sendSize;
            if(!(d = new Payload(read(addr))).decode().equals("next")) {
                Log.err("illegal chunk termination line: " + d.decode());
            }
        }
    }
       
    /**
     * A wrapper for {@link #recv(DatagramSocket, Progress) recv}. 
     * Counterpart of
     * {@link #put(DatagramSocket, SocketAddress, byte[], Progress) putf}
     * 
     * @param source address to match, <code>null</code> to match all
     * packets (dangerous)
     * @param p progress handle to use (can be null)
     * @return Received data
     * @throws IOException if an I/O exception occurs
     */
    public Payload get(SocketAddress source, Progress p) 
            throws IOException {
        Log.d(3, "udpget: " + (source != null ? UDPHost.sockAddress(source) :
                "source unknown (waiting)"));
        Payload payload = recv(source, null);
        source = payload.getRemote();
        int len = Integer.parseInt(payload.decode());
        byte[] data = new byte[len];
        int n = (len-1) / UDP_PUT_BUFFER_SIZE + 1;
        int off = 0;
        if(p != null) {
            p.totalBytes = len;
        }
        for(int i = 0; i < n; i++) {
            payload = recv(source, p);
            len = payload.get().length;
            System.arraycopy(payload.get(), 0, data, off, len);
            off += len;
            write(source, "next");
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
     * @param addr <code>SocketAddress</code> of the remote host
     * @param data byte array containing the data to write
     * @param p progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public void send(SocketAddress addr,
                     byte[] data, 
                     Progress p) throws IOException {       
        if(data.length == 0) {
            return;
        }
        int n = ((data.length-1) / (UDP_MAX_DATAGRAM_SIZE-2)) + 1;
        int bytesSent = 0;
        if(n >= 256*256) {
            Log.err("  udpsend: too big");
            return;
        }
        int i;
        Log.d(3, "  udpsend: dataLen=" + data.length + " n=" + n);
        write(addr, String.valueOf(n));
        for(i = 0; i < n; i++) {
            byte[] header = new byte[2];
            // order
            header[0] = (byte) ((i+1) / 256);
            header[1] = (byte) ((i+1) % 256);
            boolean lastChunk = data.length-bytesSent+2 <= UDP_MAX_DATAGRAM_SIZE;
            int sendSize = !lastChunk ? 
                    UDP_MAX_DATAGRAM_SIZE : data.length-bytesSent+2;
            byte[] sendBuffer = new byte[sendSize];
            System.arraycopy(header, 0, sendBuffer, 0, 2);
            System.arraycopy(data, bytesSent, sendBuffer, 2, sendSize-2);
            bytesSent += sendSize-2;
            DatagramPacket packet = new DatagramPacket(sendBuffer, sendSize, 
                    addr);
            Log.d(3, ">>> udpsend: " + UDPHost.sockAddress(addr)
                     + " i=" + i + " bytesSent=" + bytesSent);
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
     * Wrapper for {@link #send(DatagramSocket, SocketAddress, byte[]) send} to 
     * send strings that will be encoded into UTF-8
     * 
     * @param addr address of the host to send the data to
     * @param str string to send
     * @throws IOException if an I/O exception occurs
     */
    public void sendUTF8(SocketAddress addr, String str)
            throws IOException {
        send(addr, str.getBytes(StandardCharsets.UTF_8), null);
    }   
       
    /**
     * Receive UDP packets carrying data encoded by 
     * {@link #send(DatagramSocket, SocketAddress, byte[]) send}. The received 
     * data is then enclosed in a Payload object. The <code>source</code>
     * parameter is needed if we are expecting to receive packets from different
     * hosts
     * 
     * @param source source address to match, <code>null</code> to match all
     * packets (dangerous)
     * @return payload containing reconstructed data
     * @param p progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public Payload recv(SocketAddress source,
                        Progress p) throws IOException {
        DatagramPacket numPackets;
        if(source != null) {
            while((numPackets = inspect(source)) == null);
        } else {
            numPackets = read();
            source = numPackets.getSocketAddress();
        }
        Payload pp = new Payload(numPackets.getData(), 0, 
                                 numPackets.getLength(), source);
        int totalPackets = Integer.parseInt(pp.decode());
        int i = 0;
        /*
        // buffer the packets
        while(i < totalPackets) {
            Log.d(5, "recv-fill(" + UDPHost.sockAddress(source) + ": " +
                    (i+1) + " / " + totalPackets);
            i += fill(source) ? 1 : 0;
        }
        Log.d(5, "recv-fill(" + UDPHost.sockAddress(source) + ": SATISFIED");
        */
        int nr = 0;        
        byte[] payload = new byte[totalPackets * (UDP_MAX_DATAGRAM_SIZE-2)];
        for(i = 0; i < totalPackets; i++) {
            DatagramPacket packet = read(source);
            byte[] data = packet.getData();
            int len = packet.getLength();
            int order = ((int)(data[0] << 8) & 0xff00) +
                        ((int)(data[1])      & 0x00ff);
            Log.d(3, "<<< udprecv: " + UDPHost.sockAddress(source) + " " +
                     "header: " + 
                     String.format("%02X %02X", 
                     data[0], data[1]) +
                     " pieces: " + order + "/" + totalPackets + " " +
                     (len-2) + " bytes");
            System.arraycopy(data, 2, payload, 
                             (order-1)*(UDP_MAX_DATAGRAM_SIZE-2),
                             len-2);
            nr += (len-2);
            if(p != null) {
                p.copiedTotalBytes += (len-2);
            }
        }
        return new Payload(payload, 0, nr, source);
    }
    
    /**
     * Send UTF-8 string through a <b>single</b> UDP packet to be received with
     * {@link #read(DatagramSocket) read}. If the data is greater than
     * <code>UDP_MAX_DATAGRAM_SIZE</code> it will be truncated
     * 
     * @param addr <code>SocketAddress</code> of the remote host
     * @param utf8 string to write
     * @throws IOException if an I/O exception occurs 
     */
    public void write(SocketAddress addr, String utf8) throws IOException {        
        byte[] utf8Bytes = utf8.getBytes(StandardCharsets.UTF_8);
        Log.d(4, "    udpwrite: \"" + utf8 + "\"");
        s.send(new DatagramPacket(utf8Bytes, utf8Bytes.length, addr));
    }    
    
    /**
     * Receive a UDP packet
     * 
     * @return the UDP packet
     * @throws IOException if an I/O exception occurs 
     */
    public DatagramPacket read() throws IOException {       
        byte[] receiveBuffer = new byte[UDP_MAX_DATAGRAM_SIZE];
        DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        s.receive(packet);
        Log.d(4, "    udpread: bytes=" + packet.getLength());
        return packet;
    }   
    
    /**
     * Receive a UDP packet from the specified source
     * 
     * @param addr address of source
     * @return the UDP packet
     * @throws IOException if an I/O exception occurs 
     */
    public DatagramPacket read(SocketAddress addr) throws IOException {       
        DatagramPacket packet;
        Log.d(4, "> udpread: " + UDPHost.sockAddress(addr));
        while((packet = inspect(addr)) == null) {
            //Log.d(5, "    !!! udpread: " + UDPHost.sockAddress(addr) + " blocked");
        };
        Log.d(4, "< udpread: " + UDPHost.sockAddress(addr) + 
                 " bytes=" + packet.getLength() + " \"" + 
                 (new Payload(packet)).decode() + "\"");
        return packet;
    }    
}
