# Keep JNI entry-point names when optimizing the Android build. Acoustic prediction
# and persistence use direct Kotlin calls / explicit JSON codecs and need no broad keeps.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
