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
import java.net.SocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bbi.tools.Log;
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
    private int sharedPort;
    private boolean quit = false;
    private long randomID;
    private ExecutorService pool;
    private String[] clientsInfo;
    
    public static final long RETRY_DELAY_MS = 1000;
    
    public void connect(String host, int port) {
        pool = Executors.newFixedThreadPool(8);
        try {
            String d;
            String[] tokens;
            Log.d(0, "connecting to " + host + ":" + port);
            holePunchServer = new Socket();
            holePunchServer.setReuseAddress(true);
            // holePunchServer.bind(new InetSocketAddress(port));
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
            int numOfConnectedClients = Integer.parseInt(recv(holePunchServer));
            clientsInfo = new String[numOfConnectedClients];
            for(int i = 0; i < numOfConnectedClients; i++) {
                clientsInfo[i] = recv(holePunchServer);
            }
            pool.execute(new P2PConnectionListener());
            
            // execute server stream handler
            pool.execute(new PunchHoleServerHandler());
            Log.d(0, "threads running");
        } catch(Exception e) {
            Log.err("exception:" + e);
        }
    }    
    
    public void quit() {
        try {
            holePunchServer.close();
            p2pListener.close();
        } catch(Exception e) {
            
        }
        quit = true;
        pool.shutdown();
    }
    
    public Socket getP2PClient() {
        return p2pClient;
    }
    
    class PunchHoleServerHandler implements Runnable {
        @Override
        public void run() {
            Log.d(0, "console mode");
            String[] tokens;
            int numOfConnectedClients, clientID, tryCount;
            long remoteClientID;
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String d;
            try {
                while((d = in.readLine()) != null) {
                    if(d.equals("quit")) {
                        break;
                    }
                    // initiate a p2p connection with a remote host
                    if(d.startsWith("connect")) {
                        tokens = d.trim().split("\\s+");
                        clientID = Integer.parseInt(tokens[1]);
                        if(clientID < 0 || clientID > clientsInfo.length) {
                            break;
                        }
                        tokens = clientsInfo[clientID].split("\\s+");
                        remoteClientID = Long.parseLong(tokens[0], 16);
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
                        Socket s = new Socket();
                        s.setReuseAddress(true);
                        s.bind(new InetSocketAddress(sharedPort));
                        try {
                           
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
                        }
                        tryCount = 0;
                        while(!quit && !p2pClientConnected) {
                            Socket remoteSocket = new Socket();
                            s.setReuseAddress(true);
                            s.bind(new InetSocketAddress(sharedPort));
                            try {
                                tryCount++;
                                Log.d(0, tryCount + ": connecting to " + 
                                         remoteAddress.getAddress().getHostAddress() +
                                         ":" + remoteAddress.getPort());
                                s.connect(remoteAddress);
                                d = recv(s);
                                if(d.equals("magix!client " + 
                                        String.format("%08X", remoteClientID))) {
                                    // success!
                                    p2pClientConnected = true;
                                    p2pClient = remoteSocket;
                                }
                            } catch(IOException ioe) {
                                // no good
                                Log.d(0, "failed: " + ioe);
                            }
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch(InterruptedException ie) {
                                
                            }
                        }
                    }
                    send(holePunchServer, d);
                    numOfConnectedClients = Integer.parseInt(recv(holePunchServer));
                    clientsInfo = new String[numOfConnectedClients];
                    for(int i = 0; i < numOfConnectedClients; i++) {
                        clientsInfo[i] = recv(holePunchServer);
                    }
                }
            } catch(IOException ioe) {
                Log.err("fatal exception: " + ioe);
            }
            quit();
            Log.d(0, "console: exit");
        }
    }
    
    class P2PConnectionListener implements Runnable {
        @Override
        public void run() {
            Log.d(0, "P2PConnectionListener: run and serve on " + sharedPort);
            try {
                p2pListener = new ServerSocket();
                p2pListener.setReuseAddress(true);
                p2pListener.bind(new InetSocketAddress(sharedPort));
                while(!quit) {
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
