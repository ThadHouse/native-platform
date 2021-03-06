#if defined(__APPLE__)

#include "generic.h"
#include "net_rubygrapefruit_platform_internal_jni_OsxFileEventFunctions.h"
#include <CoreServices/CoreServices.h>
#include <thread>

using namespace std;

struct FileWatcherException : public exception {
public:
    FileWatcherException(const char* message) {
        this->message = message;
    }

    const char* what() const throw() {
        return message;
    }

private:
    const char* message;
};

class Server;

static void handleEventsCallback(
    ConstFSEventStreamRef streamRef,
    void* clientCallBackInfo,
    size_t numEvents,
    void* eventPaths,
    const FSEventStreamEventFlags eventFlags[],
    const FSEventStreamEventId eventIds[]);

class Server {
public:
    Server(JNIEnv* env, jobject watcherCallback, CFArrayRef rootsToWatch, long latencyInMillis);
    ~Server();

private:
    void run();

    void handleEvents(
        size_t numEvents,
        char** eventPaths,
        const FSEventStreamEventFlags eventFlags[],
        const FSEventStreamEventId eventIds[]);
    friend void handleEventsCallback(
        ConstFSEventStreamRef streamRef,
        void* clientCallBackInfo,
        size_t numEvents,
        void* eventPaths,
        const FSEventStreamEventFlags eventFlags[],
        const FSEventStreamEventId eventIds[]);

    void handleEvent(JNIEnv* env, char* path, FSEventStreamEventFlags flags);

    // TODO: Move this to somewhere else
    JNIEnv* getThreadEnv();

    JavaVM* jvm;
    jobject watcherCallback;
    jmethodID watcherCallbackMethod;

    FSEventStreamRef watcherStream;
    thread watcherThread;
    mutex watcherThreadMutex;
    condition_variable watcherThreadStarted;
    CFRunLoopRef threadLoop;
};

Server::Server(JNIEnv* env, jobject watcherCallback, CFArrayRef rootsToWatch, long latencyInMillis) {
    JavaVM* jvm;
    int jvmStatus = env->GetJavaVM(&jvm);
    if (jvmStatus < 0) {
        throw FileWatcherException("Could not store jvm instance");
    }

    this->jvm = jvm;
    // TODO Handle if returns NULL
    this->watcherCallback = env->NewGlobalRef(watcherCallback);
    jclass callbackClass = env->GetObjectClass(watcherCallback);
    this->watcherCallbackMethod = env->GetMethodID(callbackClass, "pathChanged", "(ILjava/lang/String;)V");

    jobject globalWatcherCallback = env->NewGlobalRef(watcherCallback);
    if (globalWatcherCallback == NULL) {
        throw FileWatcherException("Could not get global ref for watcher callback");
    }
    this->watcherCallback = globalWatcherCallback;

    FSEventStreamContext context = {
        0,               // version, must be 0
        (void*) this,    // info
        NULL,            // retain
        NULL,            // release
        NULL             // copyDescription
    };
    FSEventStreamRef watcherStream = FSEventStreamCreate(
        NULL,
        &handleEventsCallback,
        &context,
        rootsToWatch,
        kFSEventStreamEventIdSinceNow,
        latencyInMillis / 1000.0,
        kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagFileEvents | kFSEventStreamCreateFlagWatchRoot);
    if (watcherStream == NULL) {
        throw FileWatcherException("Could not create FSEventStreamCreate to track changes");
    }
    this->watcherStream = watcherStream;

    unique_lock<mutex> lock(watcherThreadMutex);
    this->watcherThread = thread(&Server::run, this);
    this->watcherThreadStarted.wait(lock);
    lock.unlock();
}

Server::~Server() {
    if (threadLoop != NULL) {
        CFRunLoopStop(threadLoop);
    }

    if (watcherThread.joinable()) {
        watcherThread.join();
    }

    if (watcherStream != NULL) {
        FSEventStreamRelease(watcherStream);
    }

    if (watcherCallback != NULL) {
        JNIEnv* env = getThreadEnv();
        if (env != NULL) {
            env->DeleteGlobalRef(watcherCallback);
        }
    }
}

