#!/usr/bin/env python3
"""Guarda contra comentário de bloco não fechado em arquivos Kotlin.

Por que isto existe: o Kotlin **aninha** `/* */` (diferente do Java). Um `/*` escrito dentro
de um KDoc — por exemplo ao citar um match pattern ``*://*.site/*`` — abre um comentário
aninhado que é fechado pelo `*/` do próprio KDoc, deixando o comentário externo aberto: o
restante do arquivo vira comentário. O resultado é hilário e doloroso de ler — o compilador
não reclama do arquivo (ele não vê declaração nenhuma ali), reclama de *todos os outros*
arquivos que usam aquela classe ("Unresolved reference 'BrowserDb'").

Uso: tools/kotlin-comment-check.py [<raiz>...]      (padrão: app/src)
Saida: linha por problema, código de saída 1 se achar algum.
"""

from __future__ import annotations

import os
import sys

STATE_CODE, STATE_LINE, STATE_BLOCK, STATE_STR, STATE_RAW = 0, 1, 2, 3, 4


def check(path: str) -> list[str]:
    src = open(path, encoding="utf-8", errors="replace").read()
    problems: list[str] = []
    state = STATE_CODE
    depth = 0            # níveis de /* */ abertos (>1 significa aninhamento)
    open_line = 0        # linha onde o bloco mais externo abriu
    line = 1
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            if state == STATE_LINE:
                state = STATE_CODE
            i += 1
            continue

        if state == STATE_BLOCK:
            nxt = src[i : i + 2]
            if nxt == "/*":
                depth += 1
                if depth == 2:
                    problems.append(f"{path}:{line}: /* aninhado abre aqui (bloco iniciado na linha {open_line})")
                i += 2
                continue
            if nxt == "*/":
                depth -= 1
                if depth == 0:
                    state = STATE_CODE
                i += 2
                continue
            i += 1
            continue

        if state == STATE_LINE:
            i += 1
            continue

        if state == STATE_STR:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                state = STATE_CODE
            i += 1
            continue

        if state == STATE_RAW:
            if src.startswith('"""', i):
                state = STATE_CODE
                i += 3
                continue
            i += 1
            continue

        # STATE_CODE
        nxt = src[i : i + 2]
        if nxt == "//":
            state = STATE_LINE
            i += 2
        elif nxt == "/*":
            state = STATE_BLOCK
            depth = 1
            open_line = line
            i += 2
        elif src.startswith('"""', i):
            state = STATE_RAW
            i += 3
        elif c == '"':
            state = STATE_STR
            i += 1
        elif c == "'":
            # char literal: 'a', '\n', '\'' — pula o conteúdo
            j = i + 1
            while j < n and src[j] != "\n":
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            i = j + 1
        else:
            i += 1

    if state == STATE_BLOCK:
        problems.append(f"{path}: bloco de comentário aberto na linha {open_line} chega ao fim do arquivo")
    elif state in (STATE_STR, STATE_RAW):
        problems.append(f"{path}:{line}: string não terminada")
    return problems


def main() -> int:
    roots = sys.argv[1:] or ["app/src"]
    files: list[str] = []
    for root in roots:
        if os.path.isfile(root):
            files.append(root)
            continue
        for dirpath, _, names in os.walk(root):
            files.extend(os.path.join(dirpath, f) for f in names if f.endswith((".kt", ".kts")))
    total = 0
    for path in sorted(files):
        for problem in check(path):
            print(problem)
            total += 1
    if total:
        print(f"::error file=tools/kotlin-comment-check.py::"
              f"{total} comentário(s) desbalanceado(s) — ver stdout do passo", flush=True)
        return 1
    print(f"ok: {len(files)} arquivos Kotlin, nenhum comentário de bloco pendurado")
    return 0


if __name__ == "__main__":
    sys.exit(main())
