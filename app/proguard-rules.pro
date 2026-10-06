# sherpa-onnx: the native library reads config fields and calls back into these
# classes through JNI by name, so nothing in the package may be renamed or removed.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Kotlin lambdas passed as JNI callbacks (invoke(...) is looked up by name).
-keepclassmembers class * implements kotlin.jvm.functions.Function3 {
    public java.lang.Object invoke(java.lang.Object, java.lang.Object, java.lang.Object);
}
-keep class kotlin.jvm.functions.Function3 { *; }
-keep class kotlin.Unit { *; }
-keep class java.lang.Integer { *; }

# Diarization progress callback: JNI calls invoke(int, int, long) by name and signature.
-keep class ru.transcrib.app.engine.Diarizer$ProgressCallback { *; }
