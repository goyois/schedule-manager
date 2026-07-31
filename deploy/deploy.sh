#!/usr/bin/env bash
# 앱 서버 EC2에서 실행된다 (GitHub Actions가 scp로 올린 뒤 ssh로 호출 - .github/workflows/deploy.yml의
# "deploy" 잡 참고).
# 이 EC2에는 미리 다음이 준비돼 있어야 한다 (DEPLOYMENT.md "앱 서버 EC2 준비" 절):
#   - docker 설치 + ec2-user가 docker 그룹에 속함
#   - ECR pull 권한을 가진 IAM 역할이 이 EC2에 붙어 있음
#   - /home/ec2-user/schedule-manager.env - DB_HOST/DB_PASSWORD/ANTHROPIC_API_KEY 등 실제 운영 비밀값
#     (deploy/app.env.example을 참고해 SSM Parameter Store 등에서 채워 만들어둔 파일, 이 저장소엔 없음)
set -euo pipefail

ECR_REPOSITORY_URI="$1"
IMAGE_TAG="$2"
AWS_REGION="$3"

CONTAINER_NAME="schedule-manager"
ENV_FILE="/home/ec2-user/schedule-manager.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "ERROR: $ENV_FILE 이 없습니다. deploy/app.env.example을 참고해 먼저 만들어두세요." >&2
  exit 1
fi

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$ECR_REPOSITORY_URI"
docker pull "$ECR_REPOSITORY_URI:$IMAGE_TAG"

# 롤백용으로 지금 떠 있는 이미지의 태그를 남겨둔다 - 새 컨테이너가 헬스체크를 통과하지 못하면 이걸로 되돌린다
PREVIOUS_IMAGE=$(docker inspect --format='{{.Config.Image}}' "$CONTAINER_NAME" 2>/dev/null || echo "")

docker stop "$CONTAINER_NAME" 2>/dev/null || true
docker rm "$CONTAINER_NAME" 2>/dev/null || true

docker run -d \
  --name "$CONTAINER_NAME" \
  --env-file "$ENV_FILE" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -p 8080:8080 \
  --restart unless-stopped \
  "$ECR_REPOSITORY_URI:$IMAGE_TAG"

echo "헬스체크 대기 중..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health | grep -q '"status":"UP"'; then
    echo "배포 성공: $ECR_REPOSITORY_URI:$IMAGE_TAG"
    docker image prune -f
    exit 0
  fi
  sleep 2
done

echo "ERROR: 새 컨테이너가 30번 시도 후에도 healthy 상태가 아닙니다." >&2
docker logs --tail 100 "$CONTAINER_NAME" >&2 || true

if [ -n "$PREVIOUS_IMAGE" ]; then
  echo "이전 이미지($PREVIOUS_IMAGE)로 롤백합니다."
  docker stop "$CONTAINER_NAME" 2>/dev/null || true
  docker rm "$CONTAINER_NAME" 2>/dev/null || true
  docker run -d \
    --name "$CONTAINER_NAME" \
    --env-file "$ENV_FILE" \
    -e SPRING_PROFILES_ACTIVE=prod \
    -p 8080:8080 \
    --restart unless-stopped \
    "$PREVIOUS_IMAGE"
fi

exit 1
