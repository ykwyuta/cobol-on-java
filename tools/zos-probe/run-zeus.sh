#!/bin/sh
# 同じ probe を Zeus (同じ権利者の Rust の処理系) で流し、run-local.sh と同じ置き場の形で残す。
#
#   ZEUS=/path/to/Zeus/target/release sh tools/zos-probe/run-zeus.sh
#
# 結果は tools/zos-probe/results/zeus/ に置く。突き合わせは
#   java tools/zos-probe/ProbeTool.java prb tools/zos-probe/results/zeus tools/zos-probe/results/local
#
# Zeus は実機ではない。2 つの処理系が食い違う行は「少なくとも片方が実機と違う」ことだけを言う。
# 揃った行も実機と同じとは限らない (同じ文書を同じように読み違えることがある)。それでも、
# 実機を待たずに調べる行を絞れる。
#
# 揃えること (CLAUDE.md §6: 道具が処理系の失敗を作ってはならない):
# - コードページ。Zeus の既定は CCSID 37、この処理系と probe の原文は IBM-1047 なので
#   --ccsid 1047 を渡す。揃えないと、定数の [ ] などの byte が処理系の差に化ける
# - ステップの表は run-local.sh と同じ local-steps.txt を読む
# - 「プログラム@オプション」は Zeus でもそのオプションで翻訳し直す
#
# JCL の probe は流さない。Zeus のジョブ実行はデータセットの置き場 (目録) の作り方が違い、
# 同じ JCL を同じ置き場の形で流す手順をまだ決めていない。
#
# 数を門にしない (CLAUDE.md §7)。失敗しても最後まで流し、0 で終わる。

here=$(cd "$(dirname "$0")" && pwd)
zeus=${ZEUS:?set ZEUS to the directory with zcobc, zpli and zas}
results=$here/results/zeus
work=$here/target/zeus
ccsid=1047

rm -rf "$results" "$work"
mkdir -p "$results" "$work/base"

zeus_version=$("$zeus/zcobc" --version 2>&1 | head -1)
echo "$zeus_version" > "$results/version.txt"

# ---------------------------------------------------------------- 翻訳
compile_one() { # 種類 原文 置き場 [オプション]
  kind=$1; src=$2; out=$3; option=${4:-}
  name=$(basename "$src"); name=${name%.*}
  case $kind in
    cobol)
      if [ -n "$option" ]; then
        set -- "$zeus/zcobc" compile --ccsid $ccsid -I "$here/copy" --options "$option" -o "$out/$name.zo" "$src"
      else
        set -- "$zeus/zcobc" compile --ccsid $ccsid -I "$here/copy" -o "$out/$name.zo" "$src"
      fi ;;
    pli)   set -- "$zeus/zpli" compile --ccsid $ccsid -o "$out/$name.zo" "$src" ;;
    hlasm) set -- "$zeus/zas" compile -I "$here/copy" -o "$out/$name.zo" "$src" ;;
  esac
  text=$("$@" 2>&1); rc=$?
  echo "== $(basename "$src")${option:+ --options $option} rc=$rc"
  [ -n "$text" ] && printf '%s\n' "$text" | sed 's/^/   /'
}
{
  for f in "$here"/cobol/*.cbl; do compile_one cobol "$f" "$work/base"; done
  for f in "$here"/pli/*.pli; do compile_one pli "$f" "$work/base"; done
  for f in "$here"/hlasm/*.asm; do compile_one hlasm "$f" "$work/base"; done
} > "$results/compile.txt"
echo "compile: $(grep -c ' rc=0' "$results/compile.txt") ok," \
     "$(grep -c ' rc=[1-9]' "$results/compile.txt") refused (see compile.txt)" >&2

# ------------------------------------------------------ 言語の probe
grep -v '^#' "$here/local-steps.txt" | while read -r job step program card; do
  [ -n "$job" ] || continue
  dir=$results/$job/$step
  mkdir -p "$dir"
  modules=$work/base
  case $program in
    *@*)
      option=${program#*@}; program=${program%@*}
      modules=$work/variant-$(printf '%s' "$option" | tr -c 'A-Za-z0-9' '_')
      if [ ! -f "$modules/$program.zo" ]; then
        mkdir -p "$modules"
        # 呼ばれる側 (HLASM など) は元の置き場のものを使う
        cp "$work/base"/*.zo "$modules"/ 2>/dev/null
        rm -f "$modules/$program.zo"
        compile_one cobol "$here/cobol/$program.cbl" "$modules" "$option" >> "$results/compile.txt"
      fi
      ;;
  esac
  if [ ! -f "$modules/$program.zo" ]; then
    echo "ZEUS: $program was not compiled (see compile.txt)" > "$dir/SYSOUT.txt"
    continue
  fi
  case $job in
    PRBRUNP) runner="$zeus/zpli run --ccsid $ccsid" ;;
    *) runner="$zeus/zcobc run --ccsid $ccsid" ;;
  esac
  (
    cd "$dir" || exit 2
    # shellcheck disable=SC2086
    printf '%b\n' "$card" | timeout 60 $runner "$modules/$program.zo" > SYSOUT.txt 2>&1
    echo "RC=$?" >> SYSOUT.txt
  )
done

echo "results: $results" >&2
exit 0
