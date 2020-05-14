package client.message;

import business.order.Order;
import client.bodies.FinishOrderBody;
import middleware.message.WriteMessage;


/**
 * Used to make a finish order request. This request needs certification.
 */
public class FinishOrderMessage extends WriteMessage<FinishOrderBody> {
    public FinishOrderMessage(String customer, Order order){
        super(new FinishOrderBody(customer, order));
    }
}
