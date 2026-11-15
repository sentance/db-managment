package dev.pgcp.cp.notifications;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import dev.pgcp.cp.common.Json;

/** Never log the token-bearing URI, raw Telegram response or HTTP exception. */
@Component
public class TelegramTransport {
    public record Outcome(boolean success,int retryAfter) { }
    private final URI endpoint;
    private final Json json;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public TelegramTransport(Json json,@Value("${pgcp.notifications.telegram-base-url:https://api.telegram.org}") String base,
            @Value("${pgcp.notifications.allow-test-endpoint:false}") boolean allowTest) {
        this.json=json;this.endpoint=URI.create(base);
        boolean production="https".equals(endpoint.getScheme()) && "api.telegram.org".equals(endpoint.getHost()) && endpoint.getPort()==-1;
        boolean test=allowTest && "http".equals(endpoint.getScheme()) && java.util.Set.of("127.0.0.1","localhost").contains(endpoint.getHost());
        if((!production && !test) || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                || !java.util.Set.of("","/").contains(endpoint.getPath())) throw new IllegalArgumentException("invalid Telegram API endpoint");
    }
    public Outcome send(String token,String chat,String message) {
        try {
            var request=HttpRequest.newBuilder(endpoint.resolve("/bot"+token+"/sendMessage")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("chat_id",chat,"text",message)))).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            String body;
            try(var stream=response.body()) { body=new String(stream.readNBytes(65537),java.nio.charset.StandardCharsets.UTF_8); }
            if(body.length()>65536) return new Outcome(false,0);
            var data=json.readMap(body);
            if(response.statusCode()==200 && Boolean.TRUE.equals(data.get("ok"))) return new Outcome(true,0);
            Object parameters=data.get("parameters");
            int retry=parameters instanceof Map<?,?> p && p.get("retry_after") instanceof Number n?Math.max(0,Math.min(86400,n.intValue())):0;
            return new Outcome(false,retry);
        } catch(InterruptedException e) { Thread.currentThread().interrupt();return new Outcome(false,0); }
        catch(Exception ignored) { return new Outcome(false,0); }
    }
}
