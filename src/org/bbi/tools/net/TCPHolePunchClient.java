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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bbi.tools.Log;
import static org.bbi.tools.net.Sock.recv;
import static org.bbi.tools.net.Sock.send;
import static org.bbi.tools.net.Sock.recv;
import static org.bbi.tools.net.Sock.send;

/**
 *
 * @author wira
 */
public class TCPHolePunchClient {
    private ServerSocket p2pListener;
    private Socket holePunchServer;
    private Socket p2pClient;
    private boolean p2pClientConnected = false;
    private boolean p2pListenerActive = false;
    private int sharedPort;
    private int port;
    private boolean quitServer = false;
    private long randomID;
    private ExecutorService pool;
    private String[] clientsInfo;
    
    public static final long RETRY_DELAY_MS = 1000;
    public static final int MAX_TRIES = 20;
    
    public void connect(String host, int port) {
        pool = Executors.newFixedThreadPool(8);
        this.port = port;
        try {
            String d;
            String[] tokens;
            Log.d(0, "connecting to " + host + ":" + port);
            holePunchServer = new Socket();
            holePunchServer.setReuseAddress(true);
            //holePunchServer.bind(new InetSocketAddress(port));
            holePunchServer.connect(new InetSocketAddress(host, port));
            d = recv(holePunchServer);
            tokens = d.split("\\s+", 2);
            if(!tokens[0].equals("magix!server")) {
                Log.err("unknown protocol");
                return;
            } else {
                randomID = Long.parseLong(tokens[1], 16);
            }
            Log.d(0, "connected, id=" + String.format("%08X", randomID));
            sharedPort = holePunchServer.getLocalPort();
            send(holePunchServer, 
                    holePunchServer.getLocalAddress().getHostAddress() +
                    ":" + holePunchServer.getLocalPort());
            send(holePunchServer, "list");
            int numOfConnectedClients = Integer.parseInt(recv(holePunchServer).split("\\s+")[1]);
            clientsInfo = new String[numOfConnectedClients];
            for(int i = 0; i < numOfConnectedClients; i++) {
                clientsInfo[i] = recv(holePunchServer);
            }
            
            // execute server stream handler
            pool.execute(new PunchHoleServerLocalConsole());
            pool.execute(new PunchHoleServerHandler());
        } catch(Exception e) {
            Log.err("connect exception: " + e);
        }
    }    
    
    public void quit() {
        try {
            holePunchServer.close();
            p2pListener.close();
        } catch(Exception e) {
            
        }
        quitServer = true;
        pool.shutdown();
    }
    
    public Socket getP2PClient() {
        return p2pClient;
    }
    
    public String getClientInfo(String randomID) {
        String tokens[];
        for(int i = 0; i < clientsInfo.length; i++) {
            tokens = clientsInfo[i].split("\\s+");
            if(tokens[0].equals(randomID)) {
                return clientsInfo[i];
            }
        }
        
        return null;
    }
    
    // waits and see if a peer wants to connect
    class PunchHoleServerHandler implements Runnable {
        @Override
        public void run() {
            Log.d(0, "server handler: run");
            String d;
            String[] tokens;
            long id;
            try {
                while(!quitServer && !p2pClientConnected) {
                    d = recv(holePunchServer);
                    tokens = d.trim().split("\\s+", 2);
                    if(d.startsWith("request")) {
                        // find index
                        id = Long.parseLong(d.trim().split("\\s+")[1], 16);
                        Log.d(0, "incoming peering request from " + String.format("%08X", id));
                        for(int i = 0; i < clientsInfo.length; i++) {
                            long listID = Long.parseLong(clientsInfo[i].trim().split("\\s+")[0], 16);
                            if(listID == id) {
                                // pool.execute(new OutboundToPeer(i));
                                pool.execute(new P2PConnectionListener());
                                break;
                            }
                        }
                    } else if(d.startsWith("list")) {
                        int numOfConnectedClients = Integer.parseInt(tokens[1]);
                        clientsInfo = new String[numOfConnectedClients];
                        for(int i = 0; i < numOfConnectedClients; i++) {
                            clientsInfo[i] = recv(holePunchServer);
                        }
                    }
                }
            } catch(IOException ioe) {
                Log.err("server handler: " + ioe);
            }
            Log.d(0, "server handler: exit");
        }
    }
    
    // periodically try to connect to a peer
    class OutboundToPeer implements Runnable {
        private final int clientIndex;
        
        public OutboundToPeer(int d) {
            this.clientIndex = d;
        }
        
