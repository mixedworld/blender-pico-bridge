// Diagnostic only. Calls the public Khronos OpenXR loader; creates no XR instance or session.
#define XR_USE_PLATFORM_ANDROID
#define XR_NO_PROTOTYPES
#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <dlfcn.h>
#include <sstream>
#include <vector>

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_blenderpicobridge_p7f82b4c1_OpenXrCameraCapabilityTest_probeExtensions(
    JNIEnv* env, jobject, jobject context) {
    void* loader=dlopen("libopenxr_loader.so",RTLD_NOW|RTLD_LOCAL);
    if(!loader) return env->NewStringUTF("{\"error\":\"Khronos loader unavailable\"}");
    auto resolve=reinterpret_cast<PFN_xrGetInstanceProcAddr>(dlsym(loader,"xrGetInstanceProcAddr"));
    if(!resolve) return env->NewStringUTF("{\"error\":\"Khronos global resolver unavailable\"}");
    PFN_xrVoidFunction initializeRaw=nullptr;
    XrResult lookup=resolve(XR_NULL_HANDLE,"xrInitializeLoaderKHR",&initializeRaw);
    if(XR_FAILED(lookup) || !initializeRaw) return env->NewStringUTF("{\"error\":\"Android loader initialization unavailable\"}");
    JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    info.applicationVM=vm;
    // Keep a global reference for the loader's lifetime (the opt-in instrumentation process).
    info.applicationContext=env->NewGlobalRef(context);
    XrResult initialized=reinterpret_cast<PFN_xrInitializeLoaderKHR>(initializeRaw)(
        reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&info));
    std::ostringstream result;result<<"{\"loaderInit\":"<<initialized;
    if(XR_SUCCEEDED(initialized)) {
        PFN_xrVoidFunction enumerateRaw=nullptr;
        XrResult found=resolve(XR_NULL_HANDLE,"xrEnumerateInstanceExtensionProperties",&enumerateRaw);
        result<<",\"resolve\":"<<found;
        if(XR_SUCCEEDED(found) && enumerateRaw) {
            auto enumerate=reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(enumerateRaw);
            uint32_t count=0;XrResult status=enumerate(nullptr,0,&count,nullptr);
            result<<",\"enumeration\":"<<status<<",\"count\":"<<count;
            if(XR_SUCCEEDED(status) && count>0 && count<=1024) {
                std::vector<XrExtensionProperties> properties(count,{XR_TYPE_EXTENSION_PROPERTIES});
                status=enumerate(nullptr,count,&count,properties.data());
                result<<",\"read\":"<<status<<",\"extensions\":[";
                if(XR_SUCCEEDED(status)) for(uint32_t i=0;i<count;++i) {
                    if(i) result<<",";result<<"\""<<properties[i].extensionName<<"\"";
                }
                result<<"]";
            }
        }
    }
    result<<"}";
    return env->NewStringUTF(result.str().c_str());
}
