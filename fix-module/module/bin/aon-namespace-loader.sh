#!/system/bin/sh

MODDIR="$1"
[ -n "$MODDIR" ] || exit 1
. "$MODDIR/common.sh"

AON_PACKAGE=com.aiunit.aon
AON_PAYLOAD=/data/local/tmp/coloros-aon-runtime-v2459
AON_TARGET=/my_product/app/AONService/lib/arm64
AON_DSP_SKEL_TARGET=/vendor/lib/rfsa/adsp/libQnnHtpV75Skel.so
AON_DSP_SKEL_SOURCE=$AON_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so
# libaiboost_jni is a recovered Lenovo binary.  It uses an absolute
# dlopen("/odm/lib64/libaiboost.so") instead of the app library namespace.
# KernelSU gives apps a private mount namespace, so the module's global ODM
# overlay is not visible there.  Mount the self-contained runtime directory
# only in the AON process before it can call nativeCreate.
AON_ODM_TARGET=/odm/lib64
EXPECTED_JNI=80aedb964ca38112a003a8f77b72bca0bbf37ac221017e678e031f09cde428fc

stopped_pid=
logcat_pid=
last_pid=
event_fifo="$MODDIR/aon-process-events.fifo"

# KernelSU normally creates the per-app mount namespace immediately after
# zygote forks the process.  am_proc_start can arrive while the child still
# shares zygote's namespace.  Binding at that point verifies successfully but
# can be lost when KernelSU switches the child, leaving AON with the original
# JNI runtime.
#
# Some builds intentionally keep this system package in its inherited app
# namespace, however.  Treating that valid topology as a permanent "not
# ready" state was a race: after an AON process restart the loader abandoned
# the runtime bind altogether, so nativeCreate saw the port's old DSP skeleton.
# Wait only in response to an AON process-start event; when no namespace split
# happens in the bounded window, bind into the stable inherited namespace.
wait_for_app_mount_namespace() {
    aon_pid="$1"
    zygote_pid="$(pidof zygote64 2>/dev/null)"
    set -- $zygote_pid
    zygote_pid="$1"
    zygote_ns=
    [ -n "$zygote_pid" ] && zygote_ns="$(readlink "/proc/$zygote_pid/ns/mnt" 2>/dev/null)"

    attempt=0
    while [ "$attempt" -lt 40 ]; do
        [ -d "/proc/$aon_pid" ] || return 1
        aon_ns="$(readlink "/proc/$aon_pid/ns/mnt" 2>/dev/null)"
        if [ -n "$aon_ns" ] && { [ -z "$zygote_ns" ] || [ "$aon_ns" != "$zygote_ns" ]; }; then
            return 0
        fi
        attempt=$((attempt + 1))
        sleep 0.05
    done
    log_msg "AON namespace unchanged after bounded wait; attaching inherited namespace pid=$aon_pid ns=$aon_ns"
    return 0
}

resume_stopped_process() {
    if [ -n "$stopped_pid" ]; then
        kill -CONT "$stopped_pid" 2>/dev/null
        stopped_pid=
    fi
}

cleanup() {
    resume_stopped_process
    [ -n "$logcat_pid" ] && kill "$logcat_pid" 2>/dev/null
    rm -f "$event_fifo" 2>/dev/null
}

trap 'cleanup; exit 0' INT TERM EXIT

if [ ! -r "$AON_PAYLOAD/libaiboost_jni.so" ] ||
   [ ! -r "$AON_PAYLOAD/libaiboost.so" ] ||
   [ ! -r "$AON_PAYLOAD/libaiboost_qnn_external_delegate.so" ] ||
   [ ! -r "$AON_PAYLOAD/libQnnHtpV75Stub.so" ] ||
   [ ! -r "$AON_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so" ]; then
    log_msg "ERROR: AON namespace loader payload incomplete"
    exit 1
fi

