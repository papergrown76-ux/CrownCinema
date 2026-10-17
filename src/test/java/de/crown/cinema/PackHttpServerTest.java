package de.crown.cinema;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PackHttpServerTest {
    @TempDir Path directory;
    @Test void servesOnlyExactPackAndReadMethods() throws Exception {
        byte[] bytes = new byte[]{80,75,3,4,0,9};
        Path file = directory.resolve("pack.zip"); Files.write(file,bytes);
        try (var server = new PackHttpServer("127.0.0.1", 0, file, "abc")) {
            var client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + server.port() + "/crown-cinema.zip");
            var response = client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode()); assertArrayEquals(bytes,response.body());
            assertEquals("application/zip",response.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(405,client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(404,client.send(HttpRequest.newBuilder(URI.create(uri+"/../config.properties")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
