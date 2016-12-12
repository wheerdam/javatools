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

import java.nio.file.Files;
import java.awt.BorderLayout;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JProgressBar;

/**
 *
 * @author wira
 */
public class SockTest {
    public static void main(String args[]) {
        if(args.length == 2 && args[0].equals("--serve")) {
            try {
                ExecutorService pool = Executors.newFixedThreadPool(8);
                ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
                while(true) {
                    try {
                        pool.execute(new ClientHandler(ss.accept()));
                    } catch(IOException ioe) {
                        System.err.println(ioe);
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        } else if(args.length == 2 && args[0].equals("--interactive")) {
            try {
                ExecutorService pool = Executors.newFixedThreadPool(8);
                ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
                while(true) {
                    try {
                        SockCopy.setStringTerminator((byte) 10);
                        pool.execute(new ClientHandler(ss.accept()));
                    } catch(IOException ioe) {
                        System.err.println(ioe);
                    }
                }
            } catch(Exception e) {
                e.printStackTrace();
            }
        } else if(args.length == 2 && args[0].equals("--get")) {
            try {
                String[] tokens = args[1].split(":");
                String host = tokens[0];
                int port = Integer.parseInt(tokens[1]);
                String path = tokens[2];
                Socket s = new Socket(host, port);                
                Progress p = new Progress();
                ProgressFrame pFrame = new ProgressFrame(p);
                ProgressUpdater pUpdater = new ProgressUpdater(pFrame);
                (new Thread(pUpdater)).start();
                SockCopy.send(s, "get " + path);
                SockCopy.getRecursive(s, ".", p);
                SockCopy.send(s, "quit");
                System.out.println(p.getTotalFiles() + " files (" + 
                        NumberFormat.getIntegerInstance().format(p.getCopiedTotalBytes())
                        + " bytes) copied");
                pUpdater.stop();
                pFrame.dispose();
                s.close();
            } catch(Exception e) {
                e.printStackTrace();              
            }
        } else if(args.length == 3 && args[0].equals("--sendtext")) {
            try {
                ServerSocket ss = new ServerSocket(Integer.parseInt(args[1]));
                String data = new String(Files.readAllBytes(Paths.get(args[2])), "UTF-8");
                Socket s = ss.accept();
                SockCopy.send(s, data);
            } catch(Exception e) {
                e.printStackTrace();
            }
        } else if(args.length == 2 && args[0].equals("--recvtext")) {
            try {
                String[] tokens = args[1].split(":");
                String host = tokens[0];
                int port = Integer.parseInt(tokens[1]);
                Socket s = new Socket(host, port);
                System.out.println(SockCopy.recv(s));
            } catch(Exception e) {
                e.printStackTrace();
            }
        } else {
            System.err.println("serve: java -jar <jarfile> --serve PORT");
            System.err.println("fetch: java -jar <jarfile> --get HOST:PORT:PATH");
        }
    }
    
    static class ProgressFrame extends JDialog {
        private final Progress p;
        private final JProgressBar bar;
        private final JLabel label;
        
        public ProgressFrame(Progress p) {
            this.p = p;
            bar = new JProgressBar();
            label = new JLabel("File fetch");
            getContentPane().add(bar, BorderLayout.CENTER);
            getContentPane().add(label, BorderLayout.PAGE_START);
            pack();
            setLocationRelativeTo(null);
            setSize(600, 60);
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
                if(p.getTotalBytes() < Math.pow(2, 30)) {
                    bar.setMaximum((int) p.getTotalBytes());
                    bar.setValue((int) p.getCopiedTotalBytes());
                } else {
                    bar.setMaximum((int) (p.getTotalBytes() / Math.pow(2,20)));
                    bar.setValue((int) (p.getCopiedTotalBytes() / Math.pow(2,20)));
                }
                label.setText(
                        NumberFormat.getIntegerInstance().format(p.getCopiedTotalBytes()) + " of " + 
                        NumberFormat.getIntegerInstance().format(p.getTotalBytes()) + " bytes copied"
                );
            }
            setTitle(title);
        }
    }
    
    static class ClientHandler implements Runnable {
        private Socket s;
        
        public ClientHandler(Socket s) {
            this.s = s;
        }
        
        @Override
        public void run() {
            try {
                System.out.println("new connection: " + s.getInetAddress().getHostAddress());
                SockCopy.wait(s, null);
                s.close();
            } catch(IOException ioe) {
                System.err.println(ioe);
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
