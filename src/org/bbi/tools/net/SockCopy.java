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
import java.util.ArrayList;
import java.util.List;

/**
 * Some tools to transfer string and files over a socket connection
 *
 * @author wira
 */
public class SockCopy {   
    /**
     * Size of the buffer used to send data
     */
    private static int SEND_BUFFER_SIZE = 8192;
    
    /**
     * Size of the buffer used to receive data
     */
    private static int RECEIVE_BUFFER_SIZE = 8192;
    
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
     * Interactive file server. The client must send the 'quit' command for the
     * server to escape this mode
     * 
     * @param s Socket handle to use
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void wait(Socket s, Progress p) throws IOException {
        String line;
        String[] tokens;
        String currentPath = "/";
        String effectivePath;
        boolean quit = false;
        while(!quit) {
            line = recv(s);
            tokens = line.split(" ", 2);
            File f;
            List<FileEntry> fileList;
            switch(tokens[0]) {
                case "get":
                    if(tokens.length < 2) {
                        break;
                    }
                    effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                            currentPath + tokens[1];
                    SockCopy.putRecursive(s, effectivePath, p);
                    break;
                case "quit":
                    quit = true;
                    break;
                case "list":
                    if(tokens.length < 2) {
                        effectivePath = currentPath;
                    } else {
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                            currentPath + tokens[1];
                    }
                    f = new File(effectivePath);
                    fileList = new ArrayList<>();
                    populateFileList(f.getParentFile(), f, fileList, false);
                    for(FileEntry e : fileList) {
                        send(s, e.getName());
                    }
                    break;
                case "listdir":
                    if(tokens.length < 2) {
                        effectivePath = currentPath;
                    } else {
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                            currentPath + tokens[1];
                    }
                    f = new File(effectivePath);
                    fileList = new ArrayList<>();
                    populateFileList(f.getParentFile(), f, fileList, false);
                    for(FileEntry e : fileList) {
                        if(e.getFile().isDirectory()) {
                            send(s, e.getName());
                        }
                    }
                    break;
                case "cd":
                    if(tokens.length < 2) {
                        break;
                    }
                    if(tokens[1].startsWith("/")) {
                        f = new File(tokens[1]);
                    } else {
                        f = new File(currentPath + tokens[1]);
                    }
                    if(f.exists() && f.isDirectory()) {
                        currentPath = f.getAbsolutePath() + "/";
                    }
                    break;
                case "pwd":
                    send(s, currentPath);
                    break;
            }               
        }
    }
    
