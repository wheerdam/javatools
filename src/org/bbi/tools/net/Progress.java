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

/**
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
    
    public long getCurrentFileSize() {
        return currentFileSize;
    }
    
    public long getCopiedCurrentFileBytes() {
        return currentFileCopied;
    }
    
    public long getCurrentFileNumber() {
        return currentFileNumber;
    }
    
    public long getTotalFiles() {
        return totalFiles;
    }
    
    public long getCopiedTotalBytes() {
        return copiedTotalBytes;
    }
    
    public long getTotalBytes() {
        return totalBytes;
    }
    
    public String getName() {
        return name;
    }
    
    public boolean done() {
        return totalBytes > 0 && copiedTotalBytes == totalBytes;
    }
}
