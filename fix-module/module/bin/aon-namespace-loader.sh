#!/system/bin/sh

MODDIR="$1"
[ -n "$MODDIR" ] || exit 1
. "$MODDIR/common.sh"

AON_PACKAGE=com.aiunit.aon
AON_PAYLOAD=/data/local/tmp/coloros-aon-runtime-v2459
AON_TARGET=/my_product/app/AONService/lib/arm64
AON_ODM_TARGET=/odm/lib64
AON_DSP_SKEL_TARGET=/vendor/lib/rfsa/adsp/libQnnHtpV75Skel.so
AON_DSP_SKEL_SOURCE=$AON_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so
BUSYBOX=/data/adb/ksu/bin/busybox
# The recovered Lenovo JNI ultimately resolves AIBoost through the original
# /odm/lib64 path. Expose that path only inside AON's private namespace; never
# create a global module overlay or alter zygote's inherited namespace.
EXPECTED_JNI=80aedb964ca38112a003a8f77b72bca0bbf37ac221017e678e031f09cde428fc

stopped_pid=
last_pid=

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
    while [ "$attempt" -lt 100 ]; do
        [ -d "/proc/$aon_pid" ] || return 1
        aon_ns="$(readlink "/proc/$aon_pid/ns/mnt" 2>/dev/null)"
        if [ -n "$aon_ns" ] && { [ -z "$zygote_ns" ] || [ "$aon_ns" != "$zygote_ns" ]; }; then
            return 0
        fi
        attempt=$((attempt + 1))
        if [ -x "$BUSYBOX" ]; then
            "$BUSYBOX" usleep 5000
        else
            sleep 0.01
        fi
    done
    log_msg "AON namespace unchanged after bounded wait; attaching inherited namespace pid=$aon_pid ns=$aon_ns"
    return 0
}

has_private_mount_namespace() {
    aon_pid="$1"
    zygote_pid="$(pidof zygote64 2>/dev/null)"
    set -- $zygote_pid
    zygote_pid="$1"
    [ -n "$zygote_pid" ] || return 1
    aon_ns="$(readlink "/proc/$aon_pid/ns/mnt" 2>/dev/null)"
    zygote_ns="$(readlink "/proc/$zygote_pid/ns/mnt" 2>/dev/null)"
    [ -n "$aon_ns" ] && [ -n "$zygote_ns" ] && [ "$aon_ns" != "$zygote_ns" ]
}

wait_for_process_identity() {
    aon_pid="$1"
    attempt=0
    while [ "$attempt" -lt 50 ]; do
        [ -d "/proc/$aon_pid" ] || return 1
        process_name="$(tr '\000' '\n' <"/proc/$aon_pid/cmdline" 2>/dev/null | head -1)"
        [ "$process_name" = "$AON_PACKAGE" ] && return 0
        attempt=$((attempt + 1))
        if [ -x "$BUSYBOX" ]; then
            "$BUSYBOX" usleep 5000
        else
            sleep 0.01
        fi
    done
    return 1
}

resume_stopped_process() {
    if [ -n "$stopped_pid" ]; then
        kill -CONT "$stopped_pid" 2>/dev/null
        stopped_pid=
    fi
}

cleanup() {
    resume_stopped_process
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
    # ActivityManager logs the fork while cmdline is still "zygote64". Wait
    # briefly for specialization, then reject stale or recycled PIDs.
    wait_for_process_identity "$aon_pid" || return 1
    [ "$aon_pid" = "$last_pid" ] && return 0
    # KernelSU switches this app to a private mount namespace about 25 ms
    # after process creation on the target. Poll at 5 ms and freeze only after
    # that transition; freezing earlier prevents KernelSU from completing it.
    wait_for_app_mount_namespace "$aon_pid" || return 1
    if kill -STOP "$aon_pid" 2>/dev/null; then
        stopped_pid="$aon_pid"
        if ! has_private_mount_namespace "$aon_pid"; then
            log_msg "ERROR: refusing AON runtime attach in zygote mount namespace pid=$aon_pid"
            resume_stopped_process
            return 1
        fi
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
                log_msg "AON namespace runtime attached pid=$aon_pid jni=$actual_jni private_odm_aiboost=$actual_aiboost skel=$actual_skel"
                last_pid="$aon_pid"
            else
                log_msg "ERROR: AON namespace runtime mismatch pid=$aon_pid jni=$actual_jni private_odm_aiboost=$actual_aiboost skel=$actual_skel"
            fi
        else
            log_msg "ERROR: AON namespace runtime attach failed pid=$aon_pid"
        fi
        resume_stopped_process
    fi
}

log_msg "AON namespace loader armed with ActivityManager process-start logs"

# Cover the small window between module startup and logcat subscription.
current_pid=$(pidof "$AON_PACKAGE" 2>/dev/null)
set -- $current_pid
attach_runtime "$1"

# This port does not emit every AON launch to the events buffer's am_proc_start
# tag, but ActivityManager's system-buffer "Start proc" record is present for
# every service launch. A blocking subscription reacts without the old 20 Hz
# pidof loop and extracts the PID from the record after verifying the package.
while true; do
    logcat -b system -v raw -T 1 -s ActivityManager:I '*:S' 2>/dev/null |
        while IFS= read -r event; do
            case "$event" in
                *"Start proc "*":${AON_PACKAGE}/"*)
                    event_tail=${event#*Start proc }
                    event_pid=${event_tail%%:*}
                    attach_runtime "$event_pid"
                    ;;
            esac
        done
    log_msg "AON process-event stream restarted"
    sleep 1
done
