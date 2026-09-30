package com.jobfinder.core;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Real Postgres, Redis, Mailpit and S3Mock for integration tests (and for {@code TestCoreApiApplication}). */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// Keep in step with the adobe/s3mock tag in infra/docker-compose.yml.
	private static final String S3MOCK_VERSION = "5.2.3";
	private static final String BUCKET = "jobfinder-test";

	public static class RedisContainer extends GenericContainer<RedisContainer> {

		RedisContainer() {
			super(DockerImageName.parse("redis:7-alpine"));
			withExposedPorts(6379);
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

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(
				DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
	}

	@Bean
	RedisContainer redisContainer() {
		return new RedisContainer();
	}

	@Bean
	MailpitContainer mailpitContainer() {
		return new MailpitContainer();
	}

	@Bean
	S3MockContainer s3MockContainer() {
		return new S3MockContainer(S3MOCK_VERSION).withInitialBuckets(BUCKET);
	}

	@Bean
	DynamicPropertyRegistrar testProperties(RedisContainer redis, MailpitContainer mailpit, S3MockContainer s3) {
		return registry -> {
			registry.add("app.storage.endpoint", s3::getHttpEndpoint);
			registry.add("app.storage.bucket", () -> BUCKET);
			registry.add("app.storage.access-key", () -> "test");
			registry.add("app.storage.secret-key", () -> "test");
			registry.add("app.rate-limit.redis-uri", () -> "redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
			registry.add("spring.mail.host", mailpit::getHost);
			registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
			registry.add("app.auth.jwt.secret", () -> "test-only-jwt-secret-0123456789-abcdefghijklmnop");
			// Cheap hashing keeps the suite fast; production default is 12.
			registry.add("app.auth.bcrypt-strength", () -> 4);
		};
	}

}
