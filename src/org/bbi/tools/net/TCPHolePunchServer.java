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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bbi.tools.Log;
import static org.bbi.tools.net.Sock.recv;
import static org.bbi.tools.net.Sock.send;

/**
 *
 * @author wira
 */
public class TCPHolePunchServer implements Runnable {
    private final List<Client> clientList;
    private final int port;
    private boolean quit = false;
    private final ExecutorService pool;
    
    public TCPHolePunchServer(int port) {
        pool = Executors.newFixedThreadPool(8);
        clientList = new ArrayList<>();
        this.port = port;
    }
    
    @Override
    public void run() {
        try {
            Log.d(0, "server: run");
            ServerSocket ss = new ServerSocket();
            ss.bind(new InetSocketAddress(port));
            while(!quit) {
                Client c = new Client(ss.accept());
                addClient(c);
                Log.d(0, "new connection");
                pool.execute(c);
            }
            try {
                for(Client c : clientList) {
                    c.disconnect();
                }                
            } catch(IOException ioe) {
                Log.err("failed to close socket: " + ioe);
            }
            try {
                ss.close();
            } catch(IOException ioe) {
                Log.err("exception while shutting down server: " + ioe);
            }
        } catch(IOException ioe) {
            Log.err("failed to open server: " + ioe);
        }
        Log.d(0, "server: exit");
    }
    
    public void quit() {
        quit = true;
    }
    
    private synchronized void addClient(Client c) {
        clientList.add(c);
    }
    
    private synchronized void removeClient(Client c) {
        clientList.remove(c);
    }
    
    private synchronized boolean doesIDExist(long id) {
        for(Client c : clientList) {
            if(id == c.getRandomID()) {
                return true;
            }
        }
        
        return false;
    }
    
    private synchronized String[] getClientsInfo() {
        String[] list = new String[clientList.size()];
        int i = 0;
        for(Client c : clientList) {
            list[i] = String.format("%08X", c.getRandomID()) + " ";
            list[i] += c.getPublicSocketAddress().getAddress().getHostAddress() + ":" +
                       c.getPublicSocketAddress().getPort() + " local=" +
                       c.getPrivateAddress().getAddress().getHostAddress() + ":" +
                       c.getPrivateAddress().getPort() + " inetAddress=" +
                       c.getPublicAddress().toString();
            i++;
        }
        return list;
    }
    
    class Client implements Runnable {
        private final Socket s;
        private InetAddress publicAddress;
        private InetSocketAddress publicSocketAddress;
        private InetSocketAddress privateAddress;
        private boolean disconnect = false;
        private long randomID;
        
        public Client(Socket s) {
            this.s = s;
            randomID = -1;
        }
        
        public long getRandomID() {
            return randomID;
        }
        
        public InetAddress getPublicAddress() {
            return publicAddress;
        }
        
        public InetSocketAddress getPublicSocketAddress() {
            return publicSocketAddress;
        }
        
        public InetSocketAddress getPrivateAddress() {
            return privateAddress;
        }

        @Override
        public void run() {
            String d;
            String[] tokens;
            long tempID;
            publicSocketAddress = (InetSocketAddress) s.getRemoteSocketAddress();
            publicAddress = s.getInetAddress();
            try {
                Log.d(0, "assigning random ID");
                do {
                    tempID = (long) ((new Random()).nextInt()) & 0xffffffffL;
                } while(doesIDExist(tempID));
                randomID = tempID;
                Log.d(0, "sending random ID");
                send(s, "magix!server " + String.format("%08X", randomID));
                d = recv(s);
                tokens = d.trim().split(":", 2);
                privateAddress = new InetSocketAddress(
                        tokens[0], Integer.parseInt(tokens[1]));
                Log.d(0, "new connection: " +
                         publicSocketAddress.getAddress().getHostAddress() + ":" +
                         publicSocketAddress.getPort() + " local=" +
                         privateAddress.getAddress().getHostAddress() + ":" +
                         privateAddress.getPort() + " inetAddress=" +
                         publicAddress.toString()
                );
                while(!disconnect) {
                    try {
                        d = recv(s);
                        tokens = d.trim().split("\\s+");
                        switch(tokens[0]) {
                            case "list":
                                String[] clientsInfo = getClientsInfo();
                                send(s, String.valueOf(clientsInfo.length));
                                for(String l : getClientsInfo()) {
                                    send(s, l);
                                }
                                break;
                        }
                    } catch(IOException ioe) {
                        Log.err("data read failed: " + ioe);
                        disconnect = true;
                    }
                }
            } catch(IOException ioe) {
                Log.err("unable to get local address: " + ioe);
            } catch(NumberFormatException nfe) {
                Log.err("malformed port number: " + nfe);
            }
            
            // remove ourselves from the client list on exit
            try {
                s.close();
            } catch(IOException e) {
                Log.err("unable to close socket: " + e);
            }
            Log.d(0, "removing client " + String.format("%08X", randomID));
            removeClient(this);
        }
        
        public void disconnect() throws IOException {
            disconnect = true;
            s.close();
        }
    }
}
