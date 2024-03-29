import client.MessagingService;
import io.atomix.utils.net.Address;
import middleware.message.ContentMessage;
import middleware.message.WriteMessage;
import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DBSMTest {
    private MessagingService client1;
    private MessagingService client2;
    private MessagingService client3;

    private CompletableFuture<Void> run;

    public DBSMTest(){
        List<Address> l1 = new LinkedList<>();
        List<Address> l2 = new LinkedList<>();
        List<Address> l3 = new LinkedList<>();
        l1.add(Address.from("localhost", 7777));
        l2.add(Address.from("localhost", 8888));
        l3.add(Address.from("localhost", 8888));
        this.client1 = new MessagingService(10001, l1);
        this.client2 = new MessagingService(10002, l2);
        this.client3 = new MessagingService(10003, l3);
        this.run = new CompletableFuture<>();
    }

    @Test
    public void concurrentWrite() throws Exception {
        HashSet<String> ws1 = new HashSet();
        ws1.add("arroz");
        ws1.add("batatas");
        ws1.add("cenouras");

        HashSet<String> ws2 = new HashSet();
        ws2.add("arroz");
        ws2.add("trigo");
        ws2.add("batatas");
        ws2.add("cenouras");

        HashSet<String> ws3 = new HashSet();
        ws3.add("farinha");


        WriteMessage<HashSet<String>> wm1 = new WriteMessage<>(ws1);

        WriteMessage<HashSet<String>> wm2 = new WriteMessage<>(ws2);

/*
        new Thread(() ->{
            try {
                this.client1.sendAndReceive(wm1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        new Thread(() ->{
            try {
                this.client2.sendAndReceive(wm2);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
        this.run.get();
        */

    }

}
