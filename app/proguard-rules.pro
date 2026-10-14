# Material / AppCompat 自带 consumer 规则。
# HevTunnel 的 native 方法由 JNI_OnLoad 按名字注册，且未在 Kotlin 中全部显式调用
# （如 TProxyGetStats），必须禁止 R8 删除或改名：
-keep class com.socksconnect.app.HevTunnel { *; }
