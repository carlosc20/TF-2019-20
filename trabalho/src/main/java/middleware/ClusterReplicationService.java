package middleware;

import middleware.certifier.OperationalSets;
import middleware.certifier.WriteSet;
import middleware.message.Message;
import middleware.message.replication.*;
import spread.*;

import java.net.InetAddress;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class ClusterReplicationService {
    private final int totalServers;
    private final String privateName;
    private final SpreadConnection spreadConnection;
    private final SpreadGroup spreadGroup;
    private final int port;
    private Connection dbConnection;

    //Caso sejam necessário acks poderá ser utilizada esta estrutura
    //private Map<String, List<Message>> cachedMessages;

    private final Initializer initializer;
    private final ElectionManager electionManager;
    private boolean imLeader;

    private ServerImpl<?> server;
    private CompletableFuture<Void> started;


    // safe delete
    private ScheduledExecutorService executor;

    private SpreadGroup[] members;
    private List<SpreadGroup> sdRequested; // SafeDeleteRequest sent to these members

    //Leader vars
    private boolean evicting;
    private List<Long> timestamps;
    private List<CompletableFuture<Void>> stateRequests;

    public ClusterReplicationService(int spreadPort, String privateName, ServerImpl<?> server, int totalServers, Connection connection){
        this.totalServers = totalServers;
        this.privateName = privateName;
        this.port = spreadPort;
        this.spreadGroup = new SpreadGroup();
        this.spreadConnection = new SpreadConnection();
        this.server = server;
        this.initializer = new Initializer(server, this, connection, privateName);
        this.dbConnection = connection;

        //this.cachedMessages = new HashMap<>();
        //TODO recover do estado

        this.electionManager = new ElectionManager(this.spreadGroup);
        this.imLeader = false;

        this.evicting = false;
        this.timestamps = new ArrayList<>();
        this.stateRequests = new ArrayList<>();
        this.executor = Executors.newScheduledThreadPool(1);
    }


    public CompletableFuture<Void> start() throws Exception {
        this.spreadConnection.connect(InetAddress.getByName("localhost"), port, this.privateName,
                false, true);
        this.spreadGroup.join(this.spreadConnection, "grupo");
        this.spreadConnection.add(messageListener());
        this.started = new CompletableFuture<>();
        return started;
    }

    public void stop() throws SpreadException {
        this.spreadConnection.disconnect();
    }


    public Connection getDbConnection() {
        return dbConnection;
    }

    // ###################################################################
    // Messaging Utilities
    // ###################################################################

    public void floodMessage(Message msg) throws Exception {
        safeFloodMessage(msg, this.spreadGroup);
    }

    //safe ou agreed?
    public void safeFloodMessage(Message message, SpreadGroup sg) throws Exception{
        SpreadMessage m = new SpreadMessage();
        m.addGroup(sg);
        m.setObject(message);
        m.setSafe();
        spreadConnection.multicast(m);
        System.out.println("Safe flooding to group ("+ this.spreadGroup+ "): ");
    }

    /**
     * Used to respond to all Servers in the current spread group
     * @param message the body message that should be passed to all Servers
     * @throws Exception
     */
    @Deprecated
    public void noAgreementFloodMessage(Message message) throws Exception{
        noAgreementFloodMessage(message, this.spreadGroup);
    }

    /**
     * Used to respond to all Servers connected in the corresponding spread group
     * @param message
     * @param sg
     * @throws Exception
     */
    public void noAgreementFloodMessage(Message message, SpreadGroup sg) throws Exception{
        SpreadMessage m = new SpreadMessage();
        m.addGroup(sg);
        m.setObject(message);
        m.setReliable();
        spreadConnection.multicast(m);
        //System.out.println("Sending to group ("+ sg + "): " + message);
    }


    // ###################################################################
    // Listener
    // ###################################################################

    /**
     * Method used to respond to the Sender the message defined in the handleMessage abstract method
     * @param spreadMessage
     */
    //TODO corrigir.  por no initializer?
    private final Consumer<SpreadMessage> respondMessage = (spreadMessage) -> {
        try {
            Message received = (Message) spreadMessage.getObject();
            Message response = server.handleMessage(received).from(received);
            noAgreementFloodMessage(response, spreadMessage.getSender());
        } catch (Exception e) {
            e.printStackTrace();
        }
    };




    public AdvancedMessageListener messageListener() {
        return new AdvancedMessageListener() {
            @Override
            public void regularMessageReceived(SpreadMessage spreadMessage) {
                try {
                    if (!initializer.isInitializing(spreadMessage, respondMessage)) {
                        if(!started.isDone())
                            started.complete(null);

                        Message received = (Message) spreadMessage.getObject();
                        if(received instanceof CertifyWriteMessage){
                            handleCertifyWriteMessage((CertifyWriteMessage<?>) received);

                        } else if(received instanceof SafeDeleteRequestMessage){
                            // enviado pelo líder a todos os membros no evento de GarbageCollection do certifier
                            handleSafeDeleteRequestMessage(spreadMessage.getSender());

                        } else if(received instanceof SafeDeleteReplyMessage && imLeader) {
                            // resposta á SafeDeleteRequestMessage
                            handleSafeDeleteReplyMessage((SafeDeleteReplyMessage) received);

                        } else if (received instanceof SafeDeleteMessage) {
                            // enviada pelo líder depois de obter as respostas ao SafeDeleteRequest
                            handleSafeDeleteMessage((SafeDeleteMessage) received);

                        } else if (received instanceof SendTimeStampMessage){
                            // enviada pelo líder depois de receber o timestamp do GetTimeStampMessage
                            handleSendTimeStampMessage((SendTimeStampMessage) received, spreadMessage.getSender());
                        }
                        /*
                        cachedMessages.putIfAbsent(received.getId(), new ArrayList<>());
                        List<Message> messagesReceived = cachedMessages.get(received.getId());
                        // se for escrita terá de vir o estado já calculado para ser genérico
                        Message myResponse = server.handleMessage(received).from(received);
                        messagesReceived.add(myResponse);
                        if (messagesReceived.size() >= nServers) {
                            pendingMessages.get(received.getId()).complete(myResponse);
                        }
                        */
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            @Override
            public void membershipMessageReceived(SpreadMessage spreadMessage) {
                try {
                    MembershipInfo info = spreadMessage.getMembershipInfo();

                    if(info.isRegularMembership()) {
                        members = info.getMembers();
                    } else return;

                    if(info.isCausedByJoin()) {
                        handleJoin(info);
                    }
                    else if(info.isCausedByNetwork()) {
                        handleNetworkPartition(info);
                    }
                    else if(info.isCausedByDisconnect()) {
                        handleDisconnect(info);
                    }
                    else if(info.isCausedByLeave()) {
                        handleLeave(info);
                    }
                } catch(Exception e){
                    e.printStackTrace();
                }
            }
        };
    }


    // ###################################################################
    // Handling MembershipMessage
    // ###################################################################

    private boolean isInMainPartition(SpreadGroup[] partition) {
        return partition.length > totalServers/2;
    }

    private void handleNetworkPartition(MembershipInfo info) {
        SpreadGroup[] stayed = info.getStayed(); // usar getMembers?
        System.out.println(privateName + ": MembershipMessage received -> network partition");
        System.out.println(privateName + ": Partition members: " + Arrays.toString(stayed));
        if(isInMainPartition(stayed)) {
            System.out.println(privateName + ": I'm in main partition");
            if(!imLeader) {
                imLeader = electionManager.amILeader(stayed);

                if (imLeader){
                    scheduleSafeDeleteEvent();
                    System.out.println(privateName + ": Assuming leader role");
                }
            }
        } else {
            System.out.println(privateName + ": I'm not in main partition, pausing server");
            server.pause();
            initializer.reset();
        }
    }

    private void handleJoin(MembershipInfo info) throws Exception {
        SpreadGroup newMember = info.getJoined();
        if(newMember.equals(spreadConnection.getPrivateGroup())) {
            handleSelfJoin(info);
            return;
        }
        System.out.println(privateName + ": MembershipMessage received -> join");
        System.out.println(privateName + ": Member: " + newMember);
        if(imLeader) {
            System.out.println(privateName + ": I'm leader. Requesting state diff from " + newMember);
            //TODO URGENTISSIMO -> ENVIAR ESTADO CORRETO. De momento não funciona
            //TODO enviar apenas o que interessa do certifier
            Message message = new GetTimeStampMessage();
            noAgreementFloodMessage(message, newMember);
        }
    }

    private void handleSelfJoin(MembershipInfo info) {
        System.out.println(privateName + ": MembershipMessage received -> self join");
        if(isInMainPartition(info.getMembers()))
            server.unpause();

        SpreadGroup[] members = info.getMembers();
        electionManager.joinedGroup(members);

        if (members.length == 1) {
            System.out.println(privateName + ": I'm the first member");
            // é o primeiro servidor, não precisa de transferência de estado
            initializer.initialized();
            imLeader = true;
            scheduleSafeDeleteEvent();
            if (!started.isDone())
                started.complete(null);
        }
    }


    private void handleDisconnect(MembershipInfo info) {
        SpreadGroup member = info.getDisconnected();
        System.out.println(privateName + ": MembershipMessage received -> disconnect");
        handleDL(member);
    }

    private void handleLeave(MembershipInfo info) {
        SpreadGroup member = info.getLeft();
        System.out.println(privateName + ": MembershipMessage received -> left");
        handleDL(member);
    }

    private void handleDL(SpreadGroup member) {
        imLeader = electionManager.amILeader(member);
        System.out.println(privateName + ": Member: " + member);
        if (imLeader){
            System.out.println(privateName + ": Assuming leader role");
            scheduleSafeDeleteEvent();
            if(evicting) {
                sdRequested.remove(member);
            }
        }
    }


    // ###################################################################
    // Handling RegularMessage
    // ###################################################################

    private void handleCertifyWriteMessage(CertifyWriteMessage<?> cwm) {
        System.out.println(privateName + ": RegularMessage received -> CertifyWrite");
        System.out.println(privateName + ": write id: " + cwm.getId() + " message with timestamp: " + cwm.getStartTimestamp());
        server.handleCertifierAnswer(cwm);
    }

    private void handleStateLengthReplyMessage(StateLengthReplyMessage msg, SpreadGroup sender) {
        System.out.println(privateName + ": RegularMessage received -> StateLengthReply");
        ReplicaLatestState rls = msg.getBody();
        System.out.println(privateName + ": Logs lower bound = " + rls.getLowerBound());
        CompletableFuture<Void> response = new CompletableFuture<>();
        response.thenAccept((x) -> {
            try {
                server.getState(rls.getLowerBound(), rls.getLatestTimestamp()).thenAccept((fullState) -> {
                        Message m = new StateTransferMessage(fullState);
                        try {
                            noAgreementFloodMessage(m, sender);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        if (this.evicting)
            this.stateRequests.add(response);
        else
            response.complete(null);
    }

    private void handleSafeDeleteRequestMessage(SpreadGroup sender) {
        System.out.println(privateName + ": RegularMessage received -> SafeDeleteRequest");
        server.getSafeToDeleteTimestamp()
            .thenAccept((ts) -> {
                SafeDeleteReplyMessage reply = new SafeDeleteReplyMessage(ts);
                sdRequested = Arrays.asList(members);
                try {
                    noAgreementFloodMessage(reply, sender);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
    }

    private void handleSafeDeleteReplyMessage(SafeDeleteReplyMessage msg) throws Exception {
        System.out.println(privateName + ": RegularMessage received -> SafeDeleteReply");
        long ts = msg.getTs();
        timestamps.add(ts);
        int arrived = timestamps.size();
        if (arrived >= sdRequested.size()) {
            long minTs = Collections.min(timestamps);
            SafeDeleteMessage sdmsg = new SafeDeleteMessage(minTs);
            floodMessage(sdmsg); // TODO enviar apenas aos sdRequested
        }
    }

    private void handleSafeDeleteMessage(SafeDeleteMessage msg) {
        System.out.println(privateName + ": RegularMessage received -> SafeDelete");
        long ts = msg.getTs();
        server.evictStoredWriteSets(ts).thenAccept((x) -> {
            evicting = false;
            this.stateRequests.forEach(req -> req.complete(null));
            this.stateRequests.clear();
        });
    }

    private void handleSendTimeStampMessage(SendTimeStampMessage msg, SpreadGroup sender) throws Exception {
        long timeStamp = msg.getBody();
        System.out.println("Handling SendTimeStampMessage");
        String script = null;
        long currentLowWaterMark = server.getCertifier().getLowWaterMark();
        long currentTimeStamp = server.getCertifier().getTimestamp();
        HashMap<String, HashMap<Long, OperationalSets>> writes = new HashMap<>();
        ArrayList<String> queries = server.getLogReader().getLogsAfter(timeStamp);
        HashMap<String, HashMap<Long, OperationalSets>> tables = server.getCertifier().getWritesPerTable();
        if(queries.size() == 0){
            script = Files.readString(FileSystems.getDefault().getPath("db/" + server.getPrivateName() + ".log"));
        }
        if(currentLowWaterMark < timeStamp){
            writes = tables;
        } else {
            for(String table : tables.keySet()){
                writes.put(table, new HashMap<>());
                for(Long tableTimestamp: tables.get(table).keySet()){
                    if(tableTimestamp > timeStamp) {
                        OperationalSets write = writes.get(table).get(tableTimestamp);
                        writes.get(table).put(tableTimestamp, write);
                    }
                }
            }
        }
        Message response = new DBReplicationMessage(script, queries, currentLowWaterMark, currentTimeStamp, writes);
        noAgreementFloodMessage(response, sender);
    }




    // ###################################################################
    // Safe Delete
    // ###################################################################



    private class SafeDeleteEvent implements Runnable {
        @Override
        public void run() {
            if (!imLeader) return;
            SafeDeleteRequestMessage msg = new SafeDeleteRequestMessage();
            try {
                evicting = true;
                noAgreementFloodMessage(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void scheduleSafeDeleteEvent() {
        long minutesUntilSafeDelete = 1000;
        executor.schedule(new SafeDeleteEvent(), minutesUntilSafeDelete, TimeUnit.MINUTES);
    }




}