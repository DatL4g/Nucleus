# The native OS theme observer calls back into the JVM through onThemeChanged(boolean) by name
# via JNI, and the JVM calls the native nativeIsDark/nativeStartObserving/nativeStopObserving
# methods. ProGuard cannot see these native call sites, so without keeping them a minified build
# fails with NoSuchMethodError (onThemeChanged) or UnsatisfiedLinkError. Mirrors the module's
# native-image reachability metadata.
-keepclasseswithmembernames,includedescriptorclasses class dev.nucleusframework.darkmodedetector.** {
    native <methods>;
}
-keepclassmembers class dev.nucleusframework.darkmodedetector.** {
    void onThemeChanged(boolean);
}