void Server::run() {
    JNIEnv* env = attach_jni(jvm, "File watcher server", true);

    log_fine(env, "Starting thread", NULL);

    CFRunLoopRef threadLoop = CFRunLoopGetCurrent();
    FSEventStreamScheduleWithRunLoop(watcherStream, threadLoop, kCFRunLoopDefaultMode);
    FSEventStreamStart(watcherStream);
    this->threadLoop = threadLoop;

    unique_lock<mutex> lock(watcherThreadMutex);
    watcherThreadStarted.notify_all();
    lock.unlock();

    CFRunLoopRun();

    // Reading the Apple docs it seems we should call FSEventStreamFlushSync() here.
    // But doing so produces this log:
    //
    //     2020-02-17 23:02 java[50430] (FSEvents.framework) FSEventStreamFlushSync(): failed assertion '(SInt64)last_id > 0LL'
    //
    // According to this comment we should not use flush at all, and it's probably broken:
    // https://github.com/nodejs/node/issues/854#issuecomment-294892950
    // As the comment mentions, even Watchman doesn't flush:
    // https://github.com/facebook/watchman/blob/b397e00cf566f361282a456122eef4e909f26182/watcher/fsevents.cpp#L276-L285
    // FSEventStreamFlushSync(watcherStream);
    FSEventStreamStop(watcherStream);
    FSEventStreamInvalidate(watcherStream);

    log_fine(env, "Stopping thread", NULL);

    detach_jni(jvm);
}

static void handleEventsCallback(
    ConstFSEventStreamRef streamRef,
    void* clientCallBackInfo,
    size_t numEvents,
    void* eventPaths,
    const FSEventStreamEventFlags eventFlags[],
    const FSEventStreamEventId eventIds[]) {
    Server* server = (Server*) clientCallBackInfo;
    server->handleEvents(numEvents, (char**) eventPaths, eventFlags, eventIds);
}

void Server::handleEvents(
    size_t numEvents,
    char** eventPaths,
    const FSEventStreamEventFlags eventFlags[],
    const FSEventStreamEventId eventIds[]) {
    JNIEnv* env = getThreadEnv();

    for (int i = 0; i < numEvents; i++) {
        handleEvent(env, eventPaths[i], eventFlags[i]);
    }
}

void Server::handleEvent(JNIEnv* env, char* path, FSEventStreamEventFlags flags) {
    log_fine(env, "Event flags: 0x%x for %s", flags, path);

    jint type;
    if (IS_SET(flags, kFSEventStreamEventFlagHistoryDone)) {
        return;
    } else if (IS_ANY_SET(flags,
                   kFSEventStreamEventFlagRootChanged
                       | kFSEventStreamEventFlagMount
                       | kFSEventStreamEventFlagUnmount
                       | kFSEventStreamEventFlagMustScanSubDirs)) {
        type = FILE_EVENT_INVALIDATE;
    } else if (IS_SET(flags, kFSEventStreamEventFlagItemRenamed)) {
        if (IS_SET(flags, kFSEventStreamEventFlagItemCreated)) {
            type = FILE_EVENT_REMOVED;
        } else {
            type = FILE_EVENT_CREATED;
        }
    } else if (IS_SET(flags, kFSEventStreamEventFlagItemModified)) {
        type = FILE_EVENT_MODIFIED;
    } else if (IS_SET(flags, kFSEventStreamEventFlagItemRemoved)) {
        type = FILE_EVENT_REMOVED;
    } else if (IS_ANY_SET(flags,
                   kFSEventStreamEventFlagItemInodeMetaMod    // file locked
                       | kFSEventStreamEventFlagItemFinderInfoMod
                       | kFSEventStreamEventFlagItemChangeOwner
                       | kFSEventStreamEventFlagItemXattrMod)) {
        type = FILE_EVENT_MODIFIED;
    } else if (IS_SET(flags, kFSEventStreamEventFlagItemCreated)) {
        type = FILE_EVENT_CREATED;
    } else {
        log_warning(env, "Unknown event 0x%x for %s", flags, path);
        type = FILE_EVENT_UNKNOWN;
    }

    log_fine(env, "Changed: %s %d", path, type);

    jstring javaPath = env->NewStringUTF(path);
    env->CallVoidMethod(watcherCallback, watcherCallbackMethod, type, javaPath);
    env->DeleteLocalRef(javaPath);
}

