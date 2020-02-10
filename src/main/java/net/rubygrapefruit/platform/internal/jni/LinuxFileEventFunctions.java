/*
 * Copyright 2012 Adam Murdoch
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package net.rubygrapefruit.platform.internal.jni;

import net.rubygrapefruit.platform.file.FileWatcher;
import net.rubygrapefruit.platform.file.FileWatcherCallback;
import net.rubygrapefruit.platform.internal.FunctionResult;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

public class LinuxFileEventFunctions extends AbstractFileEventFunctions {

    /**
     * Start watching the given directories.
     *
     * <h3>Remarks:</h3>
     *
     * <ul>
     *     <li>Changes to descendants to the given paths are not reported.</li>
     * 
     *     <li>TBD</li>
     * </ul>
     */
    public FileWatcher startWatching(Collection<String> paths, FileWatcherCallback callback) {
        return createWatcher(paths, callback, new WatcherFactory() {
            @Override
            public FileWatcher createWatcher(String[] canonicalPaths, NativeFileWatcherCallback callback, FunctionResult result) {
                return startWatching(canonicalPaths, callback, result);
            }
        });
    }

    private static native FileWatcher startWatching(String[] paths, NativeFileWatcherCallback callback, FunctionResult result);

    private static native void stopWatching(Object details, FunctionResult result);

    // Created from native code
    @SuppressWarnings("unused")
    private static class WatcherImpl extends AbstractFileWatcher {
        public WatcherImpl(Object details) {
            super(details);
        }

        @Override
        protected void stop(Object details, FunctionResult result) {
            stopWatching(details, result);
        }
    }
}
