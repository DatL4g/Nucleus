use jni::objects::{GlobalRef, JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jint, jlong, JNI_FALSE, JNI_TRUE, JNI_VERSION_1_8};
use jni::{JNIEnv, JavaVM};
use notify::event::{ModifyKind, RenameMode};
use notify::{
    Config, Event, EventKind, PollWatcher, RecommendedWatcher, RecursiveMode, Result as NotifyResult,
    Watcher,
};
use notify_debouncer_full::{
    new_debouncer, new_debouncer_opt, DebounceEventResult, Debouncer, FileIdMap, RecommendedCache,
};
use once_cell::sync::{Lazy, OnceCell};
use std::collections::HashMap;
use std::ffi::c_void;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

const WATCHER_LEVEL_REGISTRATION_ID: i64 = 0;
const EVENT_KIND_CREATED: i32 = 1;
const EVENT_KIND_MODIFIED: i32 = 2;
const EVENT_KIND_REMOVED: i32 = 3;
const EVENT_KIND_OVERFLOW: i32 = 4;
const EVENT_KIND_MOVED: i32 = 5;
const BACKEND_MODE_NATIVE: i32 = 1;
const BACKEND_MODE_POLLING: i32 = 2;
const DELIVERY_MODE_RAW: i32 = 1;
const DELIVERY_MODE_DEBOUNCED: i32 = 2;

static NEXT_WATCHER_HANDLE: AtomicI64 = AtomicI64::new(1);
static WATCHERS: Lazy<Mutex<HashMap<i64, WatcherState>>> = Lazy::new(|| Mutex::new(HashMap::new()));
static JVM: OnceCell<JavaVM> = OnceCell::new();
static BRIDGE_CLASS: OnceCell<GlobalRef> = OnceCell::new();

#[derive(Clone)]
struct RegistrationState {
    original_root: PathBuf,
    resolved_root: PathBuf,
    recursive: bool,
    live: bool,
}

struct WatcherState {
    registrations: HashMap<i64, RegistrationState>,
    native_watchers: HashMap<i64, NativeWatcherHandle>,
    closed: Arc<AtomicBool>,
    follow_symlinks: bool,
    backend_mode: BackendMode,
    delivery_mode: DeliveryMode,
}

enum NativeWatcherHandle {
    Raw(Arc<Mutex<RecommendedWatcher>>),
    // RecommendedCache resolves to FileIdMap on macOS/Windows and NoCache on Linux.
    Debounced(Arc<Mutex<Debouncer<RecommendedWatcher, RecommendedCache>>>),
    Polling(Arc<Mutex<PollWatcher>>),
    PollingDebounced(Arc<Mutex<Debouncer<PollWatcher, FileIdMap>>>),
}

#[derive(Copy, Clone)]
enum DeliveryMode {
    Raw,
    Debounced { window: Duration },
}

#[derive(Copy, Clone)]
enum BackendMode {
    Native,
    Polling {
        interval: Duration,
        compare_contents: bool,
    },
}

#[derive(Copy, Clone)]
enum MatchedRootKind {
    Original,
    Resolved,
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_8
}

fn cache_bridge_class(env: &mut JNIEnv, class: JClass) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    if let Ok(global) = env.new_global_ref(class) {
        let _ = BRIDGE_CLASS.set(global);
    }
}

fn detect_is_directory(path: &Path) -> i32 {
    match std::fs::metadata(path) {
        Ok(metadata) if metadata.is_dir() => 1,
        Ok(_) => 0,
        Err(_) => -1,
    }
}

fn classify_event(event: &Event) -> Option<i32> {
    match event.kind {
        EventKind::Create(_) => Some(EVENT_KIND_CREATED),
        EventKind::Modify(ModifyKind::Data(_)) | EventKind::Modify(ModifyKind::Metadata(_)) => {
            Some(EVENT_KIND_MODIFIED)
        }
        EventKind::Modify(ModifyKind::Name(RenameMode::Both)) if event.paths.len() >= 2 => {
            Some(EVENT_KIND_MOVED)
        }
        EventKind::Remove(_) => Some(EVENT_KIND_REMOVED),
        _ => None,
    }
}

