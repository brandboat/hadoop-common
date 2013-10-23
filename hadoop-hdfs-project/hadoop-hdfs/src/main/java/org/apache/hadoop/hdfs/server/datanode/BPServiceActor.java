/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode;

import static org.apache.hadoop.util.Time.now;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.DFSUtil;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.UnregisteredNodeException;
import org.apache.hadoop.hdfs.protocolPB.DatanodeProtocolClientSideTranslatorPB;
import org.apache.hadoop.hdfs.server.common.IncorrectVersionException;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.protocol.DatanodeCommand;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.hdfs.server.protocol.DisallowedDatanodeException;
import org.apache.hadoop.hdfs.server.protocol.HeartbeatResponse;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.hdfs.server.protocol.ReceivedDeletedBlockInfo;
import org.apache.hadoop.hdfs.server.protocol.StorageBlockReport;
import org.apache.hadoop.hdfs.server.protocol.StorageReceivedDeletedBlocks;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.util.Time;
import org.apache.hadoop.util.VersionInfo;
import org.apache.hadoop.util.VersionUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;

/**
 * A thread per active or standby namenode to perform:
 * <ul>
 * <li> Pre-registration handshake with namenode</li>
 * <li> Registration with namenode</li>
 * <li> Send periodic heartbeats to the namenode</li>
 * <li> Handle commands received from the namenode</li>
 * </ul>
 */
@InterfaceAudience.Private
class BPServiceActor implements Runnable {
  
  static final Log LOG = DataNode.LOG;
  final InetSocketAddress nnAddr;

  BPOfferService bpos;
  
  // lastBlockReport, lastDeletedReport and lastHeartbeat may be assigned/read
  // by testing threads (through BPServiceActor#triggerXXX), while also 
  // assigned/read by the actor thread. Thus they should be declared as volatile
  // to make sure the "happens-before" consistency.
  volatile long lastBlockReport = 0;
  volatile long lastDeletedReport = 0;

  boolean resetBlockReportTime = true;

  Thread bpThread;
  DatanodeProtocolClientSideTranslatorPB bpNamenode;
  private volatile long lastHeartbeat = 0;
  private volatile boolean initialized = false;
  
  /**
   * Between block reports (which happen on the order of once an hour) the
   * DN reports smaller incremental changes to its block list. This map,
   * keyed by block ID, contains the pending changes which have yet to be
   * reported to the NN. Access should be synchronized on this object.
   */
  private final Map<String, PerStoragePendingIncrementalBR>
      pendingIncrementalBRperStorage = Maps.newConcurrentMap();

  private volatile int pendingReceivedRequests = 0;
  private volatile boolean shouldServiceRun = true;
  private final DataNode dn;
  private final DNConf dnConf;

  private DatanodeRegistration bpRegistration;

  BPServiceActor(InetSocketAddress nnAddr, BPOfferService bpos) {
    this.bpos = bpos;
    this.dn = bpos.getDataNode();
    this.nnAddr = nnAddr;
    this.dnConf = dn.getDnConf();
  }

  /**
   * returns true if BP thread has completed initialization of storage
   * and has registered with the corresponding namenode
   * @return true if initialized
   */
  boolean isInitialized() {
    return initialized;
  }
  
  boolean isAlive() {
    return shouldServiceRun && bpThread.isAlive();
  }

  @Override
  public String toString() {
    return bpos.toString() + " service to " + nnAddr;
  }
  
  InetSocketAddress getNNSocketAddress() {
    return nnAddr;
  }

  /**
   * Used to inject a spy NN in the unit tests.
   */
  @VisibleForTesting
  void setNameNode(DatanodeProtocolClientSideTranslatorPB dnProtocol) {
    bpNamenode = dnProtocol;
  }

  @VisibleForTesting
  DatanodeProtocolClientSideTranslatorPB getNameNodeProxy() {
    return bpNamenode;
  }

  /**
   * Perform the first part of the handshake with the NameNode.
   * This calls <code>versionRequest</code> to determine the NN's
   * namespace and version info. It automatically retries until
   * the NN responds or the DN is shutting down.
   * 
   * @return the NamespaceInfo
   */
  @VisibleForTesting
  NamespaceInfo retrieveNamespaceInfo() throws IOException {
    NamespaceInfo nsInfo = null;
    while (shouldRun()) {
      try {
        nsInfo = bpNamenode.versionRequest();
        LOG.debug(this + " received versionRequest response: " + nsInfo);
        break;
      } catch(SocketTimeoutException e) {  // namenode is busy
        LOG.warn("Problem connecting to server: " + nnAddr);
      } catch(IOException e ) {  // namenode is not available
        LOG.warn("Problem connecting to server: " + nnAddr);
      }
      
      // try again in a second
      sleepAndLogInterrupts(5000, "requesting version info from NN");
    }
    
    if (nsInfo != null) {
      checkNNVersion(nsInfo);
    } else {
      throw new IOException("DN shut down before block pool connected");
    }
    return nsInfo;
  }

