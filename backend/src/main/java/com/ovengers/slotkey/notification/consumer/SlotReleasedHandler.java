package com.ovengers.slotkey.notification.consumer;

import com.ovengers.slotkey.member.entity.Member;
import com.ovengers.slotkey.member.entity.MemberStatus;
import com.ovengers.slotkey.member.repository.MemberRepository;
import com.ovengers.slotkey.notification.entity.Notification;
import com.ovengers.slotkey.notification.repository.NotificationRepository;
import com.ovengers.slotkey.space.repository.SpaceRepository;
import com.ovengers.slotkey.waitlist.entity.WaitlistEntry;
import com.ovengers.slotkey.waitlist.entity.WaitlistStatus;
import com.ovengers.slotkey.waitlist.repository.WaitlistEntryRepository;
import com.ovengers.slotkey.waitlist.service.ReservationOccupancyProvider;
import com.ovengers.slotkey.waitlist.service.ReservationRangeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * SlotReleased 이벤트 처리(4단계, docs/decisions/outbox-kafka.md "컨슈머 결정").
 * 리스너(Kafka)는 얇게 두고 로직은 여기에 둔다 — Kafka 없이 직접 호출해 CI에서 검증한다.
 *
 * 한 트랜잭션에서 ① processed_event 선점(중복이면 종료) ② 해제 구간 조회 ③ 알림 대상 선정 ④ 알림 저장과
 * 알림 시각 기록을 한다. 알림 저장이 실패하면 ①도 롤백되어 재전달 때 다시 처리된다.
 *
 * 알림 대상은 payload를 믿지 않고 "지금" DB를 다시 읽어 고른다. 홀드 만료 직후 다른 사람이 곧바로 재점유한
 * 슬롯에는 알림이 가지 않아야 하기 때문이다. 알림은 힌트일 뿐이고 예약 판정은 슬롯 UNIQUE가 한다(선착순).
 *
 * 신규 코드이므로 Lombok을 쓰지 않는다(AGENTS.md).
 */
@Service
public class SlotReleasedHandler {

    static final String CONSUMER = "waitlist-notifier";

    private static final Logger log = LoggerFactory.getLogger(SlotReleasedHandler.class);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("M월 d일 HH:mm");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ProcessedEventStore processedEventStore;
    private final ReservationRangeProvider reservationRangeProvider;
    private final ReservationOccupancyProvider reservationOccupancyProvider;
    private final WaitlistEntryRepository waitlistEntryRepository;
    private final MemberRepository memberRepository;
    private final SpaceRepository spaceRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public SlotReleasedHandler(ProcessedEventStore processedEventStore,
                               ReservationRangeProvider reservationRangeProvider,
                               ReservationOccupancyProvider reservationOccupancyProvider,
                               WaitlistEntryRepository waitlistEntryRepository,
                               MemberRepository memberRepository,
                               SpaceRepository spaceRepository,
                               NotificationRepository notificationRepository,
                               Clock clock) {
        this.processedEventStore = processedEventStore;
        this.reservationRangeProvider = reservationRangeProvider;
        this.reservationOccupancyProvider = reservationOccupancyProvider;
        this.waitlistEntryRepository = waitlistEntryRepository;
        this.memberRepository = memberRepository;
        this.spaceRepository = spaceRepository;
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional
    public void handle(String eventId, Long reservationId) {
        if (!processedEventStore.markProcessed(CONSUMER, eventId)) {
            return;
        }
        ReservationRangeProvider.ReservationRange released = reservationRangeProvider.findRange(reservationId);
        if (released == null) {
            log.warn("SlotReleased for unknown reservation: eventId={}, reservationId={}", eventId, reservationId);
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<WaitlistEntry> candidates = waitlistEntryRepository.findNotificationCandidates(
                released.spaceId(), released.startTime(), released.endTime(), WaitlistStatus.WAITING, now);
        if (candidates.isEmpty()) {
            return;
        }
        String spaceName = spaceRepository.findById(released.spaceId()).map(s -> s.getName()).orElse("공간");

        for (WaitlistEntry entry : candidates) {
            if (!isNotifiable(entry, now)) {
                continue;
            }
            notificationRepository.save(new Notification(
                    entry.getMemberId(), entry.getId(), eventId, message(spaceName, entry), now));
            waitlistEntryRepository.markNotified(entry.getId(), now, WaitlistStatus.WAITING);
        }
    }

    /** 신청 구간 전체가 지금 비어 있고, 회원이 ACTIVE이며, 본인이 겹치는 점유 예약을 갖고 있지 않은 항목만 대상이다. */
    private boolean isNotifiable(WaitlistEntry entry, LocalDateTime now) {
        Member member = memberRepository.findById(entry.getMemberId()).orElse(null);
        if (member == null || member.getStatus() != MemberStatus.ACTIVE) {
            return false;
        }
        if (reservationOccupancyProvider.hasOwnOccupyingReservationOverlapping(
                entry.getMemberId(), entry.getSpaceId(), entry.getStartTime(), entry.getEndTime(), now)) {
            return false;
        }
        return !reservationOccupancyProvider.hasOccupiedSlot(
                entry.getSpaceId(), entry.getStartTime(), entry.getEndTime(), now);
    }

    private static String message(String spaceName, WaitlistEntry entry) {
        return "[" + spaceName + "] " + entry.getStartTime().format(DATE_TIME) + "~"
                + entry.getEndTime().format(TIME) + " 자리가 비었습니다. 대기하신 모든 분께 알리는 선착순 안내이며,"
                + " 예약이 보장되지는 않습니다.";
    }
}