    /**
     * Transfer a file to a client through the socket. The file must be a
     * single file and can not be a directory. The client must use 
     * SockCopy.get to receive a file transferred using SockCopy.put.
     * Use SockCopy.putRecursive to transfer files recursively through the
     * socket. 
     * 
     * @param s Socket handle to use
     * @param fileName File to transfer
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void put(Socket s, String fileName, 
            Progress p) throws IOException {
        int nr;
        String line;
        byte[] sendBuffer = new byte[SEND_BUFFER_SIZE];
        File file = new File(fileName);
        FileInputStream in = new FileInputStream(file);
        OutputStream out = s.getOutputStream();
        if(p != null) {
            p.name = fileName;
        }
        long len = file.length();
        if(len < 0) {
            throw new IOException("file size too big");
        }
        byte[] size = { (byte) (len >> 56),
                        (byte) (len >> 48),
                        (byte) (len >> 40),
                        (byte) (len >> 32),
                        (byte) (len >> 24),
                        (byte) (len >> 16),
                        (byte) (len >> 8),
                        (byte) (len & 0xff)
        };
        out.write(size);
        if(p != null) {
            p.currentFileCopied = 0;
            p.currentFileSize = len;
        }
        while((nr = in.read(sendBuffer)) != -1) {
            out.write(sendBuffer, 0, nr);
            if(p != null) {
                p.currentFileCopied += nr;
                p.copiedTotalBytes += nr;
            }
        }
        out.flush();
        
        // block until client is done
        line = recv(s);
        if(!line.equals("done")) {
            System.err.println("illegal completion indicator");
        }
    }
    
    /**
     * Recursively transfer files to a client using a socket. If the file
     * is a directory, the directory will be traversed and all files found
     * will be transferred. The client must use SockCopy.getRecursive to receive
     * the files
     * 
     * @param s Socket handle to use
     * @param fileName File or directory to transfer
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void putRecursive(Socket s, String fileName, 
            Progress p) throws IOException {
        List<FileEntry> fileList = new ArrayList<>();
        File file = new File(fileName);
        try {
            long totalBytes = 0L;
            populateFileList(file.getParentFile(), file, fileList, true);
            for(FileEntry f : fileList) {
                totalBytes += f.getFile().length();
            }
            if(p != null) {
                p.copiedTotalBytes = 0;
                p.totalFiles = fileList.size();
                p.totalBytes = totalBytes;
            }
            send(s, String.valueOf(fileList.size()));
            send(s, String.valueOf(totalBytes));
            for(FileEntry f : fileList) {             
                File fileHandle = f.getFile();
                System.out.println("put " + f.getRelativePath() + 
                        " (" + fileHandle.length() + " bytes)");
                send(s, f.getRelativePath());
                put(s, f.getFile().getAbsolutePath(), p);
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
     * @throws IOException 
     */
    private static void populateFileList(File parent, File f, 
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
     * @throws IOException 
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
     * Recursively receive multiple files over the socket. The server use
     * SockCopy.putRecursive method to copy the files
     * 
     * @param s Socket handle to use
     * @param destDir Destination directory for the received files
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void getRecursive(Socket s, String destDir,
            Progress p) throws IOException {
        String name;
        String numOfFilesString = recv(s);
        int numOfFiles = Integer.parseInt(numOfFilesString);
        if(numOfFiles < 0) {
            System.err.println("server returned -1");
            return;
        }
        long totalBytes = Long.parseLong(recv(s));
        System.out.println("Files to fetch: " + numOfFiles);
        if(p != null) {
            p.copiedTotalBytes = 0;
            p.totalFiles = numOfFiles;
            p.totalBytes = totalBytes;
        }
        for(int i = 0; i < numOfFiles; i++) {
            if(p != null) {
                p.currentFileNumber = i + 1;
            }
            name = recv(s);
            File f = new File(destDir + File.separator + name);
            createParentDirectory(f.getParentFile());
            System.out.println("fetch " + destDir + File.separator + name);
            get(s, destDir + File.separator + name, p);
        }
    }    
    
    /**
     * Receive a file transferred from a server using SockCopy.put and write it out
     * to destFile
     * 
     * @param s Socket handle to use
     * @param destFile Detination file to write to
     * @param p Progress handle to use
     * @throws IOException
     * @throws ArrayIndexOutOfBoundsException 
     */
    public static void get(Socket s, String destFile, 
            Progress p) throws IOException, ArrayIndexOutOfBoundsException {
        FileOutputStream out = new FileOutputStream(destFile);
        int nr;
        int bytesRead = 0;
        long bytesToFetch = -1;
        int sizeBufferOffset = 0;
        byte[] sizeBuffer = new byte[8];
        byte[] receiveBuffer = new byte[RECEIVE_BUFFER_SIZE];
        InputStream in = s.getInputStream();
        if(p != null) {
            p.name = destFile;
        }
        while((bytesRead < bytesToFetch || bytesToFetch < 0) &&
                (nr = in.read(receiveBuffer)) != -1) {
            if(sizeBufferOffset < 8) {
                if(nr >= (8-sizeBufferOffset)) {
                    bytesRead = nr - (8-sizeBufferOffset);
                    System.arraycopy(receiveBuffer, 0,
                                     sizeBuffer, sizeBufferOffset, 8-sizeBufferOffset);
                    bytesToFetch = ((sizeBuffer[0] << 56) & 0xff00000000000000L) +
                                   ((sizeBuffer[1] << 48) & 0x00ff000000000000L) +
                                   ((sizeBuffer[2] << 40) & 0x0000ff0000000000L) +
                                   ((sizeBuffer[3] << 32) & 0x000000ff00000000L) +
                                   ((sizeBuffer[4] << 24) & 0x00000000ff000000L) +
                                   ((sizeBuffer[5] << 16) & 0x0000000000ff0000L) +
                                   ((sizeBuffer[6] << 8)  & 0x000000000000ff00L) +
                                   ((sizeBuffer[7])       & 0x00000000000000ffL);
                    if(p != null) {
                        p.currentFileSize = bytesToFetch;
                        p.currentFileCopied = bytesRead;
                        p.copiedTotalBytes += bytesRead;
                    }
                    if(bytesRead > 0) {
                        out.write(receiveBuffer, nr-bytesRead, bytesRead);
                    }
                    sizeBufferOffset = 8;
                } else {
                    System.arraycopy(receiveBuffer, 0,
                                     sizeBuffer, sizeBufferOffset, nr);
                    sizeBufferOffset += nr;
                }
            } else {
                out.write(receiveBuffer, 0, nr);
                bytesRead += nr;
                if(p != null) {
                    p.currentFileCopied = bytesRead;
                    p.copiedTotalBytes += nr;
                }
            }
        }
        out.close();
        send(s, "done");
    }
    
    /**
     * Send a string through the socket as UTF-8 terminated with STRING_TERMINATOR
     * 
     * @param s Socket handle to use
     * @param data String data to send
     * @throws IOException 
     */
    public static void send(Socket s, String data) throws IOException {
        System.out.println("send: \"" + data + "\"");
        OutputStream out = s.getOutputStream();
        out.write(data.getBytes("UTF-8"));
        out.write(STRING_TERMINATOR);
        out.flush();
    }
    
    /**
     * Block and receive UTF-8 string from the socket terminated with STRING_TERMINATOR
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
        
        System.out.println("recv: \"" + new String(string, "UTF-8") + "\"");
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
         * Construct a file entry with the File handle and the handle to the
         * arbitrary parent. The parent is used to construct a relative path
         * string
         * 
         * @param parent Arbitrary parent directory level of the file
         * @param file File handle
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
         * is /home/user/downloads/test.zip and parent is /home/user this
         * method will return downloads/test.zip
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
