package com.jobfinder.core.billing.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/** The bounded read behind POST /webhooks/**: no body may be buffered beyond 1 MB, whatever the client declares. */
class WebhookBodyLimitTests {

    /** A request that claims whatever length it is given (or none) and streams bytes without end. */
    static final class EndlessRequest extends MockHttpServletRequest {

        final AtomicLong served = new AtomicLong();
        private final long declared;

        EndlessRequest(long declaredLength) {
            this(null, "/webhooks/stripe", declaredLength);
        }

        EndlessRequest(jakarta.servlet.ServletContext context, String path, long declaredLength) {
            super(context, "POST", path);
            this.declared = declaredLength;
        }

        @Override
        public long getContentLengthLong() {
            return declared;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                @Override
                public int read() {
                    served.incrementAndGet();
                    return 'x';
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    served.addAndGet(len);
                    java.util.Arrays.fill(b, off, off + len, (byte) 'x');
                    return len;
                }

                @Override
                public boolean isFinished() {
                    return false;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                }
            };
        }
    }

    @Test
    void aStreamedBodyWithNoContentLengthIsCutOffAtTheLimitInsteadOfBeingBuffered() {
        EndlessRequest request = new EndlessRequest(-1);

        assertThat(WebhookController.readBounded(request)).isNull();

        // It stopped reading at the limit (plus the one byte that proves it is over), not at the end of the stream.
        assertThat(request.served.get()).isEqualTo(WebhookController.MAX_BODY_BYTES + 1L);
    }

    @Test
    void aBodyThatDeclaresATooLargeLengthIsRefusedWithoutReadingAnything() {
        EndlessRequest request = new EndlessRequest(WebhookController.MAX_BODY_BYTES + 1L);

        assertThat(WebhookController.readBounded(request)).isNull();
        assertThat(request.served.get()).isZero();
    }

    @Test
    void aBodyThatLiesAboutItsLengthIsStillCapped() {
        // Declares 10 bytes, sends endless data: the read cap does not trust the header.
        EndlessRequest request = new EndlessRequest(10);

        assertThat(WebhookController.readBounded(request)).isNull();
        assertThat(request.served.get()).isEqualTo(WebhookController.MAX_BODY_BYTES + 1L);
    }

    @Test
    void aBodyAtExactlyTheLimitAndASmallOneAreReadInFull() throws IOException {
        byte[] exactly = new byte[WebhookController.MAX_BODY_BYTES];
        java.util.Arrays.fill(exactly, (byte) 'a');
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/stripe");
        request.setContent(exactly);
        assertThat(WebhookController.readBounded(request)).isEqualTo(exactly);

        MockHttpServletRequest small = new MockHttpServletRequest("POST", "/webhooks/stripe");
        small.setContent("{\"id\":\"evt_1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(new String(WebhookController.readBounded(small), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("{\"id\":\"evt_1\"}");
    }
}
