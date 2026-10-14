#include <jni.h>
#include "hev-main.h"

JNIEXPORT jboolean JNICALL
Java_com_socksconnect_app_HevTunnel_start (JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd)
{
    const char *path;
    int res;

    path = (*env)->GetStringUTFChars (env, config_path, NULL);
    if (NULL == path)
        return JNI_FALSE;
    res = hev_socks5_tunnel_main (path, tun_fd);
    (*env)->ReleaseStringUTFChars (env, config_path, path);
    return (0 == res) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_socksconnect_app_HevTunnel_stop (JNIEnv *env, jobject thiz)
{
    hev_socks5_tunnel_quit ();
}
