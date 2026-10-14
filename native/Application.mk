APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_PLATFORM := android-26
APP_OPTIM := release
# 让 hev-jni.c 的 JNI_OnLoad 把 TProxy* 本地方法注册到应用的 HevTunnel 类
APP_CFLAGS := -O3 -DPKGNAME=com/socksconnect/app -DCLSNAME=HevTunnel
