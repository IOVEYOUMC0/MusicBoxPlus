# -*- coding: utf-8 -*-
"""
校验产物在整个支持版本范围内二进制兼容。

背景：源码能对两个版本都编译通过，并<b>不</b>代表产物在两边都能跑。最典型的反例是
`org.bukkit.Sound` 从 enum 变成 interface —— 对旧版编译出的是 Methodref，
在新版解析到 interface 时抛 IncompatibleClassChangeError，而那是 Error，接不住。
CustomJukeBox 就是这么整个启动不了的。

本脚本把产物里引用到的每一个平台类型，在「编译目标版本」与「其它支持版本」之间做对比：
  1. 类型是否仍然存在
  2. class / interface 的种类是否一致（种类变了 = 二进制不兼容）

用法：
    python scripts/verify-api-compat.py <classes-dir> <baseline-jar> <other-jar> [<other-jar> ...]
"""
import os
import re
import subprocess
import sys
import zipfile

PLATFORM_PREFIXES = ('org/bukkit/', 'io/papermc/', 'net/kyori/', 'com/destroystokyo/')


def find_classes(classes_dir):
    out = []
    for dp, _, fns in os.walk(classes_dir):
        for fn in fns:
            if fn.endswith('.class'):
                out.append(os.path.join(dp, fn))
    return out


def referenced_platform_types(classes_dir):
    """Return the set of platform types referenced from the constant pools of our classes."""
    classes = find_classes(classes_dir)
    if not classes:
        raise SystemExit('no .class files under ' + classes_dir)
    refs = set()
    # javap in batches: command lines have length limits
    for i in range(0, len(classes), 60):
        batch = classes[i:i + 60]
        proc = subprocess.run(['javap', '-v', '-p'] + batch,
                              capture_output=True, text=True, errors='replace')
        for m in re.finditer(r'//\s*(?:class\s+)?([\w/$]+)', proc.stdout):
            name = m.group(1)
            if name.startswith(PLATFORM_PREFIXES):
                refs.add(name.split('$')[0])
    return refs


def jar_kinds(classpath, wanted):
    """Map type -> 'interface' | 'class' | None(absent) for the wanted types on `classpath`.

    `classpath` may list several jars separated by os.pathsep -- a platform's API is usually
    split across paper-api plus the adventure modules it re-exports.
    """
    jars = [j for j in classpath.split(os.pathsep) if j]
    names = set()
    for j in jars:
        with zipfile.ZipFile(j) as zf:
            names.update(zf.namelist())
    kinds = {t: (None if (t + '.class') not in names else 'unknown') for t in wanted}
    present = [t for t, v in kinds.items() if v == 'unknown']
    for i in range(0, len(present), 60):
        batch = present[i:i + 60]
        proc = subprocess.run(['javap', '-cp', classpath] + [b.replace('/', '.') for b in batch],
                              capture_output=True, text=True, errors='replace')
        for line in proc.stdout.split('\n'):
            m = re.match(r'\s*(?:public |final |abstract )*(interface|class|enum)\s+([\w.$]+)', line)
            if m:
                t = m.group(2).replace('.', '/').split('$')[0]
                if t in kinds:
                    kinds[t] = 'interface' if m.group(1) == 'interface' else 'class'
    return kinds


def main():
    if len(sys.argv) < 4:
        raise SystemExit(__doc__)
    classes_dir, baseline = sys.argv[1], sys.argv[2]
    others = sys.argv[3:]

    refs = referenced_platform_types(classes_dir)
    print('产物引用的平台类型: %d' % len(refs))

    base_kinds = jar_kinds(baseline, refs)
    resolved = {t for t, k in base_kinds.items() if k}
    print('其中在基线 (%s) 中可解析: %d' % (os.path.basename(baseline), len(resolved)))

    failures = 0
    for other in others:
        other_kinds = jar_kinds(other, resolved)
        missing, changed = [], []
        for t in sorted(resolved):
            ok = other_kinds.get(t)
            if ok is None:
                missing.append(t)
            elif ok != base_kinds[t]:
                changed.append('%s: %s -> %s' % (t, base_kinds[t], ok))
        print()
        print('=== vs %s ===' % os.path.basename(other))
        if missing:
            failures += len(missing)
            print('  已移除的类型 (%d):' % len(missing))
            for t in missing:
                print('    -', t)
        if changed:
            failures += len(changed)
            print('  种类变更 (%d) —— 二进制不兼容:' % len(changed))
            for c in changed:
                print('    !', c)
        if not missing and not changed:
            print('  OK: 全部类型都存在且种类一致')

    print()
    if failures:
        print('不兼容项: %d' % failures)
        return 1
    print('通过：产物在全部受测版本上二进制兼容')
    return 0


if __name__ == '__main__':
    sys.exit(main())
