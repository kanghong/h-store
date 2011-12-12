package edu.mit.hstore.handlers;

import java.util.Collection;

import org.apache.log4j.Logger;
import org.voltdb.VoltTable;
import org.voltdb.messaging.FastDeserializer;

import ca.evanjones.protorpc.ProtoRpcController;

import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;

import edu.brown.hstore.Hstore;
import edu.brown.hstore.Hstore.HStoreService;
import edu.brown.hstore.Hstore.SendDataRequest;
import edu.brown.hstore.Hstore.SendDataResponse;
import edu.brown.logging.LoggerUtil;
import edu.brown.logging.LoggerUtil.LoggerBoolean;
import edu.mit.hstore.HStoreCoordinator;
import edu.mit.hstore.HStoreSite;
import edu.mit.hstore.dtxn.AbstractTransaction;
import edu.mit.hstore.dtxn.LocalTransaction;

public class SendDataHandler extends AbstractTransactionHandler<SendDataRequest, SendDataResponse> {
    private static final Logger LOG = Logger.getLogger(SendDataHandler.class);
    private static final LoggerBoolean debug = new LoggerBoolean(LOG.isDebugEnabled());
    private static final LoggerBoolean trace = new LoggerBoolean(LOG.isTraceEnabled());
    static {
        LoggerUtil.attachObserver(LOG, debug, trace);
    }
    
    //final Dispatcher<Object[]> MapDispatcher;
    
    public SendDataHandler(HStoreSite hstore_site, HStoreCoordinator hstore_coord) {
        super(hstore_site, hstore_coord);
    }
    
    @Override
    public void sendLocal(long txn_id, SendDataRequest request, Collection<Integer> partitions, RpcCallback<SendDataResponse> callback) {
        // We should never be called because we never want to have serialize/deserialize data
        // within our own process
        assert(false): this.getClass().getSimpleName() + ".sendLocal should never be called!";
    }
    @Override
    public void sendRemote(HStoreService channel, ProtoRpcController controller, SendDataRequest request, RpcCallback<SendDataResponse> callback) {
        channel.sendData(controller, request, callback);
    }
    @Override
    public void remoteQueue(RpcController controller, SendDataRequest request,
            RpcCallback<SendDataResponse> callback) {
        this.remoteHandler(controller, request, callback);
    }
    @Override
    public void remoteHandler(RpcController controller, SendDataRequest request,
            RpcCallback<SendDataResponse> callback) {
        assert(request.hasTransactionId()) : "Got Hstore." + request.getClass().getSimpleName() + " without a txn id!";
        long txn_id = request.getTransactionId();
        
        if (debug.get())
            LOG.debug("__FILE__:__LINE__ " + String.format("Got %s for txn #%d",
                                   request.getClass().getSimpleName(), txn_id));

        AbstractTransaction ts = hstore_site.getTransaction(txn_id);
        assert(ts != null) : "Unexpected MapReduce transaction #" + txn_id;

        Hstore.SendDataResponse.Builder builder = Hstore.SendDataResponse.newBuilder()
                                                             .setTransactionId(txn_id)
                                                             .setSenderId(hstore_site.getSiteId());
        
        for (Hstore.PartitionFragment frag : request.getFragmentsList()) {
            int partition = frag.getPartitionId();
            assert(hstore_site.getLocalPartitionIds().contains(partition));
            byte data[] = frag.toByteArray();
            
            assert(data != null);
            // Deserialize the VoltTable object for the given byte array
            VoltTable vt = null;
            if (debug.get())
                LOG.debug("__FILE__:__LINE__ " + String.format("Data length: %s",
                                       data.length));
            try {
                vt = FastDeserializer.deserialize(data, VoltTable.class);
                
            } catch (Exception ex) {
                //throw new RuntimeException("Unexpected error when deserializing VoltTable", ex);
            }
            assert(vt != null);
        
            Hstore.Status status = ts.storeData(partition, vt);
            if (status != Hstore.Status.OK) builder.setStatus(status);
            builder.addPartitions(partition);
        } // FOR
        
        callback.run(builder.build());
    }
    @Override
    protected ProtoRpcController getProtoRpcController(LocalTransaction ts, int site_id) {
        return ts.getTransactionWorkController(site_id);
    }
}
