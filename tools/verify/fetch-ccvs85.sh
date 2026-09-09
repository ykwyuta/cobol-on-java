#!/bin/sh
# NIST CCVS85 の配布物を取ってくる (要件 NFR-040)。
#
# 配布物そのものはリポジトリに同梱しない。解いて 28 MB あり、素性は NIST
# (パブリックドメイン) である。要件 NFR-042 が OSS コーパスについて言う
# 「URL とコミットハッシュで固定した取得スクリプトを置く」を、こちらにも当てはめた。
# Apache-2.0 の成果物へ素性の違うものを取り込まないためである。Hercules を
# 外から呼ぶのと同じ考え方である。
#
#   sh tools/verify/fetch-ccvs85.sh [置き場]
#   CCVS85=<置き場>/newcob.val mvn -B test
#
# SPDX-License-Identifier: LicenseRef-Public-Domain-US-Gov
# 出所: NIST COBOL-85 Validation Suite (CCVS85) VERSION 4.0, 01 OCT 1992
# 配布: GnuCOBOL プロジェクトの SourceForge (NIST の元の配布は終了している)

set -eu

here=${1:-build/verify}
url=https://sourceforge.net/projects/gnucobol/files/nist/newcob.val.tar.gz/download
sha256=e4513f26a9b38911f7bf882fe3d3339a80b45cabc2caf85eb3055b0f5ce87ee0

mkdir -p "$here"
archive="$here/newcob.val.tar.gz"

if [ ! -f "$archive" ]; then
  echo "取得中: $url"
  curl -sSL -o "$archive" "$url"
fi

# 中身が入れ替わっていないことを確かめる。検査の道具が測るものが変わっては困る
echo "$sha256  $archive" | sha256sum -c - || {
  echo "取ってきたものが違う。URL の先が入れ替わった可能性がある" >&2
  exit 1
}

tar xzf "$archive" -C "$here"
echo
echo "できた: $here/newcob.val"
echo "  CCVS85=$here/newcob.val mvn -B -pl cobol-verify -am test"
echo "  java -cp ... dev.cobolonjava.verify.Main ccvs85 $here/newcob.val"
