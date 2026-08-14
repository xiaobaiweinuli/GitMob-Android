# 项目级混淆/保留规则
#
# 默认由 getDefaultProguardFile("proguard-android-optimize.txt") 提供基础规则，
# 这里仅补充项目自定义需要的 keep/dontwarn 规则。
#
# AGP 9.x + R8 已支持默认 keep：
#  - @Keep / @AndroidEntryPoint 注解的类
#  - AndroidManifest 中声明的组件
#  - Serializable / Parcelable
#  - 视图反射（Compose / XML 绑定）
#
# 若新增反射调用或跨进程序列化的类型，按具体场景 keep，无需泛开 keep **
