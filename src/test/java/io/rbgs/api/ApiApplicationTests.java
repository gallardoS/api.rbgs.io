package io.rbgs.api;

import io.rbgs.api.identity.Account;
import io.rbgs.api.identity.AccountService;
import io.rbgs.api.characters.WowCharacterLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ApiApplicationTests {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AccountService accounts;
	@Autowired
	private CacheManager cacheManager;
	@Autowired
	private WowCharacterLoader characterLoader;

	@Test
	void characterCachingUsesCaffeineWithProductionLimitsAndSpringProxy() {
		var cache = assertInstanceOf(CaffeineCache.class, cacheManager.getCache("wowCharacters")).getNativeCache();
		assertEquals(256L, cache.policy().eviction().orElseThrow().getMaximum());
		assertEquals(Duration.ofSeconds(60), cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
		assertTrue(AopUtils.isAopProxy(characterLoader));
	}

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

	@Test
	void loginDoesNotResetModerationState() {
		String subject = java.util.UUID.randomUUID().toString();
		Account original = accounts.upsert("https://oauth.battle.net", subject, "Original#1234");
		jdbcTemplate.update("UPDATE rbgs.accounts SET account_status = 'SUSPENDED', account_role = 'MODERATOR' WHERE id = ?",
				original.id());
		Account renamed = accounts.upsert("https://oauth.battle.net", subject, "Changed#5678");
		assertEquals(original.id(), renamed.id());
		assertEquals("Changed#5678", renamed.displayName());
		assertEquals("SUSPENDED", renamed.status());
		assertEquals("MODERATOR", renamed.role());
	}

	@Test
	void concurrentLoginsKeepOneAccount() throws Exception {
		String subject = java.util.UUID.randomUUID().toString();
		var ready = new java.util.concurrent.CountDownLatch(2);
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			java.util.concurrent.Callable<Account> login = () -> {
				ready.countDown();
				if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
					throw new IllegalStateException("Concurrent login did not start");
				}
				return accounts.upsert("https://oauth.battle.net", subject, "Concurrent#1234");
			};
			var first = executor.submit(login);
			var second = executor.submit(login);
			try {
				org.junit.jupiter.api.Assertions.assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
			} finally {
				start.countDown();
			}
			assertEquals(first.get(15, java.util.concurrent.TimeUnit.SECONDS).id(),
					second.get(15, java.util.concurrent.TimeUnit.SECONDS).id());
			assertEquals(1L, jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM rbgs.accounts WHERE provider_issuer = ? AND provider_subject = ?",
					Long.class, "https://oauth.battle.net", subject));
		}
	}

}
