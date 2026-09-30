package hzpro.com.tradingdesk.client;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;

import java.net.URI;

/**
 * HTTP methods supported by {@link KalshiAuthorizedClient}.
 */
public enum HttpMethod {
    GET,
    POST,
    DELETE;

    /**
     * Creates the appropriate {@link HttpUriRequest} for this method at the given URI.
     */
    HttpUriRequest createRequest(URI uri) {
        return switch (this) {
            case GET -> new HttpGet(uri);
            case POST -> new HttpPost(uri);
            case DELETE -> new HttpDelete(uri);
        };
    }
}
