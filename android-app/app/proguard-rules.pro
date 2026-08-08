# Reasonix Proot App — R8 混淆保留规则

# 主 Activity 通过 WebView 的 @JavascriptInterface 桥接 JS<->Java，
# 必须整体保留（方法名被混淆会导致 window.onTermData 等调用失败）。
-keep class com.rxproot.app.MainActivity { *; }

# Android WebView / 系统组件
-keep class android.webkit.** { *; }

# 避免默认 keep 之外的组件被误删
-keepattributes JavascriptInterface
-keepattributes *Annotation*
