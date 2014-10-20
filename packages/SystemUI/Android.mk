LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    src/com/android/systemui/EventLogTags.logtags

LOCAL_JAVA_LIBRARIES := telephony-common

LOCAL_PACKAGE_NAME := SystemUI
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

include $(BUILD_PACKAGE)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
    jni/com_android_systemui_statusbar_phone_BarBackgroundUpdaterNative.cpp

LOCAL_SHARED_LIBRARIES := \
    libutils \
    libgui \
    liblog

LOCAL_MODULE := SystemUI
# LOCAL_CERTIFICATE := platform
# LOCAL_PRIVILEGED_MODULE := true

# TARGET_OUT_SHARED_LIBRARIES_PRIVILEGED := $(TARGET_OUT_SHARED_LIBRARIES)

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
