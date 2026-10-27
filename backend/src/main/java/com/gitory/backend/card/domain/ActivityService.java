package com.gitory.backend.card.domain;

import com.gitory.backend.audit.domain.AuditAction;
import com.gitory.backend.audit.domain.AuditLog;
import com.gitory.backend.card.infra.ActivityRepository;
import com.gitory.backend.card.infra.CardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 내 활동만 보고 고치고 지우며, 남의 활동이면 부른 요청의 action 으로 거절 기록을 남긴 뒤 없는 활동과 똑같이 404 로 답하게 한다
 * 활동을 지우면 안의 카드는 DB(ON DELETE CASCADE)가 버전·문장·근거·되묻기까지 함께 지운다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityService {

    private final ActivityRepository activities;
    private final CardRepository cards;
    private final AuditLog auditLog;
    private final TransactionTemplate transaction;

    @Transactional(readOnly = true)
    public List<ActivityView> list(Long userId) {

        List<Activity> found = activities.findByUserIdOrderByStartMonthDescIdDesc(userId);
        Map<Long, Long> counts = cards.countByActivityIds(found.stream().map(Activity::getId).toList())
                .stream()
                .collect(Collectors.toMap(CardRepository.ActivityCardCount::getActivityId,
                        CardRepository.ActivityCardCount::getCards));

        return found.stream()
                .map(activity -> ActivityView.of(activity, counts.getOrDefault(activity.getId(), 0L)))
                .toList();

    }

    /**
     * 같은 요청 키로 다시 오면 새로 만들지 않고 그 키로 만든 활동을 돌려준다
     * 같은 키의 동시 요청은 DB 유니크에 막힌 쪽이 실패한 트랜잭션 밖에서 저장된 활동을 다시 찾아야 하므로 저장만 트랜잭션으로 감싼다
     */
    public ActivityView create(Long userId, String name, String type, String startMonth, String endMonth,
                               String role, String idempotencyKey) {

        String key = IdempotencyKey.checked(idempotencyKey);
        Activity activity = existing(userId, key)
                .orElseGet(() -> save(Activity.start(userId, name, type, startMonth, endMonth, role, key)));

        return ActivityView.of(activity, cards.countByActivityId(activity.getId()));

    }

    @Transactional
    public ActivityView edit(UUID publicId, Long userId, String name, String type, String startMonth,
                             String endMonth, String role) {

        Activity activity = lockOwned(publicId, userId, AuditAction.EDIT_ACTIVITY);
        activity.edit(name, type, startMonth, endMonth, role);

        return ActivityView.of(activity, cards.countByActivityId(activity.getId()));

    }

    @Transactional
    public void delete(UUID publicId, Long userId) {

        Activity activity = lockOwned(publicId, userId, AuditAction.DELETE_ACTIVITY);
        long cardCount = cards.countByActivityId(activity.getId());
        activities.delete(activity);
        auditLog.ok(userId, AuditAction.DELETE_ACTIVITY, activity.getId());
        log.info("활동 {} 을 지우며 안의 카드 {}장을 함께 지웠다", activity.getId(), cardCount);

    }

    /** 카드를 넣을 활동이 내 것인지 보고 내부 id 를 돌려주며, 남의 활동이면 카드 만들기 거절로 기록한다 */
    @Transactional(readOnly = true)
    public Long ownedActivityId(UUID publicId, Long userId) {

        return activities.findByPublicIdAndUserId(publicId, userId)
                .map(Activity::getId)
                .orElseThrow(() -> notFound(publicId, userId, AuditAction.CREATE_CARD));

    }

    /** 카드를 저장하는 트랜잭션 안에서 활동 행을 공유 잠금해, 그사이 지워진 활동이면 500 대신 404 로 답하게 한다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireExists(Long activityId) {

        activities.findWithShareLockById(activityId).orElseThrow(ActivityNotFoundException::new);

    }

    private Activity save(Activity activity) {

        try {
            return transaction.execute(status -> activities.save(activity));
        } catch (DataIntegrityViolationException e) {
            return existing(activity.getUserId(), activity.getIdempotencyKey()).orElseThrow(() -> e);
        }

    }

    private Optional<Activity> existing(Long userId, String idempotencyKey) {

        // 키 없이 찾으면 Spring Data 가 IS NULL 조건으로 바꿔 키 없이 만든 활동들이 걸린다
        if (idempotencyKey == null) {
            return Optional.empty();
        }

        return activities.findByUserIdAndIdempotencyKey(userId, idempotencyKey);

    }

    private Activity lockOwned(UUID publicId, Long userId, AuditAction action) {

        return activities.findWithLockByPublicIdAndUserId(publicId, userId)
                .orElseThrow(() -> notFound(publicId, userId, action));

    }

    private ActivityNotFoundException notFound(UUID publicId, Long userId, AuditAction action) {

        activities.findByPublicId(publicId)
                .ifPresent(others -> auditLog.denied(userId, action, others.getId()));

        return new ActivityNotFoundException();

    }
}
