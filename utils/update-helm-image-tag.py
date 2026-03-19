#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


TAG_LINE_RE = re.compile(r'^(?P<indent>\s*)tag:\s*".*"\s*(?P<comment>#.*)?$')


def update_values_file(path: Path, sha: str) -> None:
    if not path.exists():
        raise FileNotFoundError(f"Missing file: {path}")

    lines = path.read_text(encoding="utf-8").splitlines(True)
    out: list[str] = []
    changed = False

    for line in lines:
        m = TAG_LINE_RE.match(line)
        if m:
            indent = m.group("indent") or ""
            comment = m.group("comment") or ""
            suffix = f" {comment}" if comment and not comment.startswith(" ") else (comment or "")
            out.append(f'{indent}tag: "{sha}"{suffix}\n')
            changed = True
        else:
            out.append(line)

    if not changed:
        raise RuntimeError(f"Did not find image.tag line to update in {path}")

    path.write_text("".join(out), encoding="utf-8")
    print(f"Updated {path} image.tag -> {sha}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Update Helm values image.tag to a git SHA.")
    parser.add_argument("--sha", required=True, help="Git SHA to set as image tag")
    parser.add_argument("--file", action="append", required=True, help="Values file path to update (repeatable)")
    args = parser.parse_args()

    for f in args.file:
        update_values_file(Path(f), args.sha)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

