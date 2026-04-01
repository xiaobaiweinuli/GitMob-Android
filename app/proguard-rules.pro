# Retrofit + OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# Gson — 必须保留泛型签名，否则 R8 裁剪后 TypeToken 无法推断类型参数导致崩溃
# 参考：https://github.com/google/gson/blob/main/examples/android-proguard-example/proguard.cfg
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# 保留 TypeToken 及其所有匿名子类的泛型签名（核心修复）
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { void <init>(); }
-keep class com.google.gson.** { *; }

# 保留所有使用 @SerializedName 的字段（防止 R8 重命名）
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
# 保留所有 TypeAdapter 实现类
-keep class * implements com.google.gson.TypeAdapterFactory { void <init>(); }
-keep class * implements com.google.gson.JsonSerializer { void <init>(); }
-keep class * implements com.google.gson.JsonDeserializer { void <init>(); }

# Data models — 保留完整类名和成员（Gson 反序列化依赖字段名，R8 不得重命名）
# AccountInfo / LocalRepo / BookmarkPath 已通过 @SerializedName 逐字段保护，无需整类 keep
-keep class com.gitmob.android.api.** { *; }
-keep class com.gitmob.android.ui.repo.RepoDetailState { *; }
-keep class com.gitmob.android.ui.repo.UploadPhase { *; }

# 保留所有枚举类，防止反序列化时找不到枚举常量
-keep class * extends java.lang.Enum { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    *;
}

# 保留 Markdown 相关组件
-keep class com.gitmob.android.util.MarkdownUtils { *; }
-keep class com.gitmob.android.ui.common.GmMarkdownWebView { *; }
-keep class com.gitmob.android.ui.common.GmWebViewKt { *; }

# 完整保留 Flexmark 库的所有类和成员，防止枚举类被混淆
-keep class com.vladsch.flexmark.** { *; }
-keepclassmembers class com.vladsch.flexmark.** { *; }
-dontwarn com.vladsch.flexmark.**

# ViewModel — R8 不得裁剪 ViewModel 构造函数（SavedStateHandle 注入）
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# Coil
-dontwarn coil.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { void <init>(); }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { void <init>(); }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# JGit
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn org.apache.http.**
-dontwarn org.apache.commons.**
-dontwarn com.jcraft.jsch.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Coil3
-dontwarn coil3.**
-keep class coil3.** { *; }
-keep class coil3.svg.** { *; }
-keep class coil3.network.** { *; }

# PDF 相关库 - 只忽略警告，不强制keep所有类
-dontwarn org.apache.pdfbox.**
-dontwarn com.openhtmltopdf.**
-dontwarn org.apache.fontbox.**
-dontwarn org.apache.xmpbox.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn sun.misc.**
-dontwarn sun.nio.ch.**
