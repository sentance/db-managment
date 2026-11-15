package dev.pgcp.cp.notifications;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import dev.pgcp.cp.common.Json;
import tools.jackson.databind.json.JsonMapper;

class TelegramTransportTest {
    @Test void sendsOnlyToConfiguredApiAndHonorsRateLimitWithoutLeakingResponseDiagnostics() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var requests=new AtomicInteger();
        server.createContext("/bot123456:fake-token/sendMessage",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(body).contains("-10042","Backup failed").doesNotContain("fake-token");
            int count=requests.incrementAndGet();byte[] response=(count==1?"{\"ok\":false,\"parameters\":{\"retry_after\":120},\"description\":\"private-diagnostic\"}":"{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count==1?429:200,response.length);try(var out=exchange.getResponseBody()) { out.write(response); }
        });server.start();
        try {
            var json=new Json(JsonMapper.builder().build());String base="http://127.0.0.1:"+server.getAddress().getPort();
            assertThatThrownBy(()->new TelegramTransport(json,base,false)).isInstanceOf(IllegalArgumentException.class);
            var transport=new TelegramTransport(json,base,true);
            assertThat(transport.send("123456:fake-token","-10042","Backup failed")).isEqualTo(new TelegramTransport.Outcome(false,120));
            assertThat(transport.send("123456:fake-token","-10042","Backup failed").success()).isTrue();
        } finally { server.stop(0); }
    }
}