attach_runtime() {
    aon_pid="$1"
    case "$aon_pid" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ -d "/proc/$aon_pid" ] || return 1
    [ "$aon_pid" = "$last_pid" ] && return 0
    wait_for_app_mount_namespace "$aon_pid" || return 1
    if kill -STOP "$aon_pid" 2>/dev/null; then
        stopped_pid="$aon_pid"
        if [ -e "/proc/$aon_pid/ns/mnt" ] &&
           nsenter -t "$aon_pid" -m -- mount --bind "$AON_PAYLOAD" "$AON_ODM_TARGET" 2>/dev/null &&
           nsenter -t "$aon_pid" -m -- mount --bind "$AON_PAYLOAD" "$AON_TARGET" 2>/dev/null &&
           nsenter -t "$aon_pid" -m -- mount --bind "$AON_DSP_SKEL_SOURCE" "$AON_DSP_SKEL_TARGET" 2>/dev/null; then
            # KernelSU has already mounted several children of AON_TARGET as
            # individual files.  A parent directory bind does not replace
            # those surviving child mounts, so overlay every runtime file once
            # more inside the app's private mount namespace.
            file_bind_failed=0
            for source_file in $(find "$AON_PAYLOAD" -type f 2>/dev/null); do
                relative_path=${source_file#"$AON_PAYLOAD"/}
                target_file="$AON_TARGET/$relative_path"
                if ! nsenter -t "$aon_pid" -m -- mount --bind "$source_file" "$target_file" 2>/dev/null; then
                    file_bind_failed=1
                    log_msg "ERROR: AON namespace file bind failed pid=$aon_pid file=$relative_path"
                    break
                fi
            done
            actual_jni=$(nsenter -t "$aon_pid" -m -- sha256sum "$AON_TARGET/libaiboost_jni.so" 2>/dev/null | awk '{print $1}')
            actual_aiboost=$(nsenter -t "$aon_pid" -m -- sha256sum "$AON_ODM_TARGET/libaiboost.so" 2>/dev/null | awk '{print $1}')
            actual_skel=$(nsenter -t "$aon_pid" -m -- sha256sum "$AON_DSP_SKEL_TARGET" 2>/dev/null | awk '{print $1}')
            expected_aiboost=$(sha256sum "$AON_PAYLOAD/libaiboost.so" 2>/dev/null | awk '{print $1}')
            expected_skel=$(sha256sum "$AON_DSP_SKEL_SOURCE" 2>/dev/null | awk '{print $1}')
            if [ "$file_bind_failed" -eq 0 ] && [ "$actual_jni" = "$EXPECTED_JNI" ] && [ "$actual_aiboost" = "$expected_aiboost" ] && [ "$actual_skel" = "$expected_skel" ]; then
                log_msg "AON namespace runtime attached pid=$aon_pid jni=$actual_jni odm_aiboost=$actual_aiboost skel=$actual_skel"
                last_pid="$aon_pid"
            else
                log_msg "ERROR: AON namespace runtime mismatch pid=$aon_pid jni=$actual_jni odm_aiboost=$actual_aiboost skel=$actual_skel"
            fi
        else
            log_msg "ERROR: AON namespace runtime attach failed pid=$aon_pid"
        fi
        resume_stopped_process
    fi
}

log_msg "AON namespace loader armed with ActivityManager process events"

# Cover the small window between module startup and logcat subscription.
current_pid=$(pidof "$AON_PACKAGE" 2>/dev/null)
set -- $current_pid
attach_runtime "$1"

# am_proc_start is emitted immediately after ActivityManager forks an app.  A
# blocking logcat subscription reacts at process creation without the old
# 20Hz pidof loop, which consumed minutes of CPU time and generated hundreds
# of thousands of scheduler wakeups during a normal uptime.
while true; do
    rm -f "$event_fifo" 2>/dev/null
    if ! mkfifo "$event_fifo" 2>/dev/null; then
        log_msg "ERROR: AON process-event FIFO creation failed"
        sleep 5
        continue
    fi
    logcat -b events -v raw -T 1 -s am_proc_start:I '*:S' \
        >"$event_fifo" 2>/dev/null &
    logcat_pid=$!
    while IFS= read -r event; do
        case "$event" in
            \[*",$AON_PACKAGE,"*)
                event_tail=${event#*,}
                event_pid=${event_tail%%,*}
                attach_runtime "$event_pid"
                ;;
        esac
    done <"$event_fifo"
    kill "$logcat_pid" 2>/dev/null
    wait "$logcat_pid" 2>/dev/null
    logcat_pid=
    rm -f "$event_fifo" 2>/dev/null
    log_msg "AON process-event stream restarted"
    sleep 1
done
