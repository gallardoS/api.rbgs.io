package io.rbgs.api.identity;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Account upsert(String issuer, String subject, String displayName) {
        return jdbc.queryForObject("""
                INSERT INTO rbgs.accounts (id, provider_issuer, provider_subject, display_name, region)
                VALUES (?, ?, ?, ?, 'EU')
                ON CONFLICT (provider_issuer, provider_subject) DO UPDATE
                    SET display_name = EXCLUDED.display_name, updated_at = CURRENT_TIMESTAMP
                RETURNING id, display_name, region, account_status, account_role
                """, (row, index) -> new Account(
                        row.getObject("id", UUID.class), row.getString("display_name"),
                        row.getString("region"), row.getString("account_status"), row.getString("account_role")),
                UUID.randomUUID(), issuer, subject, displayName);
    }

    public Account findByIdentity(String issuer, String subject) {
        return jdbc.queryForObject("""
                SELECT id, display_name, region, account_status, account_role
                FROM rbgs.accounts WHERE provider_issuer = ? AND provider_subject = ?
                """, (row, index) -> new Account(
                        row.getObject("id", UUID.class), row.getString("display_name"),
                        row.getString("region"), row.getString("account_status"), row.getString("account_role")),
                issuer, subject);
    }
}
