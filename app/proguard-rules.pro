-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.slf4j.**
-dontwarn java.beans.**
-dontwarn javax.**

# ExoPlayer / androidx.media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 自定义 SMB DataSource（ExoPlayer 接口实现）
-keep class com.tvnas.filebrowser.media.SmbDataSource { *; }
-keep class com.tvnas.filebrowser.media.SmbDataSourceFactory { *; }
