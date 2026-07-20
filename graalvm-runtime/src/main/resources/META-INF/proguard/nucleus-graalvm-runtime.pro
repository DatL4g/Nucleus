# The GraalVM native-image build-time API (org.graalvm.*, com.oracle.svm.*) is a compileOnly
# dependency: it exists only while native-image compiles the app, never on a plain JVM/ProGuard
# classpath. The @TargetClass substitutions and Platform predicates in this module reference it
# unconditionally, so a minified JVM distribution would otherwise fail with unresolved-class
# warnings. These references are inert off native-image, so the warnings are safe to suppress.
-dontwarn dev.nucleusframework.graalvm.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
