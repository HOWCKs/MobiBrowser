#!/usr/bin/env python3
"""Converte a saída do Gradle em anotações do GitHub Actions.

Por que isto existe: o build é o nosso compilador (não há SDK/Gradle no ambiente de
edição), e o log bruto do Actions não é legível programaticamente quando o run falha.
Anotações ficam na API de checks (`/repos/<owner>/<repo>/check-runs/<id>/annotations`)
e aparecem no diff da PR — quem lê o feedback do CI recebe erros por arquivo e linha.

Detalhe que importa: o GitHub **descarta anotações além de 50 por check run**. Por isso os
erros do compilador são agrupados por arquivo (um arquivo com 40 erros = 1 anotação), em
vez de uma anotação por linha — assim o primeiro ciclo já mostra o quadro inteiro.

Dois tipos de saída são tratados:
  * `e: file:///caminho/Kt:linha:coluna: mensagem` (compilador Kotlin/Java), agrupados por
    arquivo;
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
KOTLIN_ERROR_NOPOS = re.compile(r"^e:\s+file://(?P<path>[^\s:]+):\s*(?P<msg>.+)$")
JAVAC_ERROR = re.compile(r"^(?P<path>[\w./-]+\.(?:java|kt)):(?P<line>\d+):\s*error:\s*(?P<msg>.+)$")
TASK_FAILED = re.compile(r"^> Task (?P<task>[^\s]+) FAILED")
TEST_FAILED = re.compile(r"^(?P<cls>[\w.$]+) > (?P<test>[^\s].*?) FAILED")
WENT_WRONG = re.compile(r"^\* What went wrong:")
BLOCK_END = re.compile(r"^\* Try:|^=\+$|^\* Get more help|^\* For more")
AAR_HEADER = re.compile(r"(?P<count>\d+) issues? were found when checking AAR metadata")
COMPILE_NOTE = re.compile(r"^(FAILURE:|Execution failed for task|> Compilation error)")

MAX_PER_FILE = 14       # mensagens exibidas por arquivo
MAX_FILES = 24          # arquivos anotados
MAX_BLOCKS = 6
BLOCK_CHARS = 1400


def encode(message: str) -> str:
    # Ordem importa: % precisa ser escapado antes dos %XX gerados aqui mesmo.
    return (
        message.replace("%", "%25")
        .replace("\r", "")
        .replace("\n", "%0A")
        .replace(":", "%3A")
        .replace("|", "%7C")[:BLOCK_CHARS]
    )


def emit(level: str, message: str, path: str | None = None, line: int | None = None) -> None:
    attrs = []
    if path:
        attrs.append(f"file={path}")
    if line:
        attrs.append(f"line={line}")
    suffix = f" {','.join(attrs)}" if attrs else ""
    print(f"::{level}{suffix}::{encode(message)}")


def relative(path: str, root: str) -> str:
    path = path.lstrip("/")
    if root and path.startswith(root):
        return os.path.relpath(path, root)
    marker = "app/src/"
    idx = path.find(marker)
    return path[idx:] if idx >= 0 else path


def collect_block(lines: list[str], start: int, limit: int = 60) -> str:
    """Junta as linhas de um bloco de diagnóstico até um marcador de fim."""
    out: list[str] = []
    for raw in lines[start + 1 : start + 1 + limit]:
        if BLOCK_END.match(raw.strip()):
            break
        stripped = raw.strip()
        if stripped:
            out.append(re.sub(r"\s+", " ", stripped))
        elif out:
            out.append("")
    return "\n".join(out).strip()


def main() -> int:
    logs = [a for a in sys.argv[1:] if os.path.exists(a)]
    if not logs:
        emit("warning", "nenhum log de build encontrado para anotar")
        return 0

    root = os.getcwd()
    issues: dict[str, list[str]] = {}
    blocks: list[str] = []
    tasks: list[str] = []
    tests: list[str] = []

    for log in logs:
        with open(log, encoding="utf-8", errors="replace") as handle:
            lines = [TS.sub("", raw).rstrip() for raw in handle.read().splitlines()]

        for index, line in enumerate(lines):
            match = KOTLIN_ERROR.match(line) or KOTLIN_ERROR_NOPOS.match(line)
            if match:
                path = relative(match.group("path"), root)
                pos = (
                    f"{match.groupdict().get('line')}:{match.groupdict().get('col') or 0}"
                    if match.groupdict().get("line")
                    else "?"
                )
                issues.setdefault(path, []).append(f"{pos} {(match.group('msg') or '').strip()}")
                continue

            match = JAVAC_ERROR.match(line)
            if match:
                issues.setdefault(match.group("path"), []).append(
                    f"{match.group('line')} {match.group('msg').strip()}"
                )
                continue

            match = TEST_FAILED.match(line)
            if match:
                tests.append(f"{match.group('cls')} > {match.group('test')}")
                continue

            match = TASK_FAILED.match(line)
            if match:
                tasks.append(match.group("task"))
                continue

            if AAR_HEADER.search(line) or WENT_WRONG.match(line) or COMPILE_NOTE.match(line):
                block = collect_block(lines, index)
                blocks.append(f"{line.strip()}\n{block}" if block else line.strip())

    # Blocos primeiro: é onde está a causa quando nem se chega ao código.
    for block in blocks[:MAX_BLOCKS]:
        emit("error", block)

    for path, found in list(issues.items())[:MAX_FILES]:
        head = f"{len(found)} erro(s) de compilação em {path}"
        body = "\n".join(f"  {item}" for item in found[:MAX_PER_FILE])
        more = "" if len(found) <= MAX_PER_FILE else f"\n  … e mais {len(found) - MAX_PER_FILE}"
        first_line = found[0].split()[0].split(":")[0]
        emit("error", f"{head}\n{body}{more}", path, int(first_line) if first_line.isdigit() else None)

    for task in tasks[:8]:
        emit("error", f"task do Gradle falhou: {task}")
    for test in tests[:12]:
        emit("error", f"teste falhou: {test}")

    if not blocks and not issues and not tasks and not tests:
        emit("warning", "nenhum erro reconhecido no log (falha pode ser de infra/rede)")

    # Resumo no passo: quantos arquivos/erros ficaram de fora do limite de anotações.
    skipped = max(0, len(issues) - MAX_FILES)
    if skipped:
        print(f"::notice::{skipped} arquivo(s) com erros não anotados (limite do Actions); veja o log bruto.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
