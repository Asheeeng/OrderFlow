#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
temp="$(mktemp -d)"
trap 'rm -rf "$temp"' EXIT
for side in github gitcode; do
  git init -q --bare -b main "$temp/$side"
done
git init -q -b main "$temp/project"
cd "$temp/project"
git config user.name 'OrderFlow Git Test'
git config user.email 'orderflow-test@example.invalid'
mkdir scripts
cp "$root/scripts/git-sync.sh" scripts/
git add scripts
git commit -qm 'sync fixture'
git tag -a v1.0.0 -m 'test tag'
git remote add origin "$temp/github"
git remote add gitcode "$temp/gitcode"
bash scripts/git-sync.sh --push
bash scripts/git-sync.sh --check
git checkout -qb divergence
git commit --allow-empty -qm 'remote-only commit'
git push -q gitcode divergence:main
git checkout -q main
if bash scripts/git-sync.sh --push; then
  echo '同步脚本不应覆盖远程新增提交' >&2
  exit 1
fi
[[ "$(git --git-dir="$temp/gitcode" rev-parse main)" == "$(git rev-parse divergence)" ]]
echo 'PASS: 双 bare 仓库 main 与带注释标签同步，远端新提交时拒绝覆盖。'