        @Override
        public void run() {
            
            String[] tokens;
            String d;
            int tryCount;
            long remoteClientID;
            if(clientIndex < 0 || clientIndex >= clientsInfo.length) {
                Log.err("invalid client index");
                return;
            }
            try {
                tokens = clientsInfo[clientIndex].split("\\s+");
                remoteClientID = Long.parseLong(tokens[0], 16);  
                Log.d(0, "outbound to " + clientIndex +  " (" +
                        String.format("%08X", remoteClientID) + ")");
                
                if(!p2pListenerActive) {
                    // execute peer listener
                    // pool.execute(new P2PConnectionListener());
                    // Thread.sleep(1000);
                }

                tokens[2] = tokens[2].substring(6);
                InetSocketAddress remoteAddress = new InetSocketAddress(
                        tokens[1].split(":")[0],
                        Integer.parseInt(tokens[1].split(":")[1])
                );
                InetSocketAddress privateAddress = new InetSocketAddress(
                        tokens[2].split(":")[0],
                        Integer.parseInt(tokens[2].split(":")[1])
                );
                // let's check if our remote host happens to be in
                // the same NAT as us
                /*
                Socket s = new Socket();
                s.setReuseAddress(true);
                try {
                    s.bind(new InetSocketAddress(sharedPort));
                    Log.d(0, "connecting to private address");
                    s.connect(privateAddress);
                    d = recv(s);
                    if(d.equals("magix!client " + 
                            String.format("%08X", remoteClientID))) {
                        // success!
                        p2pClientConnected = true;
                        p2pClient = s;
                    }
                } catch(IOException ioe) {
                    // no good
                    Log.err("failed to connect to private: " + ioe);
                    s.close();
                }
                */
                tryCount = 0;
                Socket remoteSocket = new Socket();
                while(!p2pClientConnected && tryCount < MAX_TRIES) {
                    tryCount++;
                    Log.d(0, tryCount + ": connecting to " + 
                             remoteAddress.getAddress().getHostAddress() +
                             ":" + remoteAddress.getPort() + " from port " +
                             sharedPort);
                    try {
                        remoteSocket = new Socket();
                        remoteSocket.setReuseAddress(true);
                        remoteSocket.bind(new InetSocketAddress(sharedPort));
                        remoteSocket.connect(remoteAddress);
                        d = recv(remoteSocket);
                        if(d.equals("magix!client " + 
                                String.format("%08X", remoteClientID))) {
                            // success!
                            p2pClientConnected = true;
                            p2pClient = remoteSocket;
                        }
                    } catch(IOException ioe) {
                        // no good
                        Log.err("failed to connect to remote: " + ioe);
                        remoteSocket.close();
                    }
                    try {
                        remoteSocket.close();
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch(InterruptedException | IOException e) {

                    }
                }
            } catch(Exception e) {
                Log.err("outbound exception " + e);
            }
            Log.err("outbound: exit");
        }
    }
    
    class PunchHoleServerLocalConsole implements Runnable {
        @Override
        public void run() {
            Log.d(0, "console active");
            String[] tokens;
            int clientIndex;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String d;
            try {
                while((d = in.readLine()) != null) {
                    tokens = d.trim().split("\\s+", 2);
                    if(d.equals("quit")) {
                        holePunchServer.close();
                        break;
                    }
                    // initiate a p2p connection with a remote host
                    if(tokens[0].equals("connect")) {                        
                        clientIndex = Integer.parseInt(tokens[1]);
                        // tell server who we are connecting to and sayonara
                        try {
                            send(holePunchServer, "connect " +
                                    clientsInfo[clientIndex].split("\\s+")[0]);
                            // holePunchServer.close();
                        } catch(IOException ioe) {
                            Log.err("unable to close server socket: " + ioe);
                        }
                        pool.execute(new OutboundToPeer(clientIndex));
                        continue;
                        
                    // refresh local copy of peer list
                    } else if(d.equals("list")) {
                        send(holePunchServer, "list");                        
                    } else if(tokens[0].equals("direct")) {
                        send(holePunchServer, tokens[1]);
                    }
                }
            } catch(IOException ioe) {
                Log.err("fatal exception: " + ioe);
            }
            // quitServer = true;
            Log.d(0, "console: exit");
        }
    }
    
    class P2PConnectionListener implements Runnable {
        @Override
        public void run() {
            Log.d(0, "P2PConnectionListener: run and serve on " + sharedPort);
            try {
                p2pListenerActive = true;
                p2pListener = new ServerSocket();
                p2pListener.setReuseAddress(true);
                p2pListener.bind(new InetSocketAddress(sharedPort));
                while(!p2pClientConnected) {
                    p2pClient = p2pListener.accept();
                    p2pClientConnected = true;
                    InetSocketAddress addr = (InetSocketAddress) p2pClient.getRemoteSocketAddress();
                    Log.d(0, "new p2p connection: " +
                            addr.getAddress().getHostAddress() + ":" + addr.getPort());
                    // success!
                    send(p2pClient, "magix!client " + String.format("%08X", randomID));                    
                }
            } catch(IOException ioe) {
                Log.err("failed to open P2P listener: " + ioe);
            }
            Log.d(1, "P2PConnectionListener: exit");
        }
    }
}
