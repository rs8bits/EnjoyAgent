package com.enjoy.agent.billing.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class BillingReconciliationScheduler {

    private final BillingCreditReservationReaper reservationReaper;
    private final BillingUsageApplicationService usageApplicationService;

    public BillingReconciliationScheduler(
            BillingCreditReservationReaper reservationReaper,
            BillingUsageApplicationService usageApplicationService
    ) {
        this.reservationReaper = reservationReaper;
        this.usageApplicationService = usageApplicationService;
    }

    @Scheduled(
            fixedDelayString = "${enjoy.billing.reservation-reaper-delay-ms:30000}",
            initialDelayString = "${enjoy.billing.reservation-reaper-initial-delay-ms:30000}"
    )
    public void releaseExpiredReservations() {
        usageApplicationService.renewActiveCallReservations();
        reservationReaper.releaseExpiredBatch();
    }
}
