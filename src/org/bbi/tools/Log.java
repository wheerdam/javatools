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

import javax.swing.JOptionPane;

/**
 * All-purpose simple logger
 *
 * @author wira
 */
public class Log {
    public static int debugLevel = 0;
    public static boolean errorDialogBox = false;
    
    public static void d(int level, String str) {
        if(debugLevel >= level) {
            System.out.println(str);
        }
    }
    
    public static void di(int level, String str) {
        if(debugLevel >= level) {
            System.out.print(str);
        }
    }
    
    public static void err(String str) {
        System.err.println(str);
    }
    
    public static void fatal(int error, String str) {
        Log.err("FATAL ERROR: " + str);
        if(errorDialogBox) {
            JOptionPane.showMessageDialog(null, str, "FATAL ERROR " + error,
                    JOptionPane.ERROR_MESSAGE);
        }
        System.exit(error);
    }
}
