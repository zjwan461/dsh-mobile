#!/usr/bin/env python3
"""Statically verify every @Override in MainActivity against the real
Android framework method table.

The Android SDK is not installed on the development machine, so javac cannot
tell us that, for example, `onReceivedTitle` belongs to WebChromeClient and not
to WebViewClient. This check reproduces that specific javac error
("method does not override or implement a method from a supertype") by
comparing each @Override against tools/android-api-signatures.json.

Usage:
    python tools/check-overrides.py [path-to-java-file]

Exit code 0 means every @Override matches a framework method name and arity.
"""

import json
import os
import sys

try:
    import javalang
except ImportError:  # pragma: no cover - dev tooling only
    print("javalang is required: python -m pip install javalang")
    sys.exit(2)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "app", "src", "main", "java", "com", "dsh", "mobile", "MainActivity.java")
TABLE = os.path.join(ROOT, "tools", "android-api-signatures.json")


def simple_name(type_node):
    if type_node is None:
        return None
    name = type_node.name
    return name.split(".")[-1]


def arity(method):
    return len(method.parameters) if method.parameters else 0


def main():
    with open(TABLE, encoding="utf-8") as handle:
        table = json.load(handle)

    source_path = sys.argv[1] if len(sys.argv) > 1 else SOURCE
    source = open(source_path, encoding="utf-8").read()
    tree = javalang.parse.parse(source)

    checked = 0
    problems = []

    for _, node in tree.filter(javalang.tree.ClassDeclaration):
        base = simple_name(node.extends)
        if base not in table:
            continue

        methods = table[base]
        for method in (node.methods or []):
            annotations = [a.name.split(".")[-1] for a in (method.annotations or [])]
            if "Override" not in annotations:
                continue
            checked += 1
            name = method.name
            count = arity(method)
            allowed = methods.get(name)
            if allowed is None:
                problems.append(
                    "{}.{}(): {} has no such method".format(node.name, name, base)
                )
            elif count not in allowed:
                problems.append(
                    "{}.{}(): {} declares it with {} parameter(s), found {}".format(
                        node.name, name, base, allowed, count
                    )
                )

    print("classes checked against {}".format(", ".join(sorted(table))))
    print("@Override methods verified: {}".format(checked))

    if problems:
        print("\nFAILED:")
        for problem in problems:
            print("  - " + problem)
        return 1

    print("OK: every @Override matches a framework method")
    return 0


if __name__ == "__main__":
    sys.exit(main())