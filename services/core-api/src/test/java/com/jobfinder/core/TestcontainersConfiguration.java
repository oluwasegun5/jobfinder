package com.jobfinder.core;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres, Redis, RabbitMQ, Mailpit and S3Mock for integration tests (and for
 * {@code TestCoreApiApplication}), plus a WireMock stand-in for ai-service.
 *
 * <p>One set of containers per JVM (ADR 0038): they start once, on the first context, and are never stopped by a
 * context closing (Ryuk removes them when the JVM exits). Every Spring context still gets state of its own on them, as
 * it did when it had containers of its own: a fresh database, a RabbitMQ virtual host and a Redis database, created
 * when the context starts and dropped when it closes. Mailpit and the S3 bucket are shared; tests already look mail
 * up by recipient and store objects under random keys.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Keep in step with the adobe/s3mock tag in infra/docker-compose.yml.
	private static final String S3MOCK_VERSION = "5.2.3";
	private static final String BUCKET = "jobfinder-test";
	/** Redis databases available to contexts at once; far above the context cache cap (spring.properties). */
	private static final int REDIS_DATABASES = 64;
	/** Live contexts (cache cap) times the Hikari pool, plus headroom for admin connections. */
	private static final String POSTGRES_MAX_CONNECTIONS = "200";

	public static class RedisContainer extends GenericContainer<RedisContainer> {

		RedisContainer() {
			super(DockerImageName.parse("redis:7-alpine"));
			withExposedPorts(6379);
			withCommand("redis-server", "--databases", String.valueOf(REDIS_DATABASES));
		}
	}

	public static class MailpitContainer extends GenericContainer<MailpitContainer> {

		MailpitContainer() {
			super(DockerImageName.parse("axllent/mailpit:v1.31.2"));
			withExposedPorts(1025, 8025);
			waitingFor(Wait.forHttp("/readyz").forPort(8025));
		}

		public String apiBaseUrl() {
			return "http://" + getHost() + ":" + getMappedPort(8025);
		}
	}

	/**
	 * What tests need from Mailpit: its HTTP API. A plain value rather than the container, so that no context owns the
	 * shared container and closing one never stops it.
	 */
	public record Mailpit(String apiBaseUrl) {
	}

	/** The containers, started together the first time a context needs them and shared by every context after. */
	static final class Shared {

		static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
				.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + POSTGRES_MAX_CONNECTIONS);
		static final RedisContainer REDIS = new RedisContainer();
		static final MailpitContainer MAILPIT = new MailpitContainer();
		static final S3MockContainer S3 = new S3MockContainer(S3MOCK_VERSION).withInitialBuckets(BUCKET);
		// Keep in step with the rabbitmq tag in infra/docker-compose.yml.
		static final RabbitMQContainer RABBIT = new RabbitMQContainer(
				DockerImageName.parse("rabbitmq:4.3.6-management-alpine"));

		static {
			Startables.deepStart(POSTGRES, REDIS, MAILPIT, S3, RABBIT).join();
		}

		private Shared() {
		}
	}

	/**
	 * One context's own database, RabbitMQ virtual host and Redis database on the shared containers. Created before
	 * any other bean of the context, so destroyed after them (connection pools and listeners are closed first).
	 */
	public static final class ContextResources implements AutoCloseable {

		private static final AtomicInteger SEQUENCE = new AtomicInteger();
		private static final BitSet REDIS_IN_USE = new BitSet(REDIS_DATABASES);

		private final String database;
		private final String virtualHost;
		private final int redisDatabase;

		ContextResources() {
			String name = "ctx_" + SEQUENCE.incrementAndGet();
			this.database = name;
			this.virtualHost = name;
			admin("create database " + database);
			rabbitmqctl("add_vhost", virtualHost);
			rabbitmqctl("set_permissions", "-p", virtualHost, Shared.RABBIT.getAdminUsername(), ".*", ".*", ".*");
			this.redisDatabase = allocateRedisDatabase();
			exec(Shared.REDIS, "redis-cli", "-n", String.valueOf(redisDatabase), "FLUSHDB");
		}

		String jdbcUrl() {
			return "jdbc:postgresql://" + Shared.POSTGRES.getHost() + ":" + Shared.POSTGRES.getMappedPort(5432) + "/"
					+ database;
		}

		String virtualHost() {
			return virtualHost;
		}

		String redisUri() {
			return "redis://" + Shared.REDIS.getHost() + ":" + Shared.REDIS.getMappedPort(6379) + "/" + redisDatabase;
		}

		@Override
		public void close() {
			try {
				admin("drop database if exists " + database + " with (force)");
				rabbitmqctl("delete_vhost", virtualHost);
			} finally {
				releaseRedisDatabase(redisDatabase);
			}
		}

		private static synchronized int allocateRedisDatabase() {
			int free = REDIS_IN_USE.nextClearBit(0);
			if (free >= REDIS_DATABASES) {
				throw new IllegalStateException("All " + REDIS_DATABASES + " Redis databases are in use: more live "
						+ "test contexts than the context cache cap allows");
			}
			REDIS_IN_USE.set(free);
			return free;
		}

		private static synchronized void releaseRedisDatabase(int index) {
			REDIS_IN_USE.clear(index);
		}

		private static void admin(String sql) {
			try (Connection connection = DriverManager.getConnection(Shared.POSTGRES.getJdbcUrl(),
					Shared.POSTGRES.getUsername(), Shared.POSTGRES.getPassword());
					Statement statement = connection.createStatement()) {
				statement.execute(sql);
			} catch (SQLException e) {
				throw new IllegalStateException("Test database setup failed: " + sql, e);
			}
		}

		private static void rabbitmqctl(String... args) {
			String[] command = new String[args.length + 1];
			command[0] = "rabbitmqctl";
			System.arraycopy(args, 0, command, 1, args.length);
			exec(Shared.RABBIT, command);
		}

		private static void exec(GenericContainer<?> container, String... command) {
			try {
				ExecResult result = container.execInContainer(command);
				if (result.getExitCode() != 0) {
					throw new IllegalStateException(String.join(" ", command) + " failed: " + result.getStderr());
				}
			} catch (IOException e) {
				throw new IllegalStateException(String.join(" ", command) + " failed", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(String.join(" ", command) + " interrupted", e);
			}
		}
	}

	@Bean(destroyMethod = "close")
	ContextResources contextResources() {
		return new ContextResources();
	}

	@Bean
	Mailpit mailpit() {
		return new Mailpit(Shared.MAILPIT.apiBaseUrl());
	}

	/** Stands in for the internal ai-service (WireMock: no test talks to a real LLM). */
	@Bean(destroyMethod = "stop")
	WireMockServer aiServiceMock() {
		WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		AiServiceStubs.installDefault(server);
		return server;
	}

	/**
	 * Stands in for Stripe's and Paystack's APIs (WireMock: no test talks to a real provider). A holder type, not a bare
	 * {@code WireMockServer}, so tests that autowire the ai-service stub by type stay unambiguous.
	 */
	public record PaymentProviderMock(WireMockServer server) {
		public void stop() {
			server.stop();
		}
	}

	@Bean(destroyMethod = "stop")
	PaymentProviderMock paymentProviderMock() {
		WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		server.start();
		return new PaymentProviderMock(server);
	}

	@Bean
	DynamicPropertyRegistrar testProperties(ContextResources context, WireMockServer aiService,
			PaymentProviderMock payments) {
		return registry -> {
			registry.add("spring.datasource.url", context::jdbcUrl);
			registry.add("spring.datasource.username", Shared.POSTGRES::getUsername);
			registry.add("spring.datasource.password", Shared.POSTGRES::getPassword);
			registry.add("spring.rabbitmq.host", Shared.RABBIT::getHost);
			registry.add("spring.rabbitmq.port", Shared.RABBIT::getAmqpPort);
			registry.add("spring.rabbitmq.username", Shared.RABBIT::getAdminUsername);
			registry.add("spring.rabbitmq.password", Shared.RABBIT::getAdminPassword);
			registry.add("spring.rabbitmq.virtual-host", context::virtualHost);
			registry.add("app.ai-service.base-url", () -> "http://localhost:" + aiService.port());
			registry.add("app.ai-service.token", () -> AiServiceStubs.TOKEN);
			// Short backoff keeps retry tests quick; three attempts as in production.
			registry.add("app.resumes.parsing.initial-backoff", () -> "20ms");
			registry.add("app.storage.endpoint", Shared.S3::getHttpEndpoint);
			registry.add("app.storage.bucket", () -> BUCKET);
			registry.add("app.storage.access-key", () -> "test");
			registry.add("app.storage.secret-key", () -> "test");
			registry.add("app.rate-limit.redis-uri", context::redisUri);
			registry.add("spring.mail.host", Shared.MAILPIT::getHost);
			registry.add("spring.mail.port", () -> Shared.MAILPIT.getMappedPort(1025));
			registry.add("app.auth.jwt.secret", () -> "test-only-jwt-secret-0123456789-abcdefghijklmnop");
			// Cheap hashing keeps the suite fast; production default is 12.
			registry.add("app.auth.bcrypt-strength", () -> 4);
			// Fake keys and secrets, and the provider APIs pointed at WireMock: nothing real is ever contacted.
			String paymentsUrl = "http://localhost:" + payments.server().port();
			registry.add("app.billing.stripe.api-base", () -> paymentsUrl);
			registry.add("app.billing.stripe.secret-key", () -> PaymentFixtures.STRIPE_KEY);
			registry.add("app.billing.stripe.webhook-secret", () -> PaymentFixtures.STRIPE_WEBHOOK_SECRET);
			registry.add("app.billing.paystack.api-base", () -> paymentsUrl);
			registry.add("app.billing.paystack.secret-key", () -> PaymentFixtures.PAYSTACK_KEY);
		};
	}

}
