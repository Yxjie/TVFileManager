-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.**

# Bugly
-keep class com.tencent.bugly.** { *; }
-dontwarn com.tencent.bugly.**

# ExoPlayer / androidx.media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 自定义 SMB DataSource（ExoPlayer 接口实现）
-keep class com.tvnas.filebrowser.media.SmbDataSource { *; }
-keep class com.tvnas.filebrowser.media.SmbDataSourceFactory { *; }

# ===== R8 missing_rules.txt（app/build/outputs/mapping/release/，2026-09-11 构建）=====
# BouncyCastle 引用的 JDK Kerberos/GSSAPI 可选类，Android 运行时不提供也不会走到，dontwarn 即可
-dontwarn org.ietf.jgss.**

# ===== Gson 反射序列化的持久化模型（ServerStorage 用 Gson 存 SharedPreferences）=====
# 字段名混淆后 JSON 键随之改变，已保存的服务器配置会解析失效，必须保留字段名
-keep class com.tvnas.filebrowser.smb.ServerConfig { *; }
-keep class com.tvnas.filebrowser.smb.ServerStorage$StoredServerConfig { *; }

# Gson 通过匿名 TypeToken 子类读取泛型签名获取目标类型；R8 剥离 Signature
# 属性后 getType() 退化为 raw List，元素反序列化成 LinkedTreeMap，
# getServers() 里 CCE 被 catch 吞掉 → 配置永远读回为空 →「服务器配置不存在」
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
