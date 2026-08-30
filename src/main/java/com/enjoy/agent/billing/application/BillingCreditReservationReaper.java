package com.enjoy.agent.billing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Releases claimed reservations one short wallet-locked transaction at a time. */
@Service
public class BillingCreditReservationReaper {

    private static final Logger log = LoggerFactory.getLogger(BillingCreditReservationReaper.class);
    private final BillingCreditReservationClaimService claimService;
    private final BillingCreditReservationService reservationService;

    public BillingCreditReservationReaper(
            BillingCreditReservationClaimService claimService,
            BillingCreditReservationService reservationService
    ) {
        this.claimService = claimService;
        this.reservationService = reservationService;
    }

    public int releaseExpiredBatch() {
        int released = 0;
        for (var claim : claimService.claimExpiredBatch()) {
            try {
                if (reservationService.releaseClaimedReservation(claim.id())) {
                    released++;
                }
            } catch (RuntimeException ex) {
                // The RELEASING claim is reclaimed after its timeout; one bad wallet must not
                // prevent the remaining independently-claimed users from being processed.
                log.warn("Failed to release claimed billing reservation, reservationId={}", claim.id(), ex);
            }
        }
        return released;
    }
}
