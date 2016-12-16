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

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import org.bbi.tools.Log;
import org.bbi.tools.Files7;

/**
 *
 * @author wira
 */
public class SockTest {
    private static ExecutorService pool = Executors.newFixedThreadPool(8);
    
    public static void main(String args[]) {
        Log.debugLevel = 0; 
        String env, envBBIDebug;
        if((envBBIDebug = System.getenv().get("BBI_DEBUG")) != null) {
            Log.debugLevel = Integer.parseInt(envBBIDebug);
            Log.d(0, "debug level set to " + Log.debugLevel);
        }
        if((env = System.getenv("UDP_PUT_BUFFER_SIZE")) != null) {
            // multiples of the built-in max datagram size
            int i = Integer.parseInt(env);
            SockUDP.UDP_PUT_BUFFER_SIZE = i*SockUDP.UDP_MAX_DATAGRAM_SIZE - 
                    i*4;
            Log.d(0, "UDP_PUT_BUFFER_SIZE set to " + SockUDP.UDP_PUT_BUFFER_SIZE);
        }
        if((env = System.getenv("UDP_PIECE_SEND_DELAY_MS")) != null) {
            // multiples of the built-in max datagram size
            SockUDP.PIECE_SEND_DELAY_MS = Integer.parseInt(env);
            Log.d(0, "UDP_PIECE_SEND_DELAY_MS set to " + SockUDP.PIECE_SEND_DELAY_MS);
        }
        
        if(args.length == 3 && args[0].equals("serve")) {
            serve(args);
        } else if(args.length == 3 && args[0].equals("udpserve")) {
            udpserve(args);
        } else if(args.length == 2 && args[0].equals("udpclient")) {
            udpclient(args);
        } else if(args.length == 3 && args[0].equals("interactive")) {
            interactive(args);
        } else if(args.length >= 2 && args[0].equals("get")) {
            get(args);
        } else if(args.length >= 2 && args[0].equals("udpget")) {
            udpget(args);
        } else if(args.length == 3 && args[0].equals("sendtext")) {
            sendtext(args);
        } else if(args.length == 2 && args[0].equals("recvtext")) {
            recvtext(args);
        } else if(args.length == 3 && args[0].equals("udpsendfile")) {
            udpsendfile(args);
        } else if(args.length == 3 && args[0].equals("udprecvfile")) {
            udprecvfile(args);
        } else {
            System.err.println("usage: java -cp <javatools-jar> org.bbi.tools.net.SockTest <command> [options]");
            System.out.println();
            System.err.println("tcp commands:");
            System.err.println("    serve PORT ROOTPATH");            
            System.err.println("    interactive PORT ROOTPATH");
            System.err.println("    get HOST:PORT:PATH [--progress]");
            System.err.println("    sendtext PORT FILE");
            System.err.println("    recvtext HOST:PORT");
            System.out.println();
            System.out.println("udp commands:");
            System.err.println("    udpserve PORT ROOTPATH");
            System.err.println("    udpclient HOST:PORT");
            System.err.println("    udpget HOST:PORT:PATH [--progress]");
            System.err.println("    udpsendfile HOST:PORT FILE");
            System.err.println("    udprecvfile PORT DESTDIR");
        }
    }
    
