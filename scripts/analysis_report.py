#!/usr/bin/env python3
"""Regenerate all code-analysis reports. Same code in, same reports out.

Usage: python3 scripts/analysis_report.py
Then open app/build/reports/index.html.
"""
import os
import re
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / "app/build/reports"
ARCH = REPORTS / "architecture"
CLASSES = ROOT / "app/build/tmp/kotlin-classes/debug"
PACKAGE = "com.satory.graphenosai"
DEFAULT_JAVA_HOME = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"

LINKS = [
    ("Code complexity and smells (Detekt)", "detekt/detekt.html"),
    ("Test coverage (Kover)", "kover/htmlDebug/index.html"),
    ("Unit test results", "tests/testDebugUnitTest/index.html"),
    ("API documentation (Dokka)", "../dokka/html/index.html"),
]


def run(cmd):
    print("+", " ".join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True)


def run_gradle_reports():
    gradle = ["./gradlew", "--console=plain"]
    run(gradle + ["testDebugUnitTest", "koverHtmlReportDebug", "detekt"])
    # Generating docs never produces an APK, so the release-only native library check doesn't apply.
    run(gradle + ["dokkaHtml", "-x", "checkLlamaPrebuilt"])


def fold(name):
    """Map a compiled class name back to the source file it came from, e.g.
    'com.satory.graphenosai.ui.ComposableSingletons$SettingsScreenKt$lambda-1$1' -> 'ui.SettingsScreen'."""
    name = name.replace(" (debug)", "").removeprefix(PACKAGE).lstrip(".")
    name = name.replace("ComposableSingletons$", "")
    name = re.sub(r"\$.*", "", name)
    name = re.sub(r"Kt$", "", name)
    return name or "(root)"


def jdeps_edges(verbose):
    """Return the set of (from, to) dependencies between our own classes or packages."""
    jdeps = Path(os.environ["JAVA_HOME"]) / "bin/jdeps"
    with tempfile.TemporaryDirectory() as tmp:
        run([str(jdeps), f"-verbose:{verbose}", "-filter:none",
             "--regex", re.escape(PACKAGE) + ".*", "-dotoutput", tmp, str(CLASSES)])
        dot = (Path(tmp) / "debug.dot").read_text()
    edges = set()
    for src, dst in re.findall(r'"([^"]+)"\s*->\s*"([^"]+)"', dot):
        # "(not found)" targets are Android's generated R resource classes, compiled outside this directory.
        if "(not found)" in dst:
            continue
        a, b = fold(src), fold(dst)
        if a != b and "R" not in (a, b):
            edges.add((a, b))
    return edges


def package_of(node):
    return node.rsplit(".", 1)[0] if "." in node else "(root)"


def to_dot(edges, clustered):
    lines = ["digraph G {", "  rankdir=LR;", '  node [shape=box, style=rounded, fontname="Helvetica"];']
    if clustered:
        nodes = sorted({n for edge in edges for n in edge})
        for pkg in sorted({package_of(n) for n in nodes}):
            lines.append(f'  subgraph "cluster_{pkg}" {{ label="{pkg}"; style=dashed;')
            lines += [f'    "{n}" [label="{n.rsplit(".", 1)[-1]}"];' for n in nodes if package_of(n) == pkg]
            lines.append("  }")
    lines += [f'  "{a}" -> "{b}";' for a, b in sorted(edges)]
    lines.append("}")
    return "\n".join(lines) + "\n"


def render(dot_text, name):
    dot_path = ARCH / f"{name}.dot"
    dot_path.write_text(dot_text)
    run(["dot", "-Tsvg", str(dot_path), "-o", str(ARCH / f"{name}.svg")])


def write_index():
    links = "\n".join(f'<li><a href="{href}">{title}</a></li>' for title, href in LINKS)
    graphs = "\n".join(
        f'<h2>{title}</h2><a href="architecture/{n}.svg"><img src="architecture/{n}.svg"></a>'
        for title, n in [("Package dependencies", "packages"), ("File dependencies", "files")]
    )
    (REPORTS / "index.html").write_text(
        "<!doctype html><meta charset='utf-8'><title>gos-ai analysis</title>"
        "<style>body{font-family:sans-serif;margin:2em}img{max-width:100%;border:1px solid #ccc}</style>"
        f"<h1>gos-ai analysis reports</h1><ul>{links}</ul>{graphs}\n"
    )


def main():
    os.environ.setdefault("JAVA_HOME", DEFAULT_JAVA_HOME)
    run_gradle_reports()
    ARCH.mkdir(parents=True, exist_ok=True)
    render(to_dot(jdeps_edges("package"), clustered=False), "packages")
    render(to_dot(jdeps_edges("class"), clustered=True), "files")
    write_index()
    print(f"\nOpen {REPORTS / 'index.html'}")


if __name__ == "__main__":
    main()
