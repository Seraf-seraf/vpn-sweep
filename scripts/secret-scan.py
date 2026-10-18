#!/usr/bin/env python3
"""Heuristic scan of working files and the index; matched values are never printed."""
import pathlib
import re
import subprocess
import sys

PRIVATE_PARTS = {"private", "imports", "subscriptions", "state", "results", "logs", "build", "dist"}


def content_rules(data):
    rules = []
    if (b"-----BEGIN " + b"PRIVATE KEY-----") in data or (b"-----BEGIN " + b"OPENSSH PRIVATE KEY-----") in data:
        rules.append("private_key")
    if re.search(rb"https://[^\s\"']+(?:/sub/|/subscription/)[A-Za-z0-9_-]{16,}", data):
        rules.append("subscription_token")
    for match in re.finditer(rb"(?:vless|trojan|ss|socks5?)://[^\s\"'`<>]+", data):
        value = match.group()
        if b"@" in value and not any(marker in value for marker in (b".invalid", b"127.0.0.1", b"localhost", b"@a:", b"@1.2.3.4")):
            rules.append("credential_uri")
    if re.search(rb'"(?:uuid|password|pass)"\s*:\s*"(?!synthetic|test|secret-marker|local-|pass|p"|x"|\")', data):
        rules.append("credential_literal")
    return rules


def main():
    root = pathlib.Path(__file__).resolve().parent.parent
    names = set()
    for args in (("ls-files", "-z"), ("ls-files", "--others", "--exclude-standard", "-z")):
        names.update(p.decode() for p in subprocess.check_output(("git", *args), cwd=root).split(b"\0") if p)
    findings = []
    for name in sorted(names):
        path = root / name
        if pathlib.Path(name).parts[0] in PRIVATE_PARTS or pathlib.Path(name).name.startswith(".env"):
            findings.append((name, "private_path_tracked"))
        if path.is_symlink():
            findings.append((name, "source_symlink"))
        elif path.is_file():
            findings.extend((name, rule) for rule in content_rules(path.read_bytes()))
    staged = subprocess.check_output(("git", "diff", "--cached", "--name-only", "-z"), cwd=root).split(b"\0")
    for raw in staged:
        if not raw:
            continue
        name = raw.decode()
        content = subprocess.run(("git", "show", ":" + name), cwd=root, capture_output=True)
        if content.returncode == 0:
            findings.extend((name, "staged_" + rule) for rule in content_rules(content.stdout))
    if findings:
        for name, rule in sorted(set(findings)):
            print(f"{name}: {rule}", file=sys.stderr)
        return 1
    print(f"secret scan: {len(names)} files; staged {sum(bool(x) for x in staged)}; no findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
