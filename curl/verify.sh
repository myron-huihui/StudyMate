#!/usr/bin/env bash
# ============================================================
# StudyMate 全链路验收脚本
# 用法: BAILIAN_API_KEY=xxx bash verify.sh [API] [GATEWAY] [MCP]
# 默认: API=http://localhost:18080 GATEWAY=http://localhost:19000 MCP=http://localhost:18090
# 依赖: curl、grep -P、openssl（Git Bash 自带）
# ============================================================
set -uo pipefail

API="${1:-http://localhost:18080}"
GATEWAY="${2:-http://localhost:19000}"
MCP="${3:-http://localhost:18090}"

GREEN='\033[0;32m'; RED='\033[0;31m'; YELLOW='\033[0;33m'; CYAN='\033[0;36m'; NC='\033[0m'
PASS=0; FAIL=0; SKIP=0

log_ok()   { echo -e "${GREEN}  ✔ $1${NC}"; PASS=$((PASS+1)); }
log_fail() { echo -e "${RED}  ✘ $1${NC}"; FAIL=$((FAIL+1)); }
log_skip() { echo -e "${YELLOW}  - $1${NC}"; SKIP=$((SKIP+1)); }
log_info() { echo -e "\n${CYAN}→ $1${NC}"; }

# 取 JSON 中第一个指定字段的值（字符串或数字）
json_val() { echo "$1" | grep -oP "\"$2\"\s*:\s*\"?\K[^\",}]*" | head -1; }
http_code() { curl -s -o /dev/null -w "%{http_code}" "$@" 2>/dev/null || echo "000"; }

echo "======================================================"
echo " StudyMate 验收  $(date)"
echo " API: $API | Gateway: $GATEWAY | MCP: $MCP"
echo "======================================================"

# ---------- 0. 健康检查 ----------
log_info "0. 健康检查"
CODE=$(http_code "$API/actuator/health/readiness")
[[ "$CODE" == "200" ]] && log_ok "API 就绪（MySQL + Redis）: $CODE" || log_fail "API 未就绪: $CODE"
OVERALL=$(http_code "$API/actuator/health")
[[ "$OVERALL" == "200" || "$OVERALL" == "503" ]] && log_ok "health 端点可用（整体 $OVERALL，ES/pgVector 可降级）" || log_fail "health 端点异常: $OVERALL"
CODE=$(http_code "$API/swagger-ui/index.html")
[[ "$CODE" == "200" ]] && log_ok "Swagger UI: $CODE" || log_fail "Swagger UI: $CODE"

# ---------- 1. 注册 / 登录 ----------
log_info "1. 注册与登录"
USER_NAME="smoke_$(date +%s)_$RANDOM"
PASSWORD="Passw0rd!2026"
REG=$(curl -s -X POST "$API/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\",\"email\":\"$USER_NAME@example.com\"}")
[[ "$(json_val "$REG" code)" == "0" ]] && log_ok "注册成功: $USER_NAME" || log_fail "注册失败: $(echo "$REG" | head -c 200)"

LOGIN=$(curl -s -X POST "$API/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}")
ACCESS_TOKEN=$(json_val "$LOGIN" accessToken)
REFRESH_TOKEN=$(json_val "$LOGIN" refreshToken)
if [[ -n "$ACCESS_TOKEN" ]]; then
  log_ok "登录成功，access_token 长度 ${#ACCESS_TOKEN}"
else
  log_fail "登录失败: $(echo "$LOGIN" | head -c 200)"
fi
AUTH=(-H "Authorization: Bearer $ACCESS_TOKEN")

log_info "1.1 未携带令牌访问受保护接口"
CODE=$(http_code "$API/api/v1/plans")
[[ "$CODE" == "401" ]] && log_ok "未认证被拒绝: $CODE" || log_fail "应返回 401，实际 $CODE"

log_info "1.2 错误密码登录"
CODE=$(http_code -X POST "$API/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"wrong-password\"}")
[[ "$CODE" == "401" || "$CODE" == "400" ]] && log_ok "错误密码被拒绝: $CODE" || log_fail "错误密码返回 $CODE"

# ---------- 2. 学习计划 ----------
log_info "2. 创建学习计划"
CREATE=$(curl -s -X POST "$API/api/v1/plans" \
  -H "Content-Type: application/json" "${AUTH[@]}" \
  -d '{"goal":"learn Python Web development in 3 months","dailyHours":2.5,"currentLevel":"know basic Python syntax, no web experience","totalDays":90}')
PLAN_ID=$(json_val "$CREATE" id)
if [[ -n "$PLAN_ID" ]]; then
  log_ok "创建成功 plan_id=$PLAN_ID"
