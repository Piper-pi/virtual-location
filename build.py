#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build.py — 不依赖 Gradle 的 Android 构建脚本

流程：aapt2 compile → aapt2 link → javac → d8 → 组装 APK → zipalign → apksigner

环境要求
  * JDK 11 及以上（需要 JAVA_HOME，或 javac 在 PATH 上）
  * Android SDK（ANDROID_HOME / ANDROID_SDK_ROOT，需含 platforms;android-34 与 build-tools）

用法
  python build.py                 # 构建 deb 签名包（首次会自动生成 keystore.jks）
  python build.py --install       # 构建后 adb install -r
  python build.py --unsigned      # 只产出未签名 APK
  python build.py --sdk PATH      # 显式指定 SDK 路径

注意
  keystore.jks 是签名私钥，**不要提交到版本库**（.gitignore 已排除）。
"""

import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
APP_DIR = os.path.join(ROOT, 'app')
MANIFEST = os.path.join(APP_DIR, 'AndroidManifest.xml')
KS = os.path.join(ROOT, 'keystore.jks')
KS_PASS = 'android'
ALIAS = 'virtual-location'

IS_WINDOWS = os.name == 'nt'


# ------------------------------------------------------------------ 环境探测
def fail(msg):
    print('[error]', msg)
    raise SystemExit(1)


def find_sdk(explicit=None):
    candidates = []
    if explicit:
        candidates.append(explicit)
    for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT', 'ANDROID_SDK'):
        v = os.environ.get(key)
        if v:
            candidates.append(v)
    if IS_WINDOWS:
        local = os.environ.get('LOCALAPPDATA', '')
        candidates += [os.path.join(local, 'Android', 'Sdk'),
                       r'C:\Android\Sdk', r'D:\Android\Sdk']
    else:
        candidates += [os.path.expanduser('~/Android/Sdk'),
                       '/usr/lib/android-sdk', '/opt/android-sdk']
    for c in candidates:
        if c and os.path.isdir(os.path.join(c, 'platforms')):
            return os.path.abspath(c)
    fail('找不到 Android SDK：请设置 ANDROID_HOME，或用 --sdk 指定（需包含 platforms 目录）')


def find_java():
    jh = os.environ.get('JAVA_HOME')
    if jh and os.path.exists(javac_path(jh)):
        return jh
    javac = shutil.which('javac')
    if javac:
        return os.path.dirname(os.path.dirname(javac))
    fail('找不到 JDK：请设置 JAVA_HOME，或让 javac 出现在 PATH 中')


def javac_path(java_home):
    return os.path.join(java_home, 'bin', 'javac.exe' if IS_WINDOWS else 'javac')


def tool(build_tools_dir, name):
    """在 build-tools 目录下定位可执行文件（Windows 有 .exe/.bat 后缀）。"""
    for suffix in ('', '.exe', '.bat'):
        p = os.path.join(build_tools_dir, name + suffix)
        if os.path.exists(p):
            return p
    return None


def version_key(path):
    name = os.path.basename(path.rstrip('/\\'))
    parts = re.findall(r'\d+', name)
    return tuple(int(x) for x in parts) if parts else (0,)


def pick_platform(sdk, wanted=34):
    platforms = glob.glob(os.path.join(sdk, 'platforms', 'android-*'))
    if not platforms:
        fail('SDK 中没有 platforms/android-*：请安装 platforms;android-34')
    exact = [p for p in platforms if os.path.basename(p) == 'android-%d' % wanted]
    chosen = exact[0] if exact else max(platforms, key=version_key)
    jar = os.path.join(chosen, 'android.jar')
    if not os.path.exists(jar):
        fail('缺少 android.jar：' + jar)
    return chosen, jar


def pick_build_tools(sdk, minimum=30):
    dirs = [d for d in glob.glob(os.path.join(sdk, 'build-tools', '*'))
            if os.path.isdir(d) and tool(d, 'aapt2')]
    if not dirs:
        fail('SDK 中没有可用的 build-tools（需要 aapt2/zipalign/apksigner）')
    dirs.sort(key=version_key)
    usable = [d for d in dirs if version_key(d) >= (minimum,)] or dirs
    return usable[-1]


def pick_d8(tools):
    """d8 优先取新版 build-tools：旧版 R8 在 JDK 17+/21 上会抛 NPE。"""
    candidates = sorted(tools, key=version_key, reverse=True)
    for d in candidates:
        p = tool(d, 'd8')
        if p:
            return p
    fail('build-tools 中没有 d8')


def manifest_value(key):
    with open(MANIFEST, encoding='utf-8') as f:
        text = f.read()
    m = re.search(r'android:%s="([^"]+)"' % key, text)
    return m.group(1) if m else None


# ---------------------------------------------------------------------- 构建
def run(cmd, env):
    shown = ' '.join(os.path.basename(c) if i == 0 else c for i, c in enumerate(cmd[:6]))
    print('>>', shown + (' ...' if len(cmd) > 6 else ''))
    r = subprocess.run(cmd, env=env, capture_output=True, text=True, errors='replace')
    out = (r.stdout or '') + (r.stderr or '')
    if r.returncode != 0:
        print(out[-4000:])
        fail('命令失败：%s' % os.path.basename(cmd[0]))
    return out


def mirror_if_needed(src):
    """aapt2 不处理非 ASCII 路径：把工程镜像到临时目录再构建。"""
    if all(ord(ch) < 128 for ch in src):
        return src
    dst = os.path.join(tempfile.gettempdir(), 'virtual-location-build')
    if os.path.isdir(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst, ignore=shutil.ignore_patterns('build', 'keystore.jks', '.git'))
    print('[info] 工程路径含非 ASCII 字符，已镜像到', dst)
    return dst


def main():
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except Exception:
        pass

    want_install = '--install' in sys.argv
    unsigned_only = '--unsigned' in sys.argv
    sdk_arg = None
    if '--sdk' in sys.argv:
        sdk_arg = sys.argv[sys.argv.index('--sdk') + 1]

    sdk = find_sdk(sdk_arg)
    java_home = find_java()
    platform_dir, android_jar = pick_platform(sdk)
    build_tools = pick_build_tools(sdk)
    all_tools = sorted([d for d in glob.glob(os.path.join(sdk, 'build-tools', '*'))
                        if os.path.isdir(d)], key=version_key)
    d8 = pick_d8(all_tools)

    version_code = manifest_value('versionCode') or '1'
    version_name = manifest_value('versionName') or '1.0'

    print('SDK       ', sdk)
    print('platform  ', os.path.basename(platform_dir))
    print('tools     ', os.path.basename(build_tools))
    print('d8        ', d8)
    print('JAVA_HOME ', java_home)
    print('version   ', version_name, '(%s)' % version_code)

    env = dict(os.environ)
    env['JAVA_HOME'] = java_home
    env['PATH'] = (os.path.join(java_home, 'bin') + os.pathsep + build_tools
                   + os.pathsep + os.path.join(sdk, 'platform-tools')
                   + os.pathsep + env.get('PATH', ''))

    work = mirror_if_needed(ROOT)
    build = os.path.join(work, 'build')
    gen = os.path.join(build, 'gen')
    classes = os.path.join(build, 'classes')
    dex_dir = os.path.join(build, 'dex')
    if os.path.isdir(build):
        shutil.rmtree(build)
    for d in (build, gen, classes, dex_dir):
        os.makedirs(d, exist_ok=True)

    app_dir = os.path.join(work, 'app')

    # 1) 编译资源
    res_zip = os.path.join(build, 'res.zip')
    run([tool(build_tools, 'aapt2'), 'compile', '--dir', os.path.join(app_dir, 'res'),
         '-o', res_zip], env)

    # 2) 链接资源 + 生成 R.java
    base_apk = os.path.join(build, 'base.apk')
    run([tool(build_tools, 'aapt2'), 'link', '-o', base_apk,
         '-I', android_jar,
         '--manifest', os.path.join(app_dir, 'AndroidManifest.xml'),
         '-R', res_zip, '--java', gen,
         '--min-sdk-version', '24', '--target-sdk-version', '34',
         '--version-code', version_code, '--version-name', version_name,
         '--auto-add-overlay'], env)

    # 3) 编译 Java
    sources = []
    for base, _dirs, files in os.walk(os.path.join(app_dir, 'java')):
        sources += [os.path.join(base, f) for f in files if f.endswith('.java')]
    for base, _dirs, files in os.walk(gen):
        sources += [os.path.join(base, f) for f in files if f.endswith('.java')]
    if not sources:
        fail('没有找到 Java 源码')

    run([javac_path(java_home), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
         '-nowarn', '-bootclasspath', android_jar, '-classpath', android_jar,
         '-d', classes] + sources, env)

    # 4) 转 dex
    class_files = []
    for base, _dirs, files in os.walk(classes):
        class_files += [os.path.join(base, f) for f in files if f.endswith('.class')]
    run([d8, '--lib', android_jar, '--min-api', '24', '--output', dex_dir] + class_files, env)

    # 5) 组装 APK（resources.arsc 保持不压缩，便于 4 字节对齐）
    unsigned = os.path.join(build, 'unsigned.apk')
    with zipfile.ZipFile(base_apk, 'r') as src, zipfile.ZipFile(unsigned, 'w') as dst:
        for zi in src.infolist():
            data = src.read(zi.filename)
            ctype = zipfile.ZIP_STORED if zi.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
            nzi = zipfile.ZipInfo(zi.filename, date_time=(2026, 1, 1, 0, 0, 0))
            nzi.compress_type = ctype
            nzi.external_attr = zi.external_attr or (0o644 << 16)
            dst.writestr(nzi, data)
        with open(os.path.join(dex_dir, 'classes.dex'), 'rb') as f:
            nzi = zipfile.ZipInfo('classes.dex', date_time=(2026, 1, 1, 0, 0, 0))
            nzi.compress_type = zipfile.ZIP_DEFLATED
            nzi.external_attr = 0o644 << 16
            dst.writestr(nzi, f.read())

    # 6) 对齐
    aligned = os.path.join(build, 'aligned.apk')
    run([tool(build_tools, 'zipalign'), '-f', '-p', '4', unsigned, aligned], env)

    if unsigned_only:
        final = os.path.join(ROOT, 'build', 'virtual-location-unsigned.apk')
        os.makedirs(os.path.dirname(final), exist_ok=True)
        shutil.copy(aligned, final)
        print('APK:', final, os.path.getsize(final), 'bytes (unsigned)')
        return

    # 7) 签名（keystore 不存在时自动生成，仅用于本地安装调试）
    ks = os.path.join(work, 'keystore.jks')
    if not os.path.exists(ks):
        run([os.path.join(java_home, 'bin', 'keytool.exe' if IS_WINDOWS else 'keytool'),
             '-genkeypair', '-keystore', ks, '-alias', ALIAS, '-keyalg', 'RSA',
             '-keysize', '2048', '-validity', '10000',
             '-storepass', KS_PASS, '-keypass', KS_PASS,
             '-dname', 'CN=Virtual Location, O=OpenSource, C=CN'], env)
        print('[info] 已生成签名用 keystore.jks（请勿提交到版本库）')

    name = 'virtual-location-%s.apk' % version_name
    out_apk = os.path.join(build, name)
    run([tool(build_tools, 'apksigner'), 'sign',
         '--ks', ks, '--ks-pass', 'pass:' + KS_PASS, '--key-pass', 'pass:' + KS_PASS,
         '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
         '--out', out_apk, aligned], env)

    # 8) 校验
    verify = run([tool(build_tools, 'apksigner'), 'verify', '--verbose', out_apk], env)
    for line in verify.splitlines():
        if 'Verified using' in line or 'Verifies' in line:
            print('   ', line.strip())
    run([tool(build_tools, 'zipalign'), '-c', '4', out_apk], env)
    print('    zipalign: ok')

    final = out_apk
    if work != ROOT:
        final = os.path.join(ROOT, 'build', name)
        os.makedirs(os.path.dirname(final), exist_ok=True)
        shutil.copy(out_apk, final)
        if not os.path.exists(KS):
            shutil.copy(ks, KS)
    print('APK:', final, os.path.getsize(final), 'bytes')

    if want_install:
        run([os.path.join(sdk, 'platform-tools', 'adb'), 'install', '-r', final], env)


if __name__ == '__main__':
    main()
