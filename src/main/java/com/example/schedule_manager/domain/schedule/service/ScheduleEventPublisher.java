package com.example.schedule_manager.domain.schedule.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// 유저별로 열려 있는 SSE 연결(브라우저 탭)을 들고 있다가, 그 유저의 일정에 변화가 생기면(생성/수정/삭제/
// 자동 상태 전환) 곧바로 이벤트를 보낸다. ScheduleService.evictScheduleCacheForUser()가 호출되는
// 모든 지점(= 그 유저의 일정이 실제로 바뀐 시점)에서 함께 호출되며, 프론트는 더 이상 주기적으로
// 폴링하지 않고 이 이벤트를 받을 때만 서버에서 최신 상태를 다시 받아온다
@Slf4j
@Component
public class ScheduleEventPublisher {

    private final Map<Long, List<SseEmitter>> emittersByUserId = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(0L); // 0 = 타임아웃 없음, 연결이 끊길 때까지 유지
        List<SseEmitter> emitters = emittersByUserId.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        emitters.add(emitter);

        Runnable cleanup = () -> emitters.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError((e) -> cleanup.run());

        // 첫 이벤트가 실제로 쏴질 때까지는 응답 헤더 자체가 커밋되지 않아, 브라우저 쪽 EventSource가
        // (실제로는 연결됐는데도) 계속 CONNECTING 상태로 보인다 - 구독 직후 곧바로 하나 보내서
        // 연결을 확정 짓는다
        try {
            emitter.send(SseEmitter.event().name("connected").data("ok"));
        } catch (IOException e) {
            emitters.remove(emitter);
        }

        return emitter;
    }

    public void notifyChanged(Long userId) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("schedules-changed").data("changed"));
            } catch (IOException | IllegalStateException e) {
                // 이미 끊긴 연결에 completeWithError()를 또 호출하면(컨테이너가 async 에러 처리를
                // 이미 진행 중인 상태) 그 호출 자체가 다시 예외를 던진다 - 여기선 그냥 목록에서만
                // 제거한다. 실제 정리(onError/onCompletion 콜백)는 subscribe()에서 이미 걸어뒀다
                log.debug("일정 변경 이벤트 전송 실패 - 연결이 끊긴 것으로 보고 정리합니다. userId={}", userId, e);
                emitters.remove(emitter);
            }
        }
    }

    // 현재 이 유저를 구독 중인 연결 수 - 테스트 및 모니터링용
    public int subscriberCount(Long userId) {
        List<SseEmitter> emitters = emittersByUserId.get(userId);
        return emitters == null ? 0 : emitters.size();
    }
}
