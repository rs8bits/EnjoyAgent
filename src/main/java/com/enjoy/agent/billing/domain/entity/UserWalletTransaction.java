package com.enjoy.agent.billing.domain.entity;

import com.enjoy.agent.auth.domain.entity.AppUser;
import com.enjoy.agent.billing.domain.enums.UserWalletTransactionType;
import com.enjoy.agent.billing.domain.enums.WalletReferenceType;
import com.enjoy.agent.shared.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * 用户钱包流水。
 */
@Entity
@Table(name = "user_wallet_transaction")
public class UserWalletTransaction extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "wallet_id", nullable = false)
    private UserWallet wallet;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 32)
    private UserWalletTransactionType transactionType;

    @Column(name = "amount_delta", nullable = false, precision = 18, scale = 6)
    private BigDecimal amountDelta;

    @Column(name = "balance_after", nullable = false, precision = 18, scale = 6)
    private BigDecimal balanceAfter;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 32)
    private WalletReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "description", length = 512)
    private String description;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UserWallet getWallet() {
        return wallet;
    }

    public void setWallet(UserWallet wallet) {
        this.wallet = wallet;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public UserWalletTransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(UserWalletTransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmountDelta() {
        return amountDelta;
    }

    public void setAmountDelta(BigDecimal amountDelta) {
        this.amountDelta = amountDelta;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public WalletReferenceType getReferenceType() {
        return referenceType;
    }

    public void setReferenceType(WalletReferenceType referenceType) {
        this.referenceType = referenceType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public void setReferenceId(Long referenceId) {
        this.referenceId = referenceId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
