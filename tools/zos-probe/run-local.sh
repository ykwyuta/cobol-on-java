#!/bin/sh
# この処理系の側で probe を流し、実機と同じ置き場の形で観測を残す。
#
#   sh tools/zos-probe/run-local.sh
#
# 結果は tools/zos-probe/results/local/ に置く。
#   compile.txt              原文ごとの翻訳の結果 (断った診断もそのまま)
#   <ジョブ>/<ステップ>/SYSOUT.txt  そのステップの標準出力と標準エラー、最後に RC
#   <ジョブ>/<ステップ>/<DD 名>     プログラムが書いたファイル (PRTF など)
#   jcl/<ジョブ>.txt          JCL の probe をジョブ実行 (cobolj) で流したときのジョブログ
#
# 言語の probe は、ジョブ実行を通さずプログラムを直に起動する。ステップごとの
# 出力を実機と同じ <ジョブ>/<ステップ>/SYSOUT.txt の形で残すためである。SYSIN の
# 中身は標準入力として渡す (直に起動したプログラムの ACCEPT は標準入力を読む)。
# 直に起動したプログラムは、ASSIGN の名前のファイルを作業ディレクトリに作る。
#
# 数を門にしない (CLAUDE.md §7)。翻訳や実行が失敗しても最後まで流し、0 で終わる。
# 1 本が返ってこなくても残りを測れるよう、実行には 60 秒の限りを置く (§6)。

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
target=$here/target
results=$here/results/local
classes=$target/classes

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) sep=';' ;;
  *) sep=':' ;;
esac

# Windows の java に POSIX の道 (/d/...) を渡すと別の場所を指す。java に渡す道は
# すべて jpath を通す
jpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf "%s" "$1"; fi
}

mkdir -p "$target"
if [ ! -s "$target/classpath.txt" ]; then
  echo "building the classpath (mvn -o, cobol-verify and its dependencies)" >&2
  (cd "$root" && mvn -o -q -pl cobol-verify dependency:build-classpath \
      -Dmdep.outputFile="$(jpath "$target/classpath.txt")") || exit 2
fi
cp=$(cat "$target/classpath.txt")

rm -rf "$classes" "$results" "$target"/variant-*
mkdir -p "$classes" "$results/jcl"

# ---------------------------------------------------------------- 翻訳
compile_one() { # 種類 原文
  kind=$1; src=$2
  case $kind in
    cobol) set -- dev.cobolonjava.compiler.Main -d "$(jpath "$classes")" -I "$(jpath "$here/copy")" "$(jpath "$src")" ;;
    pli)   set -- dev.cobolonjava.pli.Main -d "$(jpath "$classes")" "$(jpath "$src")" ;;
    hlasm) set -- dev.cobolonjava.hlasm.Main -d "$(jpath "$classes")" "$(jpath "$src")" ;;
  esac
  out=$(java -cp "$cp" "$@" 2>&1); rc=$?
  echo "== $(basename "$src") rc=$rc"
  [ -n "$out" ] && printf '%s\n' "$out" | sed 's/^/   /'
}
{
  for f in "$here"/cobol/*.cbl; do compile_one cobol "$f"; done
  for f in "$here"/pli/*.pli; do compile_one pli "$f"; done
  for f in "$here"/hlasm/*.asm; do compile_one hlasm "$f"; done
} > "$results/compile.txt"
echo "compile: $(grep -c ' rc=0' "$results/compile.txt") ok," \
     "$(grep -c ' rc=[1-9]' "$results/compile.txt") refused (see compile.txt)" >&2

# ------------------------------------------------------ 言語の probe
grep -v '^#' "$here/local-steps.txt" | while read -r job step program card; do
  [ -n "$job" ] || continue
  dir=$results/$job/$step
  mkdir -p "$dir"
  # 「プログラム@オプション」は、そのオプションで翻訳し直した別の置き場から流す
  run_classes=$classes
  case $program in
    *@*)
      option=${program#*@}; program=${program%@*}
      run_classes=$target/variant-$option
      if [ ! -d "$run_classes" ]; then
        mkdir -p "$run_classes"
        echo "== $program.cbl -q $option" >> "$results/compile.txt"
        java -cp "$cp" dev.cobolonjava.compiler.Main -d "$(jpath "$run_classes")" \
            -I "$(jpath "$here/copy")" -q "$option" \
            "$(jpath "$here/cobol/$program.cbl")" >> "$results/compile.txt" 2>&1
      fi
      ;;
  esac
  if [ ! -f "$run_classes/cobol/generated/$program.class" ]; then
    echo "LOCAL: $program was not compiled (see compile.txt)" > "$dir/SYSOUT.txt"
    continue
  fi
  (
    cd "$dir" || exit 2
    printf '%b\n' "$card" | timeout 60 java \
        -cp "$(jpath "$run_classes")$sep$(jpath "$classes")$sep$cp" \
        "cobol.generated.$program" > SYSOUT.txt 2>&1
    echo "RC=$?" >> SYSOUT.txt
  )
done

# --------------------------------------------------------- JCL の probe
# 実機の JCL の JOBLIB / STEPLIB の行だけを注記の行 (//*) に置き換えて流す。ほかは書き換えない。
# 行を削らないのは、診断の行番号を原文と揃えるためである。以前は削っていたので、ジョブ実行の
# 報告する行が 1 つずれ、「IF の誤りを ENDIF の行で報告する」という無い不具合を報告した。
#
# データセットの置き場 (と目録) は<b>すべてのジョブで 1 つ</b>にする。実機の目録が 1 つだから
# である。以前はジョブごとに別の置き場で流していたので、JCLDISP2 から JCLDISP が作った
# DISP.A が見えず、「DISP を書かない DD が既存のデータセットを消す」という、処理系には無い
# 不具合を報告してしまった (CLAUDE.md §6: 検査の道具が処理系の失敗を作ってはならない)。
# 順番も実機の手順書 (runbook §3.4) と揃える。前のジョブの結果を読むジョブがあるからである。
jobs=$target/jobs
rm -rf "$jobs"; mkdir -p "$jobs"
for name in JCLCOND JCLCONR JCLDISP JCLDISP2 JCLGDG0 JCLGDG JCLGDG2 JCLGDGD \
            JCLSPACE JCLPDS JCLUTIL JCLTSO JCLCP; do
  jcl=$here/jcl/$name.jcl
  [ -f "$jcl" ] || continue
  sed -e 's|^//JOBLIB .*|//* JOBLIB (host only)|' \
      -e 's|^//STEPLIB .*|//* STEPLIB (host only)|' "$jcl" > "$jobs/$name.jcl"
  (
    cd "$jobs" || exit 2
    timeout 120 java -cp "$(jpath "$classes")$sep$cp" dev.cobolonjava.job.Main \
        -d "$(jpath "$classes")" -w work -b datasets "$name.jcl" < /dev/null 2>&1
    echo "JOB RC=$?"
  ) > "$results/jcl/$name.txt"
done

echo "results: $results" >&2
exit 0
