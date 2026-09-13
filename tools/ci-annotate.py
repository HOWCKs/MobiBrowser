#!/usr/bin/env python3
"""Converte a saída do Gradle em anotações do GitHub Actions.

Por que isto existe: o build é o nosso compilador (não há SDK/gradle local), e o log
bruto do Actions não é legível programaticamente quando o run falha. Anotações ficam
na API de checks (`/check-runs/<id>/annotations`) e no diff do PR — quem revise o
feedback do CI recebe a lista de erros por arquivo e linha, não 4 mil linhas de stack
trace do Gradle.

Uso: tools/ci-annotate.py <arquivo-de-log> [<mais logs>]
"""

from __future__ import annotations

import os
import re
import sys

# `e: file:///abs/caminho/File.kt:32:17: Unresolved reference 'x'.` (Kotlin 2.x)
KOTLIN_ERROR = re.compile(
    r"^e:\s+file://(?P<path>[^\s:]+):(?P<line>\d+):(?P<col>\d+):?\s*(?P<msg>.*)$"
)
# `e: file:///...: Unresolved reference: x` (Kotlin 1.9 e afins, sem coluna)
KOTLIN_ERROR_NO_COL = re.compile(r"^e:\s+file://(?P<path>[^\s:]+)(?::\d+)*:?\s*(?P<msg>.*)$")
# Java/lint/AGP: `path/File.java:12: error: ...`
JAVAC_ERROR = re.compile(r"^(?P<path>[\w./-]+\.(?:java|kt)):(?P<line>\d+):\s*error:\s*(?P<msg>.+)$")
# Falha de task: `> Task :app:compileUnstableKotlin FAILED`
TASK_FAILED = re.compile(r"^> Task (?P<task>[^\s]+) FAILED")
# Teste que falhou: `MinhaClasse > um teste FAILED`
TEST_FAILED = re.compile(r"^(?P<cls>[\w.$]+) > (?P<test>[^\s].*?) FAILED")
# Bloco "What went wrong" do Gradle: pegar a primeira linha após o cabeçalho
WENT_WRONG = re.compile(r"^\* What went wrong:")
GRADLE_CAUSE = re.compile(r"^>\s+(?P<msg>.+)$")

MAX_PER_KIND = 45
MAX_TOTAL = 120


def emit(level: str, message: str, path: str | None = None, line: int | None = None) -> None:
    """Imprime um comando de workflow. `::error file=x,line=12::msg`"""
    message = message.replace("\r", " ").replace("%", "%25").replace("\n", "%0A")
    message = message.replace(":", "%3A").replace("|", "%7C")[:900]
    attrs = []
    if path:
        attrs.append(f"file={path}")
    if line:
        attrs.append(f"line={line}")
    suffix = f" {','.join(attrs)}" if attrs else ""
    print(f"::{level}{suffix}::{message}")


def relative(path: str, root: str) -> str:
    path = path.lstrip("/")
    if root and path.startswith(root):
        return os.path.relpath(path, root)
    # Na dúvida, corta o prefixo até app/src — é o que o Actions resolve como arquivo.
    marker = "app/src/"
    idx = path.find(marker)
    return path[idx:] if idx >= 0 else path


def main() -> int:
    logs = [a for a in sys.argv[1:] if os.path.exists(a)]
    if not logs:
        emit("warning", "nenhum log de build encontrado para anotar")
        return 0

    root = os.getcwd()
    counts = {"kotlin": 0, "java": 0, "task": 0, "test": 0, "gradle": 0}
    total = 0
    what_went_wrong_pending = False

    for log in logs:
        with open(log, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()

        for raw in lines:
            if total >= MAX_TOTAL:
                emit("warning", "limite de anotações atingido; veja o log bruto para o resto")
                return 0
            line = re.sub(r"^\d{4}-\d\d-\d\dT[\d:.]+Z\s?", "", raw).rstrip()

            match = KOTLIN_ERROR.match(line) or KOTLIN_ERROR_NO_COL.match(line)
            if match and counts["kotlin"] < MAX_PER_KIND:
                counts["kotlin"] += 1
                total += 1
                emit(
                    "error",
                    match.group("msg").strip() or "erro de compilação Kotlin",
                    relative(match.group("path"), root),
                    int(match.group("line")) if match.groupdict().get("line") else None,
                )
                continue

            match = JAVAC_ERROR.match(line)
            if match and counts["java"] < MAX_PER_KIND:
                counts["java"] += 1
                total += 1
                emit("error", match.group("msg").strip(), match.group("path"), int(match.group("line")))
                continue

            match = TEST_FAILED.match(line)
            if match and counts["test"] < MAX_PER_KIND:
                counts["test"] += 1
                total += 1
                emit("error", f"teste falhou: {match.group('test')}", None, None)
                continue

            match = TASK_FAILED.match(line)
            if match and counts["task"] < 12:
                counts["task"] += 1
                total += 1
                emit("error", f"task do Gradle falhou: {match.group('task')}", None, None)
                continue

            if WENT_WRONG.match(line):
                what_went_wrong_pending = True
                continue

            if what_went_wrong_pending:
                match = GRADLE_CAUSE.match(line)
                if match and counts["gradle"] < 12:
                    counts["gradle"] += 1
                    total += 1
                    emit("error", f"causa: {match.group('msg').strip()}", None, None)
                elif line.strip() == "" or line.startswith("*"):
                    what_went_wrong_pending = False

    if total == 0:
        emit("warning", "nenhum erro reconhecido no log (falha pode ser de infra/rede)", None, None)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
