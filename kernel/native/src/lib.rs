//! Kernel-owned scalar Cedar ABI. No request, entity or permission cache.
use cedar_policy::{
    Authorizer, Context, Decision, Entities, EntityId, EntityTypeName, EntityUid, Policy, PolicyId,
    PolicySet, Request, Schema, SlotId, Template,
};
use jni::{
    objects::{JClass, JString},
    sys::{jlong, jlongArray, jstring},
    JNIEnv,
};
use serde::Deserialize;
use serde_json::Value;
use std::{
    alloc::{GlobalAlloc, Layout, System},
    cell::Cell,
    collections::{BTreeMap, HashMap, HashSet},
    panic::{catch_unwind, AssertUnwindSafe},
    str::FromStr,
    sync::{
        atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering},
        mpsc::{sync_channel, SyncSender},
        Arc, Mutex, OnceLock, Weak,
    },
    time::{Duration, Instant},
};
const INPUT: usize = 1_048_576;
const BUNDLE_INPUT: usize = 262_144;
const BUNDLES: usize = 32;
const HANDLES: usize = 128;
const CACHE_BYTES: u64 = 64 * 1024 * 1024;
const MAGIC: u64 = 0x4b75000100000000;
const INVALID: u64 = MAGIC | 2;
const FAILURE: u64 = MAGIC | 3;
const EXPIRED: u64 = MAGIC | 4;
#[repr(align(64))]
struct HotCounter(AtomicU64);
impl std::ops::Deref for HotCounter {
    type Target = AtomicU64;
    fn deref(&self) -> &AtomicU64 {
        &self.0
    }
}
static LIVE: HotCounter = HotCounter(AtomicU64::new(0));
static PEAK: HotCounter = HotCounter(AtomicU64::new(0));
static ALLOCATED: AtomicU64 = AtomicU64::new(0);
static ALLOCATIONS: AtomicU64 = AtomicU64::new(0);
static ACCOUNTING_VALID: AtomicBool = AtomicBool::new(true);
static EVALUATIONS: AtomicU64 = AtomicU64::new(0);
static FAILURES: AtomicU64 = AtomicU64::new(0);
static QUEUED: AtomicUsize = AtomicUsize::new(0);
static INPUTS: AtomicUsize = AtomicUsize::new(0);
static INVOCATIONS: AtomicU64 = AtomicU64::new(0);
static FFI_ENTRIES: AtomicU64 = AtomicU64::new(0);
#[derive(Clone, Copy)]
struct Accounting {
    bytes: u64,
    count: u64,
    flushed_bytes: u64,
    flushed_count: u64,
}
thread_local! {
    static LOCAL_ACCOUNTING: Cell<Accounting> = const { Cell::new(Accounting { bytes: 0, count: 0, flushed_bytes: 0, flushed_count: 0 }) };
}
fn local_allocated() -> u64 {
    LOCAL_ACCOUNTING.try_with(|c| c.get().bytes).unwrap_or(0)
}
fn record_allocation(size: u64) {
    if LOCAL_ACCOUNTING
        .try_with(|cell| {
            let mut local = cell.get();
            match (local.bytes.checked_add(size), local.count.checked_add(1)) {
                (Some(bytes), Some(count)) => {
                    local.bytes = bytes;
                    local.count = count;
                    cell.set(local);
                }
                _ => {
                    ACCOUNTING_VALID.store(false, Ordering::Relaxed);
                }
            }
        })
        .is_err()
    {
        ACCOUNTING_VALID.store(false, Ordering::Relaxed);
    }
}
fn flush_allocations() {
    let _ = LOCAL_ACCOUNTING.try_with(|cell| {
        let mut local = cell.get();
        let bytes = local.bytes - local.flushed_bytes;
        let count = local.count - local.flushed_count;
        if bytes != 0
            && ALLOCATED
                .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |n| {
                    n.checked_add(bytes)
                })
                .is_err()
        {
            ACCOUNTING_VALID.store(false, Ordering::Relaxed);
        }
        ALLOCATIONS.fetch_add(count, Ordering::Relaxed);
        local.flushed_bytes = local.bytes;
        local.flushed_count = local.count;
        cell.set(local);
    });
}
struct InputAdmission;
impl InputAdmission {
    fn acquire() -> Result<Self, ()> {
        INPUTS
            .fetch_update(Ordering::AcqRel, Ordering::Relaxed, |n| {
                if n < 4 {
                    Some(n + 1)
                } else {
                    None
                }
            })
            .map_err(|_| ())?;
        Ok(Self)
    }
}
impl Drop for InputAdmission {
    fn drop(&mut self) {
        flush_allocations();
        INPUTS.fetch_sub(1, Ordering::Release);
    }
}
struct MeasuredAllocator;
unsafe impl GlobalAlloc for MeasuredAllocator {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        let p = System.alloc(layout);
        if !p.is_null() {
            let size = layout.size() as u64;
            let live = LIVE.fetch_add(size, Ordering::Relaxed) + size;
            if live > PEAK.load(Ordering::Relaxed) {
                PEAK.fetch_max(live, Ordering::Relaxed);
            }
            record_allocation(size);
        }
        p
    }
    unsafe fn alloc_zeroed(&self, layout: Layout) -> *mut u8 {
        let p = System.alloc_zeroed(layout);
        if !p.is_null() {
            let size = layout.size() as u64;
            let live = LIVE.fetch_add(size, Ordering::Relaxed) + size;
            if live > PEAK.load(Ordering::Relaxed) {
                PEAK.fetch_max(live, Ordering::Relaxed);
            }
            record_allocation(size);
        }
        p
    }
    unsafe fn dealloc(&self, p: *mut u8, layout: Layout) {
        LIVE.fetch_sub(layout.size() as u64, Ordering::Relaxed);
        System.dealloc(p, layout);
    }
    unsafe fn realloc(&self, p: *mut u8, layout: Layout, new_size: usize) -> *mut u8 {
        // Preserve System's in-place realloc opportunity rather than the trait default's
        // allocate/copy/free sequence. Layout/pointer ownership is unchanged by accounting.
        let result = System.realloc(p, layout, new_size);
        if !result.is_null() {
            if new_size >= layout.size() {
                let live = LIVE.fetch_add((new_size - layout.size()) as u64, Ordering::Relaxed)
                    + (new_size - layout.size()) as u64;
                if live > PEAK.load(Ordering::Relaxed) {
                    PEAK.fetch_max(live, Ordering::Relaxed);
                }
            } else {
                LIVE.fetch_sub((layout.size() - new_size) as u64, Ordering::Relaxed);
            }
            record_allocation(new_size as u64);
        }
        result
    }
}
#[global_allocator]
static ALLOCATOR: MeasuredAllocator = MeasuredAllocator;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Uid {
    #[serde(rename = "type")]
    kind: String,
    id: String,
}
impl Uid {
    fn parse(self) -> Result<EntityUid, ()> {
        Ok(EntityUid::from_type_name_and_id(
            EntityTypeName::from_str(&self.kind).map_err(|_| ())?,
            EntityId::new(self.id),
        ))
    }
}
#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Link {
    template_id: String,
    result_policy_id: String,
    slots: BTreeMap<String, Uid>,
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BundleInput {
    schema: String,
    policies: BTreeMap<String, String>,
    #[serde(default)]
    templates: BTreeMap<String, String>,
    #[serde(default)]
    links: Vec<Link>,
}
struct Bundle {
    schema: Schema,
    policies: PolicySet,
    warnings: usize,
}
struct Cached {
    bundle: Weak<Bundle>,
    charge: u64,
}
#[derive(Default)]
struct Registry {
    next: u64,
    handles: HashMap<u64, Arc<Bundle>>,
    closing: HashSet<u64>,
    cache: HashMap<String, Cached>,
}
impl Registry {
    fn prune(&mut self) {
        self.cache.retain(|_, c| c.bundle.strong_count() != 0);
    }
    fn charge(&self) -> u64 {
        self.cache.values().map(|c| c.charge).sum()
    }
    fn attach(&mut self, bundle: Arc<Bundle>) -> Result<u64, ()> {
        if self.handles.len() >= HANDLES {
            return Err(());
        }
        self.next = self
            .next
            .checked_add(1)
            .filter(|n| *n < 0x4b75000000000000)
            .ok_or(())?;
        self.handles.insert(self.next, bundle);
        Ok(self.next)
    }
}
static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();
static BUILD: Mutex<()> = Mutex::new(());
fn registry() -> &'static Mutex<Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
}
fn prepare(text: String) -> Result<u64, ()> {
    if text.len() > BUNDLE_INPUT || !ACCOUNTING_VALID.load(Ordering::Relaxed) {
        return Err(());
    }
    let _compile = BUILD.lock().map_err(|_| ())?;
    {
        let mut r = registry().lock().map_err(|_| ())?;
        r.prune();
        if let Some(bundle) = r.cache.get(&text).and_then(|c| c.bundle.upgrade()) {
            return r.attach(bundle);
        }
        if r.cache.len() >= BUNDLES || r.handles.len() >= HANDLES {
            return Err(());
        }
    }
    // This compile's gross allocations conservatively upper-bound its retained bytes.
    // Thread-local accounting avoids other workers contending on, or inflating, this charge.
    // This is not a process RSS quota.
    let before = local_allocated();
    let input: BundleInput = serde_json::from_str(&text).map_err(|_| ())?;
    let (schema, warnings) = Schema::from_cedarschema_str(&input.schema).map_err(|_| ())?;
    let warnings = warnings.count();
    let mut policies = PolicySet::new();
    for (id, source) in input.policies {
        policies
            .add(Policy::parse(Some(PolicyId::new(id)), source).map_err(|_| ())?)
            .map_err(|_| ())?;
    }
    for (id, source) in input.templates {
        policies
            .add_template(Template::parse(Some(PolicyId::new(id)), source).map_err(|_| ())?)
            .map_err(|_| ())?;
    }
    for link in input.links {
        let slots = link
            .slots
            .into_iter()
            .map(|(s, uid)| {
                let s = if s.starts_with('?') {
                    s
                } else {
                    format!("?{s}")
                };
                let slot = match s.as_str() {
                    "?principal" => SlotId::principal(),
                    "?resource" => SlotId::resource(),
                    _ => return Err(()),
                };
                Ok((slot, uid.parse()?))
            })
            .collect::<Result<HashMap<_, _>, ()>>()?;
        policies
            .link(
                PolicyId::new(link.template_id),
                PolicyId::new(link.result_policy_id),
                slots,
            )
            .map_err(|_| ())?;
    }
    let bundle = Arc::new(Bundle {
        schema,
        policies,
        warnings,
    });
    let charge = local_allocated().saturating_sub(before) + text.capacity() as u64;
    if !ACCOUNTING_VALID.load(Ordering::Relaxed) {
        return Err(());
    }
    let mut r = registry().lock().map_err(|_| ())?;
    r.prune();
    if r.cache.len() >= BUNDLES || charge > CACHE_BYTES.saturating_sub(r.charge()) {
        return Err(());
    }
    let handle = r.attach(bundle.clone())?;
    r.cache.insert(
        text,
        Cached {
            bundle: Arc::downgrade(&bundle),
            charge,
        },
    );
    Ok(handle)
}
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Call {
    principal: Uid,
    action: Uid,
    resource: Uid,
    context: Value,
    entities: Value,
}
fn evaluate(handle: u64, text: String, deadline: Instant) -> Result<u64, ()> {
    evaluate_with_clock(handle, text, deadline, Instant::now)
}
fn evaluate_with_clock(
    handle: u64,
    text: String,
    deadline: Instant,
    mut now: impl FnMut() -> Instant,
) -> Result<u64, ()> {
    if text.len() > INPUT {
        return Err(());
    }
    if now() >= deadline {
        return Ok(EXPIRED);
    }
    let bundle = registry()
        .lock()
        .map_err(|_| ())?
        .handles
        .get(&handle)
        .cloned()
        .ok_or(())?;
    let result: Result<u64, ()> = (|| {
        let c: Call = serde_json::from_str(&text).map_err(|_| ())?;
        let principal = c.principal.parse()?;
        let action = c.action.parse()?;
        let resource = c.resource.parse()?;
        let context =
            Context::from_json_value(c.context, Some((&bundle.schema, &action))).map_err(|_| ())?;
        let request = Request::new(principal, action, resource, context, Some(&bundle.schema))
            .map_err(|_| ())?;
        let entities =
            Entities::from_json_value(c.entities, Some(&bundle.schema)).map_err(|_| ())?;
        if now() >= deadline {
            return Ok(EXPIRED);
        }
        EVALUATIONS.fetch_add(1, Ordering::Relaxed);
        let answer = Authorizer::new().is_authorized(&request, &bundle.policies, &entities);
        let errors = answer.diagnostics().errors().count().min(65535) as u64;
        let warnings = bundle.warnings.min(255) as u64;
        // Known strict diagnostics take precedence over a late evaluation timeout.
        let expired = now() >= deadline;
        if errors == 0 && warnings == 0 && expired {
            return Ok(EXPIRED);
        }
        Ok(MAGIC | u64::from(answer.decision() == Decision::Allow) | warnings << 8 | errors << 16)
    })();
    Ok(result.unwrap_or(INVALID))
}
fn close(handle: u64) -> Result<u64, ()> {
    let removed = {
        let mut r = registry().lock().map_err(|_| ())?;
        r.closing.remove(&handle);
        r.handles.remove(&handle).ok_or(())?
    };
    drop(removed);
    registry().lock().map_err(|_| ())?.prune();
    Ok(1)
}
enum Operation {
    Open(String),
    Evaluate(u64, String, Instant),
    Close(u64),
    #[cfg(test)]
    Panic,
}

