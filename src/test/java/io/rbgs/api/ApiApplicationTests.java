package io.rbgs.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ApiApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void foundationMigrationIsApplied() {
		Integer applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM rbgs.flyway_schema_history WHERE version = '1' AND success",
				Integer.class);
		assertEquals(1, applied);
	}
}
