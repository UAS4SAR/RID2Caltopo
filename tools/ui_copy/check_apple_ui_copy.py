#!/usr/bin/env python3
"""Reject other-platform names in Apple app string literals (excluding diagnostic logs)."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
# Tokenize comments and literals together so commented-out UI is not treated as live UI.
TOKENS = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|(?:#+)?"""[\s\S]*?"""(?:#+)?|(?:#+)?"(?:\\.|[^"\\])*"(?:#+)?|[A-Za-z_][A-Za-z_0-9]*|[^\s]', re.MULTILINE)
DENIED = re.compile(r'\b(?:Android|Google\s+Play|APK)\b', re.I)

def scan(text):
    tokens = [(m.group(), m.start()) for m in TOKENS.finditer(text) if not m.group().startswith(('//', '/*'))]
    hits = []
    calls = []
    for i, (token, pos) in enumerate(tokens):
        if token == '(':
            callee = tokens[i - 1][0] if i else ''
            if i >= 3 and tokens[i - 2][0] == '.':
                callee = tokens[i - 3][0] + '.' + callee
            calls.append(callee)
        elif token == ')':
            if calls: calls.pop()
        elif token.lstrip('#').startswith('"') and DENIED.search(token):
            # Diagnostic log strings are not operator UI. Identifiers/comments are never scanned.
            if any(call.startswith('AppleLog.') for call in calls):
                continue
            hits.append((text.count('\n', 0, pos) + 1, token))
    return hits

def main():
    hits = []
    for path in sorted((ROOT / 'apple' / 'App').rglob('*.swift')):
        hits.extend((path, line, text) for line, text in scan(path.read_text()))
    for path, line, text in hits:
        print(f'{path.relative_to(ROOT)}:{line}: platform name in UI: {text}')
    print(f'Apple UI copy check: {len(hits)} violation(s).')
    return bool(hits)

if __name__ == '__main__':
    sys.exit(main())
