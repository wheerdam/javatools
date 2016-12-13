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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author wira
 */
public class Config {
    private HashMap<String, HashMap<String, String>> sections;
    private HashMap<String, ArrayList<String>> orderedKeys;
    private ArrayList<Path> configFiles;
    private HashMap<String, String[]> configLines;
    
    public Config() {
        init();
    }
    
    public Config(String str) {
        init();
        load(str);
    }
    
    private void init() {
        sections = new HashMap();
        orderedKeys = new HashMap();
        configFiles = new ArrayList<>();
        configLines = new HashMap<>();
        sections.put("GLOBAL", new HashMap<String, String>());
        orderedKeys.put("GLOBAL", new ArrayList<String>());
    }
    
    public final void load(String str) {
        for(String path : str.split(";")) {
            Log.d(1, "Config.load: \"" + path + "\"");
            try {
                Path f = FileSystems.getDefault().getPath(path);
                String[] lines = Files.readAllLines(f).toArray(new String[0]);
                parse(lines);
                configFiles.add(f);
                configLines.put(path, lines);
            } catch(NullPointerException | IOException e) {
                Log.err(this + ": unable to read config file, error=" + e);
            }
        }
    }
    
    public void parse(String...lines) {
        HashMap curSection = sections.get("GLOBAL");
        ArrayList curList = orderedKeys.get("GLOBAL");
        String[] tokens;
        String sectionName = "GLOBAL";

        for(String l : lines) {
            try {
                l = l.trim();
                if(l.startsWith("#") || l.equals("")) {
                    continue;
                }
                Pattern p = Pattern.compile("\\[(.*?)\\]", Pattern.DOTALL);
                Matcher m = p.matcher(l);
                if(m.matches()) {
                    sectionName = l.substring(1, l.length()-1);
                    Log.d(4, this + ".parse: [" + sectionName + "]");
                    if(!sections.containsKey(sectionName)) {
                        curSection = new HashMap();
                        curList = new ArrayList();
                        sections.put(sectionName, curSection);
                        orderedKeys.put(sectionName, curList);
                    } else {
                        curSection = sections.get(sectionName);
                        curList = orderedKeys.get(sectionName);
                    }
                    continue;
                }
                tokens = l.split("#");
                tokens = tokens[0].split("=", 2);
                Log.d(3, this + ".parse: [" + sectionName + "] " +  tokens[0] +
                        "=" + (tokens.length > 1 ? tokens[1] : ""));
                curSection.put(tokens[0], tokens.length > 1 ? tokens[1].trim() : null);
                curList.add(tokens[0]);
            } catch(Exception e) {
                Log.err(this + ": failed to parse \"" +
                        l + "\"");
            }
        }
    }        
    
    public Path[] getFilePaths() {
        return (Path[]) configFiles.toArray();
    }
    
    public String[] getLines(String name) {
        return (String[]) configLines.get(name);
    }   
    
    public boolean hasSection(String key) {
        return sections.containsKey(key);
    }
    
    public String[] getSection(String key) {
        if(sections.containsKey(key)) {
            return (String[]) sections.get(key).values().toArray();
        }
        
        Log.d(2, "Config.getSection: \"" + key + "\" section" +
                " not found.");
        return null;
    }
    
    public HashMap<String, String> getSectionAsMap(String key) {
        if(sections.containsKey(key)) {
            return sections.get(key);
        }
        
        Log.d(2, "Config.getSectionAsMap: \"" + key +
                "\" section not found.");
        return null;
    }
    
    public String getValue(String section, String key) {
        if(!sections.containsKey(section)) {
            Log.d(2, "Config.getValue(" + section + ":" + key +"): \"" +
                    section + "\" section not found.");
            return null;
        }
        
        if(!sections.get(section).containsKey(key)) {
            Log.d(2, "Config.getValue(" + section + ":" + key +"): entry " +
                    "not found in section \"" + section + "\"");
            return null;
        }
        
        return sections.get(section).get(key);
    }
    
    public long getValue(long defaultValue, String section, String key) {
        String value = getValue(section, key);
        return value == null ? defaultValue : Integer.parseInt(value);
    }
    
    public String getValue(String defaultValue, String section, String key) {
        String value = getValue(section, key);
        return value == null ? defaultValue : value;
    }
    
    public boolean assertValue(String requestedValue, String section, String key) {
        String value = getValue(section, key);
        return value == null ? false : value.equals(requestedValue);
    }
    
    public double getValue(double defaultValue, String section, String key) {
        String value = getValue(section, key);
        return value == null ? defaultValue : Double.parseDouble(value);
    }
    
    public ArrayList<String> getKeysInOriginalOrder(String section) {
        return orderedKeys.get(section);
    }
    
    @Override
    public String toString() {
        return "Config";
    } 
}