fn emit_event(
    watcher_handle: i64,
    registration_id: i64,
    origin_native_registration_id: Option<i64>,
    event_kind: i32,
    path: Option<&Path>,
    secondary_path: Option<&Path>,
    needs_rescan: bool,
    is_directory: i32,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Some(bridge_class) = BRIDGE_CLASS.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(16, |env| -> jni::errors::Result<JObject<'_>> {
        let j_path = path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let j_secondary_path = secondary_path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let j_origin_registration = match origin_native_registration_id {
            Some(value) => env.new_object("java/lang/Long", "(J)V", &[JValue::Long(value)]).ok(),
            None => None,
        };
        let null_object = JObject::null();

        let _ = env.call_static_method(
            bridge_class,
            "onNativeEvent",
            "(JJLjava/lang/Long;ILjava/lang/String;Ljava/lang/String;ZI)V",
            &[
                JValue::Long(watcher_handle),
                JValue::Long(registration_id),
                JValue::Object(j_origin_registration.as_ref().unwrap_or(&null_object)),
                JValue::Int(event_kind),
                JValue::Object(j_path.as_ref().unwrap_or(&null_object)),
                JValue::Object(j_secondary_path.as_ref().unwrap_or(&null_object)),
                JValue::Bool(if needs_rescan { JNI_TRUE } else { JNI_FALSE }),
                JValue::Int(is_directory),
            ],
        );
        Ok(JObject::null())
    });
}

fn emit_error(
    watcher_handle: i64,
    registration_id: i64,
    origin_native_registration_id: Option<i64>,
    message: &str,
    recoverable: bool,
    path: Option<&Path>,
) {
    let Some(vm) = JVM.get() else {
        return;
    };
    let Some(bridge_class) = BRIDGE_CLASS.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread_as_daemon() else {
        return;
    };
    let _ = env.with_local_frame(16, |env| -> jni::errors::Result<JObject<'_>> {
        let Ok(j_message) = env.new_string(message) else {
            return Ok(JObject::null());
        };
        let j_path = path.and_then(|value| {
            env.new_string(value.to_string_lossy().as_ref())
                .ok()
                .map(JObject::from)
        });
        let j_origin_registration = match origin_native_registration_id {
            Some(value) => env.new_object("java/lang/Long", "(J)V", &[JValue::Long(value)]).ok(),
            None => None,
        };
        let null_object = JObject::null();

        let _ = env.call_static_method(
            bridge_class,
            "onNativeError",
            "(JJLjava/lang/Long;Ljava/lang/String;ZLjava/lang/String;)V",
            &[
                JValue::Long(watcher_handle),
                JValue::Long(registration_id),
                JValue::Object(j_origin_registration.as_ref().unwrap_or(&null_object)),
                JValue::Object(&JObject::from(j_message)),
                JValue::Bool(if recoverable { JNI_TRUE } else { JNI_FALSE }),
                JValue::Object(j_path.as_ref().unwrap_or(&null_object)),
            ],
        );
        Ok(JObject::null())
    });
}

fn path_matches_root(root: &Path, recursive: bool, path: &Path) -> bool {
    if recursive {
        path == root || path.starts_with(root)
    } else {
        path == root || path.parent() == Some(root)
    }
}

