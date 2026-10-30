"""Collect notices from the Go modules actually linked into the Android core."""

import json
import pathlib
import sys


def main() -> None:
    packages_path, output_path, repo_path = map(pathlib.Path, sys.argv[1:])
    text = packages_path.read_text(encoding="utf-8")
    decoder = json.JSONDecoder()
    modules = {}
    while text.strip():
        package, end = decoder.raw_decode(text.lstrip())
        text = text.lstrip()[end:]
        module = package.get("Module")
        if module and not module.get("Main"):
            modules[module["Path"]] = module
    sections = ["VPN Sweep Android integration\n"]
    for name, module in sorted(modules.items()):
        root = pathlib.Path(module["Dir"])
        files = sorted(
            p for p in root.iterdir()
            if p.is_file() and p.name.upper().startswith(
                ("LICENSE", "LICENCE", "NOTICE", "COPYRIGHT", "PATENTS")
            )
        )
        if not files:
            raise RuntimeError(f"No license found for module {name}")
        sections.append(f"\n{name} {module['Version']}\n")
        for file in files:
            sections.append(f"\n{file.name}\n{file.read_text(encoding='utf-8')}\n")
    for path in ("LICENSE", "docs/GO-LICENSE.txt"):
        sections.append(f"\n{path}\n{(repo_path / path).read_text(encoding='utf-8')}\n")
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / "THIRD_PARTY_NOTICES.txt").write_text("".join(sections), encoding="utf-8")


if __name__ == "__main__":
    main()
