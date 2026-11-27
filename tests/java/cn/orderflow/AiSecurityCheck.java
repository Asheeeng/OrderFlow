package cn.orderflow;

import java.net.InetAddress;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.util.*;
import org.springframework.core.env.*;

public class AiSecurityCheck {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }

    public static void main(String[] args) throws Exception {
        for (String ip : List.of("127.0.0.1","10.3.4.5","172.22.0.1","192.168.1.1","169.254.169.254",
                "100.64.1.5","198.18.0.1","::1","::ffff:127.0.0.1","fc00::1","fe80::1","2002:7f00:1::")) {
            check(!AiTransport.publicAddress(InetAddress.getByName(ip)));
        }
        check(AiTransport.publicAddress(InetAddress.getByName("1.1.1.1")));
        check(AiTransport.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        for (String uri : List.of("http://provider.example.com/v1","https://127.0.0.1/v1",
                "https://example.org:8080/v1","https://user@example.org/v1","https://example.org/v1?x=1")) {
            try { AiTransport.validatedUri(uri,false); throw new AssertionError(); }
            catch(ApiException expected) { check(expected.status.value()==400); }
        }
        check(AiTransport.validatedUri("https://dashscope.aliyuncs.com/compatible-mode/v1",false).getPort()==-1);

        Path key=Files.createTempFile("orderflow-vault-test",".key");
        try {
            Files.writeString(key, "ee".repeat(32));
            var env=new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("test",Map.of("AI_CONFIG_KEY_FILE",key.toString())));
            var vault=new AiConfig(null,null,env);
            var initial=new AiConfig.Snapshot(1,null,"","",false);
            var replacement=new AiConfig.Snapshot(2,null,"","",false);
            vault.use(initial,()->{
                long other=java.util.concurrent.CompletableFuture.supplyAsync(
                        ()->vault.use(replacement,()->vault.current().revision())).join();
                check(other==2 && vault.current().revision()==1);
                return null;
            });
            var encrypt=AiConfig.class.getDeclaredMethod("encrypt",String.class,String.class);
            var decrypt=AiConfig.class.getDeclaredMethod("decrypt",byte[].class,String.class);
            encrypt.setAccessible(true); decrypt.setAccessible(true);
            byte[] first=(byte[])encrypt.invoke(vault,"fixture-key-only","chat");
            byte[] second=(byte[])encrypt.invoke(vault,"fixture-key-only","chat");
            check(!Arrays.equals(first,second));
            check("fixture-key-only".equals(decrypt.invoke(vault,first,"chat")));
            try { decrypt.invoke(vault,first,"embedding"); throw new AssertionError(); }
            catch(InvocationTargetException error) { check(error.getCause() instanceof GeneralSecurityException); }
            first[first.length-1]^=1;
            try { decrypt.invoke(vault,first,"chat"); throw new AssertionError(); }
            catch(InvocationTargetException error) { check(error.getCause() instanceof GeneralSecurityException); }
        } finally { Files.deleteIfExists(key); }
        System.out.println("PASS: blocked special-use addresses, strict URI policy, random AES-GCM nonce, slot authentication, tamper rejection and request snapshot isolation");
    }
}
