package com.enjoy.agent.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.billing.api.response.RechargeOrderResponse;
import com.enjoy.agent.billing.domain.entity.RechargeOrder;
import com.enjoy.agent.billing.domain.enums.RechargeOrderStatus;
import com.enjoy.agent.billing.infrastructure.persistence.RechargeOrderRepository;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

class RechargeOrderMappingQueryContractTest {

    @Test
    void everyReadAndReviewQueryShouldFetchMapperAssociationsInBulk() {
        for (Method method : RechargeOrderRepository.class.getDeclaredMethods()) {
            if (!method.getName().startsWith("find")) {
                continue;
            }
            if (method.getName().startsWith("findSupport")) {
                // 客服方法返回显式构造器投影而不是 RechargeOrder 实体，所以不会
                // 调用响应 Mapper，也不应为了满足旧契约反向加载 user/reviewedBy。
                Query query = method.getAnnotation(Query.class);
                assertThat(query)
                        .as("%s must use an explicit safe projection", method.getName())
                        .isNotNull();
                assertThat(query.value())
                        .contains("RechargeOrderSummaryProjection")
                        .doesNotContain("rechargeOrder.reviewedBy")
                        .doesNotContain("rechargeOrder.user,");
                continue;
            }
            EntityGraph graph = method.getAnnotation(EntityGraph.class);
            assertThat(graph)
                    .as("%s must prevent per-row user/reviewer selects", method.getName())
                    .isNotNull();
            assertThat(Arrays.asList(graph.attributePaths()))
                    .containsExactlyInAnyOrder("user", "reviewedBy");
        }
    }

    @Test
    void responseMappingShouldUseOnlyTheTwoPrefetchedAssociations() {
        WalletSupportService mapper = new WalletSupportService(
                mock(com.enjoy.agent.auth.infrastructure.persistence.AppUserRepository.class),
                mock(com.enjoy.agent.billing.infrastructure.persistence.UserWalletRepository.class),
                mock(com.enjoy.agent.billing.infrastructure.persistence.UserWalletTransactionRepository.class),
                new BillingProperties()
        );
        AppUser user = new AppUser();
        user.setId(7L);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        AppUser reviewer = new AppUser();
        reviewer.setId(9L);
        RechargeOrder order = new RechargeOrder();
        order.setId(42L);
        order.setUser(user);
        order.setReviewedBy(reviewer);
        order.setAmount(new BigDecimal("10.000000"));
        order.setCurrency("CNY");
        order.setStatus(RechargeOrderStatus.APPROVED);

        RechargeOrderResponse response = mapper.toRechargeOrderResponse(order);

        assertThat(response.userId()).isEqualTo(7L);
        assertThat(response.userEmail()).isEqualTo("user@example.com");
        assertThat(response.reviewedBy()).isEqualTo(9L);
        assertThat(response.amount()).isEqualTo("10.000000");
    }

    @Test
    void reviewQueryShouldSerializeApproveAndRejectRaces() throws Exception {
        Method method = RechargeOrderRepository.class.getDeclaredMethod("findByIdForUpdate", Long.class);

        assertThat(method.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }
}
