package cn.orderflow;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.*;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;

final class AiTransport {
    private static final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(job -> {
        var thread=new Thread(job,"ai-http-deadlines"); thread.setDaemon(true); return thread;
    });

    record Reply(int status,String body) {}

    private AiTransport() {}

    static URI validatedUri(String base,boolean fixture) {
        try {
            URI uri=URI.create(base);
            if(fixture && uri.toString().equals("http://ai-mock:8765/v1")) return uri;
            String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme()) || host==null ||
                    !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z][a-z0-9-]{1,62}") ||
                    host.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(localhost|local|internal|lan|home|test|invalid|onion)$") ||
                    uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null ||
                    (uri.getPort()!=-1 && uri.getPort()!=443) ||
                    !uri.getRawPath().matches("(?:/[A-Za-z0-9_-]+)*")) throw AiConfig.invalid();
            return uri;
        } catch(IllegalArgumentException e) { throw AiConfig.invalid(); }
    }

    // Filter the exact DNS answers used for the socket. The TLS host remains the original DNS name.
    static InetAddress[] publicAddresses(String host) throws UnknownHostException {
        var addresses=InetAddress.getAllByName(host);
        for(var address:addresses) if(!publicAddress(address)) throw new UnknownHostException("non-public DNS answer");
        return addresses;
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b=address.getAddress();
        int a=b[0]&255, n=b[1]&255, c=b[2]&255;
        if(b.length==16) {
            // Global unicast only, excluding IETF special-use and transition/documentation blocks.
            return (a&224)==32 && !(a==32 && n==1 && (c<2 || (c==13&&(b[3]&255)==184)))
                    && !(a==32 && n==2) && !(a==63 && (n&240)==240);
        }
        return a>0 && a<224 && a!=10 && a!=127
                && !(a==100 && n>=64 && n<128) && !(a==169 && n==254)
                && !(a==172 && n>=16 && n<=31)
                && !(a==192 && (n==168 || (n==0&&(c==0||c==2)) || (n==88&&c==99)))
                && !(a==198 && (n==18 || n==19 || (n==51&&c==100)))
                && !(a==203 && n==0 && c==113);
    }

    static Reply post(URI base,String route,String key,String json,int seconds,boolean fixture) throws IOException,TimeoutException {
        var post=new HttpPost(base.toString()+"/"+route);
        post.setHeader("Authorization","Bearer "+key);
        post.setEntity(new StringEntity(json,ContentType.APPLICATION_JSON));
        var manager=PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(Math.min(seconds,4)))
                        .setSocketTimeout(Timeout.ofSeconds(seconds)).build())
                .setDnsResolver(new DnsResolver() {
                    @Override public InetAddress[] resolve(String name) throws UnknownHostException {
                        if(!base.getHost().equalsIgnoreCase(name)) throw new UnknownHostException("unexpected model host");
                        return fixture ? InetAddress.getAllByName(name) : publicAddresses(name);
                    }
                    @Override public String resolveCanonicalHostname(String name) { return name; }
                }).build();
        var timeout=deadlines.schedule(post::cancel,seconds,TimeUnit.SECONDS);
        long began=System.nanoTime();
        try(var http=HttpClients.custom().setConnectionManager(manager).disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableContentCompression().setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(seconds)).build()).build()) {
            return http.execute(post,response -> {
                if(response.getCode()!=200 || response.getEntity()==null) {
                    post.cancel(); // Discard an untrusted error body without draining it.
                    return new Reply(response.getCode(),"");
                }
                try(var body=response.getEntity().getContent()) {
                    byte[] data=body.readNBytes(512001);
                    if(data.length>512000) {post.cancel(); throw new IOException("provider body limit");}
                    return new Reply(response.getCode(),new String(data,StandardCharsets.UTF_8));
                }
            });
        } catch(IOException error) {
            if(error instanceof java.net.SocketTimeoutException || System.nanoTime()-began>=TimeUnit.SECONDS.toNanos(seconds))
                throw new TimeoutException();
            throw error;
        } finally { timeout.cancel(false); }
    }
}
