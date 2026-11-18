#!/system/bin/sh
. "${MODDIR}/mockloc-lib.sh"

# 按标记精确摘掉自己的规则（v4+v6 双栈）：旧版只刷 v4 会把 v6 的 DROP 留下来，
# 卸载后目标 App 仍被断网。不再 blanket flush OUTPUT——那会连带清掉用户其他防火墙规则。
netcut_teardown

# 停掉可能在跑的开机循环，并清掉一次性 inbox：否则卸载后循环继续推 fix，
# 重装时旧 inbox 又会把配置复活（v1.3.0 走查 P1-13）。
if [ -f /data/adb/mockloc/loop.pid ]; then
  kill "$(cat /data/adb/mockloc/loop.pid)" 2>/dev/null
  rm -f /data/adb/mockloc/loop.pid
fi
rm -f "$INBOX"
