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
package org.bbi.net.holepunch;

import org.bbi.net.UDPHost;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bbi.net.Payload;
import org.bbi.net.SockUDP;
import org.bbi.tools.Log;

/**
 *
 * @author wira
 */
public class UDPServer implements Runnable {
    public static final long   PURGE_INACTIVITY_MS = 60000;
    public static final long   PURGE_PERIOD_MS = 5000; // check every 5 secs
    public static final String PROTO_PREFIX = "magix01";
    
    private int port;
    private Map<String, UDPHost> clients;
    private Map<String, ClientHandler> clientHandlers;
    private boolean quit = false;
    private DatagramSocket s;
    private SockUDP sock;
    private PurgeThread t;
    private ExecutorService pool = Executors.newFixedThreadPool(8);
    
    public UDPServer(int port) {
        this.port = port;
        clients = new HashMap<>();
        clientHandlers = new HashMap<>();
        t = new PurgeThread();
    }
    
    public void quit() throws IOException {
        quit = true;
        pool.shutdown();
        t.quit();
        s.close();
    }
    
    private synchronized List<SocketAddress> getSocketAddressList() {
        List<SocketAddress> list = new ArrayList<>();
        for(UDPHost c : clients.values()) {
            list.add(c.getSocketAddress());
        }
        return list;
    }
    
    private synchronized String[] getClientList() {
        String[] list = new String[clients.size()];
        int i = 0;
        for(UDPHost c : clients.values()) {
            list[i] = String.format("%08X", c.getID()) + " " +
                      c.getPublicSocketAddress() + " " + 
                      c.getPrivateSocketAddress();
            i++;
        }
        return list;
    }
    
    private synchronized void addClient(UDPHost c) {
        clients.put(c.getPublicSocketAddress(), c);
    }
    
    private synchronized UDPHost getClient(SocketAddress socketAddress) {
        return clients.get(UDPHost.sockAddress(socketAddress));
    }
    
    private synchronized UDPHost getClientByID(long ID) {
        for(UDPHost c : clients.values()) {
            if(c.getID() == ID) {
                return c;
            }
        }
        return null;
    }
    
    private synchronized void assignRandomID(UDPHost client) {
        Random r = new Random();
        boolean ok = false;
        long id = 0;
        while(!ok) {
            id = r.nextInt() & 0xffffffffL;
            if(id == 0) {
                continue;
            }
            ok = true;
            for(UDPHost c : clients.values()) {
                if(id == c.getID()) {
                    ok = false;
                }
            }
        }
        client.assignID(id);
    }
    
