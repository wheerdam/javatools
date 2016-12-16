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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Java 7 compatible file I/O util funections
 *
 * @author wira
 */
public class Files7 {
    public static int READ_BUFFER_SIZE = 8192;
    
    public static byte[] readAllBytes(String path) throws IOException {
        File f = new File(path);
        byte[] buf = new byte[READ_BUFFER_SIZE];
        byte[] data = new byte[(int) f.length()];
        int off = 0, nr;
        InputStream in = new FileInputStream(f);
        while((nr = in.read(buf)) != -1) {
            System.arraycopy(buf, 0, data, off, nr);
            off += nr;
        }
        in.close();
        return data;
    }
    
    public static List<String> readAllLines(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(path))));
        String line;
        while((line = r.readLine()) != null) {
            lines.add(line);
        }
        r.close();
        return lines;
    }
    
    public static void write(String path, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(new File(path));
        out.write(data);
        out.close();
    }
}
