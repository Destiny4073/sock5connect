# Material / AppCompat 自带 consumer 规则。
# JNI 通过符号名查找 native 方法，必须保留类名与方法名：
-keepclasseswithmembernames class com.socksconnect.app.HevTunnel {
    native <methods>;
}
