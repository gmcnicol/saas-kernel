//! THROWAWAY Linux/glibc experiment. One bounded reusable worker, 8 MiB stack.
use cedar_policy::ffi::PrototypePrepared;
use jni::{JNIEnv, objects::{JClass, JString}, sys::jstring};
use serde_json::{json, Value};
use std::{sync::{mpsc::{sync_channel, SyncSender}, Mutex, OnceLock}, panic::{catch_unwind, AssertUnwindSafe}};
const BYTES: usize = 1024 * 1024;
type Job = (String, SyncSender<String>);
static WORKER: OnceLock<Mutex<SyncSender<Job>>> = OnceLock::new();
static ENTRY: Mutex<()> = Mutex::new(());
fn work(input: &str, bundles: &mut Vec<(String, PrototypePrepared)>) -> Result<Value, &'static str> {
    let v: Value = serde_json::from_str(input).map_err(|_| "input")?;
    match v["op"].as_str() {
        Some("prepare") => {
            let text = serde_json::to_string(&v["bundle"]).map_err(|_| "bundle")?;
            let id = if let Some(i) = bundles.iter().position(|b| b.0 == text) { i } else {
                if bundles.len() >= 16 { return Err("handle limit"); }
                let prepared = PrototypePrepared::prepare(&text).map_err(|_| "prepare")?;
                bundles.push((text, prepared)); bundles.len() - 1
            };
            Ok(json!({"handle": id + 1, "engine": "cedar-4dea3e60201f92139141d65c4a52966e056b007d-prototype-v1"}))
        },
        Some("evaluate") => {
            let id = v["handle"].as_u64().ok_or("handle")? as usize;
            let p = bundles.get(id.checked_sub(1).ok_or("handle")?).ok_or("handle")?;
            let calls = v["calls"].as_array().ok_or("calls")?;
            let gates = v["gates"].as_u64().ok_or("gates")? as usize;
            if calls.is_empty() || calls.len() > 64 || gates > calls.len() { return Err("chunk"); }
            let nanos = v["timeoutNanos"].as_u64().filter(|n| *n > 0 && *n <= 5_000_000_000).ok_or("deadline")?;
            let deadline = std::time::Instant::now() + std::time::Duration::from_nanos(nanos);
            let mut out = vec![];
            for (i, call) in calls.iter().enumerate() {
                if std::time::Instant::now() >= deadline { return Err("deadline"); }
                let answer = p.1.evaluate(call.clone()).map_err(|_| "evaluation")?;
                let (clean, allowed) = match &answer {
                    cedar_policy::ffi::AuthorizationAnswer::Success { response, warnings } =>
                        (warnings.is_empty() && response.diagnostics().errors().next().is_none(), response.decision() == cedar_policy::Decision::Allow),
                    _ => (false, false),
                };
                let answer = serde_json::to_value(answer).map_err(|_| "answer")?;
                out.push(json!({"index": i, "answer": answer}));
                if !clean || (i < gates && !allowed) { break; }
            }
            if std::time::Instant::now() >= deadline { return Err("deadline"); }
            Ok(json!({"results":out}))
        },
        _ => Err("operation"),
    }
}
fn dispatch(input: String) -> String {
    if input.len() > BYTES { return "{}".into(); }
    let worker = WORKER.get_or_init(|| {
        // Never log policy inputs/panics. A panic permanently poisons this worker.
        std::panic::set_hook(Box::new(|_| {}));
        let (tx, rx) = sync_channel::<Job>(1);
        std::thread::Builder::new().name("throwaway-cedar".into()).stack_size(8 * 1024 * 1024).spawn(move || {
            let mut bundles = vec![];
            let mut poisoned = false;
            while let Ok((input, reply)) = rx.recv() {
                let result = if poisoned { Ok(Err("poisoned")) } else { catch_unwind(AssertUnwindSafe(|| work(&input, &mut bundles))) };
                let output = match result { Ok(Ok(v)) => v.to_string(), Ok(Err(_)) => "{}".into(), Err(_) => { poisoned = true; "{}".into() } };
                let output = if output.len() <= BYTES { output } else { "{}".into() };
                let _ = reply.send(output);
            }
        }).expect("prototype worker");
        Mutex::new(tx)
    });
    let Ok(tx) = worker.lock() else { return "{}".into() };
    let (reply, rx) = sync_channel(1);
    if tx.send((input, reply)).is_err() { return "{}".into(); }
    rx.recv().unwrap_or_else(|_| "{}".into())
}
#[no_mangle]
pub extern "system" fn Java_io_github_gmcnicol_kernel_internal_PreparedReadEvaluator_invoke(mut env: JNIEnv, _: JClass, input: JString) -> jstring {
    let guard = ENTRY.try_lock();
    if guard.is_err() { return env.new_string("{}").map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()); }
    let output = catch_unwind(AssertUnwindSafe(|| {
        // Check UTF-16 length before copying; UTF-8 is bounded again in dispatch.
        if env.call_method(&input, "length", "()I", &[]).and_then(|v| v.i()).unwrap_or(i32::MAX) as usize > BYTES { return "{}".to_string(); }
        let input: String = match env.get_string(&input) { Ok(s) => s.into(), Err(_) => return "{}".into() };
        dispatch(input)
    })).unwrap_or_else(|_| "{}".into());
    env.new_string(output).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
