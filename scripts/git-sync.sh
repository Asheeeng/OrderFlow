#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "$*" >&2; exit 1; }
mode="${1:---push}"
[[ "$mode" == --push || "$mode" == --check ]] || fail '用法：scripts/git-sync.sh [--check|--push]'
[[ "$(git symbolic-ref --short -q HEAD)" == main ]] || fail '请切换到 main 分支。'
[[ -z "$(git status --porcelain)" ]] || fail '工作区存在未提交或未跟踪文件。'
head="$(git rev-parse --verify HEAD)"
remote_names=(origin gitcode)
for name in "${remote_names[@]}"; do
  git remote get-url "$name" >/dev/null || fail "缺少远程 $name。"
done
[[ "$(git remote get-url --push origin)" != "$(git remote get-url --push gitcode)" ]] || fail '两个推送地址相同。'

local_tags="$(git for-each-ref --format='%(objectname)%09%(refname)' refs/tags | LC_ALL=C sort)"
expected_main="$head"
declare -a remote_heads
for name in "${remote_names[@]}"; do
  url="$(git remote get-url --push "$name")"
  refs="$(git ls-remote --refs "$url" refs/heads/main 'refs/tags/*')" || fail "$name 引用检查失败。"
  branch="$(awk '$2=="refs/heads/main" {print $1}' <<<"$refs")"
  remote_heads+=("$branch")
  printf '%s main %s\n' "$name" "${branch:-（不存在）}"
  while read -r object ref; do
    [[ "$ref" == refs/tags/* ]] || continue
    [[ "$(git rev-parse --verify -q "$ref" || true)" == "$object" ]] || fail "$name 标签 $ref 与本地不一致。"
  done <<< "$refs"
  if [[ -n "$branch" ]]; then
    git fetch --no-tags "$url" refs/heads/main || fail "无法读取 $name/main 历史。"
    git merge-base --is-ancestor "$branch" "$head" || fail "$name/main 包含当前本地 main 尚未纳入的历史。"
  fi
done
if [[ "${remote_heads[0]}" != "${remote_heads[1]}" ]]; then
  echo '两端 main 当前不同，本地 main 已包含所有存在的远程 main 历史。' >&2
fi
if [[ "$mode" == --push ]]; then
  refspecs=(refs/heads/main:refs/heads/main)
  [[ -z "$local_tags" ]] || refspecs+=('refs/tags/*:refs/tags/*')
  for name in "${remote_names[@]}"; do
    git push --dry-run --atomic "$name" "${refspecs[@]}" || fail "$name 推送预检失败，未执行实际推送。"
  done
  for name in "${remote_names[@]}"; do
    git push --atomic "$name" "${refspecs[@]}" || fail "$name 实际推送失败。请检查两端引用，可在问题解决后重试。"
  done
fi
for name in "${remote_names[@]}"; do
  url="$(git remote get-url --push "$name")"
  refs="$(git ls-remote --refs "$url" refs/heads/main 'refs/tags/*')" || fail "$name 同步核验失败。"
  target="$(git ls-remote --symref "$url" HEAD | awk '$1=="ref:" && $3=="HEAD" {print $2}')"
  [[ "$target" == refs/heads/main ]] || fail "$name 远程默认分支不是 main。"
  [[ "$(awk '$2=="refs/heads/main" {print $1}' <<<"$refs")" == "$expected_main" ]] || fail "$name/main 尚未与本地同步。"
  [[ "$(awk '$2 ~ /^refs\/tags\// {print $0}' <<<"$refs" | LC_ALL=C sort)" == "$local_tags" ]] || fail "$name 的版本标签尚未与本地同步。"
done
printf '两端 main 已同步至 %s，标签一致。\n' "$expected_main"
