package server;

import business.SuperMarket;
import business.SuperMarketImpl;
import business.customer.Customer;
import business.data.DAO;
import business.data.customer.CustomerCertifierDAO;
import business.data.customer.CustomerSQLDAO;
import business.data.order.OrderCertifierDAO;
import business.data.order.OrderProductDAO;
import business.data.order.OrderSQLDAO;
import business.data.product.ProductCertifierDAO;
import business.data.product.ProductSQLDAO;
import business.order.Order;
import business.order.OrderImpl;
import business.product.OrderProductQuantity;
import business.product.Product;
import business.product.ProductPlaceholder;
import client.message.bodies.AddProductBody;
import client.message.*;
import client.message.bodies.UpdateProductBody;
import com.google.common.collect.Sets;
import middleware.certifier.*;
import middleware.ServerImpl;
import middleware.message.ContentMessage;
import middleware.message.ErrorMessage;
import middleware.message.Message;
import middleware.message.WriteMessage;
import middleware.message.replication.CertifyWriteMessage;


import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

public class GandaGotaServerImpl extends ServerImpl<ArrayList<String>> {

    private SuperMarketImpl superMarket;
    private Connection connection;
    private DAO<String, Order> orderDAO;
    private DAO<String, Product> productDAO;
    private DAO<String, Customer> customerDAO;
    private DAO<String, Order> orderCertifierDAO;

    public GandaGotaServerImpl(int spreadPort,
                               String privateName,
                               int atomixPort,
                               Connection connection,
                               int totalServerCount,
                               String logPath,
                               String timestampPath) throws Exception {
        super(spreadPort, privateName, atomixPort, connection, totalServerCount, logPath, timestampPath);
        //TODO tmax não poderá aumentar/diminuir consoante a quantidade de aborts
        this.connection = connection;
        this.orderDAO = new OrderSQLDAO(this.connection, id -> {
            try {
                return new OrderProductDAO(connection, id);
            } catch (SQLException throwables) {
                throwables.printStackTrace();
            }
            return null;
        });
        this.orderCertifierDAO = new OrderSQLDAO(connection, id -> {
            try {
                return new OrderProductDAO(connection, id);
            } catch (SQLException throwables) {
                throwables.printStackTrace();
            }
            return null;
        });
        this.productDAO = new ProductSQLDAO(this.connection);
        this.customerDAO = new CustomerSQLDAO(this.connection);
        this.superMarket = new SuperMarketImpl(orderDAO, productDAO, customerDAO, null);
    }

    @Override
    public Message handleMessage(Message message) {
        try {
            if(message instanceof GetOrderMessage) {
                String customer = ((GetOrderMessage) message).getBody();
                Map<Product, Integer> result = superMarket.getCurrentOrderProducts(customer);
                HashMap<Product, Integer> response = result != null ? new HashMap<>(result) : null;
                return new ContentMessage<>(response);
            }
            if(message instanceof GetCatalogProductsMessage){
                return new ContentMessage<>(new ArrayList<>(superMarket.getCatalogProducts()));
            }
            if(message instanceof GetHistoryMessage) {
                ArrayList<Order> response = new ArrayList<>(0);
                for (Order order:
                     superMarket.getHistory(((GetHistoryMessage) message).getBody())) {
                    response.add(new OrderImpl(order.getId(),
                            new HashMap<>(order.getProducts()),
                            order.getTimestamp(),
                            order.getCustomerId()));
                }
                return new ContentMessage<>(response);
            }
        } catch (Exception e){
            e.printStackTrace();
            return new ErrorMessage(e).from(message);
        }
        return new ErrorMessage(new Exception("Unrecognized message " + message.getId() + ": " + message));
    }


    /**
     * returns null if execution fails
    */
    @Override
    public CertifyWriteMessage<?> handleWriteMessage(WriteMessage<?> message){
        StateUpdates<String, Serializable> updates = new StateUpdatesBitSet<>();
        SuperMarket superMarket = new SuperMarketImpl(new OrderCertifierDAO(orderCertifierDAO, updates), new ProductCertifierDAO(productDAO, updates), new CustomerCertifierDAO(customerDAO, updates), updates);
        boolean success = false;

        if(message instanceof AddCustomerMessage) {
            String customer = ((AddCustomerMessage) message).getBody();
            success = superMarket.addCustomer(customer);

        } else if (message instanceof FinishOrderMessage) {
            String customer = ((FinishOrderMessage) message).getBody();
            success = superMarket.finishOrder(customer);

        } else if (message instanceof ResetOrderMessage) {
            String customer = ((ResetOrderMessage) message).getBody();
            success = superMarket.resetOrder(customer);

        } else if (message instanceof AddProductMessage) {
            AddProductBody body = ((AddProductMessage) message).getBody();
            success = superMarket.addProductToOrder(body.getCustomer(), body.getProduct(), body.getAmount());

        } else if (message instanceof UpdateProductMessage) {
            UpdateProductBody body = ((UpdateProductMessage) message).getBody();
            success = superMarket.updateProduct(body.getName(), body.getPrice(), body.getDescription(), body.getStock());

        }

        if(success)
            return new CertifyWriteMessage<>(updates.getSets(), (LinkedHashSet<TaggedObject<String, Serializable>>) updates.getAllUpdates());

        return null;
    }



    @Override
    public void updateStateFromCommitedWrite(CertifyWriteMessage<?> message) {
        //TODO
        //TESTE
        System.out.println("Server : " + this.getPrivateName() + " update state from commit");
        try {
            commit((Set<TaggedObject<String, Serializable>>) message.getState());
        } catch (Exception e) {
            //TODO: Se algo corre mal, o servidor tem de parar para ficar consistente??
            System.exit(1);
        }
    }

    @Override
    public void commit(Set<TaggedObject<String, Serializable>> changes) throws SQLException {
        this.connection.setAutoCommit(false);
        //TODO verificar se é necessário transação
        for (TaggedObject<String, Serializable> change : changes) {
            String tag = change.getTag();
            String key = change.getKey();
            Serializable object = change.getObject();
            switch (tag) {
                case "customer": {
                    if(object != null) {
                        if(!customerDAO.put((Customer) object)) throw new SQLException();
                    } else {
                        if(!customerDAO.delete(key)) throw new SQLException();
                    }
                }
                break;
                case "order": {
                    if(object != null) {
                        if(!orderDAO.put((Order) object)) throw new SQLException();
                    } else {
                        if(!orderDAO.delete(key)) throw new SQLException();
                    }
                }
                break;
                case "product": {
                    if(object != null) {
                        if(!productDAO.put((Product) object)) throw new SQLException();
                    } else {
                        if(!orderDAO.delete(key)) throw new SQLException();
                    }
                }
                break;
                case "order_product": {
                    OrderProductQuantity opq = (OrderProductQuantity) object;
                    Product product = new ProductPlaceholder(opq.getProductId());
                    orderDAO.get(opq.getOrderId())
                            .getProducts()
                            .merge(product, opq.getQuantity(), Integer::sum);
                }
                break;
            }
        }
        this.connection.commit();
        System.out.println("Server : " + this.getPrivateName() + " commit");
    }

    @Override
    public void rollback(){
        System.out.println("Server : " + this.getPrivateName() + " rollback");
    }

    @Override
    public ArrayList<String> getState() {
        return null;
    }

    @Override
    public void setState(ArrayList<String> queries) {
        try {
            Connection c = DriverManager.getConnection("jdbc:hsqldb:hsql://localhost:9001/1", "user", "password");
            for(String query : queries) {
                c.prepareStatement(query).execute();
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
}
