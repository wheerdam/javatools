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

import java.net.DatagramPacket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Class that wraps around a payload from UDP packet(s)
 *
 * @author wira
 */
public class Payload {
    private final byte[] payload;
    private final SocketAddress socketAddress;
    
    /**
     * Create a payload object with provided data and source address
     * 
     * @param p data packet
     * @param s address of where this data originated from
     */
    public Payload(byte[] p, SocketAddress s) {
        this.payload = p;
        this.socketAddress = s;
    }
    
    /**
     * Create a payload object with provided data and source address
     * 
     * @param p data packet
     */
    public Payload(DatagramPacket p) {
        this.payload = new byte[p.getLength()];
        System.arraycopy(p.getData(), 0, this.payload, 0, p.getLength());
        this.socketAddress = p.getSocketAddress();
    }
    
    /**
     * Create a payload object with provided data and source address
     * 
     * @param p data packet
     * @param off offset into the data
     * @param len length of data
     * @param s address of where this data originated from
     */
    public Payload(byte[] p, int off, int len, SocketAddress s) {
        this.payload = new byte[len];
        System.arraycopy(p, off, this.payload, 0, len);
        this.socketAddress = s;
    }
    
    /**
     * Get data as byte array
     * 
     * @return data as byte array
     */
    public byte[] get() {
        return payload;
    }
    
    /**
     * Assume the data is encoded in UTF-8 and return it as <code>String</code>
     * 
     * @return decoded data
     */
    public String decode() {
        return new String(payload, StandardCharsets.UTF_8);
    }
    
    /**
     * Get the address of the remote host that sent the data
     * 
     * @return address and port of the remote host
     */
    public SocketAddress getRemote() {
        return socketAddress;
    }
    
    /**
     * Compare source addresses of this payload and the provided payload
     * 
     * @param p payload to compare to
     * @return true if it is a match
     */
    public boolean sourceEquals(Payload p) {
        return UDPHost.sockAddress(socketAddress).equals(
                UDPHost.sockAddress(p.getRemote()));
    }
}