  private void checkNNVersion(NamespaceInfo nsInfo)
      throws IncorrectVersionException {
    // build and layout versions should match
    String nnVersion = nsInfo.getSoftwareVersion();
    String minimumNameNodeVersion = dnConf.getMinimumNameNodeVersion();
    if (VersionUtil.compareVersions(nnVersion, minimumNameNodeVersion) < 0) {
      IncorrectVersionException ive = new IncorrectVersionException(
          minimumNameNodeVersion, nnVersion, "NameNode", "DataNode");
      LOG.warn(ive.getMessage());
      throw ive;
    }
    String dnVersion = VersionInfo.getVersion();
    if (!nnVersion.equals(dnVersion)) {
      LOG.info("Reported NameNode version '" + nnVersion + "' does not match " +
          "DataNode version '" + dnVersion + "' but is within acceptable " +
          "limits. Note: This is normal during a rolling upgrade.");
    }

    if (HdfsConstants.LAYOUT_VERSION != nsInfo.getLayoutVersion()) {
      LOG.warn("DataNode and NameNode layout versions must be the same." +
        " Expected: "+ HdfsConstants.LAYOUT_VERSION +
        " actual "+ nsInfo.getLayoutVersion());
      throw new IncorrectVersionException(
          nsInfo.getLayoutVersion(), "namenode");
    }
  }

  private void connectToNNAndHandshake() throws IOException {
    // get NN proxy
    bpNamenode = dn.connectToNN(nnAddr);

    // First phase of the handshake with NN - get the namespace
    // info.
    NamespaceInfo nsInfo = retrieveNamespaceInfo();
    
    // Verify that this matches the other NN in this HA pair.
    // This also initializes our block pool in the DN if we are
    // the first NN connection for this BP.
    bpos.verifyAndSetNamespaceInfo(nsInfo);
    
    // Second phase of the handshake with the NN.
    register();
  }
  
  /**
   * This methods  arranges for the data node to send the block report at 
   * the next heartbeat.
   */
  void scheduleBlockReport(long delay) {
    if (delay > 0) { // send BR after random delay
      lastBlockReport = Time.now()
      - ( dnConf.blockReportInterval - DFSUtil.getRandom().nextInt((int)(delay)));
    } else { // send at next heartbeat
      lastBlockReport = lastHeartbeat - dnConf.blockReportInterval;
    }
    resetBlockReportTime = true; // reset future BRs for randomness
  }

  void reportBadBlocks(ExtendedBlock block) {
    if (bpRegistration == null) {
      return;
    }
    DatanodeInfo[] dnArr = { new DatanodeInfo(bpRegistration) };
    LocatedBlock[] blocks = { new LocatedBlock(block, dnArr) }; 
    
    try {
      bpNamenode.reportBadBlocks(blocks);  
    } catch (IOException e){
      /* One common reason is that NameNode could be in safe mode.
       * Should we keep on retrying in that case?
       */
      LOG.warn("Failed to report bad block " + block + " to namenode : "
          + " Exception", e);
    }
  }
  
