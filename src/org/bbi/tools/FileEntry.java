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

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * A utility class that describes a file and its relation to an arbitrary
 * parent
 */
public class FileEntry {
    private final String fileName;
    private final String relativePath;
    private final String parentPath;
    private final File f;

    /**
     * Construct a file entry with the <code>File</code> handle and the 
     * handle to the arbitrary parent. The parent is used to construct a 
     * relative path string
     * 
     * @param parent Arbitrary parent directory level of the file
     * @param file <code>File</code> handle
     */
    public FileEntry(File parent, File file) {
        this.f = file;
        fileName = file.getName();
        if(parent != null) {
            parentPath = parent.getAbsolutePath();
        } else {
            parentPath = ".";
        }
        relativePath = parent != null ? 
                file.getAbsolutePath().substring(parentPath.length()).substring(1) :
                "./" + file.getName();
    }

    /**
     * Get file name of the entry
     * 
     * @return File name
     */
    public String getName() {
        return fileName;
    }

    /**
     * Get file path relative to the specified parent. E.g. if the file
     * is <code>/home/user/downloads/test.zip</codE> and parent is 
     * <code>/home/user</code> this method will return 
     * <code>downloads/test.zip</code>
     * 
     * @return File path relative to the specified parent
     */
    public String getRelativePath() {
        return relativePath;
    }

    /**
     * Get parent path specified by the user
     * 
     * @return Parent path
     */
    public String getParentPath() {
        return parentPath;
    }

    /**
     * Get file handle
     * 
     * @return File handle
     */
    public File getFile() {
        return f;
    }
    
    /**
     * Traverse through a directory and build a file list
     * 
     * @param parent Parent directory to construct a relative path for the file entry
     * @param f Path to traverse
     * @param fileList A list of FileEntry that will be populated
     * @param recursive Recurse into subdirectories
     * @throws IOException if an I/O exception occurs 
     */
    public static void populateFileList(File parent, File f, 
            List<FileEntry> fileList,  boolean recursive)
            throws IOException {
        if(!f.exists()) {
            throw new IOException("unable to open " + f.getName());
        }
        if(f.isDirectory() && f.listFiles() != null) {
            for(File file : f.listFiles()) {
                if(recursive && file.isDirectory()) {
                    populateFileList(parent, file, fileList, true);
                } else {
                    fileList.add(new FileEntry(parent, file));
                }
            }
        } else {
            fileList.add(new FileEntry(parent, f));
        }
    }
    
    /**
     * Recursively create parent directories
     * 
     * @param f Highest level directory
     * @throws IOException if an I/O exception occurs 
     */
    public static void createParentDirectory(File f) throws IOException {
        if(f == null) {
            return;
        }
        
        if(f.getParentFile() != null && !f.getParentFile().exists()) {
            createParentDirectory(f.getParentFile());            
        }
        
        if(!f.exists()) {
            System.out.println("mkdir " + f.getAbsolutePath());
            f.mkdir();
        }
    }
}
