# MaterialDecoratedWindow/Dialog delegate to a window backend (decorated-window-jbr or -jni)
# that provides dev.nucleusframework.window.DecoratedWindowKt / DecoratedDialogKt. Apps on the
# Tao backend include neither, so those base classes are absent at release time. The references
# are only reached on the jbr/jni backends, so the unresolved-class warnings are safe to suppress.
-dontwarn dev.nucleusframework.window.material.MaterialDecoratedWindowKt
-dontwarn dev.nucleusframework.window.material.MaterialDecoratedDialogKt
-dontwarn dev.nucleusframework.window.DecoratedWindowKt
-dontwarn dev.nucleusframework.window.DecoratedDialogKt