static JNIEnv* lookupThreadEnv(JavaVM* jvm) {
    JNIEnv* env;
    // TODO Verify that JNI 1.6 is the right version
    jint ret = jvm->GetEnv((void**) &(env), JNI_VERSION_1_6);
    if (ret != JNI_OK) {
        fprintf(stderr, "Failed to get JNI env for current thread: %d\n", ret);
        throw FileWatcherException("Failed to get JNI env for current thread");
    }
    return env;
}

JNIEnv* Server::getThreadEnv() {
    return lookupThreadEnv(jvm);
}

Server* startWatching(JNIEnv* env, jclass target, jobjectArray paths, long latencyInMillis, jobject javaCallback) {
    int count = env->GetArrayLength(paths);
    CFMutableArrayRef rootsToWatch = CFArrayCreateMutable(NULL, count, NULL);
    if (rootsToWatch == NULL) {
        throw FileWatcherException("Could not allocate array to store roots to watch");
    }

    try {
        for (int i = 0; i < count; i++) {
            jstring path = (jstring) env->GetObjectArrayElement(paths, i);
            char* watchedPath = java_to_char(env, path, NULL);
            if (watchedPath == NULL) {
                throw FileWatcherException("Could not allocate string to store root to watch");
            }
            log_fine(env, "Watching %s", watchedPath);
            CFStringRef stringPath = CFStringCreateWithCString(NULL, watchedPath, kCFStringEncodingUTF8);
            free(watchedPath);
            if (stringPath == NULL) {
                throw FileWatcherException("Could not create CFStringRef");
            }
            CFArrayAppendValue(rootsToWatch, stringPath);
        }

        return new Server(env, javaCallback, rootsToWatch, latencyInMillis);
    } catch (...) {
        CFRelease(rootsToWatch);
        throw;
    }
}

JNIEXPORT jobject JNICALL
Java_net_rubygrapefruit_platform_internal_jni_OsxFileEventFunctions_startWatching(JNIEnv* env, jclass target, jobjectArray paths, long latencyInMillis, jobject javaCallback, jobject result) {
    Server* server;
    try {
        server = startWatching(env, target, paths, latencyInMillis, javaCallback);
    } catch (const exception& e) {
        log_severe(env, "Caught exception: %s", e.what());
        jclass exceptionClass = env->FindClass("net/rubygrapefruit/platform/NativeException");
        assert(exceptionClass != NULL);
        jint ret = env->ThrowNew(exceptionClass, e.what());
        assert(ret == 0);
        return NULL;
    }

    jclass clsWatcher = env->FindClass("net/rubygrapefruit/platform/internal/jni/OsxFileEventFunctions$WatcherImpl");
    assert(clsWatcher != NULL);
    jmethodID constructor = env->GetMethodID(clsWatcher, "<init>", "(Ljava/lang/Object;)V");
    assert(constructor != NULL);
    return env->NewObject(clsWatcher, constructor, env->NewDirectByteBuffer(server, sizeof(server)));
}

JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_OsxFileEventFunctions_stopWatching(JNIEnv* env, jclass target, jobject detailsObj, jobject result) {
    Server* server = (Server*) env->GetDirectBufferAddress(detailsObj);
    assert(server != NULL);
    delete server;
}

#endif
