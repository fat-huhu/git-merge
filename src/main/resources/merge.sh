#!/bin/bash
set -e

# ANSI 颜色定义
RED='\033[31m'
GREEN='\033[32m'
BLUE='\033[34m'
RESET='\033[0m'

# 安全 Git 函数：将 stdout 中的红色转为绿色
safe_git() {
  local output
  if output=$(git "$@" 2>&1); then
    echo "$output" | sed "s/\x1b\[31m/${GREEN}/g"
  else
    echo "$output" >&2
    return 1
  fi
}

# --- 原有逻辑不变 ---

if [ $# -lt 1 ]; then
  echo -e "${RED}❌ 用法: $0 <target-branch> [更多分支...] [--push] [--squash] [--no-ff]${RESET}"
  exit 1
fi

push_flag=false
squash_flag=false
merge_opt="--no-ff"

for arg in "$@"; do
  case "$arg" in
    --push) push_flag=true ;;
    --squash) squash_flag=true ;;
    --no-ff) merge_opt="--no-ff" ;;
  esac
done

branches=()
for arg in "$@"; do
  if [[ "$arg" != "--push" && "$arg" != "--squash" && "$arg" != "--no-ff" ]]; then
    branches+=("$arg")
  fi
done

current=$(git rev-parse --abbrev-ref HEAD)

# 检查未提交更改
if ! git diff-index --quiet HEAD --; then
  echo -e "${RED}❌ 当前分支有未提交的更改，请先提交或暂存。${RESET}"
  exit 1
fi

echo -e "${GREEN}🌿 当前分支: ${BLUE}$current${RESET}"

for target in "${branches[@]}"; do
  echo -e "\n${GREEN}=== 🚀 合并到分支: ${BLUE}$target${GREEN} ===${RESET}"

  echo -e "${GREEN}📥 获取远程分支 '$target'...${RESET}"
  safe_git fetch origin "$target":"$target"

  echo -e "${GREEN}🔀 切换到分支 '$target'...${RESET}"
  safe_git checkout "$target"

  if [ "$squash_flag" = true ]; then
    echo -e "${GREEN}🪄 Squash 合并 '$current'...${RESET}"
    safe_git merge --squash "$current"
    echo -e "${GREEN}📝 提交合并...${RESET}"
    safe_git commit -m "Squash merge branch '$current' into '$target'"
  else
    echo -e "${GREEN}🔀 普通合并 '$current'...${RESET}"
    safe_git merge "$current" $merge_opt -m "Merge branch '$current' into '$target'"
  fi

  if [ "$push_flag" = true ]; then
    echo -e "${GREEN}📤 推送 '$target'...${RESET}"
    safe_git push origin "$target"
  fi

  echo -e "${GREEN}✅ 分支 '$target' 合并完成！${RESET}"
done

echo -e "${GREEN}🔙 切换回 '$current'...${RESET}"
safe_git checkout "$current"

echo -e "\n${GREEN}🎉 所有合并完成！${RESET}"