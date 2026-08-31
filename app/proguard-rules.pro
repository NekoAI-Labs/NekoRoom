# Release-only keep rules for JNI entry points.  R8 can otherwise rename a class while the
# native libraries still look it up through its Java/Kotlin name.
-keep class com.example.supertonic.SuperTonicJNI { *; }
-keep class com.moeavatar.llm.LocalLlmBridge { *; }
-keep class com.moeavatar.llm.LocalLlmBridge$TokenListener { *; }
-keep class com.moeavatar.vad.FireRedVadBridge { *; }
-keep class com.chatwaifu.live2d.JniBridgeJava { *; }

# sherpa-mnn-jni reflects configuration fields (for example
# OnlineRecognizerConfig.decodingMethod) by their exact Java names.  Preserve the complete
# binding package, otherwise a minified release aborts while creating the recognizer.
-keep class com.k2fsa.sherpa.mnn.** { *; }

# Keep any future JNI method names as well, while allowing the rest of the app to be shrunk and
# obfuscated normally.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Gson reads generic type annotations at runtime.
-keepattributes Signature,*Annotation*
