package com.enjoy.agent.billing.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomically claims expired reservations without making every application instance block on them. */
@Repository
public class BillingCreditReservationClaimRepository {

    private final JdbcTemplate jdbcTemplate;

    public BillingCreditReservationClaimRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ClaimedReservation> claimExpired(
            Instant now,
            Instant staleClaimBefore,
            int batchSize
    ) {
        OffsetDateTime currentTimestamp = now.atOffset(ZoneOffset.UTC);
        OffsetDateTime staleClaimTimestamp = staleClaimBefore.atOffset(ZoneOffset.UTC);
        List<ClaimedReservation> claims = jdbcTemplate.query(
                """
                WITH candidates AS MATERIALIZED (
                    SELECT id
                    FROM billing_credit_reservation
                    WHERE (status = 'ACTIVE' AND expires_at <= ?)
                       OR (status = 'RELEASING' AND release_claimed_at <= ?)
                    ORDER BY user_id ASC, id ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                UPDATE billing_credit_reservation reservation
                SET status = 'RELEASING', release_claimed_at = ?, updated_at = ?
                FROM candidates
                WHERE reservation.id = candidates.id
                RETURNING reservation.id, reservation.user_id
                """,
                (resultSet, rowNumber) -> new ClaimedReservation(
                        resultSet.getLong("id"),
                        resultSet.getLong("user_id")
                ),
                currentTimestamp,
                staleClaimTimestamp,
                batchSize,
                currentTimestamp,
                currentTimestamp
        );
        return claims.stream()
                .sorted(Comparator.comparing(ClaimedReservation::userId)
                        .thenComparing(ClaimedReservation::id))
                .toList();
    }

    public record ClaimedReservation(Long id, Long userId) {
    }
}
