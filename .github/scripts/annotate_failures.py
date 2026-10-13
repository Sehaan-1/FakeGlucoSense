#!/usr/bin/env python3
"""Turn Gradle and JUnit failures into GitHub annotations, so they can be read from the checks API.

Usage: annotate_failures.py <gradle-log> [test-results-dir ...]

Prints a short summary to the job log and emits ::error workflow commands for:
  - Kotlin compiler errors (lines starting with "e: ")
  - the "What went wrong" block from Gradle
  - failing JUnit test cases from *.xml result files
Annotations are capped so the output stays readable. The full log is uploaded as an artifact.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX_PER_KIND = 25


def escape(value: str) -> str:
    # Workflow command escaping for the message part.
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def error(title: str, message: str) -> None:
    safe_title = title.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A").replace(":", "%3A").replace(",", "%2C")
    print(f"::error title={safe_title}::{escape(message)}")


def compile_errors(lines):
    found = [line.rstrip() for line in lines if line.startswith("e: ")]
    for line in found[:MAX_PER_KIND]:
        error("Kotlin compile", line)
    print(f"Kotlin compile errors: {len(found)}")


def gradle_failures(lines):
    blocks = []
    for index, line in enumerate(lines):
        if line.startswith("* What went wrong:"):
            block = []
            for follow in lines[index + 1 : index + 12]:
                if follow.startswith("* Try:") or follow.startswith("* Exception is:"):
                    break
                if follow.strip():
                    block.append(follow.rstrip())
            blocks.append("\n".join(block))
    for block in blocks[:MAX_PER_KIND]:
        error("Gradle: what went wrong", block)
    print(f"Gradle failure blocks: {len(blocks)}")


def test_failures(result_dirs):
    count = 0
    for directory in result_dirs:
        for path in glob.glob(os.path.join(directory, "**", "*.xml"), recursive=True):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                failure = case.find("failure")
                if failure is None:
                    failure = case.find("error")
                if failure is None:
                    continue
                count += 1
                if count <= MAX_PER_KIND:
                    name = f"{case.get('classname', '')}.{case.get('name', '')}"
                    message = (failure.get("message") or "").strip()
                    detail = (failure.text or "").strip().splitlines()[:6]
                    error(f"Test failed: {name}", "\n".join([message] + detail))
    print(f"Failing test cases: {count}")


def main(argv):
    log_path = argv[1] if len(argv) > 1 else "gradle-android.log"
    result_dirs = argv[2:] or ["app/build/test-results", "sensor-app/build/test-results"]
    try:
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except FileNotFoundError:
        lines = []
        print(f"No log at {log_path}")
    compile_errors(lines)
    gradle_failures(lines)
    test_failures(result_dirs)
    if lines:
        print("---- last 80 lines of Gradle output ----")
        print("\n".join(lines[-80:]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
