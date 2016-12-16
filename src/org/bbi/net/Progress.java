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

/**
 * Holds information about a file transfer being done by either 
 * {@link Sock#put(Socket, String, Progress) Sock.put} or 
 * {@link Sock#get(Socket, String, Progress) Sock.get}. This class can be used
 * to asynchronously check the progress of the transfer.
 *
 * @author wira
 */
public class Progress {
    protected long currentFileCopied = 0;
    protected long currentFileSize = 0;
    protected long currentFileNumber = 0;
    protected long totalBytes = 0;
    protected long copiedTotalBytes = 0;
    protected long totalFiles = 0;
    protected String name = null;
    
    /**
     * Get file size of currently transferred file
     * 
     * @return File length
     */
    public long getCurrentFileSize() {
        return currentFileSize;
    }
    
    /**
     * Get the size of portion of the file that has been transferred
     * 
     * @return Size in bytes
     */
    public long getCopiedCurrentFileBytes() {
        return currentFileCopied;
    }
    
    /**
     * Get the current number of file that is being transferred
     * 
     * @return Current file number
     */
    public long getCurrentFileNumber() {
        return currentFileNumber;
    }
    
    /**
     * Get the total number of files that are being trasnferred
     * 
     * @return Total number of files
     */
    public long getTotalFiles() {
        return totalFiles;
    }
    
    /**
     * Get the total number of bytes that have been transferred
     * 
     * @return Total transferred size in bytes
     */
    public long getCopiedTotalBytes() {
        return copiedTotalBytes;
    }
    
    /**
     * Get the the total number of bytes that are being transferred
     * 
     * @return Total transfer size in bytes
     */
    public long getTotalBytes() {
        return totalBytes;
    }
    
    /**
     * Get the name of the current file being transferred
     * 
     * @return Name of file
     */
    public String getName() {
        return name;
    }
    
    /**
     * Check if the transfer is done
     * 
     * @return 
     */
    public boolean done() {
        return totalBytes > 0 && copiedTotalBytes == totalBytes;
    }
}
