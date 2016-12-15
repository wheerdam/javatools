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

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

/**
 *
 * @author wira
 */
public class Payload {
    private final byte[] payload;
    private final SocketAddress socketAddress;
    
    public Payload(byte[] p, SocketAddress s) {
        this.payload = p;
        this.socketAddress = s;
    }
    
    public byte[] get() {
        return payload;
    }
    
    public String utf8() {
        return new String(payload, StandardCharsets.UTF_8);
    }
    
    public SocketAddress getSocketAddress() {
        return socketAddress;
    }
}
