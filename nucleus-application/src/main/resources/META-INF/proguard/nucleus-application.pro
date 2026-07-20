# The generic DecoratedWindow/DecoratedDialog entry points delegate to a window backend
# (decorated-window-jbr or -jni) that provides dev.nucleusframework.window.DecoratedWindowKt /
# DecoratedDialogKt. Apps on the Tao backend include neither, so those base classes are absent
# at release time. The references are only reached on the jbr/jni backends, so suppress the
# unresolved-class warnings rather than forcing every Tao app to depend on an unused backend.
-dontwarn dev.nucleusframework.application.DecoratedWindowKt
-dontwarn dev.nucleusframework.application.DecoratedDialogKt
-dontwarn dev.nucleusframework.window.DecoratedWindowKt
-dontwarn dev.nucleusframework.window.DecoratedDialogKt