fn match_registration(registration: &RegistrationState, path: &Path) -> Option<MatchedRootKind> {
    if path_matches_root(&registration.original_root, registration.recursive, path) {
        Some(MatchedRootKind::Original)
    } else if path_matches_root(&registration.resolved_root, registration.recursive, path) {
        Some(MatchedRootKind::Resolved)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn event_with_paths(kind: EventKind, paths: &[&str]) -> Event {
        Event {
            kind,
            paths: paths.iter().map(PathBuf::from).collect(),
            attrs: Default::default(),
        }
    }

    #[test]
    fn classify_event_accepts_only_paired_rename_with_both_paths_for_moved() {
        let paired_rename =
            event_with_paths(EventKind::Modify(ModifyKind::Name(RenameMode::Both)), &["from", "to"]);
        let paired_rename_missing_to =
            event_with_paths(EventKind::Modify(ModifyKind::Name(RenameMode::Both)), &["from"]);
        let rename_from =
            event_with_paths(EventKind::Modify(ModifyKind::Name(RenameMode::From)), &["from"]);
        let rename_to = event_with_paths(EventKind::Modify(ModifyKind::Name(RenameMode::To)), &["to"]);
        let rename_any =
            event_with_paths(EventKind::Modify(ModifyKind::Name(RenameMode::Any)), &["from", "to"]);

        assert_eq!(classify_event(&paired_rename), Some(EVENT_KIND_MOVED));
        assert_eq!(classify_event(&paired_rename_missing_to), None);
        assert_eq!(classify_event(&rename_from), None);
        assert_eq!(classify_event(&rename_to), None);
        assert_eq!(classify_event(&rename_any), None);
    }
}

fn with_registration_by_id(registration_id: i64, watcher_handle: i64) -> Option<RegistrationState> {
    WATCHERS.lock().ok().and_then(|watchers| {
        watchers
            .get(&watcher_handle)
            .and_then(|state| state.registrations.get(&registration_id).cloned())
    })
}

fn with_live_registration_by_id(registration_id: i64, watcher_handle: i64) -> Option<RegistrationState> {
    with_registration_by_id(registration_id, watcher_handle).filter(|registration| registration.live)
}

fn handle_debounce_result(
    watcher_handle: i64,
    origin_native_registration_id: i64,
    result: DebounceEventResult,
) {
    match result {
        Ok(events) => {
            for debounced_event in events {
                handle_notify_result(watcher_handle, origin_native_registration_id, Ok(debounced_event.event));
            }
        }
        Err(errors) => {
            for error in errors {
                handle_notify_result(watcher_handle, origin_native_registration_id, Err(error));
            }
        }
    }
}

fn handle_notify_result(watcher_handle: i64, origin_native_registration_id: i64, result: NotifyResult<Event>) {
    match result {
        Ok(event) => {
            let first_path = event.paths.first().map(PathBuf::as_path);
            let second_path = event.paths.get(1).map(PathBuf::as_path);
            let registration = with_live_registration_by_id(origin_native_registration_id, watcher_handle);
            let moved_supported = WATCHERS.lock().ok().and_then(|watchers| {
                watchers.get(&watcher_handle).map(|state| {
                    matches!(state.backend_mode, BackendMode::Native)
                        && matches!(state.delivery_mode, DeliveryMode::Debounced { .. })
                })
            }) == Some(true);

            if let Some(event_kind) = classify_event(&event) {
                if registration.is_some() && (event_kind != EVENT_KIND_MOVED || moved_supported) {
                    emit_event(
                        watcher_handle,
                        WATCHER_LEVEL_REGISTRATION_ID,
                        Some(origin_native_registration_id),
                        event_kind,
                        first_path,
                        second_path,
                        event.need_rescan(),
                        first_path.map(detect_is_directory).unwrap_or(-1),
                    );
                } else if event_kind == EVENT_KIND_MOVED && registration.is_some() && event.need_rescan() {
                    emit_event(
                        watcher_handle,
                        WATCHER_LEVEL_REGISTRATION_ID,
                        None,
                        EVENT_KIND_OVERFLOW,
                        None,
                        None,
                        true,
                        -1,
                    );
                }
            } else if registration.is_some() && event.need_rescan() {
                emit_event(
                    watcher_handle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    None,
                    EVENT_KIND_OVERFLOW,
                    None,
                    None,
                    true,
                    -1,
                );
            }
        }
        Err(error) => {
            let first_path = error.paths.first().map(PathBuf::as_path);
            let registration = with_live_registration_by_id(origin_native_registration_id, watcher_handle);

            if let Some(registration) = registration {
                let error_path = first_path.filter(|path| match_registration(&registration, path).is_some());
                let callback_registration_id = if error_path.is_some() {
                    WATCHER_LEVEL_REGISTRATION_ID
                } else {
                    origin_native_registration_id
                };
                emit_error(
                    watcher_handle,
                    callback_registration_id,
                    Some(origin_native_registration_id),
                    &error.to_string(),
                    true,
                    error_path,
                );
            } else if first_path.is_none() {
                emit_error(
                    watcher_handle,
                    WATCHER_LEVEL_REGISTRATION_ID,
                    None,
                    &error.to_string(),
                    true,
                    None,
                );
            }
        }
    }
}

fn native_handle_watch(
    handle: &NativeWatcherHandle,
    path: &Path,
    recursive_mode: RecursiveMode,
) -> notify::Result<()> {
    match handle {
        NativeWatcherHandle::Raw(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock raw watcher"))?;
            watcher_guard.watch(path, recursive_mode)
        }
        NativeWatcherHandle::Debounced(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock debounced watcher"))?;
            watcher_guard.watch(path, recursive_mode)
        }
        NativeWatcherHandle::Polling(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock poll watcher"))?;
            watcher_guard.watch(path, recursive_mode)
        }
        NativeWatcherHandle::PollingDebounced(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock debounced poll watcher"))?;
            watcher_guard.watch(path, recursive_mode)
        }
    }
}

fn native_handle_unwatch(handle: &NativeWatcherHandle, path: &Path) -> notify::Result<()> {
    match handle {
        NativeWatcherHandle::Raw(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock raw watcher"))?;
            watcher_guard.unwatch(path)
        }
        NativeWatcherHandle::Debounced(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock debounced watcher"))?;
            watcher_guard.unwatch(path)
        }
        NativeWatcherHandle::Polling(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock poll watcher"))?;
            watcher_guard.unwatch(path)
        }
        NativeWatcherHandle::PollingDebounced(watcher) => {
            let mut watcher_guard = watcher
                .lock()
                .map_err(|_| notify::Error::generic("failed to lock debounced poll watcher"))?;
            watcher_guard.unwatch(path)
        }
    }
}

fn create_native_handle(
    watcher_handle: i64,
    registration_id: i64,
    follow_symlinks: bool,
    backend_mode: BackendMode,
    delivery_mode: DeliveryMode,
) -> Option<NativeWatcherHandle> {
    match backend_mode {
        BackendMode::Native => {
            let config = Config::default().with_follow_symlinks(follow_symlinks);
            match delivery_mode {
                DeliveryMode::Raw => RecommendedWatcher::new(
                    move |result| handle_notify_result(watcher_handle, registration_id, result),
                    config,
                )
                .ok()
                .map(|watcher| NativeWatcherHandle::Raw(Arc::new(Mutex::new(watcher)))),
                DeliveryMode::Debounced { window } => new_debouncer(
                    window,
                    None,
                    move |result| handle_debounce_result(watcher_handle, registration_id, result),
                )
                .ok()
                .and_then(|mut debouncer| {
                    if debouncer.configure(config).is_err() {
                        return None;
                    }
                    Some(NativeWatcherHandle::Debounced(Arc::new(Mutex::new(debouncer))))
                }),
            }
        }
        BackendMode::Polling {
            interval,
            compare_contents,
        } => {
            let config = Config::default()
                .with_follow_symlinks(follow_symlinks)
                .with_poll_interval(interval)
                .with_compare_contents(compare_contents);
            match delivery_mode {
                DeliveryMode::Raw => PollWatcher::new(
                    move |result| handle_notify_result(watcher_handle, registration_id, result),
                    config,
                )
                .ok()
                .map(|watcher| NativeWatcherHandle::Polling(Arc::new(Mutex::new(watcher)))),
                DeliveryMode::Debounced { window } => new_debouncer_opt::<_, PollWatcher, FileIdMap>(
                    window,
                    None,
                    move |result| handle_debounce_result(watcher_handle, registration_id, result),
                    FileIdMap::new(),
                    config,
                )
                .ok()
                .map(|watcher| NativeWatcherHandle::PollingDebounced(Arc::new(Mutex::new(watcher)))),
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeIsSupported(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeCreate(
    mut env: JNIEnv,
    class: JClass,
    follow_symlinks: jboolean,
    backend_mode: jint,
    delivery_mode: jint,
    debounce_window_millis: jlong,
    poll_interval_millis: jlong,
    compare_contents: jboolean,
) -> jlong {
    cache_bridge_class(&mut env, class);

    let backend_mode = match backend_mode {
        BACKEND_MODE_NATIVE => BackendMode::Native,
        BACKEND_MODE_POLLING if poll_interval_millis > 0 => BackendMode::Polling {
            interval: Duration::from_millis(poll_interval_millis as u64),
            compare_contents: compare_contents != JNI_FALSE,
        },
        BACKEND_MODE_POLLING => return 0,
        _ => return 0,
    };
    let delivery_mode = match delivery_mode {
        DELIVERY_MODE_RAW => DeliveryMode::Raw,
        DELIVERY_MODE_DEBOUNCED if debounce_window_millis > 0 => DeliveryMode::Debounced {
            window: Duration::from_millis(debounce_window_millis as u64),
        },
        DELIVERY_MODE_DEBOUNCED => return 0,
        _ => return 0,
    };

    let watcher_handle = NEXT_WATCHER_HANDLE.fetch_add(1, Ordering::Relaxed);
    let closed = Arc::new(AtomicBool::new(false));

    if let Ok(mut watchers) = WATCHERS.lock() {
        watchers.insert(
            watcher_handle,
            WatcherState {
                registrations: HashMap::new(),
                native_watchers: HashMap::new(),
                closed,
                follow_symlinks: follow_symlinks != JNI_FALSE,
                backend_mode,
                delivery_mode,
            },
        );
        watcher_handle
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeClose(
    _env: JNIEnv,
    _class: JClass,
    watcher_handle: jlong,
) {
    let Some((native_watchers, closed)) = WATCHERS.lock().ok().and_then(|mut watchers| {
        let mut state = watchers.remove(&watcher_handle)?;
        state.closed.store(true, Ordering::Release);
        Some((
            state
                .native_watchers
                .drain()
                .into_iter()
                .filter_map(|(registration_id, native_handle)| {
                    state
                        .registrations
                        .get(&registration_id)
                        .map(|registration| (native_handle, registration.original_root.clone()))
                })
                .collect::<Vec<_>>(),
            Arc::clone(&state.closed),
        ))
    }) else {
        return;
    };
    closed.store(true, Ordering::Release);
    for (native_handle, path) in native_watchers {
        let _ = native_handle_unwatch(&native_handle, &path);
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeWatch(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    registration_id: jlong,
    path: JString,
    recursive: jboolean,
    _name: JString,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let original_root = PathBuf::from(path.to_string_lossy().into_owned());
    let resolved_root = original_root
        .canonicalize()
        .unwrap_or_else(|_| original_root.clone());
    let recursive_mode = if recursive == JNI_FALSE {
        RecursiveMode::NonRecursive
    } else {
        RecursiveMode::Recursive
    };
    let follow_symlinks = {
        let Ok(watchers) = WATCHERS.lock() else {
            return JNI_FALSE;
        };
        let Some(state) = watchers.get(&watcher_handle) else {
            return JNI_FALSE;
        };
        (state.follow_symlinks, state.backend_mode, state.delivery_mode)
    };
    let (follow_symlinks, backend_mode, delivery_mode) = follow_symlinks;
    if matches!(backend_mode, BackendMode::Polling { .. }) && std::fs::metadata(&original_root).is_err() {
        return JNI_FALSE;
    }
    let mut native_watcher = Some(match create_native_handle(
        watcher_handle,
        registration_id,
        follow_symlinks,
        backend_mode,
        delivery_mode,
    ) {
        Some(native_watcher) => native_watcher,
        None => return JNI_FALSE,
    });

    if native_handle_watch(
        native_watcher.as_ref().expect("native watcher must exist"),
        &original_root,
        recursive_mode,
    )
    .is_err()
    {
        return JNI_FALSE;
    }

    let registration = RegistrationState {
        original_root,
        resolved_root,
        recursive: recursive != JNI_FALSE,
        live: true,
    };
    let should_cleanup = {
        let Ok(mut watchers) = WATCHERS.lock() else {
            return JNI_FALSE;
        };
        match watchers.get_mut(&watcher_handle) {
            Some(state) if !state.closed.load(Ordering::Acquire) => {
                state.registrations.insert(registration_id, registration.clone());
                state.native_watchers.insert(
                    registration_id,
                    native_watcher.take().expect("native watcher must exist"),
                );
                false
            }
            _ => true,
        }
    };

    if should_cleanup {
        let registration_path = registration.original_root.clone();
        if let Some(native_handle) = native_watcher.take() {
            let _ = native_handle_unwatch(&native_handle, &registration_path);
        }
        JNI_FALSE
    } else {
        JNI_TRUE
    }
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeUnwatch(
    _env: JNIEnv,
    _class: JClass,
    watcher_handle: jlong,
    registration_id: jlong,
) {
    let Some((native_handle, path)) = WATCHERS.lock().ok().and_then(|mut watchers| {
        let state = watchers.get_mut(&watcher_handle)?;
        let registration = state.registrations.remove(&registration_id)?;
        let path = registration.original_root;
        let native_handle = state.native_watchers.remove(&registration_id)?;
        Some((native_handle, path))
    }) else {
        return;
    };
    let _ = native_handle_unwatch(&native_handle, &path);
}

#[no_mangle]
// Temporary JNI test probe kept in the production cdylib to exercise native-side
// liveness and path-matching gates from JVM tests. Do not extend this surface
// into runtime API without a separate design decision.
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathEvent(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    event_kind: jint,
    path: JString,
    secondary_path: JString,
    needs_rescan: jboolean,
    is_directory: jint,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let first_path = PathBuf::from(path.to_string_lossy().into_owned());
    let second_path = if secondary_path.is_null() {
        None
    } else {
        env.get_string(&secondary_path)
            .ok()
            .map(|value| PathBuf::from(value.to_string_lossy().into_owned()))
    };

    if event_kind == EVENT_KIND_MOVED && second_path.is_none() {
        return JNI_FALSE;
    }

    let Some(registration) = with_live_registration_by_id(origin_native_registration_id, watcher_handle) else {
        return JNI_FALSE;
    };
    if match_registration(&registration, &first_path).is_none() {
        return JNI_FALSE;
    }
    emit_event(
        watcher_handle,
        WATCHER_LEVEL_REGISTRATION_ID,
        Some(origin_native_registration_id),
        event_kind,
        Some(first_path.as_path()),
        second_path.as_deref(),
        needs_rescan != JNI_FALSE,
        is_directory,
    );
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathError(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    message: JString,
    recoverable: jboolean,
    path: JString,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(message) = env.get_string(&message) else {
        return JNI_FALSE;
    };
    let Ok(path) = env.get_string(&path) else {
        return JNI_FALSE;
    };
    let first_path = PathBuf::from(path.to_string_lossy().into_owned());

    let Some(registration) = with_live_registration_by_id(origin_native_registration_id, watcher_handle) else {
        return JNI_FALSE;
    };
    if match_registration(&registration, &first_path).is_none() {
        return JNI_FALSE;
    }

    emit_error(
        watcher_handle,
        WATCHER_LEVEL_REGISTRATION_ID,
        Some(origin_native_registration_id),
        message.to_string_lossy().as_ref(),
        recoverable != JNI_FALSE,
        Some(first_path.as_path()),
    );
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_dev_nucleusframework_fswatcher_NativeFsWatcherBridge_nativeDebugEmitPathlessError(
    mut env: JNIEnv,
    class: JClass,
    watcher_handle: jlong,
    origin_native_registration_id: jlong,
    message: JString,
    recoverable: jboolean,
) -> jboolean {
    cache_bridge_class(&mut env, class);

    let Ok(message) = env.get_string(&message) else {
        return JNI_FALSE;
    };

    let Some(_registration) = with_live_registration_by_id(origin_native_registration_id, watcher_handle) else {
        return JNI_FALSE;
    };

    emit_error(
        watcher_handle,
        origin_native_registration_id,
        Some(origin_native_registration_id),
        message.to_string_lossy().as_ref(),
        recoverable != JNI_FALSE,
        None,
    );
    JNI_TRUE
}
