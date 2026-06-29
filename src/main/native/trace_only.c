#include <jni.h>
#include <jvmti.h>
#include <stdio.h>
#include <string.h>

static jvmtiEnv* g_jvmti = NULL;
static FILE* fp = NULL;

static void JNICALL onEntry(jvmtiEnv* jvmti, JNIEnv* env, jthread t, jmethodID m) {
    jclass dc;
    char* cn = NULL;
    char* mn = NULL;

    (*jvmti)->GetMethodDeclaringClass(jvmti, m, &dc);
    (*jvmti)->GetClassSignature(jvmti, dc, &cn, NULL);
    (*jvmti)->GetMethodName(jvmti, m, &mn, NULL, NULL);

    if (cn && strstr(cn, "MultiblockBuilder") && mn && strcmp(mn, "register") == 0) {
        if (!fp) {
            fp = fopen("gtocutcorners_TRACE_NEW.log", "w");
        }
        fprintf(fp, "=== MultiblockBuilder.register ===\n");
        fflush(fp);

        jclass ec = (*env)->FindClass(env, "java/lang/Exception");
        jobject ex = (*env)->NewObject(env, ec, (*env)->GetMethodID(env, ec, "<init>", "()V"));
        jobjectArray st = (*env)->CallObjectMethod(
            env,
            ex,
            (*env)->GetMethodID(env, ec, "getStackTrace", "()[Ljava/lang/StackTraceElement;")
        );
        int len = (*env)->GetArrayLength(env, st);
        jclass sc = (*env)->FindClass(env, "java/lang/StackTraceElement");
        jmethodID ts = (*env)->GetMethodID(env, sc, "toString", "()Ljava/lang/String;");

        for (int i = 0; i < len && i < 30; i++) {
            jobject e = (*env)->GetObjectArrayElement(env, st, i);
            jstring s = (*env)->CallObjectMethod(env, e, ts);
            const char* cs = (*env)->GetStringUTFChars(env, s, NULL);
            fprintf(fp, "  %s\n", cs);
            fflush(fp);
            (*env)->ReleaseStringUTFChars(env, s, cs);
        }
        fprintf(fp, "\n");
        fflush(fp);
    }

    if (cn) {
        (*jvmti)->Deallocate(jvmti, (unsigned char*)cn);
    }
    if (mn) {
        (*jvmti)->Deallocate(jvmti, (unsigned char*)mn);
    }
}

JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* o, void* r) {
    fprintf(stderr, "=== NEW TRACING DLL LOADED ===\n");
    fflush(stderr);
    (*vm)->GetEnv(vm, (void**)&g_jvmti, JVMTI_VERSION_1_2);
    if (!g_jvmti) {
        return -1;
    }

    jvmtiCapabilities c = {0};
    c.can_generate_method_entry_events = 1;
    (*g_jvmti)->AddCapabilities(g_jvmti, &c);

    jvmtiEventCallbacks cb = {0};
    cb.MethodEntry = &onEntry;
    (*g_jvmti)->SetEventCallbacks(g_jvmti, &cb, sizeof(cb));
    (*g_jvmti)->SetEventNotificationMode(g_jvmti, JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY, NULL);
    return 0;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* r) {
    return JNI_VERSION_1_8;
}