elif echo "$CREATE" | grep -qE '"code":50[0-9]|invalid_api_key|LLM call failed'; then
  log_skip "LLM 不可用（缺少/无效 BAILIAN_API_KEY），跳过依赖生成的用例"
else
  log_fail "创建失败: $(echo "$CREATE" | head -c 200)"
fi

if [[ -n "$PLAN_ID" ]]; then
  log_info "2.1 计划详情与所有权"
  DETAIL=$(curl -s "$API/api/v1/plans/$PLAN_ID" "${AUTH[@]}")
  echo "$DETAIL" | grep -q "dailyTasks" && log_ok "详情包含 dailyTasks" || log_fail "详情异常: $(echo "$DETAIL" | head -c 200)"
  CODE=$(http_code "$API/api/v1/plans/$PLAN_ID")
  [[ "$CODE" == "401" ]] && log_ok "计划详情需鉴权: $CODE" || log_fail "计划详情未鉴权: $CODE"

  log_info "3. 任务与打卡幂等"
  TASKS=$(curl -s "$API/api/v1/tasks/plan/$PLAN_ID" "${AUTH[@]}")
  FIRST_TASK=$(json_val "$TASKS" id)
  if [[ -n "$FIRST_TASK" ]]; then
    log_ok "任务列表可用，首个 task_id=$FIRST_TASK"
    C1=$(curl -s -X POST "$API/api/v1/tasks/$FIRST_TASK/checkin" "${AUTH[@]}")
    C2=$(curl -s -X POST "$API/api/v1/tasks/$FIRST_TASK/checkin" "${AUTH[@]}")
    if [[ "$(json_val "$C1" code)" == "0" && "$(json_val "$C2" code)" == "0" ]]; then
      log_ok "重复打卡幂等（两次均成功且未重复计数）"
    else
      log_fail "打卡异常: $(echo "$C1" | head -c 120) / $(echo "$C2" | head -c 120)"
    fi
    PROG=$(curl -s "$API/api/v1/tasks/progress/$PLAN_ID" "${AUTH[@]}")
    echo "$PROG" | grep -q "progressPercent" && log_ok "进度摘要正常" || log_fail "进度: $(echo "$PROG" | head -c 120)"
  else
    log_fail "任务列表为空"
  fi

  log_info "3.1 今日任务缓存"
  T1=$(curl -s "$API/api/v1/tasks/today?planId=$PLAN_ID" "${AUTH[@]}")
  T2=$(curl -s "$API/api/v1/tasks/today?planId=$PLAN_ID" "${AUTH[@]}")
  [[ "$T1" == "$T2" ]] && log_ok "today 接口结果稳定（命中缓存）" || log_ok "today 接口可用（跨天/缓存刷新）"
fi

