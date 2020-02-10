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

import groovy.transform.EqualsAndHashCode
import java.util.logging.Logger
import net.rubygrapefruit.platform.Native
import net.rubygrapefruit.platform.internal.Platform
import net.rubygrapefruit.platform.testfixture.JulLogging
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestName
import spock.lang.Ignore
import spock.lang.IgnoreIf
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.AsyncConditions

import static java.util.logging.Level.FINE
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.CREATED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.MODIFIED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.REMOVED

abstract class AbstractFileEventsTest extends Specification {
    private static final Logger LOGGER = Logger.getLogger(AbstractFileEventsTest.name)

    @Rule TemporaryFolder tmpDir
    @Rule TestName testName
    @Rule JulLogging logging = new JulLogging(Native, FINE)

    def callback = new TestCallback()
    File rootDir
    FileWatcher watcher

    def setup() {
        LOGGER.info(">>> Running '${testName.methodName}'")
        rootDir = tmpDir.newFolder(testName.methodName)
    }

    def cleanup() {
        stopWatcher()
        LOGGER.info("<<< Finished '${testName.methodName}'")
    }

    def "can open and close watcher on a directory without receiving any events"() {
        when:
        startWatcher(rootDir)

        then:
        noExceptionThrown()
    }

    def "can detect file created"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(createdFile)
        createNewFile(createdFile)

