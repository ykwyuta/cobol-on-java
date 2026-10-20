#!/bin/sh
# 業務アプリの資産 (CardDemo、GenApp) の翻訳の数を測る (改善計画 段階 0-2)。
#
#   sh tools/verify/fetch-corpus.sh build/corpus      # corpus.tsv の資産を固定のコミットで取る
#   sh tools/verify/measure-apps.sh build/corpus
#
# 数える範囲と写し句の置き場は、同じ資産を測っている Zeus (tools/apps-inventory.sh) に揃える。
# 範囲が違うと、2 つの処理系の数を並べても比べられないからである。
#   CardDemo: app/cbl と app/app-*/cbl の .cbl / .CBL。写し句は cpy、cpy-bms、bms、dcl
#   GenApp:   base/src の .cbl。写し句と BMS も base/src
#
# 数を門にしない (CLAUDE.md §7)。結果は標準出力に出すだけで、0 で終わる。
set -eu

corpus=${1:-build/corpus}
here=$(cd "$(dirname "$0")/../.." && pwd)
work=$here/target/measure-apps
rm -rf "$work"
mkdir -p "$work/carddemo" "$work/genapp"

cd "$here"
if [ ! -s "$work/../measure-apps.classpath" ]; then
  mvn -o -q -pl cobol-verify dependency:build-classpath -Dmdep.outputFile="$work/../measure-apps.classpath"
fi
cp="cobol-verify/target/classes:cobol-compiler/target/classes:$(cat "$work/../measure-apps.classpath")"

measure() { # 名前 原文の置き場 写し句の置き場...
  name=$1; sources=$2; shift 2
  includes=
  for d in "$@"; do
    [ -d "$d" ] && includes="$includes -I $d"
  done
  echo "### $name"
  # shellcheck disable=SC2086
  java -cp "$cp" dev.cobolonjava.verify.Main corpus "$sources" $includes 2>&1 | sed -n '4,40p'
}

cd_app=$corpus/carddemo/app
if [ -d "$cd_app" ]; then
  for f in "$cd_app"/cbl/*.cbl "$cd_app"/cbl/*.CBL "$cd_app"/app-*/cbl/*.cbl "$cd_app"/app-*/cbl/*.CBL; do
    [ -f "$f" ] && cp "$f" "$work/carddemo/"
  done
  # shellcheck disable=SC2046
  measure CardDemo "$work/carddemo" "$cd_app/cpy" "$cd_app/cpy-bms" "$cd_app/bms" \
      $(for d in "$cd_app"/app-*; do echo "$d/cpy $d/cpy-bms $d/bms $d/dcl"; done)
fi
g=$corpus/genapp/base/src
if [ -d "$g" ]; then
  cp "$g"/*.cbl "$work/genapp/"
  measure GenApp "$work/genapp" "$g"
fi
exit 0
