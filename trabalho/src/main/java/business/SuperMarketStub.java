package business;

import business.order.Order;
import business.order.OrderImpl;
import business.product.Product;
import client.message.AddCostumerMessage;
import client.message.FinishOrderMessage;
import client.message.GetCatalogProducts;
import client.message.GetHistoryMessage;
import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.MessagingConfig;
import io.atomix.cluster.messaging.impl.NettyMessagingService;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;
import io.atomix.utils.serializer.SerializerBuilder;
import middleware.message.ContentMessage;
import middleware.message.Message;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;

public class SuperMarketStub implements SuperMarket {

    private ManagedMessagingService mms;
    private Address primaryServer;
    private Serializer s;
    private CompletableFuture<ContentMessage> res;
    private ScheduledExecutorService ses;
    private String privateCustumer;
    private Order currentOrder;

    public SuperMarketStub(int myPort, Address primaryServer){
        this.res = null;
        this.currentOrder = null;

        this.primaryServer = primaryServer;
        this.ses = Executors.newScheduledThreadPool(1);

        this.mms = new NettyMessagingService(
                "server",
                Address.from(myPort),
                new MessagingConfig());
        this.mms.start();
        this.s = new SerializerBuilder()
                .withRegistrationRequired(false)
                .build();

        this.mms.registerHandler("reply", (a,b) -> {
            ContentMessage<?> repm = s.decode(b);
                res.complete(repm);
        }, ses);
    }

    public <T extends Serializable> ContentMessage<T> getResponse(Message reqm) throws Exception{
        res = new CompletableFuture<>();
        ScheduledFuture<?> sf = scheduleTimeout(reqm);
        //Caso a resposta tenha chegado cancela o timeout
        res.whenComplete((m,t) -> { System.out.println(t.getStackTrace()); sf.cancel(true);});
        mms.sendAsync(primaryServer, "request", s.encode(reqm));
        return res.get();
    }


    public ScheduledFuture<?> scheduleTimeout(Message reqm){
        return ses.scheduleAtFixedRate(()->{
            System.out.println("timeout...sending new request");
            mms.sendAsync(primaryServer, "request", s.encode(reqm));
        }, 1, 4, TimeUnit.SECONDS);
    }


    // define o utilizador atual, deve ser usado antes dos outros métodos
    @Override
    public boolean addCustomer(String customer) throws Exception {
        this.privateCustumer = customer;
        return (Boolean) getResponse(new AddCostumerMessage(customer)).getBody();
    }

    // local
    @Override
    public boolean resetOrder(String customer) {
        this.currentOrder.reset();
        return true;
    }


    // envia para servidor
    @Override
    public boolean finishOrder(String customer) throws Exception {
        if(!customer.equals(privateCustumer))
            return false;
        return (Boolean) getResponse(new FinishOrderMessage(customer, currentOrder)).getBody();
    }


    // local
    @Override
    public boolean addProduct(String customer, Product product, int amount) {
        if(!customer.equals(privateCustumer))
            return false;
        if(this.currentOrder == null)
            this.currentOrder = new OrderImpl();
        this.currentOrder.addProduct(product, amount);
        return true;
    }


    // local
    @Override
    public Map<Product, Integer> getCurrentOrderProducts(String customer) {
        return this.currentOrder.getProducts();
    }


    // pedido ao servidor
    @Override
    public ArrayList<Order> getHistory(String customer) throws Exception {
        ContentMessage<ArrayList<Order>> cm = getResponse(new GetHistoryMessage(customer));
        return cm.getBody();
    }


    // pedido ao servidor
    @Override
    public ArrayList<Product> getCatalogProducts() throws Exception {
        ContentMessage<ArrayList<Product>> cm = getResponse(new GetCatalogProducts());
        return cm.getBody();
    }
}
