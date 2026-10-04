package com.jobfinder.core;

import com.adobe.testing.s3mock.testcontainers.S3MockContainer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres, Redis, RabbitMQ, Mailpit and S3Mock for integration tests (and for
 * {@code TestCoreApiApplication}), plus a WireMock stand-in for ai-service.
 */
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
	@ServiceConnection
	RabbitMQContainer rabbitContainer() {
		// Keep in step with the rabbitmq tag in infra/docker-compose.yml.
		return new RabbitMQContainer(DockerImageName.parse("rabbitmq:4.3.6-management-alpine"));
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
	DynamicPropertyRegistrar testProperties(RedisContainer redis, MailpitContainer mailpit, S3MockContainer s3,
			WireMockServer aiService, PaymentProviderMock payments) {
		return registry -> {
			registry.add("app.ai-service.base-url", () -> "http://localhost:" + aiService.port());
			registry.add("app.ai-service.token", () -> AiServiceStubs.TOKEN);
			// Short backoff keeps retry tests quick; three attempts as in production.
			registry.add("app.resumes.parsing.initial-backoff", () -> "20ms");
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