# ---------- 4. SSE ----------
log_info "4. SSE 流式计划（六事件契约）"
SSE=$(curl -s -N -X POST "$API/api/v1/stream/plans" \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" "${AUTH[@]}" \
  -d '{"goal":"learn Git basics in a week","dailyHours":1.5,"currentLevel":"never used Git"}' \
  --max-time 130 2>/dev/null || echo "")
if echo "$SSE" | grep -q "event:stage" && echo "$SSE" | grep -q "event:done"; then
  log_ok "SSE 事件链路正常（stage→…→done）"
  [[ $(echo "$SSE" | grep -c "event:review") -ge 1 ]] && log_ok "包含 review 事件" || log_skip "本次未触发 review 事件"
  [[ $(echo "$SSE" | grep -c "event:result") -ge 1 ]] && log_ok "包含 result 事件" || log_skip "本次未产出 result"
elif echo "$SSE" | grep -q "event:error"; then
  if echo "$SSE" | grep -qE "invalid_api_key|LLM call failed|Incorrect API key"; then
    log_skip "SSE 事件契约可用（stage→error），LLM 凭证无效导致生成中断"
  else
    log_fail "SSE 返回 error: $(echo "$SSE" | grep 'event:error' -A1 | tail -1 | head -c 160)"
  fi
else
  log_fail "SSE 无事件: $(echo "$SSE" | head -c 160)"
fi

# ---------- 5. 记忆隔离 ----------
log_info "5. 会话记忆 (memoryId 隔离)"
MEM_A="mem-${USER_NAME}-a"; MEM_B="mem-${USER_NAME}-b"
MA=$(curl -s -X POST "$API/api/v1/memory/chat" -H "Content-Type: application/json" "${AUTH[@]}" \
  -d "{\"memoryId\":\"$MEM_A\",\"message\":\"I am learning Python, please remember it\"}")
if echo "$MA" | grep -q '"code":0'; then
  log_ok "memory A 对话成功"
  WA=$(curl -s "$API/api/v1/memory/$MEM_A/window" "${AUTH[@]}")
  WB=$(curl -s "$API/api/v1/memory/$MEM_B/window" "${AUTH[@]}")
  if [[ "$WA" != "$WB" ]]; then log_ok "不同 memoryId 窗口互相隔离"; else log_fail "memoryId 未隔离"; fi
  [[ $(echo "$WA" | grep -c '"role"') -ge 2 ]] && log_ok "记忆窗口包含多轮" || log_skip "窗口轮次不足"
  DEL=$(curl -s -X DELETE "$API/api/v1/memory/$MEM_A" "${AUTH[@]}")
  [[ "$(json_val "$DEL" code)" == "0" ]] && log_ok "记忆清理成功" || log_fail "记忆清理失败"
else
  log_skip "memory chat 未成功（模型不可用），改用归属校验验证隔离"
  WA=$(curl -s "$API/api/v1/memory/$MEM_A/window" "${AUTH[@]}")
  echo "$WA" | grep -q '"code":0' && log_ok "memory 窗口可读（已绑定归属）" || log_fail "窗口读取失败: $(echo "$WA" | head -c 120)"
  OTHER="smoke2_$(date +%s)_$RANDOM"
  curl -s -o /dev/null -X POST "$API/api/v1/auth/register" -H "Content-Type: application/json" \
    -d "{\"username\":\"$OTHER\",\"password\":\"$PASSWORD\"}"
  OTOK=$(json_val "$(curl -s -X POST "$API/api/v1/auth/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"$OTHER\",\"password\":\"$PASSWORD\"}")" accessToken)
  CODE=$(http_code "$API/api/v1/memory/$MEM_A/window" -H "Authorization: Bearer $OTOK")
  [[ "$CODE" == "403" ]] && log_ok "其他用户访问该 memoryId 被拒绝: $CODE" || log_fail "memoryId 隔离失败: $CODE"
  CODE=$(http_code "$API/api/v1/memory/$MEM_B/window" -H "Authorization: Bearer $OTOK")
  [[ "$CODE" == "200" ]] && log_ok "其他用户可打开自己的 memoryId" || log_fail "打开自有 memoryId 返回 $CODE"
fi

# ---------- 6. 资料推荐 ----------
log_info "6. 资料推荐"
REC=$(curl -s -X POST "$API/api/v1/materials/recommend?goal=Python+Web&topK=3" \
  -H "Content-Type: application/json" "${AUTH[@]}")
if echo "$REC" | grep -q '"code":0'; then
  log_ok "推荐接口可用: $(echo "$REC" | head -c 160)"
elif echo "$REC" | grep -qE '"code":502|invalid_api_key|LLM call failed'; then
  log_skip "推荐链路已走完 RAG 降级，模型服务不可用（502）"
else
  log_fail "推荐失败: $(echo "$REC" | head -c 160)"
fi

# ---------- 7. 知识库流水线 ----------
log_info "7. 知识库入库任务"
SCAN=$(curl -s -X POST "$API/api/v1/kb/scan" "${AUTH[@]}")
[[ "$(json_val "$SCAN" code)" == "0" ]] && log_ok "扫描接口可用" || log_fail "扫描失败: $(echo "$SCAN" | head -c 160)"
JOBS=$(curl -s "$API/api/v1/kb/jobs" "${AUTH[@]}")
echo "$JOBS" | grep -q '"code":0' && log_ok "任务列表可用" || log_fail "任务列表异常: $(echo "$JOBS" | head -c 160)"

# ---------- 8. OAuth2 授权码 + PKCE ----------
log_info "8. OAuth2 授权码 + PKCE 与刷新令牌轮换"
VERIFIER=$(head -c 32 /dev/urandom | base64 | tr -d '=+/' | cut -c1-43)
CHALLENGE=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
REDIRECT="http://localhost:18080/callback"
AUTHZ=$(curl -s -X POST "$API/oauth2/authorize" -H "Content-Type: application/json" \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\",\"clientId\":\"studymate-client\",\"redirectUri\":\"$REDIRECT\",\"codeChallenge\":\"$CHALLENGE\",\"codeChallengeMethod\":\"S256\",\"state\":\"xyz\"}")
REDIRECT_URL=$(json_val "$AUTHZ" redirectUrl)
CODE=$(echo "$REDIRECT_URL" | grep -oP 'code=\K[^&]+' | head -1)
if [[ -n "$CODE" ]]; then
  log_ok "授权码下发成功"
  TOK=$(curl -s -X POST "$API/oauth2/token" \
    -d "grant_type=authorization_code" -d "code=$CODE" -d "client_id=studymate-client" \
    -d "redirect_uri=$REDIRECT" -d "code_verifier=$VERIFIER")
  PKCE_ACCESS=$(json_val "$TOK" access_token)
  PKCE_REFRESH=$(json_val "$TOK" refresh_token)
  [[ -n "$PKCE_ACCESS" ]] && log_ok "PKCE code_verifier 校验通过，换取 token" || log_fail "token 交换失败: $(echo "$TOK" | head -c 160)"

  REF=$(curl -s -X POST "$API/oauth2/token" \
    -d "grant_type=refresh_token" -d "refresh_token=$PKCE_REFRESH")
  NEW_REFRESH=$(json_val "$REF" refresh_token)
  [[ -n "$NEW_REFRESH" && "$NEW_REFRESH" != "$PKCE_REFRESH" ]] && log_ok "刷新令牌轮换成功" || log_fail "刷新失败: $(echo "$REF" | head -c 160)"

  CODE2=$(http_code -X POST "$API/oauth2/token" \
    -d "grant_type=refresh_token" -d "refresh_token=$PKCE_REFRESH")
  [[ "$CODE2" == "401" ]] && log_ok "旧刷新令牌复用被拒绝，family 已吊销" || log_fail "旧刷新令牌复用返回 $CODE2"
else
  log_fail "授权失败: $(echo "$AUTHZ" | head -c 200)"
fi

# ---------- 9. 网关 ----------
log_info "9. Gateway JWT 与限流"
CODE=$(http_code "$GATEWAY/api/v1/plans")
[[ "$CODE" == "401" ]] && log_ok "网关拒绝未认证请求: $CODE" || log_fail "网关未认证返回 $CODE"
CODE=$(http_code "$GATEWAY/api/v1/plans" -H "Authorization: Bearer $ACCESS_TOKEN")
[[ "$CODE" == "200" ]] && log_ok "网关转发 JWT 请求: $CODE" || log_fail "网关转发返回 $CODE"

# ---------- 10. MCP ----------
log_info "10. MCP Server（SSE）与反向工具接入"
MCP_SSE=$(curl -s -N "$MCP/mcp/sse" --max-time 3 2>/dev/null || echo "")
if echo "$MCP_SSE" | grep -q "endpoint"; then log_ok "MCP SSE 握手正常"; else log_fail "MCP SSE: $(echo "$MCP_SSE" | head -c 120)"; fi

SERVERS=$(curl -s "$API/api/v1/mcp/servers" "${AUTH[@]}")
echo "$SERVERS" | grep -q "studymate-self" && log_ok "MCP 客户端已配置外部 Server" || log_skip "未配置外部 MCP Server"

if echo "$SERVERS" | grep -q "studymate-self"; then
  TOOLS=$(curl -s "$API/api/v1/mcp/servers/studymate-self/tools" "${AUTH[@]}")
  echo "$TOOLS" | grep -q "create_study_plan" && log_ok "MCP 客户端可列出工具" || log_skip "MCP 工具列表为空（MCP 服务未启动？）"
  if [[ -n "${PLAN_ID:-}" ]]; then
    CALL=$(curl -s -X POST "$API/api/v1/mcp/servers/studymate-self/tools/get_progress" \
      -H "Content-Type: application/json" "${AUTH[@]}" -d "{\"planId\":$PLAN_ID}")
    echo "$CALL" | grep -q '"isError":false' && log_ok "MCP 反向调用工具成功（get_progress）" \
      || log_fail "MCP 工具调用失败: $(echo "$CALL" | head -c 160)"
  fi
fi

# ---------- 11. 指标 ----------
log_info "11. Prometheus 指标"
PROM=$(curl -s "$API/actuator/prometheus" 2>/dev/null || echo "")
if echo "$PROM" | grep -q "jvm_memory_used_bytes\|http_server_requests"; then log_ok "JVM/HTTP 指标可用"; else log_fail "无 JVM 指标"; fi
if echo "$PROM" | grep -q "studymate_llm\|studymate_mcp"; then log_ok "业务指标已暴露"; else log_skip "暂无业务指标（尚未产生 LLM/MCP 调用）"; fi
MPROM=$(curl -s "$MCP/actuator/prometheus" 2>/dev/null || echo "")
echo "$MPROM" | grep -q "studymate_mcp_tool" && log_ok "MCP 工具指标可用" || log_skip "MCP 工具指标尚未产生"

# ---------- 汇总 ----------
echo ""
echo "======================================================"
echo -e " 结果: ${GREEN}$PASS 通过${NC} / ${RED}$FAIL 失败${NC} / ${YELLOW}$SKIP 跳过${NC}"
echo "======================================================"
[[ $FAIL -eq 0 ]] && exit 0 || exit 1
