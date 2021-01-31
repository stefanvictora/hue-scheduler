package at.sv.hue.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpResourceProviderSystemTest {

    private URL exampleUrl;
    private HttpResourceProviderImpl provider;
    private URL invalidUrl;

    private URL getUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    @BeforeEach
    public void setUp() {
        exampleUrl = getUrl("http://example.com");
        provider = new HttpResourceProviderImpl();
        invalidUrl = getUrl("http://-");
    }

    @Test
    public void getResource() {
        String resource = provider.getResource(exampleUrl);

        assertThat(resource, notNullValue());
        assertThat(resource, containsString("Example Domain"));
    }

    @Test
    public void getResource_invalidUrl_null() {
        String resource = provider.getResource(invalidUrl);

        assertThat(resource, nullValue());
    }
}
