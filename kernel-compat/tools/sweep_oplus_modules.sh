#!/usr/bin/env bash
# 把一加各内核模块逐个对着自建 GKI 6.1 试编，输出谁能编、谁还差什么。只编不装。
#
# 关键：整棵树镜像过来再就地编，不能把模块摊平——一加源码里到处是
# ../sched_assist/sa_group.h 这类相对包含，摊平了必然找不到。
# 只镜像现代树（kernel/cpu、kernel/mm 等 6.1 时代代码）；
# oplus_performance_5.10 是 5.10 遗留，同名目录内容更旧，忽略。
S=${S:-$HOME/oplus-src}
SRC=$S/aosp/src
W=${W:-/run/media/ACLaniakea/IXUNICS/pad}
K=$S/modules/vendor/oplus/kernel
PORT=$W/coloros-pad-fixes/kernel-compat/patches/port_oplus_modules.py
export PATH="$S/toolchain/clang-r487747c/bin:$PATH"
SUBTREES=${SUBTREES:-"cpu mm ipc synchronize hans framework_stability"}

if [ "${MIRROR:-1}" = 1 ]; then
    rm -rf "$S/mport"; mkdir -p "$S/mport"
    for t in $SUBTREES; do [ -d "$K/$t" ] && cp -r "$K/$t" "$S/mport/$t"; done
    # 源码里的 ../kernel/oplus_cpu/... 指向镜像（改过的那份），不是原始树
    mkdir -p "$S/kernel"
    ln -sfn "$S/mport/cpu" "$S/kernel/oplus_cpu"
    ln -sfn "$S/mport/mm"  "$S/kernel/oplus_mm"
    find "$S/mport" -type d | while read -r d; do
        ls "$d"/*.[ch] >/dev/null 2>&1 && python3 "$PORT" "$d"
    done > "$S/mport/port.log" 2>&1
    echo "镜像+改写完成：$(grep -c '个文件改动' "$S/mport/port.log") 个目录扫过"
fi

# 全局 CONFIG：一加自家构建是整棵树一起开的，而 oplus_rq / oplus_task_struct
# 的字段本身受这些宏控制——各模块开的宏不一致，结构体布局就不一致，
# 编得过也是踩内存。所以取全树并集，所有模块统一用同一套。
GLOBAL_DEFS=$(find "$S/mport" -name Makefile -o -name Makefile.orig | xargs -r sed -nE 's/.*\$\(CONFIG_([A-Z_0-9]+)\).*/-DCONFIG_\1/p' | sort -u | tr '\n' ' ')
echo "全局 CONFIG 数：$(echo $GLOBAL_DEFS | wc -w)"

for d in $(find "$S/mport" -name Makefile | xargs -r -n1 dirname | sort -u); do
    ls "$d"/*.c >/dev/null 2>&1 || continue
    [ -f "$d/Makefile.orig" ] || cp "$d/Makefile" "$d/Makefile.orig"
    # 取所有对象：无条件的 -y，以及 -$(CONFIG_X) 的（一加默认全开，我们也全开）
    # 只取第一个 obj- 目标的对象组：有的 Makefile 里同时有正式版和 dbg 版，
    # 两组共用大部分源文件，混在一起编就是重复符号。
    tgt=$(sed -nE 's/^obj-[^ ]+ *\+?= *([a-zA-Z_0-9]+)\.o.*/\1/p' "$d/Makefile.orig" | head -1)
    if [ -n "$tgt" ]; then
        objs=$(sed -nE "s/^${tgt}-(y|\\\$\\(CONFIG_[A-Z_0-9]+\\)) *[:+]= *//p" "$d/Makefile.orig" | tr '\n' ' ')
    else
        objs=""
    fi
    [ -n "$objs" ] || objs=$(sed -nE 's/^[a-zA-Z_0-9]*-(y|\$\(CONFIG_[A-Z_0-9]+\)) *[:+]= *//p' "$d/Makefile.orig" | tr '\n' ' ')
    defs="$GLOBAL_DEFS"
    keep=""
    for o in $(echo $objs | tr ' ' '\n' | awk '!seen[$0]++'); do
        [ -f "$d/${o%.o}.c" ] && keep="$keep $o"
    done
    [ -n "$keep" ] || continue
    n=$(echo "${d#$S/mport/}" | tr '/' '_')
    mkdir -p "$d/trace/events"
    for h in "$d"/*.h; do ln -sfn "$h" "$d/trace/events/$(basename "$h")"; done
    printf 'obj-m += oplus_%s.o\noplus_%s-y :=%s\nccflags-y += -I%s -I%s -I%s/mm -I%s/cpu -I%s -I%s/drivers/android -include linux/sched/cputime.h -Wno-error %s\n' \
        "$n" "$n" "$keep" "$d" "$S/mport" "$S/mport" "$S/mport" "$SRC" "$SRC" "$defs" > "$d/Makefile"
    timeout 300 make -j16 -C "$SRC" O="$S/out-ref" M="$d" ARCH=arm64 LLVM=1 \
        KBUILD_MODPOST_WARN=1 modules > "$d/build.log" 2>&1
    rc=$?
    e=$(grep -cE "error:" "$d/build.log"); u=$(grep -cE "undefined!" "$d/build.log")
    k=$(ls "$d"/*.ko 2>/dev/null | wc -l)
    printf "== %-38s 退出 %-3s 报错 %-4s 未定义 %-4s ko %s\n" "$n" "$rc" "$e" "$u" "$k"
    [ "$e" = 0 ] || grep -E "error:" "$d/build.log" | sed 's/^.*error:/  E:/' | sort -u | head -4
    [ "$u" = 0 ] || grep -E "undefined!" "$d/build.log" | sed 's/^.*modpost: //;s/ \[.*\] / /' | sort -u | head -6
done
