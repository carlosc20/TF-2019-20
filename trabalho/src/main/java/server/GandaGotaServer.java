package server;

import business.SuperMarket;
import business.SuperMarketImpl;
import middleware.PassiveReplicationServer;

public class GandaGotaServer extends PassiveReplicationServer<SuperMarket> {

    private SuperMarket superMarket;

    public GandaGotaServer(int port, String privateName) {
        super(port, privateName);
        this.superMarket = new SuperMarketImpl();
    }

    @Override
    public SuperMarket getState() {
        return null;
    }

    @Override
    public void setState(SuperMarket superMarket) {

    }

    @Override
    public void handleMessage(Object message) {

    }
}
