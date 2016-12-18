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
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.bbi.tools.FileEntry;
import org.bbi.tools.Log;
import static org.bbi.tools.FileEntry.populateFileList;

/**
 * Interactive TCP and UDP file download servers
 *
 * @author wira
 */
public class FileDownloadServer {
    /**
     * UDP interactive file server
     * 
     * @param s UDP socket handle to use
     * @param root Root directory, client won't be able to access a higher level
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void wait(DatagramSocket s, String root, Progress p)
            throws IOException {
        String line;
        String addr;
        String[] tokens;
        String effectivePath;
        InetSocketAddress source;
        Payload payload;
        SockUDP sock = new SockUDP(s);
        Map<String, String> clientPaths = new HashMap<>();
        List<SocketAddress> activeClients = new ArrayList<>();
        File rootDirectory = new File(root);
        if(!rootDirectory.exists()) {
            Log.err(root + " does not exist");
            return;
        }
        if(!rootDirectory.isDirectory()) {
            Log.err(root + " is not a directory");
            return;
        }
        String rootPath =  rootDirectory.getCanonicalPath() + "/";
        String currentPath;
        boolean quit = false;
        Log.d(0, "udp listening");
        while(!quit) {
            DatagramPacket packet;
            for(SocketAddress client : activeClients) {
                Log.d(0, UDPHost.sockAddress(client));
            }
            while((packet = sock.listen(activeClients)) == null) {
                for(String str : sock.dumpBuffer()) {
                    Log.d(0, str);
                }
                try {
                    // give other threads 10 seconds to consume the buffer
                    Thread.sleep(10000);
                    sock.clearBuffer(10000);
                } catch(Exception e) {
                    
                }
            }
            payload = new Payload(packet);
            activeClients.add(payload.getRemote());
            line = payload.decode();
            if(line.endsWith("\n")) {
                line = line.substring(0, line.length()-1);
            }
            source = (InetSocketAddress) payload.getRemote();
            addr = UDPHost.sockAddress(source);
            if(!clientPaths.containsKey(addr)) {
                clientPaths.put(addr, rootPath);
                Log.d(1, "new client: " + addr);
            }
            currentPath = clientPaths.get(addr);
            tokens = line.split(" ", 2);
            File f;
            List<FileEntry> fileList;
            Log.d(0, "command: \"" + tokens[0] + "\"");
            try {
                switch(tokens[0]) {
                    case "get":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            udputf8(sock, source, "-2");
                            break;
                        }
                        sock.putf(source, effectivePath, p);
                        break;
                    case "quit":
                        clientPaths.remove(addr);
                        Log.d(1, "removing from known list " + addr);
                        break;
                    case "ls":
                        if(tokens.length < 2) {
                            effectivePath = currentPath;
                        } else {
                            effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        }
                        if(!effectivePath.startsWith(root)) {
                            udputf8(sock, source, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        udputf8(sock, source, effectivePath + ": " + 
                                           fileList.size() + " files");
                        for(FileEntry e : fileList) {
                            if(e.getFile().isDirectory()) {
                                udputf8(sock, source, String.format("%1$15s", 
                                                "[dir]") + "  " + e.getName());
                            }
                        }
                        for(FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                udputf8(sock, source, String.format("%1$15s", 
                                                e.getFile().length()) + "  " +
                                                        e.getName());
                            }
                        }
                        break;
                    case "size":
                        if(tokens.length < 2) {
                            effectivePath = currentPath;
                        } else {
                            effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        }
                        if(!effectivePath.startsWith(root)) {
                            udputf8(sock, source, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        long size = 0;
                        for(FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                size += e.getFile().length();
                            }
                        }
                        udputf8(sock, source, String.valueOf(size));
                        break;
                    case "cat":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            udputf8(sock, source, "illegal path");
                            break;
                        }
                        udputf8(sock, source, new String(
                                Files.readAllBytes(Paths.get(effectivePath)),
                                "UTF-8"));
                        break;
                    case "cd":
                        if(tokens.length < 2) {
                            break;
                        }
                        if(tokens[1].startsWith("/")) {
                            f = new File(tokens[1]);
                        } else if(tokens[1].equals("..")) {
                            File parent = (new File(currentPath)).getParentFile();
                            if(parent != null) {
                                f = parent;
                            } else {
                                f = new File(currentPath);
                            }
                        } else {
                            f = new File(currentPath + tokens[1]);
                        }
                        if(f.exists() && f.isDirectory()) {
                            if(!(f.getCanonicalPath() + "/").startsWith(root)) {
                                udputf8(sock, source, "illegal path");
                                break;
                            }
                            currentPath = f.getCanonicalPath() + "/";
                            // update current path for this host
                            clientPaths.put(addr, currentPath);
                            udputf8(sock, source, currentPath);
                        }
                        break;
                    case "pwd":
                        udputf8(sock, source, currentPath);
                        break;
                }
                Log.d(3, "command done");
            } catch(Exception e) {
                // write(s, source, e.toString());
                Log.err("udp wait: " + e.toString());
                if(Log.debugLevel > 1) {
                    e.printStackTrace();
                }
            }
            activeClients.remove(payload.getRemote());
        }
    }
    
    private static void udputf8(SockUDP sock, SocketAddress source, String str) 
            throws IOException {
        sock.put(source, str.getBytes(StandardCharsets.UTF_8), null);
    }
    
    /**
     * TCP interactive file server. The client must write the 'quit' command for 
     * the server to escape this mode
     * 
     * @param s Socket handle to use
     * @param root Root directory, client won't be able to access a higher level
     * @param p Progress handle to use
     * @throws IOException 
     */
    public static void wait(Socket s, String root, Progress p)
            throws IOException 
    {
        String line;
        String[] tokens;
        String effectivePath;
        File rootDirectory = new File(root);
        if(!rootDirectory.exists()) {
            Log.err(root + " does not exist");
            return;
        }
        if(!rootDirectory.isDirectory()) {
            Log.err(root + " is not a directory");
            return;
        }
        String currentPath = rootDirectory.getCanonicalPath() + "/";
        boolean quit = false;
        while(!quit) {
            line = Sock.read(s);
            tokens = line.split(" ", 2);
            File f;
            List<FileEntry> fileList;
            try {
                switch(tokens[0]) {
                    case "get":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            Sock.write(s, "-2");
                            break;
                        }
                        Sock.put(s, effectivePath, p);
                        break;
                    case "quit":
                        quit = true;
                        break;
                    case "ls":
                        if(tokens.length < 2) {
                            effectivePath = currentPath;
                        } else {
                            effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        }
                        if(!effectivePath.startsWith(root)) {
                            Sock.write(s, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        Sock.write(s, effectivePath + ": " + 
                                      fileList.size() + " files");
                        for(FileEntry e : fileList) {
                            if(e.getFile().isDirectory()) {
                                Sock.write(s, String.format("%1$15s", 
                                        "[dir]") + "  " + e.getName());
                            }
                        }
                        for(FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                Sock.write(s, String.format("%1$15s", 
                                        e.getFile().length()) + "  " + e.getName());
                            }
                        }
                        break;               
                    case "size":
                        if(tokens.length < 2) {
                            effectivePath = currentPath;
                        } else {
                            effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        }
                        if(!effectivePath.startsWith(root)) {
                            Sock.write(s, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        long size = 0;
                        for(FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                size += e.getFile().length();
                            }
                        }
                        Sock.write(s, String.valueOf(size));
                        break;
                    case "cat":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            Sock.write(s, "illegal path");
                            break;
                        }
                        Sock.write(s, new String(
                                Files.readAllBytes(Paths.get(effectivePath)),
                                "UTF-8"));
                        break;
                    case "cd":
                        if(tokens.length < 2) {
                            break;
                        }
                        if(tokens[1].startsWith("/")) {
                            f = new File(tokens[1]);
                        } else if(tokens[1].equals("..")) {
                            File parent = (new File(currentPath)).getParentFile();
                            if(parent != null) {
                                f = parent;
                            } else {
                                f = new File(currentPath);
                            }
                        } else {
                            f = new File(currentPath + tokens[1]);
                        }
                        if(f.exists() && f.isDirectory()) {
                            if(!(f.getCanonicalPath() + "/").startsWith(root)) {
                                Sock.write(s, "illegal path");
                                break;
                            }
                            currentPath = f.getCanonicalPath() + "/";
                            Sock.write(s, currentPath);
                        }
                        break;
                    case "pwd":
                        Sock.write(s, currentPath);
                        break;
                }               
            } catch(Exception e) {
                Sock.write(s, e.toString());
            }
        }
    }        
}
