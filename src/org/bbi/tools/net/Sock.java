/*
 * Copyright 2016 Wira Mulia.
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
package org.bbi.tools.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import org.bbi.tools.Log;

/**
 * Some tools to transfer string and files over a socket connection
 *
 * @author wira
 */
public class Sock {   
    /**
     * Size of the buffer used to send data
     */
    private static int SEND_BUFFER_SIZE = 8192;
    
    /**
     * Size of the buffer used to receive data
     */
    private static int RECEIVE_BUFFER_SIZE = 32768;
    
    /**
     * String terminator for send and receive methods
     */
    private static byte STRING_TERMINATOR = 0;
    
    /**
     * Set a new send buffer size for the class
     * 
     * @param n New buffer size in BYTES
     */
    public static void setSendBufferSize(int n) {
        SEND_BUFFER_SIZE = n;
    }
    
    /**
     * Set a new receive buffer size for the class
     * 
     * @param n New buffer size in BYTES
     */
    public static void setReceiveBufferSize(int n) {
        RECEIVE_BUFFER_SIZE = n;
    }
    
    /**
     * Set a new string terminator 
     * 
     * @param b String termination byte
     */
    public static void setStringTerminator(byte b) {
        STRING_TERMINATOR = b;
    }        
    
    /**
     * Recursively transfer files to a client using a socket. If the file
     * is a directory, the directory will be traversed and all files found
     * will be transferred. The client must use 
     * {@link #get(Socket, String, Progress) get} to receive the files
     * 
     * @param s Socket handle to use
     * @param fileName File or directory to transfer
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs
     */
    public static void put(Socket s, String fileName, 
            Progress p) throws IOException {
        String d;
        List<FileEntry> fileList = new ArrayList<>();
        File file = new File(fileName);
        FileInputStream in;
        byte[] sendBuffer = new byte[SEND_BUFFER_SIZE];
        int nr;
        try {
            long totalBytes = 0L;
            populateFileList(file.getParentFile(), file, fileList, true);
            send(s, String.valueOf(fileList.size()));
            for(FileEntry f : fileList) {
                send(s, String.valueOf(f.getFile().length()) + " " +
                        f.getRelativePath());
                totalBytes += f.getFile().length();
            }
            send(s, String.valueOf(totalBytes));
            if(p != null) {
                p.copiedTotalBytes = 0;
                p.totalFiles = fileList.size();
                p.totalBytes = totalBytes;
            }
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
                while((nr = in.read(sendBuffer)) != -1) {
                    s.getOutputStream().write(sendBuffer, 0, nr);
                    if(p != null) {
                        p.currentFileCopied += nr;
                        p.copiedTotalBytes += nr;
                    }
                }
                in.close();
            }
            s.getOutputStream().flush();
            if(!(d = recv(s)).equals("done")) {
                Log.err("illegal termination line: " + d);
            }
        } catch(IOException ioe) {
            send(s, "-1");
        }        
    }
    
    /**
     * Traverse through a directory and build a file list
     * 
     * @param parent Parent directory to construct a relative path for the file entry
     * @param f Path to traverse
     * @param fileList A list of FileEntry that will be populated
     * @param recursive Recurse into subdirectories
     * @throws IOException if an I/O exception occurs 
     */
    public static void populateFileList(File parent, File f, 
            List<FileEntry> fileList,  boolean recursive)
            throws IOException {
        if(!f.exists()) {
            throw new IOException("unable to open " + f.getName());
        }
        if(f.isDirectory() && f.listFiles() != null) {
            for(File file : f.listFiles()) {
                if(recursive && file.isDirectory()) {
                    populateFileList(parent, file, fileList, true);
                } else {
                    fileList.add(new FileEntry(parent, file));
                }
            }
        } else {
            fileList.add(new FileEntry(parent, f));
        }
    }
    
    /**
     * Recursively create parent directories
     * 
     * @param f Highest level directory
     * @throws IOException if an I/O exception occurs 
     */
    private static void createParentDirectory(File f) throws IOException {
        if(f == null) {
            return;
        }
        
        if(f.getParentFile() != null && !f.getParentFile().exists()) {
            createParentDirectory(f.getParentFile());            
        }
        
        if(!f.exists()) {
            System.out.println("mkdir " + f.getAbsolutePath());
            f.mkdir();
        }
    }
    
    /**
     * Recursively receive multiple files over the socket. The server must use
     * {@link #put(Socket, String, Progress) put} to transfer the files
     * 
     * @param s Socket handle to use
     * @param destDir Destination directory for the received files
     * @param p Progress handle to use (can be null)
     * @throws IOException if an I/O exception occurs 
     */
    public static void get(Socket s, String destDir,
            Progress p) throws IOException {
        long startTime = System.nanoTime();
        String[] tokens;
        long currentFileCopiedBytes;
        long totalCopiedBytes = 0;
        int nr, remainingBytes, nextBytes;
        byte[] receiveBuffer = new byte[RECEIVE_BUFFER_SIZE];
        byte[] overflowBuffer = null;
        
        // get total number of files
        String numOfFilesString = recv(s);
        FileOutputStream out;
        int numOfFiles = Integer.parseInt(numOfFilesString);
        if(numOfFiles < 0) {
            System.err.println("server returned " + numOfFiles);
            return;
        }
        
        // get file names and sizes
        String[] fileNames = new String[numOfFiles];
        long[] fileSizes = new long[numOfFiles];
        for(int i = 0; i < numOfFiles; i++) {
            tokens = recv(s).split(" ", 2);
            fileNames[i] = tokens[1];
            fileSizes[i] = Long.parseLong(tokens[0]);
        }
        
        // get total number of bytes so the user knows how big the incoming
        // transmission is
        long totalBytes = Long.parseLong(recv(s));
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
            createParentDirectory(f.getParentFile());
            Log.d(0, "get " + String.format("[%1$15s]", 
                    NumberFormat.getIntegerInstance().format(fileSizes[i])) + " "
                    + destDir + File.separator + fileNames[i]);
            currentFileCopiedBytes = 0;
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
                    (nr = s.getInputStream().read(receiveBuffer)) != -1) {
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
                if(p != null) {
                    p.currentFileCopied = currentFileCopiedBytes;
                    p.copiedTotalBytes = totalCopiedBytes;
                }
            }
            out.close();
        }
        send(s, "done");
            
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
     * Send a string through the socket as <code>UTF-8</code> terminated with 
     * <code>STRING_TERMINATOR</code>
     * 
     * @param s Socket handle to use
     * @param data String data to send
     * @throws IOException 
     */
    public static void send(Socket s, String data) throws IOException {
        Log.d(1, "send: \"" + data + "\"");
        OutputStream out = s.getOutputStream();
        out.write(data.getBytes("UTF-8"));
        out.write(STRING_TERMINATOR);
        out.flush();
    }
    
    /**
     * Block and receive <code>UTF-8</code> string terminated with 
     * <code>STRING_TERMINATOR</code>. The function will not read off the input 
     * stream beyond the terminator, making it really easy to use for 
     * interactive data exchange. The function reads in the stream in per-byte 
     * fashion instead of buffering up data off the stream in memory.
     * 
     * @param s Socket handle to use
     * @return String representation of the received data
     * @throws IOException 
     */
    public static String recv(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        int bufferSizeMultiplier = 1;
        byte[] buffer = new byte[RECEIVE_BUFFER_SIZE];
        int nr = 0;
        int d;
        
        while((d = in.read()) != STRING_TERMINATOR && d != -1) {
            buffer[nr] = (byte) d;
            nr++;
            if(nr == buffer.length) {
                bufferSizeMultiplier++;
                byte[] newBuffer = new byte[bufferSizeMultiplier*RECEIVE_BUFFER_SIZE];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                buffer = newBuffer;
            }
        }
        
        byte[] string = new byte[nr];
        System.arraycopy(buffer, 0, string, 0, nr);
        
        if(d == -1) {
            throw new IOException("connection lost before string termination");
        }
        
        Log.d(1, "recv: \"" + new String(string, "UTF-8") + "\"");
        return new String(string, 0, nr, "UTF-8");
    }    
    
    /**
     * A utility class that describes a file and its relation to an arbitrary
     * parent
     */
    static class FileEntry {
        private final String fileName;
        private final String relativePath;
        private final String parentPath;
        private final File f;
        
        /**
         * Construct a file entry with the <code>File</code> handle and the 
         * handle to the arbitrary parent. The parent is used to construct a 
         * relative path string
         * 
         * @param parent Arbitrary parent directory level of the file
         * @param file <code>File</code> handle
         */
        public FileEntry(File parent, File file) {
            this.f = file;
            fileName = file.getName();
            if(parent != null) {
                parentPath = parent.getAbsolutePath();
            } else {
                parentPath = ".";
            }
            relativePath = parent != null ? 
                    file.getAbsolutePath().substring(parentPath.length()).substring(1) :
                    "./" + file.getName();
        }
        
        /**
         * Get file name of the entry
         * 
         * @return File name
         */
        public String getName() {
            return fileName;
        }
        
        /**
         * Get file path relative to the specified parent. E.g. if the file
         * is <code>/home/user/downloads/test.zip</codE> and parent is 
         * <code>/home/user</code> this method will return 
         * <code>downloads/test.zip</code>
         * 
         * @return File path relative to the specified parent
         */
        public String getRelativePath() {
            return relativePath;
        }
        
        /**
         * Get parent path specified by the user
         * 
         * @return Parent path
         */
        public String getParentPath() {
            return parentPath;
        }
        
        /**
         * Get file handle
         * 
         * @return File handle
         */
        public File getFile() {
            return f;
        }
    }
}
