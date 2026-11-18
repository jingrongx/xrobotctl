#!/usr/bin/env python3
"""自动生成 XRobot 更新日志。

职责：
  1. 根据 git tag 计算下一个版本号（无 tag 时 v1.0.0，否则 patch +1）
  2. 汇总上个 tag..HEAD 的提交，按类型分组生成 Markdown
  3. 把新版本段落插到 CHANGELOG.md 顶部（保留历史）
  4. 输出本次 release notes 到独立文件供 gh release 使用

用法（在 CI 中）：
  python3 tools/gen_changelog.py \
      --changelog CHANGELOG.md --notes-out .release_notes.md
  echo "VERSION=$(python3 tools/gen_changelog.py --next-only)" >> "$GITHUB_OUTPUT"
"""
import argparse
import re
import subprocess
import sys
from datetime import datetime, timezone, timedelta

# 提交分类规则：按优先级匹配，命中即归类（中文提交信息也覆盖）
CATEGORIES = [
    ("新增", ("feat", "feature", "新增", "添加", "支持", "add", "implement")),
    ("修复", ("fix", "修复", "修正", "bug", "hotfix", "修")),
    ("文档", ("docs", "doc", "文档", "说明", "readme", "read")),
    ("重构", ("refactor", "重构", "优化", "perf", "perfomance", "chore")),
    ("发版", ("release", "发版", "版本", "build", "ci", "action")),
]
FALLBACK = "其他变更"


def git(*args: str) -> str:
    r = subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8")
    if r.returncode != 0:
        return ""
    return r.stdout.strip()


def latest_tag() -> str:
    tags = git("tag", "--sort=-v:refname", "--list", "v[0-9]*").splitlines()
    return tags[0] if tags else ""


def next_version(tag: str) -> str:
    m = re.match(r"^v(\d+)\.(\d+)\.(\d+)$", tag or "")
    if not m:
        return "v1.0.0"
    major, minor, patch = map(int, m.groups())
    return f"v{major}.{minor}.{patch + 1}"


def commits_since(tag: str):
    """返回 (hash, subject, body_first_line) 列表，跳过 merge 与 bot 发版提交。"""
    rev_range = f"{tag}..HEAD" if tag else "HEAD"
    raw = git("log", rev_range, "--no-merges", "--pretty=format:%h%x1f%s%x1f%b%x1e")
    out = []
    for block in raw.split("\x1e"):
        block = block.strip()
        if not block:
            continue
        parts = block.split("\x1f", 2)
        h, subject = parts[0], parts[1] if len(parts) > 1 else ""
        if not subject:
            continue
        if "更新更新日志" in subject or "[skip ci]" in subject or "自动生成" in subject and "日志" in subject:
            continue
        out.append((h, subject))
    return out


def classify(subject: str) -> str:
    s = subject.lower()
    for label, keys in CATEGORIES:
        if any(k in s for k in keys):
            return label
    return FALLBACK


def build_notes(version: str, tag: str, commits) -> str:
    tz = timezone(timedelta(hours=8))
    date = datetime.now(tz).strftime("%Y-%m-%d")
    grouped = {}
    for h, subject in commits:
        grouped.setdefault(classify(subject), []).append((h, subject))

    lines = [f"## {version} ({date})", ""]
    if not commits:
        lines.append("- 无代码变更（仅版本号推进）")
    else:
        for label, _ in CATEGORIES + [(FALLBACK, ())]:
            items = grouped.get(label)
            if not items:
                continue
            lines.append(f"### {label}")
            lines.append("")
            for h, subject in items:
                lines.append(f"- {subject} (`{h}`)")
            lines.append("")
    if tag:
        lines.append(f"**完整变更**: {tag}...{version}")
    return "\n".join(lines).rstrip() + "\n"


def prepend_changelog(path: str, version: str, notes: str) -> None:
    header = "# 更新日志\n\n本文件由发版流程自动生成，请勿手动编辑。\n"
    try:
        with open(path, "r", encoding="utf-8") as f:
            existing = f.read()
    except FileNotFoundError:
        existing = ""
    # 按版本块切分：第 0 段是头部说明，其余是历史版本块
    history = []
    for block in re.split(r"(?m)(?=^## v\d)", existing)[1:]:
        b = block.strip()
        if b and not b.startswith(f"## {version} "):
            history.append(b)  # 跳过同版本块（同名由本次内容替换，保证幂等）
    blocks = [notes.strip()] + history
    with open(path, "w", encoding="utf-8") as f:
        f.write(header + "\n" + "\n\n---\n\n".join(blocks) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--changelog", default="CHANGELOG.md")
    ap.add_argument("--notes-out", default="")
    ap.add_argument("--next-only", action="store_true", help="只打印下一个版本号后退出")
    ap.add_argument("--dry-run", action="store_true", help="不写文件，仅打印结果")
    args = ap.parse_args()

    tag = latest_tag()
    version = next_version(tag)
    if args.next_only:
        print(version)
        return 0

    commits = commits_since(tag)
    notes = build_notes(version, tag, commits)
    print(notes, file=sys.stderr)
    if args.dry_run:
        return 0

    prepend_changelog(args.changelog, version, notes)
    if args.notes_out:
        with open(args.notes_out, "w", encoding="utf-8") as f:
            f.write(notes)
    print(f"VERSION={version}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
