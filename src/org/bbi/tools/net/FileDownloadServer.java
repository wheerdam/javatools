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
package org.bbi.tools.net;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.bbi.tools.Log;
import static org.bbi.tools.net.Sock.put;
import static org.bbi.tools.net.Sock.recv;
import static org.bbi.tools.net.Sock.send;
import static org.bbi.tools.net.Sock.populateFileList;

/**
 * An interactive file download server
 *
 * @author wira
 */
public class FileDownloadServer {
/**
     * Interactive file server. The client must send the 'quit' command for the
     * server to escape this mode
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
            line = recv(s);
            tokens = line.split(" ", 2);
            File f;
            List<Sock.FileEntry> fileList;
            try {
                switch(tokens[0]) {
                    case "get":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            send(s, "-2");
                            break;
                        }
                        put(s, effectivePath, p);
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
                            send(s, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        for(Sock.FileEntry e : fileList) {
                            if(e.getFile().isDirectory()) {
                                send(s, String.format("%1$15s", 
                                        "") + "   " + e.getName());
                            }
                        }
                        for(Sock.FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                send(s, String.format("%1$15s", 
                                        e.getFile().length()) + "   " + e.getName());
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
                            send(s, "illegal path");
                            break;
                        }
                        f = new File(effectivePath);
                        fileList = new ArrayList<>();
                        populateFileList(f.getParentFile(), f, fileList, false);
                        long size = 0;
                        for(Sock.FileEntry e : fileList) {
                            if(!e.getFile().isDirectory()) {
                                size += e.getFile().length();
                            }
                        }
                        send(s, String.valueOf(size));
                        break;
                    case "cat":
                        if(tokens.length < 2) {
                            break;
                        }
                        effectivePath = tokens[1].startsWith("/") ? tokens[1] :
                                currentPath + tokens[1];
                        if(!effectivePath.startsWith(root)) {
                            send(s, "illegal path");
                            break;
                        }
                        send(s, new String(
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
                                send(s, "illegal path");
                                break;
                            }
                            currentPath = f.getCanonicalPath() + "/";
                            send(s, currentPath);
                        }
                        break;
                    case "pwd":
                        send(s, currentPath);
                        break;
                }               
            } catch(Exception e) {
                send(s, e.toString());
            }
        }
    }        
}
