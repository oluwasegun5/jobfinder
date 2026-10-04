package com.jobfinder.core.storage.internal;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import com.jobfinder.core.storage.UploadScanner;
import com.jobfinder.core.storage.UploadScannerUnavailableException;

/**
 * Scans through a ClamAV daemon with the {@code INSTREAM} command over TCP: {@code zINSTREAM\0}, then the bytes in
 * length-prefixed chunks (4-byte big-endian length), then a zero length. clamd answers {@code stream: OK},
 * {@code stream: <signature> FOUND} or {@code ... ERROR} (for example when it refuses a stream over its size limit),
 * NUL- or newline-terminated. Anything but OK or FOUND is "unavailable": we do not guess that an unscanned file is clean.
 */
final class ClamAvUploadScanner implements UploadScanner {

    private static final int CHUNK = 16 * 1024;
    private static final int MAX_REPLY = 4096;

    private final String host;
    private final int port;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    ClamAvUploadScanner(String host, int port, int connectTimeoutMillis, int readTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
    }

    @Override
    public Verdict scan(byte[] content) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            socket.setSoTimeout(readTimeoutMillis);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < content.length; offset += CHUNK) {
                int length = Math.min(CHUNK, content.length - offset);
                out.writeInt(length);
                out.write(content, offset, length);
            }
            out.writeInt(0);
            out.flush();
            return parse(readReply(socket.getInputStream()));
        } catch (IOException e) {
            throw new UploadScannerUnavailableException("The virus scanner could not be reached", e);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        StringBuilder reply = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != 0 && b != '\n') {
            reply.append((char) b);
            if (reply.length() > MAX_REPLY) {
                throw new IOException("scanner reply too long");
            }
        }
        return reply.toString().trim();
    }

    static Verdict parse(String reply) {
        if (reply.endsWith("OK")) {
            return Verdict.clean();
        }
        if (reply.endsWith("FOUND")) {
            String body = reply.substring(0, reply.length() - "FOUND".length()).trim();
            int colon = body.indexOf(':');
            return Verdict.infected(colon >= 0 ? body.substring(colon + 1).trim() : body);
        }
        throw new UploadScannerUnavailableException("The virus scanner gave no verdict", null);
    }
}
