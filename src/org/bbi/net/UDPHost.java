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

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Description of a UDP host
 *
 * @author wira
 */
public class UDPHost {
    private long id;
    private final String publicAddr;
    private final int publicPort;
    private String privateAddr;
    private int privatePort;
    private final SocketAddress publicSock;
    private long timeLastAccessed;
    
    /**
     * The minimum information about the host is its endpoint
     * described in the <code>SocketAddress</code> handle
     * 
     * @param publicSock <code>SocketAddress</code> that is the endpoint of the
     * socket connection
     */
    public UDPHost(SocketAddress publicSock) {
        this.id = 0;
        this.publicSock = publicSock;
        publicAddr = ((InetSocketAddress)publicSock).getAddress().getHostAddress();
        publicPort = ((InetSocketAddress)publicSock).getPort();
        timeLastAccessed = System.currentTimeMillis();
    }

    /**
     * Provide visible endpoint address and the host's local network address
     * 
     * @param publicSock <code>SocketAddress</code> that is the endpoint of the
     * socket connection
     * @param privateSock Information about the host's local network address
     */
    public UDPHost(SocketAddress publicSock, String privateSock) {
        this.id = 0;
        this.publicSock = publicSock;
        publicAddr = ((InetSocketAddress)publicSock).getAddress().getHostAddress();
        publicPort = ((InetSocketAddress)publicSock).getPort();
        String[] tokens = privateSock.split(":");
        privateAddr = tokens[0];
        privatePort = Integer.parseInt(tokens[1]);
        timeLastAccessed = System.currentTimeMillis();
    }

    /**
     * Assign an arbitrary identification number to this object
     * 
     * @param id an identifying number
     */
    public void assignID(long id) {
        this.id = id;
    }

    /**
     * Get the object's identifying number. The default is 0 if not assigned
     * 
     * @return the object's identifying number
     */
    public long getID() {
        return id;
    }

    /**
     * Get the object's identifying number formatted in 8-digit hexadecimal
     * 
     * @return formatted identifying number
     */
    public String getFormattedID() {
        return String.format("%08X", id);
    }

    /**
     * Get the object's <code>SocketAddress</code> instance
     * 
     * @return a <code>SocketAddress</code> instance describing the endpoint
     */
    public SocketAddress getSocketAddress() {
        return publicSock;
    }

    /**
     * Get the object's endpoint address as a string
     * 
     * @return a string describing the endpoint
     */
    public String getPublicSocketAddress() {
        return publicAddr + ":" + publicPort;
    }

    /**
     * Get the object's local network address as a string
     * 
     * @return a string describing the object's local network address
     */
    public String getPrivateSocketAddress() {
        return privateAddr + ":" + privatePort;
    }

    /**
     * Get the time between the invocation of this method and the last time
     * {@link #updateLastAccessed() updateLastAccessed} is invoked. If no
     * invocation of {@link #updateLastAccessed() updateLastAccessed} has been
     * done for this object, the starting time would be the creation of this
     * object
     * 
     * @return duration in milliseconds
     */
    public long getDuration() {
        return System.currentTimeMillis() - timeLastAccessed;
    }

    /**
     * Update the last accessed timestamp of this object
     */
    public void updateLastAccessed() {
        timeLastAccessed = System.currentTimeMillis();
    }
    
    /**
     * Parse a socket address formatted as HOST:PORT into a
     * <code>SocketAddress</code> instance
     * 
     * @param sockAddr string containing the socket address
     * @return the <code>SocketAddress</code> instance describing the address
     */
    public static SocketAddress parseAddress(String sockAddr) {
        String[] tokens = sockAddr.split(":");
        return new InetSocketAddress(tokens[0], Integer.parseInt(tokens[1]));
    }
    
    /**
     * Take an address and port pair and create a <code>SocketAddress</code>
     * instance
     * 
     * @param addr address of the host as a string
     * @param port port of the host service
     * @return the <code>SocketAddres</code> instance describing the address
     */
    public static SocketAddress parseAddress(String addr, int port) {
        return new InetSocketAddress(addr, port);
    }

    /**
     * Take a <code>SocketAddress</code> instance and create a string 
     * representation of the address in the HOST:PORT format
     * 
     * @param socket the instance of <code>SocketAddress</code> to use
     * @return representation of the address as a string
     */
    public static String sockAddress(SocketAddress socket) {
        InetSocketAddress s = (InetSocketAddress) socket;
        return s.getAddress().getHostAddress() + ":" + s.getPort();
    }
    
    /**
     * Take a <code>SocketAddress</code> instance and create a string 
     * representation of the address (no port information)
     * 
     * @param socket the instance of <code>SocketAddress</code> to use
     * @return representation of the address as a string
     */
    public static String address(SocketAddress socket) {
        InetSocketAddress s = (InetSocketAddress) socket;
        return s.getAddress().getHostAddress();
    }
    
    /**
     * Take a <code>SocketAddress</code> instance and get the port number of
     * the socket
     * 
     * @param socket the instance of <code>SocketAddress</code> to use
     * @return socket port number
     */
    public static int port(SocketAddress socket) {
        InetSocketAddress s = (InetSocketAddress) socket;
        return s.getPort();
    }
}