    public static void serve(String[] args) {
        try {
            ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
            while(true) {
                try {
                    pool.execute(new ClientHandler(ss.accept(), args[2]));
                } catch(IOException ioe) {
                    System.err.println(ioe);
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void interactive(String[] args) {
        try {
            ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
            while(true) {
                try {
                    Sock.setStringTerminator((byte) 10);
                    pool.execute(new ClientHandler(ss.accept(), args[2]));
                } catch(IOException ioe) {
                    System.err.println(ioe);
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void get(String[] args) {
        try {
            String[] tokens = args[1].split(":");
            String host = tokens[0];
            int port = Integer.parseInt(tokens[1]);
            String path = tokens[2];
            Socket s = new Socket(host, port);        
            Progress p = null;
            if(args.length == 3 && args[2].equals("--progress")) {
                p = new Progress();
                ProgressFrame pFrame = new ProgressFrame(p);
                ProgressUpdater pUpdater = new ProgressUpdater(pFrame);
                (new Thread(pUpdater)).start();
                Sock.write(s, "get " + path);
                Sock.get(s, ".", p);
                Sock.write(s, "quit");
                pUpdater.stop();
                pFrame.dispose();
            } else {
                Sock.write(s, "get " + path);
                Sock.get(s, ".", null);
                Sock.write(s, "quit");
            }
            s.close();
        } catch(Exception e) {
            e.printStackTrace();              
        }
    }
    
    public static void sendtext(String[] args) {
        try {
            ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
            String data = new String(Files7.readAllBytes(args[2]), "UTF-8");
            Socket s = ss.accept();
            Sock.write(s, data);
            s.close();
            ss.close();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void recvtext(String[] args) {
        try {
            String[] tokens = args[1].split(":");
            String host = tokens[0];
            int port = Integer.parseInt(tokens[1]);
            Socket s = new Socket(host, port);
            Sock.read(s);
            s.close();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void udpserve(String[] args) {
        try {
            DatagramSocket ss = new DatagramSocket(Integer.parseInt(args[1]));
            try {
                FileDownloadServer.wait(ss, args[2], null);
            } catch(IOException ioe) {
                System.err.println(ioe);
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void udpclient(String[] args) {
        try {
            DatagramSocket ss = new DatagramSocket(0);
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String[] tokens = args[1].split(":");
            String host = tokens[0];
            int port = Integer.parseInt(tokens[1]);
            InetSocketAddress addr = new InetSocketAddress(host, port);
            // get a reader socket running
            pool.execute(new UDPHandler(ss));
            try {
                String l;
                while((l = r.readLine()) != null) {
                    SockUDP.write(ss, addr, l);
                    if(l.equals("quit")) {
                        System.exit(0);
                    }
                }
            } catch(IOException ioe) {
                System.err.println(ioe);
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void udpget(String[] args) {
        try {
            String[] tokens = args[1].split(":");
            String host = tokens[0];
            int port = Integer.parseInt(tokens[1]);
            String path = tokens[2];
            DatagramSocket s = new DatagramSocket(0);      
            InetSocketAddress addr = new InetSocketAddress(host, port);
            Progress p = null;
            if(args.length == 3 && args[2].equals("--progress")) {
                p = new Progress();
                ProgressFrame pFrame = new ProgressFrame(p);
                ProgressUpdater pUpdater = new ProgressUpdater(pFrame);
                pool.execute(pUpdater);
                SockUDP.write(s, addr, "get " + path);
                SockUDP.get(s, ".", p);
                SockUDP.write(s, addr, "quit");
                pUpdater.stop();
                pFrame.dispose();
                pool.shutdownNow();
            } else {
                SockUDP.write(s, addr, "get " + path);
                SockUDP.get(s, ".", null);
                SockUDP.write(s, addr, "quit");
            }
            s.close();
        } catch(Exception e) {
            e.printStackTrace();              
        }
    }
    
    public static void udpsendfile(String[] args) {
        try {
            DatagramSocket ss = new DatagramSocket();
            ss.setSoTimeout(5000);
            String[] tokens = args[1].split(":");
            String host = tokens[0];
            int port = Integer.parseInt(tokens[1]);
            InetSocketAddress addr = new InetSocketAddress(host, port);
            SockUDP.put(ss, addr, Files7.readAllBytes(args[2]), null);
            ss.close();
        } catch(Exception e) {
            Log.err("exception: " + e);
            e.printStackTrace();
        }
    }
    
    public static void udprecvfile(String[] args) {
        try {
            DatagramSocket ss = new DatagramSocket(Integer.parseInt(args[1]));
            Progress p = new Progress();
            ProgressFrame pFrame = new ProgressFrame(p);
            ProgressUpdater pUpdater = new ProgressUpdater(pFrame);
            pool.execute(pUpdater);
            // SockUDP.get(ss, args[2], p);
            Files7.write(args[2], SockUDP.get(ss, p).get());
            ss.close();
            pUpdater.stop();
            pFrame.dispose();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }
    
    static class ProgressFrame extends JDialog {
        private final Progress p;
        private final JProgressBar bar;
        private final JProgressBar barFile;
        private final JLabel totalBytes;
        
        public ProgressFrame(Progress p) {
            this.p = p;
            bar = new JProgressBar();
            barFile = new JProgressBar();
            totalBytes = new JLabel("File fetch");
            bar.setMinimum(0);
            bar.setMaximum(100000);
            barFile.setMinimum(0);
            barFile.setMaximum(100000);
            JPanel barContainer = new JPanel();
            barContainer.setLayout(new GridLayout(2, 1));
            barContainer.add(barFile);
            barContainer.add(bar);            
            getContentPane().add(barContainer, BorderLayout.CENTER);
            getContentPane().add(totalBytes, BorderLayout.PAGE_END);
            pack();
            setLocationRelativeTo(null);
            setSize(600, 100);
            setVisible(true);
        }
        
        public void update() {
            String title = "";
            if(p.getName() != null) {
                title = "File fetch " +
                        p.getCurrentFileNumber() + " of " +
                        p.getTotalFiles() + ": " + p .getName();
                setTitle(title);
            }
            if(p.getTotalBytes() > 0) {
                int value = (int)(
                    (double)p.getCopiedTotalBytes() / p.getTotalBytes() * 100000
                );
                int fileValue = (int)(
                    (double)p.getCopiedCurrentFileBytes() / p.getCurrentFileSize() * 100000
                );
                bar.setValue(value);
                barFile.setValue(fileValue);
                totalBytes.setText("total: " +
                        NumberFormat.getIntegerInstance().format(p.getCopiedTotalBytes()) + " of " + 
                        NumberFormat.getIntegerInstance().format(p.getTotalBytes()) + " bytes copied"
                );
            }
            setTitle(title);
        }
    }
    
    static class ClientHandler implements Runnable {
        private final Socket s;
        private final String root;
        
        public ClientHandler(Socket s, String root) {
            this.s = s;
            this.root = root;
        }
        
        @Override
        public void run() {
            try {
                System.out.println("new connection: " + s.getInetAddress().getHostAddress());
                FileDownloadServer.wait(s, root, null);
                s.close();
            } catch(IOException ioe) {
                System.err.println(ioe);
            }
        }
    }
    
    static class UDPHandler implements Runnable {
        private final DatagramSocket s;
        private boolean quit = false;
        
        public UDPHandler(DatagramSocket s) {
            this.s = s;
        }        
        
        public void quit() {
            quit = true;
            s.close();
        }
        
        @Override
        public void run() {
            Payload p;
            try {
                while(!quit) {
                    p = SockUDP.read(s);
                    Log.d(0, p.utf8());
                }                
            } catch(IOException ioe) {
                Log.err("udp handler exception " + ioe);
            }
        }
    }
    
    static class ProgressUpdater implements Runnable {
        private boolean stop = false;
        private ProgressFrame f;
        
        public ProgressUpdater(ProgressFrame f) {
            this.f = f;
        }
        
        @Override
        public void run() {
            while(!stop) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {

                }
                f.update();
            }
        }        
        
        public void stop() {
            stop = true;
        }
    }
}
