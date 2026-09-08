# R8: missing classes from compile-time-only dependencies (kotlinpoet references javax.lang.model.*)
-dontwarn javax.lang.model.**
-dontwarn com.squareup.kotlinpoet.**

# Disable R8 optimization (keep only shrinking/obfuscation) to avoid breaking reflection
-dontoptimize

# Xposed entry points (loaded by framework via reflection)
-keep class dev.sun.wechat.loader.** { *; }

# @Feature objects (referenced by KSP-generated FeaturesProvider, inheriting BaseFeature)
-keep class dev.sun.wechat.features.core.FeaturesProvider { *; }
-keep class dev.sun.wechat.features.core.BaseFeature { *; }
-keep class * extends dev.sun.wechat.features.core.BaseFeature { *; }

# DexKit IResolveDex + cache + descriptors (loaded via filterIsInstance)
-keep class dev.sun.wechat.dexkit.** { *; }

# WeAgent model/provider/enums/data/settings/service (all runtime agent classes)
-keep class dev.sun.wechat.agent.** { *; }

# UI utilities (showComposeDialog, VectorPathDrawable, icons, etc.)
-keep class dev.sun.wechat.ui.** { *; }

# WeChat API classes (context menu, message, database, etc.)
-keep class dev.sun.wechat.features.api.** { *; }

# All utils (WePrefs, WeLogger, etc.)
-keep class dev.sun.wechat.utils.** { *; }

# Activity, application, constants, preferences
-keep class dev.sun.wechat.activity.** { *; }
-keep class dev.sun.wechat.application.** { *; }
-keep class dev.sun.wechat.constants.** { *; }
-keep class dev.sun.wechat.preferences.** { *; }

# BeanShell serialization
-keep class bsh.** { *; }