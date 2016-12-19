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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import org.bbi.net.Payload;
import org.bbi.net.UDPHost;
import org.bbi.net.SockUDP;
import org.bbi.tools.Log;


/**
 *
 * @author wira
 */
public class UDPClient {
    private final DatagramSocket s;
    private final SockUDP sock;
    private final SocketAddress server;
    private final SocketAddress local;
    private final int localPort;
    private List<UDPHost> peers;
    private SocketAddress peer;
    private long id;
    private boolean console;
    private boolean active;
    
    public static final int HOLEPUNCH_PACKET_INTERVAL_MS = 1000;
    
    public UDPClient(SocketAddress server, int recvTimeout) throws SocketException {
        this.server = server;
        s = new DatagramSocket(0);
        sock = new SockUDP(s);
        //s.setSoTimeout(recvTimeout);
        this.local = s.getLocalSocketAddress();
        localPort = s.getLocalPort();
        peers = new ArrayList<>();
    }
    
    public void connect(boolean c) throws IOException, NumberFormatException {
        this.console = c;
        Payload p;
        String[] tokens;
        writeServer("!register " + UDPHost.sockAddress(local));
        p = new Payload(sock.read(server));
        String d = p.decode();
        tokens = d.split("\\s+");
        if(!tokens[0].equals(UDPServer.PROTO_PREFIX + "#id")) {
            throw new IOException("unknown registration response: " + d);
        }
        id = Long.parseLong(tokens[1], 16);
        Log.d(0, "id=" + String.format("%08X", id));
        active = true;
        if(console) {
            (new Thread(new Reader(s))).start();
        }
        while(console) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in));
            String cmd;
            while((cmd = in.readLine()) != null) {
                if(cmd.equals("!quit")) {
                    writeServer(cmd);
                    active = false;
                } else {
                    writeServer(cmd);
                }
            }
        }
    }
    
    public void writeServer(String str) throws IOException {
        sock.write(server, UDPServer.PROTO_PREFIX + str);
    }
    
    public boolean isError(String str) {
        return str.startsWith(UDPServer.PROTO_PREFIX + "#error");
    }
    
    public UDPHost getPeerByID(long id) {
        for(UDPHost host : peers) {
            if(host.getID() == id) {
                return host;
            }
        }
        return null;
    }
    
    class Reader implements Runnable {
        private Queue<Payload> data;
        private SockUDP socket;
        
        public Reader(DatagramSocket s) {
            data = new LinkedBlockingQueue<>();
            this.socket = new SockUDP(s);
        }
        
        public Payload take() {
            return data.remove();
        }
        
        @Override
        public void run() {
            String d;
            String[] tokens;
            Payload p;
            Log.d(0, "UDPClient$Reader: run");
            while(active) {
                try {
                    d = (new Payload(socket.read(server))).decode();
                    if(d.equals(UDPServer.PROTO_PREFIX + "#list")) {
                        p = socket.get(server, null);
                        Log.d(0, "UDPClient$Reader: " + p.decode());
                        peers = new ArrayList<>();
                        for(String l : p.decode().trim().split("\\r?\\n")) {
                            if(l.startsWith(UDPServer.PROTO_PREFIX)) {
                                continue;
                            }
                            tokens = l.split("\\s+");
                            UDPHost host = new UDPHost(
                                    UDPHost.parseAddress(tokens[1]),
                                    tokens[2]
                            );
                            host.assignID(Long.parseLong(tokens[0], 16));
                            peers.add(host);
                        }
                    } else {
                        Log.d(0, "UDPClient$Reader: " + d);
                        tokens = d.split("\\s+");
                        switch(tokens[0]) {
                            case UDPServer.PROTO_PREFIX + "#request":
                                long id = Long.parseLong(tokens[1], 16);
                                
                                break;
                            case UDPServer.PROTO_PREFIX + ".peering":
                                // success!
                                Log.d(0, "received peering request from " +
                                          tokens[1]);
                                break;
                        }
                        
                    }
                    // data.add(p);
                    
                } catch(IOException ioe) {
                    
                }
            }
            Log.d(0, "UDPClient$Reader: exit");
        }
    }
    
    class HolePuncher implements Runnable {
        private final SocketAddress target;
        private boolean quit = false;
        
        public HolePuncher(SocketAddress target) {
            this.target = target;
        }
        
        public void quit() {
            quit = true;
        }
        
        @Override
        public void run() {
            while(!quit) {
                try {
                    sock.write(target, UDPServer.PROTO_PREFIX + ".peering "+
                                       String.format("%08X", id));
                    Thread.sleep(HOLEPUNCH_PACKET_INTERVAL_MS);
                } catch(Exception e) {
                    Log.err("HolePuncher: " + e);
                }
            }
        }
    }
}
