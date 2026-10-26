package com.ovengers.slotkey.access.entity;

import com.ovengers.slotkey.reservation.entity.Reservation;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "door_access_token")
public class DoorAccessToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoke_reason", length = 100)
    private String revokeReason;

    protected DoorAccessToken() {
    }

    public Long getId() {
        return id;
    }

    public Reservation getReservation() {
        return reservation;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getIssuedAt() {
        return issuedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }

    // 출입 토큰 생성
    public DoorAccessToken(
            Reservation reservation,
            String tokenHash,
            LocalDateTime issuedAt
    ) {
        this.reservation = reservation;
        this.tokenHash = tokenHash;
        this.issuedAt = issuedAt;
    }

    // 토큰 폐기
    public void revoke(
            LocalDateTime revokedAt,
            String revokeReason
    ) {
        this.revokedAt = revokedAt;
        this.revokeReason = revokeReason;
    }

    // 폐기 여부 확인
    public boolean isRevoked() {
        return revokedAt != null;
    }
}
