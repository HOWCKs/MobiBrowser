#!/usr/bin/env python3
"""Import duplicado ou de nome ambíguo em arquivos .kt.

Existe por um erro meu específico: inseri `import java.io.File` num arquivo que já o tinha e o
Kotlin respondeu `Conflicting import: imported name 'File' is ambiguous` — 1 ciclo de CI para um
duplicado que um grep resolve. A checagem cobre os dois formatos de ambiguidade:

  - a mesma linha repetida;
  - nomes simples iguais vindos de pacotes diferentes (`java.io.File` + `kotlin.io.File`).
"""
import glob
import os
import sys


def check(path):
    """Retorna os conflitos: nome simples repetido no arquivo, vindo de um ou dois pacotes."""
    problems = []
    first = {}
    for num, raw in enumerate(open(path, encoding='utf-8'), 1):
        line = raw.strip()
        if not line.startswith('import ') or line.endswith('.*'):
            continue
        fqn = line[len('import '):].split(' as ')[0].strip()
        simple = fqn.rpartition('.')[2]
        if simple in first:
            prev_num, prev_fqn = first[simple]
            kind = 'repetido' if prev_fqn == fqn else 'ambíguo'
            problems.append(
                f'linha {num}: nome "{simple}" {kind} — já importado na linha {prev_num} ({prev_fqn})'
            )
        else:
            first[simple] = (num, fqn)
    return problems


def main():
    roots = sys.argv[1:] or ['app/src']
    files = []
    for root in roots:
        files += [root] if os.path.isfile(root) else glob.glob(root + '/**/*.kt', recursive=True)
    bad = []
    for f in sorted(set(files)):
        for problem in check(f):
            bad.append(f'{f}: {problem}')
    for b in bad:
        print('ERRO ' + b)
    print(f'{len(set(files))} arquivos, {len(bad)} importos conflitantes')
    return 1 if bad else 0


if __name__ == '__main__':
    raise SystemExit(main())
