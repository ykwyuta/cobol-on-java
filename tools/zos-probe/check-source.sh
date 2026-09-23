#!/bin/sh
# z/OS へ送る原文の検査。
# 原文は IBM-1047 で表せる字 (ASCII の印字文字) だけで書き、固定形式の
# 72 桁 (PL/I と HLASM は 72 桁、JCL は 71 桁 + 継続桁) を超えないこと。
# 日本語を書くと、DBCS の SO/SI で桁がずれ、実機で原文が壊れる。
# 使い方: sh tools/zos-probe/check-source.sh
cd "$(dirname "$0")" || exit 2
status=0
for f in cobol/*.cbl copy/*.cpy pli/*.pli hlasm/*.asm jcl/*.jcl; do
  [ -f "$f" ] || continue
  if LC_ALL=C grep -n '[^ -~]' "$f" >/dev/null; then
    echo "$f: non-ASCII character" >&2
    LC_ALL=C grep -n '[^ -~]' "$f" | head -3 >&2
    status=1
  fi
  awk -v f="$f" 'length($0) > 72 { print f ":" FNR ": " length($0) " columns"; bad = 1 }
                 END { exit bad }' "$f" >&2 || status=1
done
exit $status
