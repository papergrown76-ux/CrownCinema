package de.crown.cinema;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fixed-file download endpoint; serves only the embedded, extracted resource pack. */
final class PackHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    PackHttpServer(String bind, int port, Path pack, String hash) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        server.setExecutor(executor);
        server.createContext("/crown-cinema.zip", exchange -> {
            try {
                if (!exchange.getRequestURI().getPath().equals("/crown-cinema.zip")) {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    exchange.sendResponseHeaders(405, -1); return;
                }
                long size = Files.size(pack);
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("ETag", "\"" + hash + "\"");
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                exchange.getResponseHeaders().set("Content-Length", Long.toString(size));
                exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : size);
                if (method.equals("GET")) Files.copy(pack, exchange.getResponseBody());
            } finally { exchange.close(); }
        });
        server.start();
    }

    int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); executor.shutdownNow(); }
}
