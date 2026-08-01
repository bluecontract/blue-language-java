package blue.language.provider.ipfs;

import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;

/** Minimal HTTP gateway client used by the compatibility IPFS provider. */
public class IPFSContentFetcher {

    private static final String BASE_URL = "https://ipfs.io/ipfs/";
    private static final int TIMEOUT_IN_SECONDS = 2;

    /** Creates a compatibility facade over the static gateway operation. */
    public IPFSContentFetcher() {
    }

    /**
     * Fetches one CID from the configured public gateway.
     *
     * @param cid CIDv1 to fetch
     * @return response body, or {@code null} for an empty successful response
     * @throws IOException for transport failures or non-200 responses
     */
    public static String fetchContent(String cid) throws IOException {
        int timeout = TIMEOUT_IN_SECONDS * 1000;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();

        try (CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build()) {

            HttpGet request = new HttpGet(BASE_URL + cid);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                if (response.getStatusLine().getStatusCode() == 200) {
                    HttpEntity entity = response.getEntity();
                    return entity != null ? EntityUtils.toString(entity) : null;
                } else {
                    throw new IOException("Unexpected response status: " + response.getStatusLine().getStatusCode());
                }
            }
        }
    }
}
