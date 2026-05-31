package io.sample.app;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** Calls an upstream greeting API through the shared HTTP client. */
public class GreetService {

    private final HttpClient http = HttpClientConfig.client();

    public String greet(String name) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://upstream.example/greet?name=" + name))
                .GET()
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "Hello, " + name;
        }
    }
}
