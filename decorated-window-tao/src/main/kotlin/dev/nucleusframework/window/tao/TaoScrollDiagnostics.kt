package dev.nucleusframework.window.tao

/**
 * Live scroll-pipeline diagnostics for the Tao Windows backend, meant for
 * test/diagnostic screens (e.g. the example app's ScrollTestScreen).
 *
 * Answers two questions a tester can't see from the outside:
 *  - which input pipeline is running — a DirectManipulation viewport
 *    (OS-computed pan/pinch/inertia) or the synthesized-wheel fallback;
 *  - whether an inertia phase is active right now, and whose it is
 *    (OS-computed vs. the software [render.TaoWheelFling] glide).
 *
 * Values are written by the window host on its event-loop thread and read
 * from anywhere (@Volatile); with several windows open the last writer
 * wins — fine for a diagnostic. Always false on non-Windows platforms.
 */
public object TaoScrollDiagnostics {
    /** A DirectManipulation viewport is attached to the active window. */
    @Volatile
    public var directManipulationAttached: Boolean = false
        internal set

    /** The OS is running a manipulation right now (gesture or inertia). */
    @Volatile
    public var directManipulationSession: Boolean = false
        internal set

    /** The software wheel fling is emitting glide ticks right now. */
    @Volatile
    public var softwareFlingActive: Boolean = false
        internal set
}
