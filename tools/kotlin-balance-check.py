#!/usr/bin/env python3
"""Checa balanceamento de (), {} e [] em arquivos .kt sem compilar nada.

Existe porque um `/*` perdido dentro de um KDoc já custou quatro ciclos de CI neste projeto:
o Kotlin aninha comentário, o arquivo inteiro fica "vazio" e o erro aparece em outros arquivos.
Este script é a checagem de 2 segundos que antecede o push.

Três casos que um contador ingênuo erra e que aqui são tratados, porque aparecem no código real:
  1. `//` e `/* */` (o bloco aninha, como no Kotlin);
  2. strings com `${expr}` — a expressão pode conter aspas e chaves, e é dentro dela que a
     contagem de chaves continua;
  3. literais de caractere ('"').
"""
import sys
import glob


def blank(part: str) -> str:
    """Substitui um trecho removido por espaços, preservando as quebras de linha dele."""
    return ''.join('\n' if ch == '\n' else ' ' for ch in part)


def code_only(src: str) -> str:
    out = []
    i, n = 0, len(src)
    tmpl = []  # profundidade de chaves dentro de cada ${ } aberto
    while i < n:
        two = src[i:i + 2]
        if two == '//':
            j = src.find('\n', i)
            out.append(blank(src[i:j]))
            i = j
            continue
        if two == '/*':
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith('/*', j):
                    depth, j = depth + 1, j + 2
                elif src.startswith('*/', j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            out.append(blank(src[i:j]))
            i = j
            continue
        if src.startswith('"""', i):
            j = i + 3
            end = n
            while j < n:
                if src[j] == '"':
                    run = 0
                    while j + run < n and src[j + run] == '"':
                        run += 1
                    if run >= 3:
                        # os últimos três são o fechamento; os anteriores são conteúdo
                        end = j + run
                        break
                    j += run
                    continue
                j += 1
            out.append(blank(src[i:end]))
            i = end
            continue
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src.startswith('${', j):
                    # expressão de template: as chaves contam, as aspas dentro são strings reais
                    k, d = j + 2, 1
                    while k < n and d:
                        if src[k] == '{':
                            d += 1
                        elif src[k] == '}':
                            d -= 1
                        elif src[k] == '"':
                            k += 1
                            while k < n and src[k] != '"':
                                k += 1 if src[k] != '\\' else 2
                        k += 1
                    # Nada é emitido para o interior de ${ }: as chaves e as aspas de dentro são
                    # do template, e o corpo do arquivo não fica nem mais aberto nem mais fechado.
                    j = k
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(blank(src[i:min(j + 1, n)]))
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == "'":
                    break
                j += 1
            out.append(blank(src[i:min(j + 1, n)]))
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def check(path: str):
    code = code_only(open(path, encoding='utf-8').read())
    stack, pairs, opens = [], {'}': '{', ')': '(', ']': '['}, set('{([')
    line = 1
    for ch in code:
        if ch == '\n':
            line += 1
        elif ch in opens:
            stack.append((ch, line))
        elif ch in pairs:
            if not stack or stack[-1][0] != pairs[ch]:
                return f"linha {line}: fecha '{ch}' sem par"
            stack.pop()
    if stack:
        ch, ln = stack[-1]
        return f"'{ch}' aberto na linha {ln} nunca fechou"
    return None


def main() -> int:
    roots = sys.argv[1:] or ['app/src']
    files = []
    import os
    for root in roots:
        if os.path.isfile(root):
            files.append(root)
        else:
            files += glob.glob(root + '/**/*.kt', recursive=True)
    bad = []
    for f in sorted(set(files)):
        problem = check(f)
        if problem:
            bad.append(f'{f}: {problem}')
    for b in bad:
        print('ERRO ' + b)
    print(f'{len(set(files))} arquivos Kotlin verificados, {len(bad)} com desbalanceamento')
    return 1 if bad else 0


if __name__ == '__main__':
    raise SystemExit(main())
