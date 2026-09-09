#!/bin/sh
# OSS の COBOL 資産を取ってきて、構文網羅率を測れる形に並べる (要件 NFR-042)。
#
# コーパス本体はリポジトリに同梱しない。取ってくる先は corpus.tsv に、
# URL とコミットハッシュと SPDX 識別子で固定してある。
#
#   sh tools/verify/fetch-corpus.sh [置き場]
#   COBOL_CORPUS=<置き場> mvn -B test
#
# 取ったものの素性は <置き場>/PROVENANCE.tsv に残す。どの数字がどの資産の
# どのコミットに対するものか、後から言えるようにするためである。

set -eu

here=${1:-build/corpus}
list=$(dirname "$0")/corpus.tsv

mkdir -p "$here"
provenance="$here/PROVENANCE.tsv"
: > "$provenance"
printf 'name\turl\tcommit\tspdx\tfetched\n' >> "$provenance"

count=0
while IFS="$(printf '\t')" read -r name url commit spdx || [ -n "$name" ]; do
  case "$name" in
    ''|'#'*) continue ;;
  esac
  if [ -z "$url" ] || [ -z "$commit" ] || [ -z "$spdx" ]; then
    echo "URL とコミットと SPDX がそろっていない: $name" >&2
    exit 1
  fi
  target="$here/$name"
  if [ ! -d "$target/.git" ]; then
    echo "取得中: $name"
    git clone --quiet --no-checkout "$url" "$target"
  fi
  # 枝ではなくコミットで固定する。同じ数字が別のものを指すようになっては困る
  (cd "$target" && git fetch --quiet origin "$commit" 2>/dev/null || true)
  (cd "$target" && git checkout --quiet "$commit")
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$url" "$commit" "$spdx" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$provenance"
  count=$((count + 1))
done < "$list"

echo
if [ "$count" -eq 0 ]; then
  echo "corpus.tsv に資産が 1 つも書かれていない。"
  echo "行を足すのはライセンスを確かめる作業である。確かめていないものを書いてはならない。"
  exit 0
fi
echo "できた: $count 件を $here へ"
echo "  素性: $provenance"
echo "  COBOL_CORPUS=$here mvn -B -pl cobol-verify -am test"
