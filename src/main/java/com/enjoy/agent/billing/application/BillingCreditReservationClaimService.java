package com.enjoy.agent.billing.application;

import com.enjoy.agent.billing.infrastructure.persistence.BillingCreditReservationClaimRepository;
import com.enjoy.agent.billing.infrastructure.persistence.BillingCreditReservationClaimRepository.ClaimedReservation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a short-lived SKIP LOCKED claim transaction for one reaper batch. */
@Service
public class BillingCreditReservationClaimService {

    private final BillingCreditReservationClaimRepository claimRepository;
    private final BillingProperties properties;
    private final Clock clock;

    public BillingCreditReservationClaimService(
            BillingCreditReservationClaimRepository claimRepository,
            BillingProperties properties,
            Clock clock
    ) {
        this.claimRepository = claimRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public List<ClaimedReservation> claimExpiredBatch() {
        Instant now = Instant.now(clock);
        return claimRepository.claimExpired(
                now,
                now.minus(properties.getReservationReleaseClaimTimeout()),
                properties.getRabbit().getOutboxBatchSize()
        );
    }
}
