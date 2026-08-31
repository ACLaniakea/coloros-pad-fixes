#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
智能功放（AWINIC aw882xx）Re 校准 —— 电脑端一键执行器

Windows / Linux / macOS 通用，只依赖 Python 3 和 adb。设备侧的实际逻辑在
fix-module/module/bin/aw_cali_run.sh 里；这个脚本负责挑设备、推送、用 root
执行并把输出原样打回来。

用法：
    python tools/aw_cali.py                 # 自动选择唯一在线设备
    python tools/aw_cali.py -s <序列号>      # 多设备时指定
    python tools/aw_cali.py --adb D:\\adb.exe # 指定 adb 路径
    python tools/aw_cali.py --check         # 只查看当前校准值，不执行校准

背景：移植后 /mnt/vendor/persist/factory/audio/aw_cali.bin 可能是空的，功放
缺少每台实测的阻抗基准，大音量下容易破音。Re 是逐台数据，不能跨机复制。

注意：校准期间设备会以较低音量播放一段方波，请在安静环境下进行，不要遮挡扬声器。
"""

import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
LOCAL_SH = os.path.join(REPO, "fix-module", "module", "bin", "aw_cali_run.sh")
REMOTE_SH = "/data/local/tmp/aw_cali_run.sh"
CALI_FILE = "/mnt/vendor/persist/factory/audio/aw_cali.bin"


def find_adb(explicit):
    if explicit:
        if os.path.isfile(explicit):
            return explicit
        sys.exit("指定的 adb 不存在：%s" % explicit)
    found = shutil.which("adb") or shutil.which("adb.exe")
    if found:
        return found
    sys.exit("找不到 adb。请把它加入 PATH，或用 --adb 指定完整路径。")


def run(adb, serial, args, **kw):
    cmd = [adb] + (["-s", serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", **kw)


def pick_device(adb, serial):
    out = run(adb, None, ["devices"]).stdout.splitlines()
    devices = [l.split("\t")[0] for l in out[1:]
               if "\t" in l and l.split("\t")[1].strip() == "device"]
    if serial:
        if serial not in devices:
            sys.exit("设备 %s 不在线。当前在线：%s" % (serial, devices or "无"))
        return serial
    if not devices:
        sys.exit("没有在线设备。请确认已连接并开启 USB 调试。")
    if len(devices) > 1:
        sys.exit("检测到多台设备，请用 -s 指定：%s" % ", ".join(devices))
    return devices[0]


def have_root(adb, serial):
    r = run(adb, serial, ["shell", "su", "-c", "id -u"])
    return r.stdout.strip().endswith("0")


def main():
    ap = argparse.ArgumentParser(description="aw882xx Re 校准执行器")
    ap.add_argument("-s", "--serial", help="设备序列号")
    ap.add_argument("--adb", help="adb 可执行文件路径")
    ap.add_argument("--check", action="store_true", help="只查看当前校准值")
    a = ap.parse_args()

    adb = find_adb(a.adb)
    serial = pick_device(adb, a.serial)
    print("设备：%s" % serial)

    if not have_root(adb, serial):
        sys.exit("拿不到 root。本操作需要 KernelSU / Magisk 授权 shell。")

    cur = run(adb, serial, ["shell", "su", "-c", "cat %s" % CALI_FILE]).stdout.strip()
    digits = "".join(c for c in cur if c.isdigit())
    print("当前校准值：%s" % (" ".join(cur.split()) if digits else "（空，未校准）"))
    if a.check:
        return

    if digits:
        ans = input("已有校准值，重新校准会覆盖。继续？[y/N] ").strip().lower()
        if ans not in ("y", "yes"):
            print("已取消。")
            return

    if not os.path.isfile(LOCAL_SH):
        sys.exit("找不到设备侧脚本：%s" % LOCAL_SH)

    print("推送 %s -> %s" % (os.path.relpath(LOCAL_SH, REPO), REMOTE_SH))
    r = run(adb, serial, ["push", LOCAL_SH, REMOTE_SH])
    if r.returncode != 0:
        sys.exit("推送失败：%s" % (r.stderr.strip() or r.stdout.strip()))

    print("开始校准，设备会以较低音量播放方波，请保持安静且不要遮挡扬声器。\n" + "-" * 56)
    cmd = [adb] + ["-s", serial] + [
        "shell", "su", "-c", "chmod 755 %s; %s" % (REMOTE_SH, REMOTE_SH)]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace", bufsize=1)
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
    proc.wait()
    print("-" * 56)

    run(adb, serial, ["shell", "su", "-c", "rm -f %s" % REMOTE_SH])
    if proc.returncode != 0:
        sys.exit("校准脚本返回非零：%d" % proc.returncode)
    print("完成。重启一次让音频 HAL 重新读取校准值。")


if __name__ == "__main__":
    main()
