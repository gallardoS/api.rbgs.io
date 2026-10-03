package io.rbgs.api;

import io.rbgs.api.identity.Account;
import io.rbgs.api.identity.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ApiApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AccountRepository accounts;

	@Test
	void foundationMigrationIsApplied() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM rbgs.flyway_schema_history WHERE version IN ('1', '2') AND success",
				Integer.class);
		assertEquals(2, applied);
	}

	@Test
	void changedBattleTagKeepsTheSameAccount() {
		String subject = java.util.UUID.randomUUID().toString();
		Account first = accounts.upsert("https://oauth.battle.net", subject, "Original#1234");
		Account renamed = accounts.upsert("https://oauth.battle.net", subject, "Changed#5678");
		assertEquals(first.id(), renamed.id());
		assertEquals("Changed#5678", renamed.displayName());
		assertEquals("USER", renamed.role());
	}

}
