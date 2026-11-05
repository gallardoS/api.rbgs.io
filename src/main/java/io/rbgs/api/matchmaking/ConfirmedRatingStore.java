package io.rbgs.api.matchmaking;

import io.rbgs.api.seasons.RatingSubjectType;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Confirmed database state, with separate subject/season/queue ownership. No browser writes or pending-result estimates. */
@Service @RequiredArgsConstructor
public class ConfirmedRatingStore {
    public enum Queue { SOLO, PREMADE }
    public record Snapshot(UUID seasonId,RatingSubjectType subjectType,UUID subjectId,int mmr,int visibleRating,
            int gamesPlayed,int priorCaptainAssignments,long version) { }
    private final JdbcTemplate db;

    @Transactional
    public void initializeSelection(UUID selectionId) {
        db.update("""
            INSERT INTO rbgs.competitive_ratings(season_id,queue_type,subject_type,subject_id,account_id,character_id)
            SELECT s.season_id,q.queue_type,s.rating_subject_type,
                   CASE WHEN s.rating_subject_type='ACCOUNT' THEN s.account_id ELSE s.character_id END,
                   s.account_id,CASE WHEN s.rating_subject_type='CHARACTER' THEN s.character_id END
            FROM rbgs.season_selections s JOIN rbgs.seasons r ON r.id=s.season_id AND r.rating_subject_type=s.rating_subject_type
            JOIN rbgs.accounts a ON a.id=s.account_id
            CROSS JOIN (VALUES ('SOLO'),('PREMADE')) AS q(queue_type)
            WHERE s.id=? AND a.provider_issuer NOT IN ('https://soloq-lab.invalid','https://premade-lab.invalid')
            ON CONFLICT DO NOTHING
            """,selectionId);
    }

    public Optional<Snapshot> confirmed(UUID season,Queue queue,RatingSubjectType type,UUID subject) {
        return db.query("""
            SELECT c.* FROM rbgs.competitive_ratings c JOIN rbgs.seasons s
              ON s.id=c.season_id AND s.rating_subject_type=c.subject_type
            WHERE c.season_id=? AND c.queue_type=? AND c.subject_type=? AND c.subject_id=?
            """,(r,i)->new Snapshot(season,type,subject,r.getInt("mmr"),r.getInt("visible_rating"),
                    r.getInt("games_played"),r.getInt("captain_assignments"),r.getLong("version")),season,queue.name(),type.name(),subject)
                .stream().findFirst();
    }
}
