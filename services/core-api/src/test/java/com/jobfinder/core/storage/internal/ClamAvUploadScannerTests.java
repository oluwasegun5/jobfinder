package com.jobfinder.core.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jobfinder.core.storage.UploadScanner;
import com.jobfinder.core.storage.UploadScannerUnavailableException;

/** The clamd INSTREAM client against a stand-in daemon on a local port: protocol, verdicts and every way it can fail. */
class ClamAvUploadScannerTests {

    private ServerSocket server;
    private ExecutorService executor;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        executor.shutdownNow();
    }

    @Test
    void streamsTheBytesInLengthPrefixedChunksAndReadsAnOkVerdict() throws Exception {
        byte[] content = new byte[40_000];
        java.util.Arrays.fill(content, (byte) 7);
        CompletableFuture<byte[]> received = daemon("stream: OK\0");

        UploadScanner.Verdict verdict = scanner(1000).scan(content);

        assertThat(verdict.infected()).isFalse();
        assertThat(received.get()).isEqualTo(content);
    }

    @Test
    void anInfectedVerdictCarriesTheSignature() throws Exception {
        daemon("stream: Win.Test.EICAR_HDB-1 FOUND\0");

        UploadScanner.Verdict verdict = scanner(1000).scan("x".getBytes(StandardCharsets.UTF_8));

        assertThat(verdict.infected()).isTrue();
        assertThat(verdict.signature()).isEqualTo("Win.Test.EICAR_HDB-1");
    }

    @Test
    void anErrorReplyIsNotTreatedAsClean() throws Exception {
        daemon("INSTREAM size limit exceeded. ERROR\0");

        assertThatThrownBy(() -> scanner(1000).scan(new byte[10])).isInstanceOf(UploadScannerUnavailableException.class);
    }

    @Test
    void aDaemonThatSaysNothingTimesOutAsUnavailable() throws Exception {
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // test teardown
            }
        });

        assertThatThrownBy(() -> scanner(300).scan(new byte[10])).isInstanceOf(UploadScannerUnavailableException.class);
    }

    @Test
    void nothingListeningIsUnavailable() throws Exception {
        int closedPort = server.getLocalPort();
        server.close();

        assertThatThrownBy(() -> new ClamAvUploadScanner("127.0.0.1", closedPort, 300, 300).scan(new byte[10]))
                .isInstanceOf(UploadScannerUnavailableException.class);
    }

    @Test
    void aRunawayReplyIsRefused() throws Exception {
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                socket.getOutputStream().write("A".repeat(10_000).getBytes(StandardCharsets.US_ASCII));
                Thread.sleep(500);
            } catch (Exception ignored) {
                // test teardown
            }
        });

        assertThatThrownBy(() -> scanner(1000).scan(new byte[10])).isInstanceOf(UploadScannerUnavailableException.class);
    }

    @Test
    void theFailurePolicyIsAppliedByTheFacade() {
        UploadScanner down = content -> {
            throw new UploadScannerUnavailableException("down", null);
        };

        assertThatThrownBy(() -> new DefaultUploadScans(down, UploadScanProperties.OnError.closed).requireClean(new byte[1]))
                .isInstanceOfSatisfying(com.jobfinder.core.shared.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("upload_scan_unavailable"));
        new DefaultUploadScans(down, UploadScanProperties.OnError.open).requireClean(new byte[1]);
    }

    private ClamAvUploadScanner scanner(int timeoutMillis) {
        return new ClamAvUploadScanner("127.0.0.1", server.getLocalPort(), timeoutMillis, timeoutMillis);
    }

    /** Accepts one connection, checks the INSTREAM framing, replies, and returns the reassembled payload. */
    private CompletableFuture<byte[]> daemon(String reply) {
        CompletableFuture<byte[]> payload = new CompletableFuture<>();
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] command = new byte[10];
                in.readFully(command);
                assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                int length;
                while ((length = in.readInt()) != 0) {
                    assertThat(length).isLessThanOrEqualTo(16 * 1024);
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    body.write(chunk);
                }
                socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                payload.complete(body.toByteArray());
            } catch (Throwable t) {
                payload.completeExceptionally(t);
            }
        });
        return payload;
    }
}
