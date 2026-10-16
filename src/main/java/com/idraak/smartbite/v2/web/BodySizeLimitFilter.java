package com.idraak.smartbite.v2.web;

import com.idraak.smartbite.v2.error.ApiException;
import com.idraak.smartbite.v2.error.ErrorCode;
import com.idraak.smartbite.v2.error.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Rejects v2 bodies larger than the limit with 413 PAYLOAD_TOO_LARGE, whether or not Content-Length is sent. The
 * history upload ({@code /api/v2/me/history}, up to 100 entries) has its own, larger limit.
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    static final String HISTORY_PATH = "/api/v2/me/history";

    private final int defaultMaxBytes;
    private final int historyMaxBytes;
    private final ObjectMapper mapper;

    public BodySizeLimitFilter(int defaultMaxBytes, int historyMaxBytes, ObjectMapper mapper) {
        this.defaultMaxBytes = defaultMaxBytes;
        this.historyMaxBytes = historyMaxBytes;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v2/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int maxBytes = HISTORY_PATH.equals(request.getRequestURI()) ? historyMaxBytes : defaultMaxBytes;
        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            Problems.write(response, mapper, new ApiException(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            Problems.write(response, mapper, new ApiException(ErrorCode.PAYLOAD_TOO_LARGE));
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            InputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    try {
                        return in.available() == 0;
                    } catch (IOException e) {
                        return true;
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
