package io.rbgs.api.seasons;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "seasons", schema = "rbgs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeasonEntity {
    @Id
    private UUID id;
    @Column(nullable = false, length = 120)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeasonKind kind;
    @Enumerated(EnumType.STRING)
    @Column(name = "rating_subject_type", nullable = false, length = 16)
    private RatingSubjectType ratingSubjectType;
    @Column(name = "started_at")
    private Instant startedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    public SeasonEntity(String name, SeasonKind kind, RatingSubjectType ratingSubjectType) {
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Season name must contain 1 to 120 characters");
        }
        this.id = UUID.randomUUID();
        this.name = name;
        this.kind = Objects.requireNonNull(kind, "Season kind is required");
        this.ratingSubjectType = Objects.requireNonNull(ratingSubjectType, "Rating subject type is required");
    }

    public void configureRatingSubjectType(RatingSubjectType ratingSubjectType) {
        if (startedAt != null) {
            throw new IllegalStateException("A started season cannot change rating ownership");
        }
        this.ratingSubjectType = Objects.requireNonNull(ratingSubjectType, "Rating subject type is required");
    }

    // Recording a start freezes identity; it does not enable queues or ranked play.
    public void start(Instant startedAt) {
        if (this.startedAt != null) {
            throw new IllegalStateException("Season has already started");
        }
        this.startedAt = Objects.requireNonNull(startedAt, "Season start time is required");
    }
}
