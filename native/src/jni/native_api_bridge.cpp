#include "core/native_api.h"
#include "jni/jni_bridge.h"
#include "jni/jni_hooks.h"

namespace vector::native::jni {
AEVIX_DEF_NATIVE_METHOD(void, NativeAPI, recordNativeEntrypoint, jstring jstr) {
    lsplant::JUTFString str(env, jstr);
    vector::native::RegisterNativeLib(str);
}

static JNINativeMethod gMethods[] = {
    AEVIX_NATIVE_METHOD(NativeAPI, recordNativeEntrypoint, "(Ljava/lang/String;)V")};

void RegisterNativeApiBridge(JNIEnv *env) { REGISTER_AEVIX_NATIVE_METHODS(NativeAPI); }

}  // namespace vector::native::jni