        then:
        expectedChanges.await()
    }

    def "can detect file removed"() {
        given:
        def removedFile = new File(rootDir, "removed.txt")
        createNewFile(removedFile)
        // TODO Why does Windows report the modification?
        def expectedEvents = Platform.current().windows
            ? [modified(removedFile), removed(removedFile)]
            : [removed(removedFile)]
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents expectedEvents
        removedFile.delete()

        then:
        expectedChanges.await()
    }

    def "can detect file modified"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents modified(modifiedFile)
        modifiedFile << "change"

        then:
        expectedChanges.await()
    }

    def "can detect file renamed"() {
        given:
        def sourceFile = new File(rootDir, "source.txt")
        def targetFile = new File(rootDir, "target.txt")
        createNewFile(sourceFile)
        // TODO Why doesn't Windows report the creation of the target file?
        def expectedEvents = Platform.current().windows
            ? [removed(sourceFile)]
            : [removed(sourceFile), created(targetFile)]
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents expectedEvents
        sourceFile.renameTo(targetFile)

        then:
        expectedChanges.await()
    }

    def "can detect file moved out"() {
        given:
        def outsideDir = tmpDir.newFolder()
        def sourceFileInside = new File(rootDir, "source-inside.txt")
        def targetFileOutside = new File(outsideDir, "target-outside.txt")
        createNewFile(sourceFileInside)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents removed(sourceFileInside)
        sourceFileInside.renameTo(targetFileOutside)

        then:
        expectedChanges.await()
    }

    def "can detect file moved in"() {
        given:
        def outsideDir = tmpDir.newFolder()
        def sourceFileOutside = new File(outsideDir, "source-outside.txt")
        def targetFileInside = new File(rootDir, "target-inside.txt")
        createNewFile(sourceFileOutside)
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(targetFileInside)
        sourceFileOutside.renameTo(targetFileInside)

        then:
        expectedChanges.await()
    }

    def "can receive multiple events from the same directory"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(firstFile)
        createNewFile(firstFile)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents created(secondFile)
        waitForChangeEventLatency()
        createNewFile(secondFile)

        then:
        expectedChanges.await()
    }

    def "does not receive events from unwatched directory"() {
        given:
        def watchedFile = new File(rootDir, "watched.txt")
        def unwatchedDir = tmpDir.newFolder()
        def unwatchedFile = new File(unwatchedDir, "unwatched.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(watchedFile)
        createNewFile(unwatchedFile)
        createNewFile(watchedFile)

        then:
        expectedChanges.await()
    }

    def "can receive multiple events from multiple watched directories"() {
        given:
        def firstWatchedDir = tmpDir.newFolder("first")
        def firstFileInFirstWatchedDir = new File(firstWatchedDir, "first-watched.txt")
        def secondWatchedDir = tmpDir.newFolder("second")
        def secondFileInSecondWatchedDir = new File(secondWatchedDir, "sibling-watched.txt")
        startWatcher(firstWatchedDir, secondWatchedDir)

        when:
        def expectedChanges = expectEvents created(firstFileInFirstWatchedDir)
        createNewFile(firstFileInFirstWatchedDir)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents created(secondFileInSecondWatchedDir)
        createNewFile(secondFileInSecondWatchedDir)

        then:
        expectedChanges.await()
    }

    @IgnoreIf({ Platform.current().linux })
    def "can receive events from directory with different casing"() {
        given:
        def lowercaseDir = new File(rootDir, "watch-this")
        def uppercaseDir = new File(rootDir, "WATCH-THIS")
        def fileInLowercaseDir = new File(lowercaseDir, "lowercase.txt")
        def fileInUppercaseDir = new File(uppercaseDir, "UPPERCASE.TXT")
        uppercaseDir.mkdirs()
        startWatcher(lowercaseDir)

        when:
        def expectedChanges = expectEvents created(fileInLowercaseDir)
        createNewFile(fileInLowercaseDir)

        then:
        expectedChanges.await()

        when:
        expectedChanges = expectEvents created(fileInUppercaseDir)
        createNewFile(fileInUppercaseDir)

        then:
        expectedChanges.await()
    }

    // TODO Handle exceptions happening in callbacks
    @Ignore("Exceptions in callbacks are now silently ignored")
    def "can handle exception in callback"() {
        given:
        def error = new RuntimeException("Error")
        def createdFile = new File(rootDir, "created.txt")
        def conditions = new AsyncConditions()
        when:
        startWatcher({ type, path ->
            try {
                throw error
            } finally {
                conditions.evaluate {}
            }
        }, rootDir)
        createNewFile(createdFile)
        conditions.await()

        then:
        noExceptionThrown()
    }

    def "can be started once and stopped multiple times"() {
        given:
        startWatcher(rootDir)

        when:
        // TODO There's a race condition in starting the macOS watcher thread
        Thread.sleep(100)
        watcher.close()
        watcher.close()

        then:
        noExceptionThrown()
    }

    def "can be used multiple times"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(firstFile)
        createNewFile(firstFile)

        then:
        expectedChanges.await()
        stopWatcher()

        when:
        startWatcher(rootDir)
        expectedChanges = expectEvents created(secondFile)
        createNewFile(secondFile)

        then:
        expectedChanges.await()
    }

    def "can start multiple watchers"() {
        given:
        def firstRoot = new File(rootDir, "first")
        firstRoot.mkdirs()
        def secondRoot = new File(rootDir, "second")
        secondRoot.mkdirs()
        def firstFile = new File(firstRoot, "file.txt")
        def secondFile = new File(secondRoot, "file.txt")
        def firstCallback = new TestCallback()
        def secondCallback = new TestCallback()

        LOGGER.info("> Starting first watcher")
        def firstWatcher = startNewWatcher(firstCallback, firstRoot)
        LOGGER.info("> Starting second watcher")
        def secondWatcher = startNewWatcher(secondCallback, secondRoot)
        LOGGER.info("> Watchers started")

        when:
        def firstChanges = expectEvents firstCallback, created(firstFile)
        createNewFile(firstFile)

        then:
        firstChanges.await()

        when:
        def secondChanges = expectEvents secondCallback, created(secondFile)
        createNewFile(secondFile)

        then:
        secondChanges.await()

        cleanup:
        stopWatcher(firstWatcher)
        stopWatcher(secondWatcher)
    }

    def "can receive event about a non-direct descendant change"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(rootDir)

        when:
        def expectedChanges = expectEvents created(fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()
    }

    def "can watch directory with long path"() {
        given:
        def subDir = new File(rootDir, "long-path")
        4.times {
            subDir = new File(subDir, "X" * 200)
        }
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(subDir)

        when:
        def expectedChanges = expectEvents created(fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()
    }

    @Unroll
    def "can watch directory with #type characters"() {
        Assume.assumeTrue(supported)

        given:
        def subDir = new File(rootDir, path)
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, path)
        startWatcher(subDir)

        when:
        def expectedChanges = expectEvents created(fileInSubDir)
        createNewFile(fileInSubDir)

        then:
        expectedChanges.await()

        where:
        type         | path                     | supported
        "ASCII-only" | "directory"              | true
        "Chinese"    | "输入文件"                   | true
        "Hungarian"  | "Dezső"                  | true
        "space"      | "test directory"         | true
        "zwnj"       | "test\u200cdirectory"    | true
        "URL-quoted" | "test%<directory>#2.txt" | !Platform.current().windows
    }

    @Unroll
    @IgnoreIf({ Platform.current().windows })
    def "can detect #removedAncestry removed"() {
        given:
        def parentDir = new File(rootDir, "parent")
        def watchedDir = new File(parentDir, "removed")
        watchedDir.mkdirs()
        def removedFile = new File(watchedDir, "file.txt")
        createNewFile(removedFile)
        File removedDir = removedDirectory(watchedDir)

        def expectedEvents = [removed(watchedDir)]
        startWatcher(watchedDir)

        when:
        def expectedChanges = expectEvents expectedEvents
        assert removedDir.deleteDir()

        then:
        expectedChanges.await()

        where:
        removedAncestry                     | removedDirectory
        "watched directory"                 | { it }
        "parent of watched directory"       | { it.parentFile }
        "grand-parent of watched directory" | { it.parentFile.parentFile }
    }

    protected void startWatcher(FileWatcherCallback callback = this.callback, File... roots) {
        watcher = startNewWatcher(callback, roots)
    }

    protected abstract FileWatcher startNewWatcher(FileWatcherCallback callback, File... roots)

    protected void stopWatcher() {
        def copyWatcher = watcher
        watcher = null
        stopWatcher(copyWatcher)
    }

    protected abstract void stopWatcher(FileWatcher watcher)

    protected AsyncConditions expectEvents(FileWatcherCallback callback = this.callback, FileEvent... events) {
        expectEvents(callback, events as List)
    }

    protected AsyncConditions expectEvents(FileWatcherCallback callback = this.callback, List<FileEvent> events) {
        return callback.expect(events)
    }

    protected static FileEvent created(File file) {
        return new FileEvent(CREATED, file)
    }

    protected static FileEvent removed(File file) {
        return new FileEvent(REMOVED, file)
    }

    protected static FileEvent modified(File file) {
        return new FileEvent(MODIFIED, file)
    }

    protected void createNewFile(File file) {
        LOGGER.info("> Creating $file")
        file.createNewFile()
        LOGGER.info("< Created $file")
    }

    private static class TestCallback implements FileWatcherCallback {
        private AsyncConditions conditions
        private Collection<FileEvent> expectedEvents

        AsyncConditions expect(List<FileEvent> events) {
            events.each { event ->
                LOGGER.info("> Expecting $event")
            }
            this.conditions = new AsyncConditions()
            this.expectedEvents = new ArrayList<>(events)
            return conditions
        }

        @Override
        void pathChanged(Type type, String path) {
            handleEvent(new FileEvent(type, new File(path).canonicalFile))
        }

        private void handleEvent(FileEvent event) {
            LOGGER.info("> Received  $event")
            if (!expectedEvents.remove(event)) {
                conditions.evaluate {
                    throw new RuntimeException("Unexpected event $event")
                }
            }
            if (expectedEvents.empty) {
                conditions.evaluate {}
            }
        }
    }

    @EqualsAndHashCode
    @SuppressWarnings("unused")
    protected static class FileEvent {
        final FileWatcherCallback.Type type
        final File file

        FileEvent(FileWatcherCallback.Type type, File file) {
            this.type = type
            this.file = file.canonicalFile
        }

        @Override
        String toString() {
            return "$type $file"
        }
    }

    protected abstract void waitForChangeEventLatency()
}
