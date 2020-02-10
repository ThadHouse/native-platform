#ifdef __linux__

#include "net_rubygrapefruit_platform_internal_jni_LinuxFileEventFunctions.h"
#include "generic.h"

JNIEXPORT jobject JNICALL
Java_net_rubygrapefruit_platform_internal_jni_LinuxFileEventFunctions_startWatching(JNIEnv *env, jclass target, jobjectArray paths, jobject javaCallback, jobject result) {

    log_fine(env, "Hello, Linux", NULL);

    jclass clsWatcher = env->FindClass("net/rubygrapefruit/platform/internal/jni/LinuxFileEventFunctions$WatcherImpl");
    jmethodID constructor = env->GetMethodID(clsWatcher, "<init>", "(Ljava/lang/Object;)V");
    return env->NewObject(clsWatcher, constructor, NULL);
}

JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_LinuxFileEventFunctions_stopWatching(JNIEnv *env, jclass target, jobject detailsObj, jobject result) {
    log_fine(env, "Good bye, Linux", NULL);
}

#endif
