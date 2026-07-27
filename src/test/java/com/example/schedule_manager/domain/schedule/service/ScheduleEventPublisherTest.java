package com.example.schedule_manager.domain.schedule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ScheduleEventPublisher 는 유저별 SSE 구독자 목록을 관리하고, 그 유저의 일정이 바뀌었을 때
 * 구독 중인 연결에 이벤트를 보낸다. 실제로 전송된 바이트를 검증하려면 서블릿 컨테이너의 비동기
 * 처리(AsyncContext)까지 필요해 이 레이어의 책임이 아니므로, 여기서는 우리가 작성한 로직인
 * "구독자 등록/집계"와 "구독자가 없어도 안전하게 무시"만 검증한다.
 */
class ScheduleEventPublisherTest {

    private final ScheduleEventPublisher publisher = new ScheduleEventPublisher();

    @Test
    @DisplayName("구독하면 해당 유저의 구독자 수가 늘어난다")
    void subscribe_increasesSubscriberCount() {
        assertThat(publisher.subscriberCount(1L)).isZero();

        SseEmitter emitter = publisher.subscribe(1L);

        assertThat(emitter).isNotNull();
        assertThat(publisher.subscriberCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 유저가 여러 번 구독하면(여러 탭) 구독자 수가 누적된다")
    void subscribe_multipleTimes_accumulatesForSameUser() {
        publisher.subscribe(1L);
        publisher.subscribe(1L);

        assertThat(publisher.subscriberCount(1L)).isEqualTo(2);
        assertThat(publisher.subscriberCount(2L)).isZero();
    }

    @Test
    @DisplayName("구독자가 없는 유저에게 알려도 예외 없이 무시된다")
    void notifyChanged_noSubscribers_doesNothing() {
        assertThatCode(() -> publisher.notifyChanged(999L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("구독자가 있는 유저에게 알리면 예외 없이 처리된다")
    void notifyChanged_withSubscribers_doesNotThrow() {
        publisher.subscribe(1L);

        assertThatCode(() -> publisher.notifyChanged(1L)).doesNotThrowAnyException();
        assertThat(publisher.subscriberCount(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 끊긴 연결에 알리려 해도 예외가 밖으로 새지 않고, 목록에서 정리된다")
    void notifyChanged_deadEmitter_doesNotThrowAndCleansUp() {
        SseEmitter emitter = publisher.subscribe(1L);
        emitter.complete(); // 클라이언트가 이미 연결을 끊은 상태를 흉내낸다 - 이후 send()는 실패해야 한다

        assertThatCode(() -> publisher.notifyChanged(1L)).doesNotThrowAnyException();
        assertThat(publisher.subscriberCount(1L)).isZero();
    }
}
