#!/usr/bin/env python3
# rename_project.py
#
# Usage:
#   python rename_project.py OLD_NAME NEW_NAME .
#   python rename_project.py OLD_NAME NEW_NAME . --apply
#
# Eksempel:
#   python rename_project.py Restless BlackSwan .
#   python rename_project.py Restless BlackSwan . --apply

from pathlib import Path
import argparse
import re
import os

SKIP_DIRS = {
    ".git", ".hg", ".svn",
    ".venv", "venv", "env",
    "__pycache__",
    ".mypy_cache", ".pytest_cache", ".ruff_cache",
    "node_modules",
    "dist", "build",
    ".idea", ".vscode",
}

SKIP_SUFFIXES = {
    ".pyc", ".pyo", ".so", ".dll", ".exe",
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico",
    ".zip", ".tar", ".gz", ".xz", ".7z",
    ".pdf", ".mp3", ".mp4", ".ogg", ".wav",
    ".db", ".sqlite", ".sqlite3",
}


def words(s):
    """Del opp fooBar-baz_qux -> [foo, Bar, baz, qux]."""
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", s)
    return [x for x in re.split(r"[^A-Za-z0-9]+", s) if x]


def variants(old, new):
    ow = words(old)
    nw = words(new)

    def snake(x):  return "_".join(w.lower() for w in x)
    def kebab(x):  return "-".join(w.lower() for w in x)
    def flat(x):   return "".join(w.lower() for w in x)
    def upper(x):  return "_".join(w.upper() for w in x)
    def pascal(x): return "".join(w[:1].upper() + w[1:].lower() for w in x)
    def camel(x):
        p = pascal(x)
        return p[:1].lower() + p[1:] if p else p

    pairs = [
        (old, new),
        (snake(ow), snake(nw)),
        (kebab(ow), kebab(nw)),
        (flat(ow), flat(nw)),
        (upper(ow), upper(nw)),
        (pascal(ow), pascal(nw)),
        (camel(ow), camel(nw)),
    ]

    # lengste først, fjern duplikater
    out = {}
    for a, b in pairs:
        if a and a != b:
            out[a] = b

    return sorted(out.items(), key=lambda x: len(x[0]), reverse=True)


def ignored(path):
    return any(part in SKIP_DIRS for part in path.parts)


def text_file(path):
    if path.suffix.lower() in SKIP_SUFFIXES:
        return False

    try:
        chunk = path.read_bytes()[:8192]
    except OSError:
        return False

    if b"\x00" in chunk:
        return False

    try:
        chunk.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def replace_all(s, pairs):
    for old, new in pairs:
        s = s.replace(old, new)
    return s


def replace_contents(root, pairs, apply):
    changed = 0

    for path in root.rglob("*"):
        if ignored(path) or not path.is_file() or path.is_symlink():
            continue

        if not text_file(path):
            continue

        try:
            old_text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue

        new_text = replace_all(old_text, pairs)

        if new_text != old_text:
            print(f"TEXT   {path}")
            changed += 1

            if apply:
                tmp = path.with_name(path.name + ".rename_tmp")
                tmp.write_text(new_text, encoding="utf-8")
                os.replace(tmp, path)

    return changed


def rename_paths(root, pairs, apply):
    changed = 0

    # deepest-first så mapper ikke flyttes før innholdet
    paths = sorted(
        (p for p in root.rglob("*") if not ignored(p) and not p.is_symlink()),
        key=lambda p: len(p.parts),
        reverse=True,
    )

    for path in paths:
        new_name = replace_all(path.name, pairs)

        if new_name == path.name:
            continue

        target = path.with_name(new_name)

        if target.exists():
            print(f"SKIP   {path}")
            print(f"       target exists: {target}")
            continue

        print(f"RENAME {path}")
        print(f"    -> {target}")
        changed += 1

        if apply:
            path.rename(target)

    return changed


def main():
    ap = argparse.ArgumentParser(
        description="Recursive project rename + find/replace"
    )
    ap.add_argument("old")
    ap.add_argument("new")
    ap.add_argument("directory", nargs="?", default=".")
    ap.add_argument(
        "--apply",
        action="store_true",
        help="Actually modify files. Without this flag = dry-run.",
    )
    args = ap.parse_args()

    root = Path(args.directory).expanduser().resolve()

    if not root.is_dir():
        raise SystemExit(f"Not a directory: {root}")

    pairs = variants(args.old, args.new)

    print("=== REPLACEMENTS ===")
    for old, new in pairs:
        print(f"{old!r:30} -> {new!r}")

    print()
    print("MODE:", "APPLY" if args.apply else "DRY RUN")
    print("ROOT:", root)
    print()

    # Først innhold, deretter fil-/mappenavn
    content_count = replace_contents(root, pairs, args.apply)
    path_count = rename_paths(root, pairs, args.apply)

    print()
    print("=== DONE ===")
    print(f"text files changed : {content_count}")
    print(f"paths renamed      : {path_count}")

    if not args.apply:
        print()
        print("Nothing was modified.")
        print("Run again with --apply when output looks sane.")


if __name__ == "__main__":
    main()