  /**
   * Report received blocks and delete hints to the Namenode
   * TODO: Fix reportReceivedDeletedBlocks to send reports per-volume.
   * 
   * @throws IOException
   */
  private void reportReceivedDeletedBlocks() throws IOException {
    // For each storage, check if there are newly received blocks and if
    // so then send an incremental report to the NameNode.
    for (Map.Entry<String, PerStoragePendingIncrementalBR> entry :
        pendingIncrementalBRperStorage.entrySet()) {
      final String storageUuid = entry.getKey();
      final PerStoragePendingIncrementalBR perStorageMap = entry.getValue();
      ReceivedDeletedBlockInfo[] receivedAndDeletedBlockArray = null;
      // TODO: We can probably use finer-grained synchronization now.
      synchronized (pendingIncrementalBRperStorage) {
        if (perStorageMap.getBlockInfoCount() > 0) {
          // Send newly-received and deleted blockids to namenode
          receivedAndDeletedBlockArray = perStorageMap.dequeueBlockInfos();
          pendingReceivedRequests -= receivedAndDeletedBlockArray.length;
        }
      }

      if (receivedAndDeletedBlockArray != null) {
        StorageReceivedDeletedBlocks[] report = { new StorageReceivedDeletedBlocks(
            storageUuid, receivedAndDeletedBlockArray) };
        boolean success = false;
        try {
          bpNamenode.blockReceivedAndDeleted(bpRegistration, bpos.getBlockPoolId(),
              report);
          success = true;
        } finally {
          synchronized (pendingIncrementalBRperStorage) {
            if (!success) {
              // If we didn't succeed in sending the report, put all of the
              // blocks back onto our queue, but only in the case where we
              // didn't put something newer in the meantime.
              perStorageMap.putMissingBlockInfos(receivedAndDeletedBlockArray);
              pendingReceivedRequests += perStorageMap.getBlockInfoCount();
            }
          }
        }
      }
    }
  }

  /**
   * Retrieve the incremental BR state for a given storage UUID
   * @param storageUuid
   * @return
   */
  private PerStoragePendingIncrementalBR getIncrementalBRMapForStorage(
      String storageUuid) {
    PerStoragePendingIncrementalBR mapForStorage =
        pendingIncrementalBRperStorage.get(storageUuid);

    if (mapForStorage == null) {
      // This is the first time we are adding incremental BR state for
      // this storage so create a new map. This is required once per
      // storage, per service actor.
      mapForStorage = new PerStoragePendingIncrementalBR();
      pendingIncrementalBRperStorage.put(storageUuid, mapForStorage);
    }

    return mapForStorage;
  }

  /*
   * Informing the name node could take a long long time! Should we wait
   * till namenode is informed before responding with success to the
   * client? For now we don't.
   */
  void notifyNamenodeBlockImmediately(
      ReceivedDeletedBlockInfo bInfo, String storageUuid) {
    synchronized (pendingIncrementalBRperStorage) {
      getIncrementalBRMapForStorage(storageUuid).putBlockInfo(bInfo);
      pendingReceivedRequests++;
      pendingIncrementalBRperStorage.notifyAll();
    }
  }

  void notifyNamenodeDeletedBlock(
      ReceivedDeletedBlockInfo bInfo, String storageUuid) {
    synchronized (pendingIncrementalBRperStorage) {
      getIncrementalBRMapForStorage(storageUuid).putBlockInfo(bInfo);
    }
  }

