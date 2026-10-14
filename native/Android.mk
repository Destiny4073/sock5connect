# 基于 hev-socks5-tunnel 官方 Android.mk 修改：去掉可执行文件构建，
# 并把本项目的 JNI 封装（tunnel_jni.c）编入同一个共享库。

TOP_PATH := $(call my-dir)

ifeq ($(filter $(modules-get-list),yaml),)
    include $(TOP_PATH)/third-part/yaml/Android.mk
endif
ifeq ($(filter $(modules-get-list),lwip),)
    include $(TOP_PATH)/third-part/lwip/Android.mk
endif
ifeq ($(filter $(modules-get-list),hev-task-system),)
    ENABLE_MEMALLOC_SLICE := 0
    include $(TOP_PATH)/third-part/hev-task-system/Android.mk
endif

LOCAL_PATH := $(TOP_PATH)
SRCDIR := $(LOCAL_PATH)/src

include $(LOCAL_PATH)/build.mk
HEV_SOCKS5_TUNNEL_SRC := $(patsubst $(SRCDIR)/%,src/%,$(SRCFILES))
HEV_SOCKS5_TUNNEL_INCLUDES := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/src/misc \
    $(LOCAL_PATH)/src/core/include \
    $(LOCAL_PATH)/third-part/yaml/include \
    $(LOCAL_PATH)/third-part/lwip/src/include \
    $(LOCAL_PATH)/third-part/lwip/src/ports/include \
    $(LOCAL_PATH)/third-part/hev-task-system/include
HEV_SOCKS5_TUNNEL_LDFLAGS := \
    -Wl,-z,max-page-size=16384 \
    -Wl,-z,common-page-size=16384

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel
LOCAL_SRC_FILES := $(HEV_SOCKS5_TUNNEL_SRC) tunnel_jni.c
LOCAL_C_INCLUDES := $(HEV_SOCKS5_TUNNEL_INCLUDES)
LOCAL_CFLAGS += -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED -DENABLE_LIBRARY
LOCAL_CFLAGS += -fno-stack-clash-protection $(VERSION_CFLAGS)
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_CFLAGS += -mfpu=neon
endif
LOCAL_STATIC_LIBRARIES := yaml lwip hev-task-system
LOCAL_LDFLAGS += $(HEV_SOCKS5_TUNNEL_LDFLAGS)
include $(BUILD_SHARED_LIBRARY)
