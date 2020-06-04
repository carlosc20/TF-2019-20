package middleware;

import io.atomix.cluster.messaging.ManagedMessagingService;
import io.atomix.cluster.messaging.MessagingConfig;
import io.atomix.cluster.messaging.impl.NettyMessagingService;
import io.atomix.utils.net.Address;
import io.atomix.utils.serializer.Serializer;
import io.atomix.utils.serializer.SerializerBuilder;
import middleware.certifier.NoTableDefinedException;
import middleware.certifier.WriteSet;
import middleware.logreader.LogReader;
import middleware.message.ContentMessage;
import middleware.message.Message;
import middleware.message.TransactionMessage;
import middleware.message.WriteMessage;
import middleware.message.replication.CertifyWriteMessage;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ServerImpl<K, W extends WriteSet<K>, STATE extends Serializable> implements Server {

    private final ClusterReplicationService<K, W, STATE> replicationService;
    private final ExecutorService e;
    private final Serializer s;
    private final ManagedMessagingService mms;
    private final CompletableFuture<Void> runningCompletable;
    private ReentrantLock rl;
    private Map<String, CompletableFuture<Boolean>> pendingWrites;
    private String privateName;
    private LogReader logReader;
    private boolean isPaused;

    public ServerImpl(int spreadPort, String privateName, int atomixPort){
        this.privateName = privateName;
        // TODO numero de servidores max/total
        this.replicationService = new ClusterReplicationService<>(spreadPort, privateName, this, 3);
        this.e = Executors.newFixedThreadPool(1);
        this.runningCompletable = new CompletableFuture<>();
        this.rl = new ReentrantLock();
        this.pendingWrites = new HashMap<>();
        this.s = new SerializerBuilder()
                .withRegistrationRequired(false)
                .build();
        Address myAddress = Address.from("localhost", atomixPort);
        this.mms = new NettyMessagingService(
                "server",
                myAddress,
                new MessagingConfig());
        this.logReader = new LogReader("./testdb.sql.log");
        this.isPaused = false;
    }

    /**
     * Called from atomix clientListener, used to get the correct response from the extended
     * It is handled locally and doesnt need replication
     * server
     * @param message The body Message received
     * @return the message body of the response
     */
    public abstract Message handleMessage(Message message);

    /**
     * Called from atomix clientListener, used to get the writeSet after application custom preprocessors
     * the request and then it is passed down the certification pipeline
     * server
     * @param message The body Message received
     * @return the message body of the response
     */
    //TODO: Converter para WriteMessage visto que só aceita write message
    public abstract CertifyWriteMessage<W,?> handleTransactionMessage(TransactionMessage<?> message);

    /**
     * Called from handleCertifierAnswer when a certified write operation arrived at a replicated server.
     * Needs to persist the incoming changes
     * server
     * @param message contains the state to persist
     */
    public abstract void updateStateFromCommitedWrite(CertifyWriteMessage<W,?> message);

    /**
     * Called from handleCertifierAnswer when a write request was made and is considered valid.
     * Needs to make changes effective
     * server
     */
    public abstract void commit();

    /**
     * Called from handleCertifierAnswer when a write request was made and is considered invalid.
     * Needs to cancel the transaction
     * server
     */
    public abstract void rollback();


    /**
     * Get of the state of the current Server
     * @return the state of the current Server
     */
    @Deprecated
    public abstract STATE getState();

    /**
     * Set of the state of the Server, used for extended classes to update their state, called when secondary server
     * receives the updated version
     * @param state the updated state of the server
     */
    @Deprecated
    public abstract void setState(STATE state);

    public void pause() {
        isPaused = true;
    }

    public void unpause() {
        isPaused = false;
    }

    /**
     * Set of tables for certifier module
     */
    public void addTablesToCertifier(List<String> tables){
        this.replicationService.certifier.addTables(tables);
    }

    public Collection<String> getQueries(int from){
        try {
            return logReader.getQueries(from);
        } catch (Exception ex) {
            ex.printStackTrace();
            //alterar
            return new LinkedList<>();
        }
    }

    public void updateQueries(Collection<String> queries){
        try {
            System.out.println("Updating queries (size: " + queries.size() + ")");
            Connection c = DriverManager.getConnection("jdbc:hsqldb:file:db/"+ privateName +";shutdown=true;hsqldb.sqllog=2;sql.syntax_mys=true", "", "");
            for(String querie : queries) {
                c.prepareStatement(querie).execute();
                System.out.println("querie: " + querie);
            }
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Starts the server communication listeners. First it starts the ClusterReplicationService so that a replica that is
     * joining the server reaches a consistent state. When on a consistent state the server can listen to clients requests
     * server
     */
    //TODO pode ouvir os pedidos dos clientes mesmo sem estar consistente e guarda-os,
    // mas é discutível, porque um cliente pode ligar-se a outro e talvez ser atendido mais rapidamente
    @Override
    public void start() throws Exception {
        replicationService.start().thenAccept(x -> {
            startClientListener();
            System.out.println("Server : " + privateName + " Primary Server initialized");
        });
        runningCompletable.get();
    }

    /**
     * Called from regularMessageListener in ClusterReplicationServer when a write request passed through the certification
     * process and got answered.
     * server
     */
    protected void handleCertifierAnswer(CertifyWriteMessage<W,?> message, boolean isWritable) throws NoTableDefinedException {
        CompletableFuture<Boolean> res;
        try{
            rl.lock();
            res = this.pendingWrites.get(message.getId());
        }finally {
            rl.unlock();
        }
        // se for null então não foi iniciada a escrita neste servidor
        if(res == null){
            if(isWritable) {
                updateStateFromCommitedWrite(message);
                //assumimos que a função anterior não falha..senão está tudo perdido...talvez resolver com os acks
                //Só é incrementado o timestamp e dado o "commit" quando as mudanças estão feitas na BD
                System.out.println("Server " + privateName + " commiting to certifier");
                replicationService.certifier.commit(message.getWriteSets());
            }
        }
        else{
            //Eu acho que a execução do res após o complete continua na thread do spread ....terá de ser assim para funcionar
            //Chama a callback de onde foi iniciado o pedido de escrita
            res.complete(isWritable);
        }

    }






    /**
     * Called from clientListener handler when a transactionMessage arrived. Starts a transaction by getting the current starting
     * timestamp and pre-processing the request.
     * server
     * @param cwm message
     * @return the information necessary to certify and replicate the transaction that is for now in a transient state
     */
    private CertifyWriteMessage<W,?> startTransaction(CertifyWriteMessage<W,?> cwm) throws NoTableDefinedException {
        long ts = replicationService.certifier.getTimestamp();
        replicationService.certifier.transactionStarted(cwm.getTables(), ts, cwm.getId());
        cwm.setTimestamp(ts);
        //TODO tirar o sleep após testes
        try {
            Thread.sleep(5000);
        } catch (InterruptedException interruptedException) {
            interruptedException.printStackTrace();
        }
        return cwm;
    }

    /**
     * Called from clientListener handler when a transactionMessage arrived. Tries to commit a transient transaction and also
     * formulates logic of the incoming answer
     * server
     * @param cwm message necessary for the certification and replication of the transaction
     * @param requester address of the requester
     */
    private void tryCommit(Address requester, CertifyWriteMessage<W, ?> cwm) throws Exception {
        CompletableFuture<Boolean> res = new CompletableFuture<>();
        try {
            rl.lock();
            this.pendingWrites.put(cwm.getId(), res);
        } finally {
            rl.unlock();
        }
        res.thenAccept(isWritable -> {
            if (isWritable) {
                //Primeiro persiste o estado
                System.out.println("Server " + privateName + " flushing write to db");
                commit();
                //Atualiza as transações que foram feitas
                System.out.println("Server " + privateName + " commiting to certifier");
                try {
                    replicationService.certifier.commitLocalStartedTransaction(cwm.getWriteSets(), cwm.getStartTimestamp(), cwm.getId());
                } catch (NoTableDefinedException e) {
                    e.printStackTrace();
                }
            } else {
                System.out.println("Server " + privateName + " rolling back write from db");
                rollback();
            }
            Message message = new ContentMessage<>(isWritable);
            System.out.println("Sending response message: " + message);
            sendReply(message, requester);
        });
        System.out.println("Server: " + privateName + " trying to commit " + cwm.getId());
        replicationService.floodMessage(cwm);
    }

    private void sendReply(Message message, Address address) {
        mms.sendAsync(address, "reply", s.encode(message)).whenComplete((m, t) -> {
            if (t != null) {
                t.printStackTrace();
            }
        });
    }


    /**
     * Provides middleware logic to receive and reply to clients requests.
     * server
     */
    public void startClientListener(){
        this.mms.start();
        mms.registerHandler("request", (requester,b) -> {
            System.out.println("\uD83D\uDE02");
            if(isPaused)
                return;

            Message request = s.decode(b);
            try {
                if(request instanceof TransactionMessage) {
                    System.out.println("Server " + privateName + " handling the request with group members, certification needed");
                    tryCommit(requester, startTransaction(handleTransactionMessage((TransactionMessage<?>) request)));
                }
                else if(request instanceof WriteMessage) {
                    System.out.println("Server " + privateName + " handling the request with group members");
                    Message reply = handleMessage(request);
                    sendReply(reply, requester);
                    replicationService.floodMessage(request);
                }
                else {
                    System.out.println("Server " + privateName + " handling the request locally");
                    System.out.println("??");
                    Message reply = handleMessage(request);
                    sendReply(reply, requester);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        },e);
    }

    @Override
    public void stop() {
        this.runningCompletable.complete(null);
    }

    public String getPrivateName() {
        return privateName;
    }
}