  /**
   * Run an immediate block report on this thread. Used by tests.
   */
  @VisibleForTesting
  void triggerBlockReportForTests() {
    synchronized (pendingIncrementalBRperStorage) {
      lastBlockReport = 0;
      lastHeartbeat = 0;
      pendingIncrementalBRperStorage.notifyAll();
      while (lastBlockReport == 0) {
        try {
          pendingIncrementalBRperStorage.wait(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }
  
  @VisibleForTesting
  void triggerHeartbeatForTests() {
    synchronized (pendingIncrementalBRperStorage) {
      lastHeartbeat = 0;
      pendingIncrementalBRperStorage.notifyAll();
      while (lastHeartbeat == 0) {
        try {
          pendingIncrementalBRperStorage.wait(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  @VisibleForTesting
  void triggerDeletionReportForTests() {
    synchronized (pendingIncrementalBRperStorage) {
      lastDeletedReport = 0;
      pendingIncrementalBRperStorage.notifyAll();

      while (lastDeletedReport == 0) {
        try {
          pendingIncrementalBRperStorage.wait(100);
        } catch (InterruptedException e) {
          return;
        }
      }
    }
  }

  /**
   * Report the list blocks to the Namenode
   * @throws IOException
   */
  DatanodeCommand blockReport() throws IOException {
    // send block report if timer has expired.
    DatanodeCommand cmd = null;
    long startTime = now();
    if (startTime - lastBlockReport > dnConf.blockReportInterval) {

      // Flush any block information that precedes the block report. Otherwise
      // we have a chance that we will miss the delHint information
      // or we will report an RBW replica after the BlockReport already reports
      // a FINALIZED one.
      reportReceivedDeletedBlocks();

      // Send one block report per known storage.

      // Create block report
      long brCreateStartTime = now();
      long totalBlockCount = 0;

      Map<String, BlockListAsLongs> perVolumeBlockLists =
          dn.getFSDataset().getBlockReports(bpos.getBlockPoolId());

      // Send block report
      long brSendStartTime = now();
      StorageBlockReport[] reports =
          new StorageBlockReport[perVolumeBlockLists.size()];

      int i = 0;
      for(Map.Entry<String, BlockListAsLongs> kvPair : perVolumeBlockLists.entrySet()) {
        String storageID = kvPair.getKey();
        BlockListAsLongs blockList = kvPair.getValue();
        totalBlockCount += blockList.getNumberOfBlocks();

        // Dummy DatanodeStorage object just for sending the block report.
        DatanodeStorage dnStorage = new DatanodeStorage(storageID);
        reports[i++] =
            new StorageBlockReport(
              dnStorage, blockList.getBlockListAsLongs());
      }

      cmd = bpNamenode.blockReport(bpRegistration, bpos.getBlockPoolId(), reports);

      // Log the block report processing stats from Datanode perspective
      long brSendCost = now() - brSendStartTime;
      long brCreateCost = brSendStartTime - brCreateStartTime;
      dn.getMetrics().addBlockReport(brSendCost);
      LOG.info("BlockReport of " + totalBlockCount
          + " blocks took " + brCreateCost + " msec to generate and "
          + brSendCost + " msecs for RPC and NN processing");

      // If we have sent the first block report, then wait a random
      // time before we start the periodic block reports.
      if (resetBlockReportTime) {
        lastBlockReport = startTime - DFSUtil.getRandom().nextInt((int)(dnConf.blockReportInterval));
        resetBlockReportTime = false;
      } else {
        /* say the last block report was at 8:20:14. The current report
         * should have started around 9:20:14 (default 1 hour interval).
         * If current time is :
         *   1) normal like 9:20:18, next report should be at 10:20:14
         *   2) unexpected like 11:35:43, next report should be at 12:20:14
         */
        lastBlockReport += (now() - lastBlockReport) /
        dnConf.blockReportInterval * dnConf.blockReportInterval;
      }
      LOG.info("sent block report, processed command:" + cmd);
    }
    return cmd;
  }
  
  
  HeartbeatResponse sendHeartBeat() throws IOException {
    StorageReport[] reports =
        dn.getFSDataset().getStorageReports(bpos.getBlockPoolId());
    if (LOG.isDebugEnabled()) {
      LOG.debug("Sending heartbeat with " + reports.length +
                " storage reports from service actor: " + this);
    }

    return bpNamenode.sendHeartbeat(bpRegistration,
        reports,
        dn.getXmitsInProgress(),
        dn.getXceiverCount(),
        dn.getFSDataset().getNumFailedVolumes());
  }
  
  //This must be called only by BPOfferService
  void start() {
    if ((bpThread != null) && (bpThread.isAlive())) {
      //Thread is started already
      return;
    }
    bpThread = new Thread(this, formatThreadName());
    bpThread.setDaemon(true); // needed for JUnit testing
    bpThread.start();
  }
  
  private String formatThreadName() {
    Collection<StorageLocation> dataDirs =
        DataNode.getStorageLocations(dn.getConf());
    return "DataNode: [" + dataDirs.toString() + "] " +
      " heartbeating to " + nnAddr;
  }
  
  //This must be called only by blockPoolManager.
  void stop() {
    shouldServiceRun = false;
    if (bpThread != null) {
        bpThread.interrupt();
    }
  }
  
  //This must be called only by blockPoolManager
  void join() {
    try {
      if (bpThread != null) {
        bpThread.join();
      }
    } catch (InterruptedException ie) { }
  }
  
  //Cleanup method to be called by current thread before exiting.
  private synchronized void cleanUp() {
    
    shouldServiceRun = false;
    IOUtils.cleanup(LOG, bpNamenode);
    bpos.shutdownActor(this);
  }

  /**
   * Main loop for each BP thread. Run until shutdown,
   * forever calling remote NameNode functions.
   */
  private void offerService() throws Exception {
    LOG.info("For namenode " + nnAddr + " using DELETEREPORT_INTERVAL of "
        + dnConf.deleteReportInterval + " msec " + " BLOCKREPORT_INTERVAL of "
        + dnConf.blockReportInterval + "msec" + " Initial delay: "
        + dnConf.initialBlockReportDelay + "msec" + "; heartBeatInterval="
        + dnConf.heartBeatInterval);

    //
    // Now loop for a long time....
    //
    while (shouldRun()) {
      try {
        long startTime = now();

        //
        // Every so often, send heartbeat or block-report
        //
        if (startTime - lastHeartbeat >= dnConf.heartBeatInterval) {
          //
          // All heartbeat messages include following info:
          // -- Datanode name
          // -- data transfer port
          // -- Total capacity
          // -- Bytes remaining
          //
          lastHeartbeat = startTime;
          if (!dn.areHeartbeatsDisabledForTests()) {
            HeartbeatResponse resp = sendHeartBeat();
            assert resp != null;
            dn.getMetrics().addHeartbeat(now() - startTime);

            // If the state of this NN has changed (eg STANDBY->ACTIVE)
            // then let the BPOfferService update itself.
            //
            // Important that this happens before processCommand below,
            // since the first heartbeat to a new active might have commands
            // that we should actually process.
            bpos.updateActorStatesFromHeartbeat(
                this, resp.getNameNodeHaState());

            long startProcessCommands = now();
            if (!processCommand(resp.getCommands()))
              continue;
            long endProcessCommands = now();
            if (endProcessCommands - startProcessCommands > 2000) {
              LOG.info("Took " + (endProcessCommands - startProcessCommands)
                  + "ms to process " + resp.getCommands().length
                  + " commands from NN");
            }
          }
        }
        if (pendingReceivedRequests > 0
            || (startTime - lastDeletedReport > dnConf.deleteReportInterval)) {
          reportReceivedDeletedBlocks();
          lastDeletedReport = startTime;
        }

        DatanodeCommand cmd = blockReport();
        processCommand(new DatanodeCommand[]{ cmd });

        // Now safe to start scanning the block pool.
        // If it has already been started, this is a no-op.
        if (dn.blockScanner != null) {
          dn.blockScanner.addBlockPool(bpos.getBlockPoolId());
        }

        //
        // There is no work to do;  sleep until hearbeat timer elapses, 
        // or work arrives, and then iterate again.
        //
        long waitTime = dnConf.heartBeatInterval - 
        (Time.now() - lastHeartbeat);
        synchronized(pendingIncrementalBRperStorage) {
          if (waitTime > 0 && pendingReceivedRequests == 0) {
            try {
              pendingIncrementalBRperStorage.wait(waitTime);
            } catch (InterruptedException ie) {
              LOG.warn("BPOfferService for " + this + " interrupted");
            }
          }
        } // synchronized
      } catch(RemoteException re) {
        String reClass = re.getClassName();
        if (UnregisteredNodeException.class.getName().equals(reClass) ||
            DisallowedDatanodeException.class.getName().equals(reClass) ||
            IncorrectVersionException.class.getName().equals(reClass)) {
          LOG.warn(this + " is shutting down", re);
          shouldServiceRun = false;
          return;
        }
        LOG.warn("RemoteException in offerService", re);
        try {
          long sleepTime = Math.min(1000, dnConf.heartBeatInterval);
          Thread.sleep(sleepTime);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
        }
      } catch (IOException e) {
        LOG.warn("IOException in offerService", e);
      }
    } // while (shouldRun())
  } // offerService

  /**
   * Register one bp with the corresponding NameNode
   * <p>
   * The bpDatanode needs to register with the namenode on startup in order
   * 1) to report which storage it is serving now and 
   * 2) to receive a registrationID
   *  
   * issued by the namenode to recognize registered datanodes.
   * 
   * @see FSNamesystem#registerDatanode(DatanodeRegistration)
   * @throws IOException
   */
  void register() throws IOException {
    // The handshake() phase loaded the block pool storage
    // off disk - so update the bpRegistration object from that info
    bpRegistration = bpos.createRegistration();

    LOG.info(this + " beginning handshake with NN");

    while (shouldRun()) {
      try {
        // Use returned registration from namenode with updated fields
        bpRegistration = bpNamenode.registerDatanode(bpRegistration);
        break;
      } catch(SocketTimeoutException e) {  // namenode is busy
        LOG.info("Problem connecting to server: " + nnAddr);
        sleepAndLogInterrupts(1000, "connecting to server");
      }
    }
    
    LOG.info("Block pool " + this + " successfully registered with NN");
    bpos.registrationSucceeded(this, bpRegistration);

    // random short delay - helps scatter the BR from all DNs
    scheduleBlockReport(dnConf.initialBlockReportDelay);
  }


  private void sleepAndLogInterrupts(int millis,
      String stateString) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ie) {
      LOG.info("BPOfferService " + this + " interrupted while " + stateString);
    }
  }

  /**
   * No matter what kind of exception we get, keep retrying to offerService().
   * That's the loop that connects to the NameNode and provides basic DataNode
   * functionality.
   *
   * Only stop when "shouldRun" or "shouldServiceRun" is turned off, which can
   * happen either at shutdown or due to refreshNamenodes.
   */
  @Override
  public void run() {
    LOG.info(this + " starting to offer service");

    try {
      // init stuff
      try {
        // setup storage
        connectToNNAndHandshake();
      } catch (IOException ioe) {
        // Initial handshake, storage recovery or registration failed
        // End BPOfferService thread
        LOG.fatal("Initialization failed for block pool " + this, ioe);
        return;
      }

      initialized = true; // bp is initialized;
      
      while (shouldRun()) {
        try {
          offerService();
        } catch (Exception ex) {
          LOG.error("Exception in BPOfferService for " + this, ex);
          sleepAndLogInterrupts(5000, "offering service");
        }
      }
    } catch (Throwable ex) {
      LOG.warn("Unexpected exception in block pool " + this, ex);
    } finally {
      LOG.warn("Ending block pool service for: " + this);
      cleanUp();
    }
  }

  private boolean shouldRun() {
    return shouldServiceRun && dn.shouldRun();
  }

  /**
   * Process an array of datanode commands
   * 
   * @param cmds an array of datanode commands
   * @return true if further processing may be required or false otherwise. 
   */
  boolean processCommand(DatanodeCommand[] cmds) {
    if (cmds != null) {
      for (DatanodeCommand cmd : cmds) {
        try {
          if (bpos.processCommandFromActor(cmd, this) == false) {
            return false;
          }
        } catch (IOException ioe) {
          LOG.warn("Error processing datanode Command", ioe);
        }
      }
    }
    return true;
  }

  void trySendErrorReport(int errCode, String errMsg) {
    try {
      bpNamenode.errorReport(bpRegistration, errCode, errMsg);
    } catch(IOException e) {
      LOG.warn("Error reporting an error to NameNode " + nnAddr,
          e);
    }
  }

  /**
   * Report a bad block from another DN in this cluster.
   */
  void reportRemoteBadBlock(DatanodeInfo dnInfo, ExtendedBlock block)
      throws IOException {
    LocatedBlock lb = new LocatedBlock(block, 
                                    new DatanodeInfo[] {dnInfo});
    bpNamenode.reportBadBlocks(new LocatedBlock[] {lb});
  }

  void reRegister() throws IOException {
    if (shouldRun()) {
      // re-retrieve namespace info to make sure that, if the NN
      // was restarted, we still match its version (HDFS-2120)
      retrieveNamespaceInfo();
      // and re-register
      register();
    }
  }

  private static class PerStoragePendingIncrementalBR {
    private Map<Long, ReceivedDeletedBlockInfo> pendingIncrementalBR =
        Maps.newHashMap();

    /**
     * Return the number of blocks on this storage that have pending
     * incremental block reports.
     * @return
     */
    int getBlockInfoCount() {
      return pendingIncrementalBR.size();
    }

    /**
     * Dequeue and return all pending incremental block report state.
     * @return
     */
    ReceivedDeletedBlockInfo[] dequeueBlockInfos() {
      ReceivedDeletedBlockInfo[] blockInfos =
          pendingIncrementalBR.values().toArray(
              new ReceivedDeletedBlockInfo[getBlockInfoCount()]);

      pendingIncrementalBR.clear();
      return blockInfos;
    }

    /**
     * Add blocks from blockArray to pendingIncrementalBR, unless the
     * block already exists in pendingIncrementalBR.
     * @param blockArray list of blocks to add.
     */
    void putMissingBlockInfos(ReceivedDeletedBlockInfo[] blockArray) {
      for (ReceivedDeletedBlockInfo rdbi : blockArray) {
        if (!pendingIncrementalBR.containsKey(rdbi.getBlock().getBlockId())) {
          pendingIncrementalBR.put(rdbi.getBlock().getBlockId(), rdbi);
        }
      }
    }

    /**
     * Add pending incremental block report for a single block.
     * @param blockID
     * @param blockInfo
     */
    void putBlockInfo(ReceivedDeletedBlockInfo blockInfo) {
      pendingIncrementalBR.put(blockInfo.getBlock().getBlockId(), blockInfo);
    }
  }
}
