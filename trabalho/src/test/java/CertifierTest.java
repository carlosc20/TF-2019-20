import middleware.certifier.BitWriteSet;
import middleware.certifier.Certifier;
import middleware.certifier.NoTableDefinedException;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CertifierTest {
    Certifier c;

    private boolean checkConflict(Map<String, BitWriteSet> ws, long ts){
        if (c.isWritable(ws, ws, ts)) {
            c.commit(ws);
            c.shutDownLocalStartedTransaction(ws.keySet(), ts);
            System.out.println("Commited");
            return true;
        }
        else
            System.out.println("Had conflict on ts: " + ts);
        return false;
    }


    private long startTransaction(Set<String> tables){
        long t = c.getTimestamp();
        c.transactionStarted(tables, t);
        return t;
    }

    @Test
    public void test() throws NoTableDefinedException {
        int running = 0;

        this.c = new Certifier();

        BitWriteSet bws1 = new BitWriteSet();
        bws1.add("marco");
        BitWriteSet bws11 = new BitWriteSet();
        bws11.add("bananas");

        BitWriteSet bws2 = new BitWriteSet();
        bws2.add("cesar");
        bws2.add("daniel");

        BitWriteSet bws3 = new BitWriteSet();
        bws3.add("cesar");
        bws3.add("carlos");

        HashMap<String, BitWriteSet> ws1 = new HashMap<>();
        ws1.put("customer", bws1);
        ws1.put("products", bws11);

        HashMap<String, BitWriteSet> ws2 = new HashMap<>();
        ws2.put("customer", bws2);

        HashMap<String, BitWriteSet> ws3 = new HashMap<>();
        ws3.put("customer", bws3);

        long t1 = startTransaction(ws1.keySet());
        long t11 = startTransaction(ws2.keySet());
        running += 2;

        assertTrue("Shouldn't conflict", checkConflict(ws1, t1));
        running--;

        long t2 = startTransaction(ws3.keySet());
        assertTrue("Shouldn't conflict", checkConflict(ws2, t11));
        running--;

        assertTrue("Should conflict", !checkConflict(ws3, t2));

        //started and not commited
        long t3 = startTransaction(ws2.keySet());
        running++;

        long newLowWaterMark = c.getSafeToDeleteTimestamp();

        System.out.println("running: " + c.getRunningTransactionsPerTable().toString());

        c.evictStoredWriteSets(newLowWaterMark);

        assertEquals(c.getLowWaterMark(), t1);
    }
}
