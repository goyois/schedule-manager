// AWS + Docker 배포 파이프라인. 전체 절차/사전 준비물은 DEPLOYMENT.md 참고.
//
// 전제:
// - Jenkins가 떠 있는 EC2에 ECR 푸시 권한(AmazonEC2ContainerRegistryPowerUser 등)을 가진 IAM 역할이
//   붙어 있다 (DEPLOYMENT.md "Jenkins EC2 IAM 역할" 절) - 그래서 여기엔 AWS Access Key를 전혀 다루지 않는다.
// - Jenkins Credentials에 앱 서버 SSH 개인키가 'app-server-ssh-key'(SSH Username with private key,
//   username=ec2-user)로 등록돼 있다.
// - ECR_REPOSITORY_URI / APP_SERVER_HOST는 아래 environment 블록의 값을 실제 값으로 바꿔서 쓴다.
pipeline {
    agent any

    environment {
        AWS_REGION = 'ap-northeast-2'
        // ECR 리포지토리 URI - DEPLOYMENT.md의 "ECR 리포지토리 만들기" 절에서 만든 값으로 바꾼다
        ECR_REPOSITORY_URI = '123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/schedule-manager'
        // 앱을 실제로 띄우는 EC2의 퍼블릭 IP(또는 Elastic IP) - DEPLOYMENT.md "앱 서버 EC2" 절 참고
        APP_SERVER_HOST = '203.0.113.10'
        IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    options {
        // 이전 빌드가 남겨둔 워크스페이스 상태(특히 CI 컨테이너)가 다음 빌드에 영향을 주지 않게 매번 깨끗하게 시작한다
        skipDefaultCheckout(false)
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Test') {
            steps {
                sh '''
                    set -e
                    docker compose -f docker-compose.ci.yml up -d --wait

                    docker exec schedule-manager-ci-postgres psql -U ci -d api -c "CREATE EXTENSION IF NOT EXISTS vector;"
                    docker exec schedule-manager-ci-postgres psql -U ci -d api -c "CREATE EXTENSION IF NOT EXISTS \\"uuid-ossp\\";"

                    # spring.profiles.default가 local이라 이 파일이 있어야 컨텍스트가 뜬다. 값은 전부 CI 전용
                    # 임시 컨테이너를 가리키거나(진짜 비밀이 아님) 형식만 맞으면 되는 더미값이다(AI/구글 키는
                    # 실제로 호출되지 않는 한 부팅 시 형식 검증만 통과하면 된다) - 실제 운영 비밀과는 무관하다.
                    cat > src/main/resources/application-local.yml <<EOF
spring:
  datasource:
    driver-class-name: org.postgresql.Driver
    url: jdbc:postgresql://localhost:5433/api
    username: ci
    password: ci
  jpa:
    hibernate:
      ddl-auto: update
  data:
    redis:
      host: localhost
      port: 6380
  ai:
    anthropic:
      api-key: dummy-ci-key
      chat:
        options:
          model: claude-opus-4-8
    openai:
      api-key: dummy-ci-key
  jwt:
    secret: ci-only-dummy-secret-key-must-be-at-least-256-bits-long
    expiration: 1800000
    refresh-expiration: 1209600000

google:
  oauth:
    client-id: dummy-ci-client-id
EOF

                    ./gradlew test -PciBuild --no-daemon
                '''
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                    sh 'docker compose -f docker-compose.ci.yml down -v || true'
                    sh 'rm -f src/main/resources/application-local.yml'
                }
            }
        }

        stage('Build & Push Docker Image') {
            steps {
                sh """
                    docker build -t ${ECR_REPOSITORY_URI}:${IMAGE_TAG} -t ${ECR_REPOSITORY_URI}:latest .
                    aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REPOSITORY_URI}
                    docker push ${ECR_REPOSITORY_URI}:${IMAGE_TAG}
                    docker push ${ECR_REPOSITORY_URI}:latest
                """
            }
        }

        stage('Deploy') {
            steps {
                sshagent(credentials: ['app-server-ssh-key']) {
                    sh """
                        scp -o StrictHostKeyChecking=no deploy/deploy.sh ec2-user@${APP_SERVER_HOST}:/home/ec2-user/deploy.sh
                        ssh -o StrictHostKeyChecking=no ec2-user@${APP_SERVER_HOST} "chmod +x /home/ec2-user/deploy.sh && /home/ec2-user/deploy.sh ${ECR_REPOSITORY_URI} ${IMAGE_TAG} ${AWS_REGION}"
                    """
                }
            }
        }
    }

    post {
        always {
            sh 'docker image prune -f || true'
        }
    }
}
