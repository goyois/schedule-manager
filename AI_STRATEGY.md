# AI 일정 추천 기능 도입 전략

> TASKS.md Task #7(AI 일정 추천 기능 구현)의 실행 계획. Task #7은 API 스펙만 짧게 정의돼 있고, 이 문서는
> "어떻게 만들 것인가"에 집중한다. 아직 구현 시작 전 — 실제 작업은 이 문서 기준으로 순서대로 진행한다.

## 배경

- `spring-ai-anthropic-spring-boot-starter`(1.0.0-M1) 의존성과 API 키는 이미 `application-local.yml`에 있지만, `ChatClient`/AI 컨트롤러는 전혀 연결돼 있지 않음(미구현 상태).
- 개인 일정 서비스에서 자연스러운 확장 후보들(알림/리마인더, 반복 일정, AI 추천) 중, 이미 의존성이 깔려 있어 시작 장벽이 가장 낮다는 이유로 AI 추천을 먼저 선택.

## 사전 확인된 사실 (구현 전 조사 결과)

실제 `spring-ai-anthropic-1.0.0-M1.jar` / `spring-ai-spring-boot-autoconfigure-1.0.0-M1.jar` 클래스를 직접 열어 확인한 내용 — 버전이 milestone이라 공식 문서와 실제 API가 어긋날 수 있어 코드 작성 전 이 사실을 기준으로 삼는다.

- **설정 프로퍼티 경로 버그 발견**: 현재 `application-local.yml`은 `spring.ai.anthropic.model`로 돼 있는데, 실제 바인딩 대상은 `AnthropicChatProperties.CONFIG_PREFIX = "spring.ai.anthropic.chat"` 하위의 `options.model`이다. 즉 올바른 경로는 **`spring.ai.anthropic.chat.options.model`**. 지금 설정은 조용히 무시되고 있을 가능성이 높음 — 구현 0단계에서 반드시 수정.
- `api-key`는 `AnthropicConnectionProperties.CONFIG_PREFIX = "spring.ai.anthropic"` 소속이라 현재 위치(`spring.ai.anthropic.api-key`)가 맞음.
- 모델 이름 `claude-3-5-sonnet-latest`는 예전 네이밍 — 실제 연동 시점에 유효한 모델 ID로 재확인 필요.
- `ChatModel` 빈(`AnthropicChatModel`)은 스타터가 자동 구성하므로, 애플리케이션 코드는 `ChatClient.builder(chatModel).build()`만 추가하면 됨.
- `ChatClient` 플루언트 API 확인됨: `.prompt().system(String).user(String).call().content()` (String 반환), 스트리밍은 `.prompt()...stream().content()` (`Flux<String>` 반환).
- 구조화된 출력도 지원됨: `.call().entity(SomeDto.class)` — 자유 텍스트보다 프론트 렌더링이 쉬워지지만 M1이라 안정성 검증이 덜 됨. **1차 구현은 텍스트로 시작하고, 구조화 출력은 stretch goal로 미룬다.**

## 구현 단계

### 0단계 — 설정 버그 수정
`application-local.yml`의 `spring.ai.anthropic.model` → `spring.ai.anthropic.chat.options.model`로 수정. 모델 ID도 최신값으로 갱신.

### 1단계 — `ChatClient` 빈 구성
```java
@Configuration
public class AiConfig {
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
```

### 2단계 — `domain/ai` 패키지 신설 (기존 도메인별 패키지 컨벤션 그대로)
```
domain/ai/
  controller/AiController.java
  service/AiService.java
  dto/ScheduleSuggestRequestDto.java
  dto/ScheduleSuggestResponseDto.java
  dto/WeeklySummaryResponseDto.java
```

### 3단계 — 컨텍스트 구성 전략
- 기존 `ScheduleService.getSchedules(requesterEmail, userId, categoryId)`를 재사용해 일정 목록을 가져온다.
- 토큰 비용 때문에 전체 기간을 다 넣지 않고 범위를 제한한다(예: 최근 2주 + 향후 2주).
- 카테고리 이름, 상태(`PENDING`/`IN_PROGRESS`/`COMPLETED`/`CANCELLED`)까지 포함해 컨텍스트를 구성한다.

### 4단계 — `AiService` 구현 (Task #7 스펙: 추천 + 요약 두 기능)
```java
@Service
@RequiredArgsConstructor
public class AiService {
    private final ChatClient chatClient;
    private final ScheduleService scheduleService;

    public String suggestSchedule(String requesterEmail, String userPrompt) {
        String context = buildScheduleContext(requesterEmail);
        return chatClient.prompt()
                .system("당신은 일정 관리 도우미입니다. 사용자의 기존 일정을 참고해 추천하세요.")
                .user(userPrompt + "\n\n[기존 일정]\n" + context)
                .call()
                .content();
    }
}
```
- 요약(`/api/ai/summary`)은 스트리밍이 자연스러움 — `.stream().content()`로 `Flux<String>`을 받아 컨트롤러에서 SSE로 흘려보낸다.

### 5단계 — 컨트롤러
```java
@PostMapping("/suggest")
public ResponseEntity<ApiResponse<ScheduleSuggestResponseDto>> suggest(
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody ScheduleSuggestRequestDto request) { ... }
```
스트리밍 엔드포인트(`/summary`)는 `ApiResponse` 래퍼를 쓸 수 없어 `Flux<ServerSentEvent<String>>` 또는 `SseEmitter`로 별도 처리 — 기존 컨벤션(모든 응답을 `ApiResponse`로 감싸는 것)의 유일한 예외 지점이 된다는 점을 감안해야 함.

### 6단계 — 에러 처리 / 비용 관리
- Claude API 타임아웃·레이트리밋 실패 시 `ErrorCode.AI_REQUEST_FAILED`(신규) → `BusinessException`으로 감싸 기존 `GlobalExceptionHandler` 그대로 재사용.
- 매 요청마다 실제 과금이 발생하는 API 호출이 나가므로, 유저당 호출 빈도 제한(예: 분당 N회)을 처음부터 고려한다.

### 7단계 — 테스트
- `ChatClient`를 Mockito로 mock해 `AiService`가 프롬프트를 올바르게 구성하는지만 검증(실제 API 호출 없이).
- 실제 Claude 호출이 나가는 통합 테스트는 비용/속도 문제로 보통 생략하거나 수동 검증으로 대체한다.

### 8단계 — 프론트엔드 연동
`dashboard.js`에 "AI 추천" 버튼 추가 → `API.post('/api/ai/suggest', {...})` → 결과를 모달/토스트로 렌더링.

## 아직 결정 안 된 것 (진행하며 확정할 사항)

- [ ] 추천 응답을 텍스트로만 보여줄지, 구조화된 일정 후보(제목/시작·종료 시각 등)로 만들어 "바로 추가" 버튼까지 붙일지
- [ ] 요약 기능을 스트리밍으로 구현할지, 처음엔 단건 텍스트로 단순화할지
- [ ] 유저별 호출 빈도 제한을 어디서 관리할지(Redis 카운터 재사용 여지 있음 — 기존 blacklist/refresh 패턴과 동일한 저장소 활용 가능)
- [ ] 최신 Claude 모델 ID로 무엇을 쓸지

## 진행 순서

0단계(설정 버그 수정) → 1~2단계(배선) → 4~5단계(추천 기능 먼저, 텍스트 응답) → 7단계(테스트) → 8단계(프론트) → 이후 요약/스트리밍 기능 확장
