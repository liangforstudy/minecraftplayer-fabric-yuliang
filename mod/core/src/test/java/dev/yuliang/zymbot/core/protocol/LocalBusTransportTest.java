package dev.yuliang.zymbot.core.protocol;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Real UDP multicast on this machine — two "bots" sharing one port, as two headless bots would. */
class LocalBusTransportTest {
    @Test
    void twoEndpointsOnOneMachineHearEachOther() throws Exception {
        int port = 25590 + 100 + (int) (Math.random() * 400);
        LocalBusTransport one, two;
        try {
            one = new LocalBusTransport("239.255.90.67", port);
            two = new LocalBusTransport("239.255.90.67", port);
        } catch (IOException e) {
            assumeTrue(false, "no multicast-capable network here: " + e.getMessage());
            return;
        }
        try {
            var heard = new CopyOnWriteArrayList<String>();
            two.onReceive(heard::add);
            one.send("zb1 hello-from-one");
            long until = System.currentTimeMillis() + 3000;
            while (heard.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20);
            assertTrue(heard.contains("zb1 hello-from-one"), "heard: " + heard);
        } finally {
            one.close();
            two.close();
        }
    }
}