    public synchronized void purgeInactiveClients() {
        String addr;
        Iterator it = clients.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, UDPHost> entry = (Map.Entry) it.next();
            UDPHost c = entry.getValue();
            if(c.getDuration() > PURGE_INACTIVITY_MS) {
                Log.d(1, this + ": removing " + c.getFormattedID() + " "
                        + c.getPublicSocketAddress());
                addr = c.getPublicSocketAddress();
                clientHandlers.get(addr).quit();
                clientHandlers.remove(addr);
                it.remove();
            }
        }
    }
    
    public synchronized void removeClient(UDPHost c) {
        Log.d(1, this + ": removing " + c.getFormattedID() + " "
                        + c.getPublicSocketAddress());
        String addr = c.getPublicSocketAddress();
        clients.remove(addr);
        clientHandlers.get(addr).quit();
        clientHandlers.remove(addr);
    }
    
    @Override
    public void run() {
        Log.d(0, this + ": run");
        try {
            s = new DatagramSocket(port);
            sock = new SockUDP(s);
        } catch(IOException ioe) {
            Log.err(this + ": failed to bind");
            return;
        }
        Log.d(0, this + ": listening");
        (new Thread(t)).start();
        Payload p = null;
        String[] tokens;
        SocketAddress addr = null;

        while(!quit) {
            try {
                DatagramPacket packet;
                while((packet = sock.listen(getSocketAddressList())) == null) {
                    //sock.clearBuffer(2000);
                }            
                tokens = (new Payload(packet)).decode().split("\\s+");
                addr = packet.getSocketAddress();
                UDPHost c = getClient(addr);
                if(c != null) {
                    Log.err("ERROR listen handling active client, shouldn't happen");
                    reply(addr, "#error something terrible has happened");
                } else if(tokens[0].equals(PROTO_PREFIX + "!register")) {
                    try {
                        c = new UDPHost(addr, tokens[1]);
                        assignRandomID(c);
                        addClient(c);
                        Log.d(1, this + ": new " + c.getFormattedID() + " "
                                 + c.getPublicSocketAddress());
                        write(addr, "#id " + c.getFormattedID()); 
                        ClientHandler handler = new ClientHandler(addr);
                        clientHandlers.put(UDPHost.sockAddress(addr), handler);
                        (new Thread(handler)).start();                        
                    } catch(Exception e) {
                        Log.err(this + ": init registration error " + e);
                        e.printStackTrace();
                        write(addr, "#error registration failed");
                    }
                } else {
                    write(addr, "#error who are you");
                }
                
            } catch(Exception e) {
                Log.err(this + ": error on receive: " + e);
                // e.printStackTrace();
            }
        }
        Log.d(0, this + ": exit");
    }
    
    public void reply(SocketAddress addr, String data) 
            throws IOException {
        Log.d(1, this + ": " + UDPHost.sockAddress(addr) + " \"" + data + "\"");
        sock.put(addr, (PROTO_PREFIX + data).getBytes(StandardCharsets.UTF_8), null);
    }
    
    public void write(SocketAddress addr, String data) 
            throws IOException {
        Log.d(1, this + ": " + UDPHost.sockAddress(addr) + " \"" + data + "\"");
        sock.write(addr, PROTO_PREFIX + data);
    }
        
    @Override
    public String toString() {
        return "UDPServer[" + port + "]";
    }
    
    class ClientHandler implements Runnable {
        private SocketAddress addr;
        private boolean quit = false;
        
        public ClientHandler(SocketAddress addr) {
            this.addr = addr;
        }
        
        public void quit() {
            quit = true;
        }
        
        @Override 
        public void run() {
            Payload command;
            String[] tokens;
            UDPHost c = null;
            Log.d(0, this + ": run");
            while(!quit) {
                try {
                    // ALERT!!! RESOURCE LEAK!!! ALERT!!!
                    // this thing can get stuck forever if the client doesn't 
                    // quit cleanly
                    command = new Payload(sock.read(addr));
                    c = getClient(addr);
                    if(c != null) {
                        c.updateLastAccessed();
                    } else {
                        write(addr, "#error timed out");
                        quit = true;
                        break;
                    }
                    Log.d(3, this + ": \"" + command.decode() + "\"");
                    tokens = command.decode().split("\\s+");
                    switch(tokens[0]) {
                        /*
                        case PROTO_PREFIX + "!register":
                            Log.d(1, this + ": register request from " + 
                                     UDPHost.sockAddress(addr));
                            try {
                                String privateSocketAddress = tokens[1];
                                if(c != null) {
                                    write(addr, "#error already registered");
                                } else {
                                    c = new UDPHost(addr, privateSocketAddress);
                                    assignRandomID(c);
                                    addClient(c);
                                    Log.d(1, this + ": new " + c.getFormattedID() + " "
                                             + c.getPublicSocketAddress());
                                    write(addr, "#id " + c.getFormattedID());
                                }
                            } catch(NumberFormatException | ArrayIndexOutOfBoundsException e) {
                                write(addr, "#error registration failed");
                            }
                            break;
                        */
                        case PROTO_PREFIX + "!list":
                            String[] list = getClientList();
                            StringBuilder str = new StringBuilder();
                            for(String l : list) {
                                str.append(l);
                                str.append("\n");
                            }
                            if(str.length() > 0) {
                                str.deleteCharAt(str.length()-1);
                                write(addr, "#list");
                                reply(addr, "#ok " + list.length + "\n"
                                               + str.toString());
                            } else {
                                write(addr, "#error no clients");
                            }                        
                            break;
                        case PROTO_PREFIX + "!request":
                            if(c == null) {
                                write(addr, "#error not registered");
                                break;
                            }
                            long reqID = Long.parseLong(tokens[1], 16);
                            UDPHost peer = getClientByID(reqID);
                            if(peer == null) {
                                write(addr, "#error no such peer");
                            } else {
                                write(addr, "#ok");
                                write(peer.getSocketAddress(), 
                                      "#request " + c.getFormattedID());
                            }
                            break;
                        case PROTO_PREFIX + "!ping":
                            write(addr, "#pong");
                            break;
                        case PROTO_PREFIX + "!quit":
                            if(c == null) {
                                write(addr, "#error not registered");
                            } else {
                                write(addr, "#ok");
                                quit = true;
                            }
                            break;
                        default:
                            write(addr, "#error");
                    }
                } catch(IOException e) {
                    Log.d(0, this + ": I/O fatal exception " + e);
                    if(c != null) {
                        removeClient(c);
                    }
                    quit = true;
                } catch(Exception e) {
                    e.printStackTrace();
                    Log.d(0, this + ": exception " + e);
                }
            }
            if(c != null) {
                removeClient(c);
            }
            Log.d(0, this + ": exit");
        }
        
        @Override
        public String toString() {
            return "UDPServer$ClientHandler[" + UDPHost.sockAddress(addr) + "]";
        }
    }
    
    class PurgeThread implements Runnable {
        private boolean stop = false;
        
        @Override
        public void run() {
            while(!stop) {
                try {
                    purgeInactiveClients();
                    Thread.sleep(PURGE_PERIOD_MS);
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
        public void quit() {
            stop = true;
        }
    }                
}
