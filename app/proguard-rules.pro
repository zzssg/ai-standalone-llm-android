# --- JNI boundary -----------------------------------------------------------
#
# The native library resolves these by name at runtime, so R8 must not rename
# or remove them. Getting this wrong produces an app that works in debug and
# throws UnsatisfiedLinkError the moment it is built for release.

# LlamaBridge is the @file:JvmName facade holding the static native methods.
# Its name is baked into every Java_org_zzssg_llmchatapp_llm_LlamaBridge_*
# symbol in llama_wrapper.cpp.
-keep class org.zzssg.llmchatapp.llm.LlamaBridge { *; }

# Any method the native side may call back into.
-keepclasseswithmembernames class * {
    native <methods>;
}

# llama_wrapper.cpp looks these up with GetMethodID by exact name and
# signature, on whatever concrete class implements the interface.
-keep interface org.zzssg.llmchatapp.llm.TokenSink { *; }
-keep class * implements org.zzssg.llmchatapp.llm.TokenSink {
    void onToken(java.lang.String);
    void onDone(int, long);
    void onError(java.lang.String);
}

# --- Diagnostics ------------------------------------------------------------

# Keep line numbers so release crash reports stay readable, while still hiding
# the original source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
