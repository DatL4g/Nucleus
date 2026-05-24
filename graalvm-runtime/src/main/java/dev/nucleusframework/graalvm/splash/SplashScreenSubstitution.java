package dev.nucleusframework.graalvm.splash;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * GraalVM native-image substitution for {@code java.awt.SplashScreen}.
 * <p>
 * The JVM's splash screen is a feature of the launcher (handled before main()),
 * never available inside a native image. The class is dragged into reachability
 * by callers that close the splash explicitly when the first window shows
 * (see Tao's {@code DecoratedWindowComposable}). Its instance methods
 * ({@code close}, {@code isVisible}, etc.) are JNI-bound to {@code libsplashscreen.a},
 * which the native-image link step does not include — producing
 * {@code Undefined symbols: _Java_java_awt_SplashScreen__1*} on macOS/Linux.
 * <p>
 * Substituting {@code getSplashScreen()} to return {@code null} short-circuits
 * every caller (they all guard with {@code ?.close()}) and prevents the JNI
 * methods from ever being referenced from compiled code.
 */
@TargetClass(className = "java.awt.SplashScreen")
final class Target_java_awt_SplashScreen {

    @Substitute
    public static java.awt.SplashScreen getSplashScreen() {
        return null;
    }
}
