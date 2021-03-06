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

package net.rubygrapefruit.platform.file

import net.rubygrapefruit.platform.Native
import net.rubygrapefruit.platform.internal.Platform
import net.rubygrapefruit.platform.internal.jni.OsxFileEventFunctions
import spock.lang.Requires

import java.util.concurrent.TimeUnit

@Requires({ Platform.current().macOs })
class OsxFileEventFunctionsTest extends AbstractFileEventsTest {
    private static final LATENCY_IN_MILLIS = 0

    final OsxFileEventFunctions fileEvents = Native.get(OsxFileEventFunctions.class)

    def "caches file events instance"() {
        expect:
        Native.get(OsxFileEventFunctions.class) is fileEvents
    }

    @Override
    protected FileWatcher startNewWatcher(FileWatcherCallback callback, File... roots) {
        // Avoid setup operations to be reported
        waitForChangeEventLatency()
        fileEvents.startWatching(
            roots*.absolutePath.toList(),
            LATENCY_IN_MILLIS, TimeUnit.MILLISECONDS,
            callback
        )
    }

    @Override
    protected void stopWatcher(FileWatcher watcher) {
        watcher?.close()
    }

    @Override
    protected void waitForChangeEventLatency() {
        TimeUnit.MILLISECONDS.sleep(LATENCY_IN_MILLIS + 20)
    }
}
