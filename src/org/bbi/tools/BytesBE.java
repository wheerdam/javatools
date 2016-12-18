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
package org.bbi.tools;

/**
 * Byte-oriented encoding/decoding
 *
 * @author wira
 */
public class BytesBE {
    public static long decode(byte...b) {
        long ret = 0;
        for(int i = b.length-1; i >= 0; i--) {
            ret += ((long)(b[i]) << 8*i) & 0xffL << 8*i;
        }
        return ret;
    }
    
    public static byte[] encode(long d, int size) {
        byte[] b = new byte[size];
        for(int i = size-1; i >= 0; i--) {
            b[size-1-i] = (byte)((d >> 8*i) & 0xffL);
        }
        return b;
    }
    
    public static String format(byte...bytes) {
        String str = "";
        for(byte b : bytes) {
            str += String.format("%02X ", b);
        }
        return str.trim();
    }
}
