#!/usr/bin/env python3
"""Converte a saída do Gradle em anotações do GitHub Actions.

Por que isto existe: o build é o nosso compilador (não há SDK/Gradle no ambiente de
edição), e o log bruto do Actions não é legível programaticamente quando o run falha.
Anotações ficam na API de checks (`/repos/<owner>/<repo>/check-runs/<id>/annotations`)
e aparecem no diff da PR — quem lê o feedback do CI recebe erros por arquivo e linha,
e não quatro mil linhas de stack trace do Gradle.

Dois tipos de saída são tratados:
  * linhas do compilador Kotlin/Java (`e: arquivo:linha:coluna: mensagem`), uma anotação
    por erro, para poder corrigir em lote;
  * blocos de diagnóstico do Gradle/AGP ("N issues were found when checking AAR metadata",
    "What went wrong", "Execution failed for task"), uma anotação por bloco com o texto
    inteiro — esses não têm arquivo nem linha, e é onde mora a causa real.

Uso: tools/ci-annotate.py <arquivo-de-log> [<mais logs>]
Saída: comandos ::error::/::warning:: no stdout do passo.
"""

from __future__ import annotations

import os
import re
import sys

TS = re.compile(r"^\d{4}-\d\d-\d\dT[\d:.]+Z\s?")
KOTLIN_ERROR = re.compile(r"^e:\s+file://(?P<path>[^\s:]+):(?P<line>\d+):(?P<col>\d+):?\s*(?P<msg>.*)$")
KOTLIN_ERROR_LOOSE = re.compile(r"^e:\s+file://(?P<path>[^\s:]+)\s*(?P<msg>.*)$")
JAVAC_ERROR = re.compile(r"^(?P<path>[\w./-]+\.(?:java|kt)):(?P<line>\d+):\s*error:\s*(?P<msg>.+)$")
TASK_FAILED = re.compile(r"^> Task (?P<task>[^\s]+) FAILED")
TEST_FAILED = re.compile(r"^(?P<cls>[\w.$]+) > (?P<test>[^\s].*?) FAILED")
WENT_WRONG = re.compile(r"^\* What went wrong:")
BLOCK_END = re.compile(r"^\* Try:|^=\+$|^\* Get more help")
AAR_HEADER = re.compile(r"(?P<count>\d+) issues? were found when checking AAR metadata")

MAX_ISSUES = 45
MAX_BLOCKS = 8
BLOCK_CHARS = 1500
MAX_TOTAL = 140


def encode(message: str) -> str:
    # Ordem importa: % precisa vir antes dos %XX gerados aqui mesmo.
    return (
        message.replace("%", "%25")
        .replace("\r", "")
        .replace("\n", "%0A")
        .replace(":", "%3A")
        .replace("|", "%7C")
    )


def emit(level: str, message: str, path: str | None = None, line: int | None = None) -> None:
    message = encode(message[:BLOCK_CHARS])
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
    marker = "app/src/"
    idx = path.find(marker)
    return path[idx:] if idx >= 0 else path


def read_lines(log: str) -> list[str]:
    with open(log, encoding="utf-8", errors="replace") as handle:
        return [TS.sub("", line).rstrip() for line in handle.read().splitlines()]


def collect_block(lines: list[str], start: int, limit: int = 60) -> str:
    """Junta as linhas de um bloco de diagnóstico até um marcador de fim."""
    out: list[str] = []
    for raw in lines[start + 1 : start + 1 + limit]:
        if BLOCK_END.match(raw.strip()):
            break
        if raw.strip():
            out.append(re.sub(r"\s+", " ", raw.strip()))
        elif out:
            out.append("")
            if len(out) > 6 and not out[-1]:
                break
    return "\n".join(out).strip()


def main() -> int:
    logs = [a for a in sys.argv[1:] if os.path.exists(a)]
    if not logs:
        emit("warning", "nenhum log de build encontrado para anotar")
        return 0

    root = os.getcwd()
    kinds = {"kotlin": 0, "java": 0, "task": 0, "test": 0, "block": 0}
    total = 0
    seen: set[str] = set()

    for log in logs:
        lines = read_lines(log)
        for index, line in enumerate(lines):
            if total >= MAX_TOTAL:
                emit("warning", "limite de anotações atingido; veja o log bruto para o resto")
                return 0

            match = KOTLIN_ERROR.match(line)
            if match and kinds["kotlin"] < MAX_ISSUES:
                key = f"{match.group('path')}:{match.group('line')}:{match.group('msg')}"
                kinds["kotlin"] += 1
                total += 1
                if key not in seen:
                    seen.add(key)
                    emit(
                        "error",
                        match.group("msg").strip() or "erro de compilação Kotlin",
                        relative(match.group("path"), root),
                        int(match.group("line")),
                    )
                continue

            match = KOTLIN_ERROR_LOOSE.match(line)
            if match and kinds["kotlin"] < MAX_ISSUES and "e: " in line[:4]:
                kinds["kotlin"] += 1
                total += 1
                emit("error", f"erro Kotlin: {match.group('msg').strip()}", relative(match.group("path"), root))
                continue

            match = JAVAC_ERROR.match(line)
            if match and kinds["java"] < MAX_ISSUES:
                kinds["java"] += 1
                total += 1
                emit("error", match.group("msg").strip(), match.group("path"), int(match.group("line")))
                continue

            match = TEST_FAILED.match(line)
            if match and kinds["test"] < MAX_ISSUES:
                kinds["test"] += 1
                total += 1
                emit("error", f"teste falhou: {match.group('cls')} > {match.group('test')}")
                continue

            match = TASK_FAILED.match(line)
            if match and kinds["task"] < 12:
                kinds["task"] += 1
                total += 1
                emit("error", f"task do Gradle falhou: {match.group('task')}")
                continue

            interesting = AAR_HEADER.search(line) or WENT_WRONG.match(line)
            if interesting and kinds["block"] < MAX_BLOCKS:
                block = collect_block(lines, index)
                if not block:
                    continue
                header = line.strip()
                key = f"block::{hash(block)}"
                if key in seen:
                    continue
                seen.add(key)
                kinds["block"] += 1
                total += 1
                emit("error", f"{header}\n{block}")

    if total == 0:
        emit("warning", "nenhum erro reconhecido no log (falha pode ser de infra/rede)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
