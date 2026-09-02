#!/system/bin/sh

MODDIR="$1"
[ -n "$MODDIR" ] || exit 1
. "$MODDIR/common.sh"

AON_PACKAGE=com.aiunit.aon
AON_PAYLOAD=/data/local/tmp/coloros-aon-runtime-v2459
AON_TARGET=/my_product/app/AONService/lib/arm64
AON_DSP_SKEL_TARGET=/vendor/lib/rfsa/adsp/libQnnHtpV75Skel.so
AON_DSP_SKEL_SOURCE=$AON_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so
EXPECTED_JNI=80aedb964ca38112a003a8f77b72bca0bbf37ac221017e678e031f09cde428fc

last_pid=
stopped_pid=

resume_stopped_process() {
    if [ -n "$stopped_pid" ]; then
        kill -CONT "$stopped_pid" 2>/dev/null
        stopped_pid=
    fi
}

trap 'resume_stopped_process; exit 0' INT TERM EXIT

if [ ! -r "$AON_PAYLOAD/libaiboost_jni.so" ] ||
   [ ! -r "$AON_PAYLOAD/libaiboost.so" ] ||
   [ ! -r "$AON_PAYLOAD/libaiboost_qnn_external_delegate.so" ] ||
   [ ! -r "$AON_PAYLOAD/libQnnHtpV75Stub.so" ] ||
   [ ! -r "$AON_PAYLOAD/cdsp/unsigned/libQnnHtpV75Skel.so" ]; then
    log_msg "ERROR: AON namespace loader payload incomplete"
    exit 1
fi

log_msg "AON namespace loader armed for original 2.4.59 runtime"

while true; do
    aon_pid=$(pidof "$AON_PACKAGE" 2>/dev/null)
    set -- $aon_pid
    aon_pid="$1"

    if [ -z "$aon_pid" ]; then
        last_pid=
        sleep 0.05
        continue
    fi

    if [ "$aon_pid" = "$last_pid" ]; then
        sleep 0.05
        continue
    fi

    if kill -STOP "$aon_pid" 2>/dev/null; then
        stopped_pid="$aon_pid"
        if [ -e "/proc/$aon_pid/ns/mnt" ] &&
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
            actual_skel=$(nsenter -t "$aon_pid" -m -- sha256sum "$AON_DSP_SKEL_TARGET" 2>/dev/null | awk '{print $1}')
            expected_skel=$(sha256sum "$AON_DSP_SKEL_SOURCE" 2>/dev/null | awk '{print $1}')
            if [ "$file_bind_failed" -eq 0 ] && [ "$actual_jni" = "$EXPECTED_JNI" ] && [ "$actual_skel" = "$expected_skel" ]; then
                log_msg "AON namespace runtime attached pid=$aon_pid jni=$actual_jni skel=$actual_skel"
            else
                log_msg "ERROR: AON namespace runtime mismatch pid=$aon_pid jni=$actual_jni skel=$actual_skel"
            fi
        else
            log_msg "ERROR: AON namespace runtime attach failed pid=$aon_pid"
        fi
        resume_stopped_process
    fi

    last_pid="$aon_pid"
    sleep 0.05
done
