#!/bin/sh
# 운영 DB 를 매일 BACKUP_HOUR 시(UTC)에 pg_dump 로 백업하고 오래된 백업을 지운다 (docs/DEPLOYMENT.md 7절 DB 백업·복구).
# 인자 once 를 주면 지금 한 번만 백업하고 끝난다. alpine 의 busybox sh 로 돌아야 해서 bash·GNU date 문법을 쓰지 않는다.
set -u

BACKUP_DIR=${BACKUP_DIR:-/backups}
BACKUP_HOUR=${BACKUP_HOUR:-19}
KEEP_DAYS=${KEEP_DAYS:-7}
# 손 백업과 예약 백업이 같은 분에 겹쳐도 서로의 임시 파일을 건드리지 않게 프로세스 번호를 붙인다
ERR_FILE=/tmp/db-backup.$$.err
child=
tmp=

# docker stop 의 멈춤 신호(이 이미지는 SIGINT)에 기다리지 않고 끝내고, 쓰던 임시 파일이 백업처럼 남지 않게 지운다
on_term() {
  [ -n "$child" ] && kill "$child" 2>/dev/null
  [ -n "$tmp" ] && rm -f "$tmp"
  rm -f "$ERR_FILE"
  exit 0
}
trap on_term TERM INT

# 앞에서 돌리면 끝날 때까지 trap 이 미뤄지므로 뒤에서 돌리고 wait 로 기다린다
run() {
  "$@" &
  child=$!
  wait "$child"
  status=$?
  child=
  return "$status"
}

seconds_until_next() {
  now=$1
  target=$((now - now % 86400 + BACKUP_HOUR * 3600))
  [ "$target" -le "$now" ] && target=$((target + 86400))
  echo $((target - now))
}

# 매일 같은 시각에 돌아 몇 초 차이로 지우는 날과 안 지우는 날이 갈리지 않게 한 시간 여유를 둔다
prune() {
  find "$BACKUP_DIR" -maxdepth 1 -type f -name 'gitory-*.dump' -mmin +$((KEEP_DAYS * 1440 - 60)) |
    while read -r old; do
      rm -f "$old" && echo "오래된 DB 백업 삭제: ${old##*/}"
    done
}

backup() {
  # 같은 분에 또 돌면(손 백업 직후 등) 앞 백업을 덮어쓰지 않게 다음 분까지 기다린다
  while name="gitory-$(date -u +%F-%H%M).dump"; [ -e "$BACKUP_DIR/$name" ]; do
    run sleep 1
  done
  tmp="$BACKUP_DIR/.$name.$$.part"
  # 표 잠금을 끝없이 기다리면 성공·실패 줄이 모두 안 남아 경보도 없으니 10분에서 끊는다
  if run pg_dump -Fc --lock-wait-timeout=10min -f "$tmp" 2>"$ERR_FILE" && mv "$tmp" "$BACKUP_DIR/$name" 2>>"$ERR_FILE"; then
    tmp=
    rm -f "$ERR_FILE"
    echo "DB 백업 성공: $name ($(du -k "$BACKUP_DIR/$name" | cut -f1)KB)"
    # 실패가 이어지는 동안 지우면 남은 백업이 모두 사라질 수 있어 성공했을 때만 지운다
    prune
    return 0
  fi
  rm -f "$tmp"
  tmp=
  echo "DB 백업 실패: $(tr '\n' ' ' <"$ERR_FILE" | sed 's/ *$//')"
  rm -f "$ERR_FILE"
  return 1
}

if [ "${1:-}" = once ]; then
  backup
  exit
fi

# 손으로 돌린 once 가 컨테이너와 함께 끊기면 임시 파일이 남는다
# 컨테이너가 막 떴으니 돌던 백업이 없어 지워도 된다
rm -f "$BACKUP_DIR"/.gitory-*.part

while :; do
  now=$(date -u +%s)
  wait_s=$(seconds_until_next "$now")
  echo "다음 DB 백업: $(date -u -d "@$((now + wait_s))" '+%F %H:%M') UTC"
  run sleep "$wait_s"
  backup
done
