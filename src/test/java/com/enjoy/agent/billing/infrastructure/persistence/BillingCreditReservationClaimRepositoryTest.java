package com.enjoy.agent.billing.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class BillingCreditReservationClaimRepositoryTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void claimQueryShouldUseSkipLockedAndStableWalletOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        BillingCreditReservationClaimRepository repository =
                new BillingCreditReservationClaimRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-08-09T00:00:00Z");

        repository.claimExpired(now, now.minusSeconds(60), 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbcTemplate)
                .query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertThat(sql.getValue())
                .contains("FOR UPDATE SKIP LOCKED")
                .contains("ORDER BY user_id ASC, id ASC")
                .contains("status = 'RELEASING'");
        OffsetDateTime nowUtc = now.atOffset(ZoneOffset.UTC);
        assertThat(parameters.getValue()).containsExactly(
                nowUtc,
                now.minusSeconds(60).atOffset(ZoneOffset.UTC),
                50,
                nowUtc,
                nowUtc
        );
    }
}