type Job = (Operation, SyncSender<u64>);
struct Pool {
    sender: SyncSender<Job>,
    workers: Vec<std::thread::JoinHandle<()>>,
}
#[derive(Default)]
struct Manager {
    pool: Option<Pool>,
    active: usize,
}
static MANAGER: OnceLock<Mutex<Manager>> = OnceLock::new();
static WORKER_THREADS: AtomicUsize = AtomicUsize::new(0);
struct WorkerLife;
impl Drop for WorkerLife {
    fn drop(&mut self) {
        flush_allocations();
        WORKER_THREADS.fetch_sub(1, Ordering::Relaxed);
    }
}
impl Pool {
    fn start() -> Result<Self, ()> {
        std::panic::set_hook(Box::new(|_| {})); // Cedar panic diagnostics can contain policy/request payloads.
        let (tx, rx) = sync_channel::<Job>(4);
        let rx = Arc::new(Mutex::new(rx));
        let mut workers = Vec::new();
        for i in 0..4 {
            let rx = rx.clone();
            let worker = std::thread::Builder::new()
                .name(format!("kernel-cedar-{i}"))
                .stack_size(8 * 1024 * 1024)
                .spawn(move || {
                    WORKER_THREADS.fetch_add(1, Ordering::Relaxed);
                    let _life = WorkerLife;
                    loop {
                        let job = match rx.lock() {
                            Ok(rx) => rx.recv(),
                            Err(_) => return,
                        };
                        let Ok((operation, reply)) = job else { return };
                        QUEUED.fetch_sub(1, Ordering::Relaxed);
                        let result = catch_unwind(AssertUnwindSafe(|| match operation {
                            Operation::Open(text) => prepare(text),
                            Operation::Evaluate(id, text, deadline) => evaluate(id, text, deadline),
                            Operation::Close(id) => close(id),
                            #[cfg(test)]
                            Operation::Panic => panic!("worker isolation test"),
                        }));
                        let value = match result {
                            Ok(Ok(value)) => value,
                            _ => {
                                FAILURES.fetch_add(1, Ordering::Relaxed);
                                FAILURE
                            }
                        };
                        // No failed decision is retried. Fresh jobs use a fresh Authorizer.
                        // Publish exact cumulative allocation totals once per job, not per malloc.
                        flush_allocations();
                        let _ = reply.send(value);
                    }
                });
            match worker {
                Ok(worker) => workers.push(worker),
                Err(_) => {
                    drop(tx);
                    for worker in workers {
                        let _ = worker.join();
                    }
                    return Err(());
                }
            }
        }
        Ok(Self {
            sender: tx,
            workers,
        })
    }
    fn stop(self) {
        drop(self.sender);
        for worker in self.workers {
            let _ = worker.join();
        }
    }
}
fn dispatch(operation: Operation) -> u64 {
    let manager = MANAGER.get_or_init(|| Mutex::new(Manager::default()));
    let tx = {
        let Ok(mut m) = manager.lock() else {
            return FAILURE;
        };
        if m.pool.is_none() {
            if !matches!(operation, Operation::Open(_)) {
                return FAILURE;
            }
            let Ok(pool) = Pool::start() else {
                return FAILURE;
            };
            m.pool = Some(pool);
        }
        m.active += 1;
        m.pool.as_ref().unwrap().sender.clone()
    };
    let (reply, rx) = sync_channel(1);
    QUEUED.fetch_add(1, Ordering::Relaxed);
    let sent = tx.send((operation, reply));
    drop(tx);
    let value = if sent.is_ok() {
        rx.recv().unwrap_or(FAILURE)
    } else {
        QUEUED.fetch_sub(1, Ordering::Relaxed);
        FAILURE
    };
    if let Ok(mut m) = manager.lock() {
        m.active -= 1;
        let empty = registry()
            .lock()
            .map(|r| r.handles.is_empty())
            .unwrap_or(false);
        if m.active == 0 && empty {
            // Drain/join before the last JNI call returns, so no Rust thread can
            // execute unloaded library code after its owning classloader is released.
            if let Some(pool) = m.pool.take() {
                pool.stop();
            }
            if let Ok(mut r) = registry().lock() {
                r.prune();
                r.handles.shrink_to_fit();
                r.cache.shrink_to_fit();
                r.closing.shrink_to_fit();
            }
        }
    }
    value
}
fn input(env: &mut JNIEnv, text: &JString, limit: usize) -> Result<String, ()> {
    if env
        .call_method(text, "length", "()I", &[])
        .and_then(|v| v.i())
        .map_err(|_| ())? as usize
        > limit
    {
        return Err(());
    }
    let value: String = env.get_string(text).map_err(|_| ())?.into();
    if value.len() > limit {
        return Err(());
    }
    Ok(value)
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_NativeCedar_identity(
    env: JNIEnv,
    _: JClass,
) -> jstring {
    FFI_ENTRIES.fetch_add(1, Ordering::Relaxed);
    let result = env
        .new_string(concat!("kernel-cedar/1;cedar/09e21d967f1da2cf2abbe1e0b0b2067b41867735+patch.sha256:", env!("KERNEL_CEDAR_PATCH_SHA256")))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    flush_allocations();
    result
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_NativeCedar_open(
    mut env: JNIEnv,
    _: JClass,
    text: JString,
) -> jlong {
    FFI_ENTRIES.fetch_add(1, Ordering::Relaxed);
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(_admitted) = InputAdmission::acquire() else {
            return FAILURE;
        };
        input(&mut env, &text, BUNDLE_INPUT)
            .map(|s| dispatch(Operation::Open(s)))
            .unwrap_or(FAILURE)
    }))
    .unwrap_or(FAILURE) as jlong
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_NativeCedar_evaluate(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    text: JString,
    nanos: jlong,
) -> jlong {
    FFI_ENTRIES.fetch_add(1, Ordering::Relaxed);
    INVOCATIONS.fetch_add(1, Ordering::Relaxed);
    let started = Instant::now();
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(_admitted) = InputAdmission::acquire() else {
            return FAILURE;
        };
        if nanos <= 0 || nanos > 5_000_000_000 {
            return EXPIRED;
        }
        input(&mut env, &text, INPUT)
            .map(|s| {
                dispatch(Operation::Evaluate(
                    handle as u64,
                    s,
                    started + Duration::from_nanos(nanos as u64),
                ))
            })
            .unwrap_or(INVALID)
    }))
    .unwrap_or(FAILURE) as jlong
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_NativeCedar_close(
    _: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jlong {
    FFI_ENTRIES.fetch_add(1, Ordering::Relaxed);
    let result = catch_unwind(AssertUnwindSafe(|| {
        // At most one queued close per live token; malformed/duplicate closes cannot grow the queue.
        let valid = match registry().lock() {
            Ok(mut r) => {
                r.handles.contains_key(&(handle as u64)) && r.closing.insert(handle as u64)
            }
            Err(_) => false,
        };
        if !valid {
            return FAILURE;
        }
        dispatch(Operation::Close(handle as u64))
    }))
    .unwrap_or(FAILURE) as jlong;
    flush_allocations();
    result
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_NativeCedar_statistics(
    env: JNIEnv,
    _: JClass,
) -> jlongArray {
    FFI_ENTRIES.fetch_add(1, Ordering::Relaxed);
    flush_allocations();
    let values = catch_unwind(AssertUnwindSafe(|| {
        let mut r = registry().lock().map_err(|_| ())?;
        r.prune();
        Ok::<_, ()>([
            r.handles.len() as i64,
            r.cache.len() as i64,
            r.charge() as i64,
            LIVE.load(Ordering::Relaxed) as i64,
            PEAK.load(Ordering::Relaxed) as i64,
            ALLOCATED.load(Ordering::Relaxed) as i64,
            ALLOCATIONS.load(Ordering::Relaxed) as i64,
            EVALUATIONS.load(Ordering::Relaxed) as i64,
            FAILURES.load(Ordering::Relaxed) as i64,
            QUEUED.load(Ordering::Relaxed) as i64,
            INVOCATIONS.load(Ordering::Relaxed) as i64,
            FFI_ENTRIES.load(Ordering::Relaxed) as i64,
            WORKER_THREADS.load(Ordering::Relaxed) as i64,
        ])
    }));
    let Ok(Ok(values)) = values else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.new_long_array(values.len() as i32) else {
        return std::ptr::null_mut();
    };
    if env.set_long_array_region(&array, 0, &values).is_err() {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

/// JVM calls this only after the defining classloader has no active native frames.
/// Normal close already drains the pool; this also cleans orphaned handles from abrupt Java failure.
#[no_mangle]
pub extern "system" fn JNI_OnUnload(_: *mut jni::sys::JavaVM, _: *mut std::ffi::c_void) {
    let _ = catch_unwind(AssertUnwindSafe(|| {
        let Some(manager) = MANAGER.get() else { return };
        let Ok(mut m) = manager.lock() else { return };
        if m.active != 0 {
            return;
        }
        if let Some(pool) = m.pool.take() {
            let ids: Vec<u64> = registry()
                .lock()
                .map(|r| r.handles.keys().copied().collect())
                .unwrap_or_default();
            for id in ids {
                let (reply, rx) = sync_channel(1);
                QUEUED.fetch_add(1, Ordering::Relaxed);
                if pool.sender.send((Operation::Close(id), reply)).is_ok() {
                    let _ = rx.recv();
                } else {
                    QUEUED.fetch_sub(1, Ordering::Relaxed);
                }
            }
            pool.stop();
        }
        if let Ok(mut r) = registry().lock() {
            r.prune();
            r.handles.shrink_to_fit();
            r.cache.shrink_to_fit();
            r.closing.shrink_to_fit();
        }
    }));
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prepared_scalar_does_not_rebuild_unvisited_action_scope() {
        // A false principal scope short-circuits the Action set. Widening that
        // immutable, unvisited set must not allocate during scalar evaluation.
        let request = serde_json::json!({
            "principal": {"type":"User", "id":"u"}, "action":{"type":"Action", "id":"read"},
            "resource":{"type":"Row", "id":"r"}, "context":{}, "entities":[]
        }).to_string();
        let mut costs = Vec::new();
        for width in [1, 64] {
            let actions = (0..width).map(|n| format!("Action::\"a{n}\""))
                .collect::<Vec<_>>().join(",");
            let source = serde_json::json!({
                "schema": "entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };",
                "policies": {"irrelevant": format!("permit(principal == User::\"other\", action in [{actions}], resource);")}
            }).to_string();
            let id = prepare(source).unwrap();
            for _ in 0..3 {
                let text = request.clone();
                let before = LOCAL_ACCOUNTING.with(Cell::get);
                let evaluations = EVALUATIONS.load(Ordering::Relaxed);
                let answer = evaluate(id, text, Instant::now() + Duration::from_secs(1)).unwrap();
                let after = LOCAL_ACCOUNTING.with(Cell::get);
                assert_eq!(answer, MAGIC);
                assert_eq!(EVALUATIONS.load(Ordering::Relaxed), evaluations + 1);
                costs.push((after.bytes - before.bytes, after.count - before.count));
            }
            close(id).unwrap();
        }
        eprintln!("allocation-only scalar costs (bytes, allocations): {costs:?}");
        assert!(costs.iter().all(|cost| *cost == costs[0]),
            "unvisited prepared scope must not add decision-local allocations: {costs:?}");
    }

    #[test]
    fn job_local_accounting_records_requested_bytes_without_losing_global_totals() {
        let before_local = local_allocated();
        let before_global = ALLOCATED.load(Ordering::Relaxed);
        let layout = Layout::from_size_align(4096, 8).unwrap();
        let pointer = unsafe { ALLOCATOR.alloc(layout) };
        assert!(!pointer.is_null());
        assert_eq!(local_allocated() - before_local, 4096);
        let pointer = unsafe { ALLOCATOR.realloc(pointer, layout, 8192) };
        assert!(!pointer.is_null());
        assert_eq!(local_allocated() - before_local, 4096 + 8192);
        unsafe {
            ALLOCATOR.dealloc(pointer, Layout::from_size_align(8192, 8).unwrap());
        }
        let zeroed = unsafe { ALLOCATOR.alloc_zeroed(layout) };
        assert!(!zeroed.is_null());
        assert_eq!(unsafe { *zeroed.add(4095) }, 0);
        unsafe {
            ALLOCATOR.dealloc(zeroed, layout);
        }
        assert_eq!(local_allocated() - before_local, 4096 + 8192 + 4096);
        flush_allocations();
        assert!(ALLOCATED.load(Ordering::Relaxed) >= before_global + 4096 + 8192 + 4096);
    }

    #[test]
    fn late_policy_errors_and_schema_warnings_keep_original_scalar_diagnostics() {
        let request = serde_json::json!({
            "principal": {"type":"User", "id":"u"}, "action":{"type":"Action", "id":"read"},
            "resource":{"type":"Row", "id":"r"}, "context":{}, "entities":[]
        }).to_string();
        let start = Instant::now();
        let deadline = start + Duration::from_secs(1);
        for (schema_prefix, policy, expected) in [
            ("", "forbid(principal, action, resource) when { resource.missing };", MAGIC | 1 | 1 << 16),
            ("entity String;", "permit(principal, action, resource);", MAGIC | 1 | 1 << 8),
            ("", "permit(principal, action, resource);", EXPIRED),
        ] {
            let source = serde_json::json!({
                "schema": format!("{schema_prefix} entity User; entity Row {{ missing?: Bool }}; action read appliesTo {{ principal: User, resource: Row, context: {{}} }};"),
                "policies": {"permit": "permit(principal, action, resource);", "diagnostic": policy}
            }).to_string();
            let id = prepare(source).unwrap();
            let original = evaluate_with_clock(id, request.clone(), deadline, || start).unwrap();
            let mut checks = 0;
            let late = evaluate_with_clock(id, request.clone(), deadline, || {
                checks += 1;
                if checks < 3 { start } else { deadline }
            }).unwrap();
            // Only the post-evaluation clock crosses the deadline; the real Cedar response is unchanged.
            let before_execution = evaluate_with_clock(id, request.clone(), deadline, || deadline).unwrap();
            close(id).unwrap();
            assert_eq!(before_execution, EXPIRED);
            assert_eq!(checks, 3);
            assert_eq!(late, expected);
            if expected != EXPIRED { assert_eq!(late, original); }
            else { assert_eq!(original, MAGIC | 1); }
        }
    }

    #[test]
    fn closing_an_admitted_scalar_retains_its_ast_but_never_reuses_the_token() {
        let source = serde_json::json!({
            "schema": "entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };",
            "policies": {"permit": "permit(principal, action == Action::\"read\", resource);"}
        }).to_string();
        let request = serde_json::json!({
            "principal": {"type":"User", "id":"u"}, "action":{"type":"Action", "id":"read"},
            "resource":{"type":"Row", "id":"r"}, "context":{}, "entities":[]
        }).to_string();
        let id = prepare(source.clone()).unwrap();
        let now = Instant::now();
        let deadline = now + Duration::from_secs(1);
        let mut checks = 0;
        let answer = evaluate_with_clock(id, request.clone(), deadline, || {
            checks += 1;
            // This checkpoint is after the bundle Arc is acquired and all fresh
            // inputs validated, but before the actual Authorizer evaluates it.
            if checks == 2 { close(id).unwrap(); }
            now
        }).unwrap();
        assert_eq!(checks, 3);
        assert_eq!(answer, MAGIC | 1);
        assert!(evaluate(id, request.clone(), deadline).is_err());
        assert!(close(id).is_err());
        let fresh = prepare(source).unwrap();
        assert!(fresh > id);
        assert_eq!(evaluate(fresh, request, deadline).unwrap(), MAGIC | 1);
        close(fresh).unwrap();
        let mut r = registry().lock().unwrap();
        r.prune();
        assert!(r.handles.is_empty());
        assert!(r.cache.is_empty());
    }

    #[test]
    fn failed_job_is_not_retried_and_healthy_scalar_work_can_continue() {
        let source = serde_json::json!({
            "schema": "entity User; entity Row; action read appliesTo { principal: User, resource: Row, context: {} };",
            "policies": {"permit": "permit(principal, action, resource);"}
        }).to_string();
        let id = dispatch(Operation::Open(source));
        assert!(id > 0 && id < 0x4b75000000000000);
        assert_eq!(dispatch(Operation::Panic), FAILURE);
        let request = serde_json::json!({
            "principal": {"type":"User", "id":"u"}, "action":{"type":"Action", "id":"read"},
            "resource":{"type":"Row", "id":"r"}, "context":{}, "entities":[]
        })
        .to_string();
        assert_eq!(
            dispatch(Operation::Evaluate(
                id,
                request.clone(),
                Instant::now() + Duration::from_secs(1)
            )),
            MAGIC | 1
        );
        assert_eq!(dispatch(Operation::Close(id)), 1);
        assert_eq!(
            dispatch(Operation::Evaluate(
                id,
                request,
                Instant::now() + Duration::from_secs(1)
            )),
            FAILURE
        );
        assert!(registry().lock().unwrap().handles.is_empty());
        assert_eq!(WORKER_THREADS.load(Ordering::Relaxed), 0);
    }
}
