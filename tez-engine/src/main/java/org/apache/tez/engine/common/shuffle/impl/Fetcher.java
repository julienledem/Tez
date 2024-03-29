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
package org.apache.tez.engine.common.shuffle.impl;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.SecretKey;
import javax.net.ssl.HttpsURLConnection;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.security.ssl.SSLFactory;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.tez.common.IDUtils;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.TezTaskReporter;
import org.apache.tez.common.counters.TezCounter;
import org.apache.tez.dag.records.TezTaskAttemptID;
import org.apache.tez.engine.common.ConfigUtils;
import org.apache.tez.engine.common.security.SecureShuffleUtils;
import org.apache.tez.engine.common.shuffle.impl.MapOutput.Type;
import org.apache.tez.engine.common.sort.impl.IFileInputStream;

import com.google.common.annotations.VisibleForTesting;

class Fetcher extends Thread {
  
  private static final Log LOG = LogFactory.getLog(Fetcher.class);
  
  /** Basic/unit connection timeout (in milliseconds) */
  private final static int UNIT_CONNECT_TIMEOUT = 60 * 1000;
  
  private final Progressable reporter;
  private static enum ShuffleErrors{IO_ERROR, WRONG_LENGTH, BAD_ID, WRONG_MAP,
                                    CONNECTION, WRONG_REDUCE}
  
  private final static String SHUFFLE_ERR_GRP_NAME = "Shuffle Errors";
  private final TezCounter connectionErrs;
  private final TezCounter ioErrs;
  private final TezCounter wrongLengthErrs;
  private final TezCounter badIdErrs;
  private final TezCounter wrongMapErrs;
  private final TezCounter wrongReduceErrs;
  private final MergeManager merger;
  private final ShuffleScheduler scheduler;
  private final ShuffleClientMetrics metrics;
  private final ExceptionReporter exceptionReporter;
  private final int id;
  private static int nextId = 0;
  private final int reduce;
  
  private final int connectionTimeout;
  private final int readTimeout;
  
  // Decompression of map-outputs
  private final CompressionCodec codec;
  private final Decompressor decompressor;
  private final SecretKey jobTokenSecret;

  private volatile boolean stopped = false;

  private Configuration job;

  private static boolean sslShuffle;
  private static SSLFactory sslFactory;

  public Fetcher(Configuration job, TezTaskAttemptID reduceId, 
      ShuffleScheduler scheduler, MergeManager merger,
      TezTaskReporter reporter, ShuffleClientMetrics metrics,
      ExceptionReporter exceptionReporter, SecretKey jobTokenSecret) {
    this.job = job;
    this.reporter = reporter;
    this.scheduler = scheduler;
    this.merger = merger;
    this.metrics = metrics;
    this.exceptionReporter = exceptionReporter;
    this.id = ++nextId;
    this.reduce = reduceId.getTaskID().getId();
    this.jobTokenSecret = jobTokenSecret;
    ioErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.IO_ERROR.toString());
    wrongLengthErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.WRONG_LENGTH.toString());
    badIdErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.BAD_ID.toString());
    wrongMapErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.WRONG_MAP.toString());
    connectionErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.CONNECTION.toString());
    wrongReduceErrs = reporter.getCounter(SHUFFLE_ERR_GRP_NAME,
        ShuffleErrors.WRONG_REDUCE.toString());

    if (ConfigUtils.isIntermediateInputCompressed(job)) {
      Class<? extends CompressionCodec> codecClass =
          ConfigUtils.getIntermediateInputCompressorClass(job, DefaultCodec.class);
      codec = ReflectionUtils.newInstance(codecClass, job);
      decompressor = CodecPool.getDecompressor(codec);
    } else {
      codec = null;
      decompressor = null;
    }

    this.connectionTimeout = 
        job.getInt(TezJobConfig.TEZ_ENGINE_SHUFFLE_CONNECT_TIMEOUT,
            TezJobConfig.DEFAULT_TEZ_ENGINE_SHUFFLE_STALLED_COPY_TIMEOUT);
    this.readTimeout = 
        job.getInt(TezJobConfig.TEZ_ENGINE_SHUFFLE_READ_TIMEOUT, 
            TezJobConfig.DEFAULT_TEZ_ENGINE_SHUFFLE_READ_TIMEOUT);

    setName("fetcher#" + id);
    setDaemon(true);

    synchronized (Fetcher.class) {
      sslShuffle = job.getBoolean(TezJobConfig.TEZ_ENGINE_SHUFFLE_ENABLE_SSL,
          TezJobConfig.DEFAULT_TEZ_ENGINE_SHUFFLE_ENABLE_SSL);
      if (sslShuffle && sslFactory == null) {
        sslFactory = new SSLFactory(SSLFactory.Mode.CLIENT, job);
        try {
          sslFactory.init();
        } catch (Exception ex) {
          sslFactory.destroy();
          throw new RuntimeException(ex);
        }
      }
    }
  }
  public void run() {
    try {
      while (!stopped && !Thread.currentThread().isInterrupted()) {
        MapHost host = null;
        try {
          // If merge is on, block
          merger.waitForInMemoryMerge();

          // Get a host to shuffle from
          host = scheduler.getHost();
          metrics.threadBusy();

          // Shuffle
          copyFromHost(host);
        } finally {
          if (host != null) {
            scheduler.freeHost(host);
            metrics.threadFree();            
          }
        }
      }
    } catch (InterruptedException ie) {
      return;
    } catch (Throwable t) {
      exceptionReporter.reportException(t);
    }
  }

  public void shutDown() throws InterruptedException {
    this.stopped = true;
    interrupt();
    try {
      join(5000);
    } catch (InterruptedException ie) {
      LOG.warn("Got interrupt while joining " + getName(), ie);
    }
    if (sslFactory != null) {
      sslFactory.destroy();
    }
  }

  @VisibleForTesting
  protected HttpURLConnection openConnection(URL url) throws IOException {
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    if (sslShuffle) {
      HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
      try {
        httpsConn.setSSLSocketFactory(sslFactory.createSSLSocketFactory());
      } catch (GeneralSecurityException ex) {
        throw new IOException(ex);
      }
      httpsConn.setHostnameVerifier(sslFactory.getHostnameVerifier());
    }
    return conn;
  }
  
  /**
   * The crux of the matter...
   * 
   * @param host {@link MapHost} from which we need to  
   *              shuffle available map-outputs.
   */
  @VisibleForTesting
  protected void copyFromHost(MapHost host) throws IOException {
    // Get completed maps on 'host'
    List<TezTaskAttemptID> maps = scheduler.getMapsForHost(host);
    
    // Sanity check to catch hosts with only 'OBSOLETE' maps, 
    // especially at the tail of large jobs
    if (maps.size() == 0) {
      return;
    }
    
    if(LOG.isDebugEnabled()) {
      LOG.debug("Fetcher " + id + " going to fetch from " + host + " for: "
        + maps);
    }
    
    // List of maps to be fetched yet
    Set<TezTaskAttemptID> remaining = new HashSet<TezTaskAttemptID>(maps);
    
    // Construct the url and connect
    DataInputStream input;
    boolean connectSucceeded = false;
    
    try {
      URL url = getMapOutputURL(host, maps);
      HttpURLConnection connection = openConnection(url);
      
      // generate hash of the url
      String msgToEncode = SecureShuffleUtils.buildMsgFrom(url);
      String encHash = SecureShuffleUtils.hashFromString(msgToEncode, jobTokenSecret);
      
      // put url hash into http header
      connection.addRequestProperty(
          SecureShuffleUtils.HTTP_HEADER_URL_HASH, encHash);
      // set the read timeout
      connection.setReadTimeout(readTimeout);
      connect(connection, connectionTimeout);
      connectSucceeded = true;
      input = new DataInputStream(connection.getInputStream());

      // Validate response code
      int rc = connection.getResponseCode();
      if (rc != HttpURLConnection.HTTP_OK) {
        throw new IOException(
            "Got invalid response code " + rc + " from " + url +
            ": " + connection.getResponseMessage());
      }
      
      // get the replyHash which is HMac of the encHash we sent to the server
      String replyHash = connection.getHeaderField(SecureShuffleUtils.HTTP_HEADER_REPLY_URL_HASH);
      if(replyHash==null) {
        throw new IOException("security validation of TT Map output failed");
      }
      LOG.debug("url="+msgToEncode+";encHash="+encHash+";replyHash="+replyHash);
      // verify that replyHash is HMac of encHash
      SecureShuffleUtils.verifyReply(replyHash, encHash, jobTokenSecret);
      LOG.info("for url="+msgToEncode+" sent hash and receievd reply");
    } catch (IOException ie) {
      ioErrs.increment(1);
      LOG.warn("Failed to connect to " + host + " with " + remaining.size() + 
               " map outputs", ie);

      // If connect did not succeed, just mark all the maps as failed,
      // indirectly penalizing the host
      if (!connectSucceeded) {
        for(TezTaskAttemptID left: remaining) {
          scheduler.copyFailed(left, host, connectSucceeded);
        }
      } else {
        // If we got a read error at this stage, it implies there was a problem
        // with the first map, typically lost map. So, penalize only that map
        // and add the rest
        TezTaskAttemptID firstMap = maps.get(0);
        scheduler.copyFailed(firstMap, host, connectSucceeded);
      }
      
      // Add back all the remaining maps, WITHOUT marking them as failed
      for(TezTaskAttemptID left: remaining) {
        scheduler.putBackKnownMapOutput(host, left);
      }
      
      return;
    }
    
    try {
      // Loop through available map-outputs and fetch them
      // On any error, faildTasks is not null and we exit
      // after putting back the remaining maps to the 
      // yet_to_be_fetched list and marking the failed tasks.
      TezTaskAttemptID[] failedTasks = null;
      while (!remaining.isEmpty() && failedTasks == null) {
        failedTasks = copyMapOutput(host, input, remaining);
      }
      
      if(failedTasks != null && failedTasks.length > 0) {
        LOG.warn("copyMapOutput failed for tasks "+Arrays.toString(failedTasks));
        for(TezTaskAttemptID left: failedTasks) {
          scheduler.copyFailed(left, host, true);
        }
      }
      
      IOUtils.cleanup(LOG, input);
      
      // Sanity check
      if (failedTasks == null && !remaining.isEmpty()) {
        throw new IOException("server didn't return all expected map outputs: "
            + remaining.size() + " left.");
      }
    } finally {
      for (TezTaskAttemptID left : remaining) {
        scheduler.putBackKnownMapOutput(host, left);
      }
    }
  }
  
  private static TezTaskAttemptID[] EMPTY_ATTEMPT_ID_ARRAY = new TezTaskAttemptID[0];
  
  private TezTaskAttemptID[] copyMapOutput(MapHost host,
                                DataInputStream input,
                                Set<TezTaskAttemptID> remaining) {
    MapOutput mapOutput = null;
    TezTaskAttemptID mapId = null;
    long decompressedLength = -1;
    long compressedLength = -1;
    
    try {
      long startTime = System.currentTimeMillis();
      int forReduce = -1;
      //Read the shuffle header
      try {
        ShuffleHeader header = new ShuffleHeader();
        header.readFields(input);
        mapId = IDUtils.toTaskAttemptId(header.mapId);
        compressedLength = header.compressedLength;
        decompressedLength = header.uncompressedLength;
        forReduce = header.forReduce;
      } catch (IllegalArgumentException e) {
        badIdErrs.increment(1);
        LOG.warn("Invalid map id ", e);
        //Don't know which one was bad, so consider all of them as bad
        return remaining.toArray(new TezTaskAttemptID[remaining.size()]);
      }

 
      // Do some basic sanity verification
      if (!verifySanity(compressedLength, decompressedLength, forReduce,
          remaining, mapId)) {
        return new TezTaskAttemptID[] {mapId};
      }
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("header: " + mapId + ", len: " + compressedLength + 
            ", decomp len: " + decompressedLength);
      }
      
      // Get the location for the map output - either in-memory or on-disk
      mapOutput = merger.reserve(mapId, decompressedLength, id);
      
      // Check if we can shuffle *now* ...
      if (mapOutput.getType() == Type.WAIT) {
        LOG.info("fetcher#" + id + " - MergerManager returned Status.WAIT ...");
        //Not an error but wait to process data.
        return EMPTY_ATTEMPT_ID_ARRAY;
      } 
      
      // Go!
      LOG.info("fetcher#" + id + " about to shuffle output of map " + 
               mapOutput.getMapId() + " decomp: " +
               decompressedLength + " len: " + compressedLength + " to " +
               mapOutput.getType());
      if (mapOutput.getType() == Type.MEMORY) {
        shuffleToMemory(host, mapOutput, input, 
                        (int) decompressedLength, (int) compressedLength);
      } else {
        shuffleToDisk(host, mapOutput, input, compressedLength);
      }
      
      // Inform the shuffle scheduler
      long endTime = System.currentTimeMillis();
      scheduler.copySucceeded(mapId, host, compressedLength, 
                              endTime - startTime, mapOutput);
      // Note successful shuffle
      remaining.remove(mapId);
      metrics.successFetch();
      return null;
    } catch (IOException ioe) {
      ioErrs.increment(1);
      if (mapId == null || mapOutput == null) {
        LOG.info("fetcher#" + id + " failed to read map header" + 
                 mapId + " decomp: " + 
                 decompressedLength + ", " + compressedLength, ioe);
        if(mapId == null) {
          return remaining.toArray(new TezTaskAttemptID[remaining.size()]);
        } else {
          return new TezTaskAttemptID[] {mapId};
        }
      }
      
      LOG.warn("Failed to shuffle output of " + mapId + 
               " from " + host.getHostName(), ioe); 

      // Inform the shuffle-scheduler
      mapOutput.abort();
      metrics.failedFetch();
      return new TezTaskAttemptID[] {mapId};
    }

  }
  
  /**
   * Do some basic verification on the input received -- Being defensive
   * @param compressedLength
   * @param decompressedLength
   * @param forReduce
   * @param remaining
   * @param mapId
   * @return true/false, based on if the verification succeeded or not
   */
  private boolean verifySanity(long compressedLength, long decompressedLength,
      int forReduce, Set<TezTaskAttemptID> remaining, TezTaskAttemptID mapId) {
    if (compressedLength < 0 || decompressedLength < 0) {
      wrongLengthErrs.increment(1);
      LOG.warn(getName() + " invalid lengths in map output header: id: " +
               mapId + " len: " + compressedLength + ", decomp len: " + 
               decompressedLength);
      return false;
    }
    
    if (forReduce != reduce) {
      wrongReduceErrs.increment(1);
      LOG.warn(getName() + " data for the wrong reduce map: " +
               mapId + " len: " + compressedLength + " decomp len: " +
               decompressedLength + " for reduce " + forReduce);
      return false;
    }

    // Sanity check
    if (!remaining.contains(mapId)) {
      wrongMapErrs.increment(1);
      LOG.warn("Invalid map-output! Received output for " + mapId);
      return false;
    }
    
    return true;
  }

  /**
   * Create the map-output-url. This will contain all the map ids
   * separated by commas
   * @param host
   * @param maps
   * @return
   * @throws MalformedURLException
   */
  private URL getMapOutputURL(MapHost host, List<TezTaskAttemptID> maps
                              )  throws MalformedURLException {
    // Get the base url
    StringBuffer url = new StringBuffer(host.getBaseUrl());
    
    boolean first = true;
    for (TezTaskAttemptID mapId : maps) {
      if (!first) {
        url.append(",");
      }
      url.append(mapId);
      first = false;
    }
   
    if (LOG.isDebugEnabled()) {
      LOG.debug("MapOutput URL for " + host + " -> " + url.toString());
    }
    return new URL(url.toString());
  }
  
  /** 
   * The connection establishment is attempted multiple times and is given up 
   * only on the last failure. Instead of connecting with a timeout of 
   * X, we try connecting with a timeout of x < X but multiple times. 
   */
  private void connect(URLConnection connection, int connectionTimeout)
  throws IOException {
    int unit = 0;
    if (connectionTimeout < 0) {
      throw new IOException("Invalid timeout "
                            + "[timeout = " + connectionTimeout + " ms]");
    } else if (connectionTimeout > 0) {
      unit = Math.min(UNIT_CONNECT_TIMEOUT, connectionTimeout);
    }
    // set the connect timeout to the unit-connect-timeout
    connection.setConnectTimeout(unit);
    while (true) {
      try {
        connection.connect();
        break;
      } catch (IOException ioe) {
        // update the total remaining connect-timeout
        connectionTimeout -= unit;

        // throw an exception if we have waited for timeout amount of time
        // note that the updated value if timeout is used here
        if (connectionTimeout == 0) {
          throw ioe;
        }

        // reset the connect timeout for the last try
        if (connectionTimeout < unit) {
          unit = connectionTimeout;
          // reset the connect time out for the final connect
          connection.setConnectTimeout(unit);
        }
      }
    }
  }

  private void shuffleToMemory(MapHost host, MapOutput mapOutput, 
                               InputStream input, 
                               int decompressedLength, 
                               int compressedLength) throws IOException {    
    IFileInputStream checksumIn = 
      new IFileInputStream(input, compressedLength, job);

    input = checksumIn;       
  
    // Are map-outputs compressed?
    if (codec != null) {
      decompressor.reset();
      input = codec.createInputStream(input, decompressor);
    }
  
    // Copy map-output into an in-memory buffer
    byte[] shuffleData = mapOutput.getMemory();
    
    try {
      IOUtils.readFully(input, shuffleData, 0, shuffleData.length);
      metrics.inputBytes(shuffleData.length);
      reporter.progress();
      LOG.info("Read " + shuffleData.length + " bytes from map-output for " +
               mapOutput.getMapId());
    } catch (IOException ioe) {      
      // Close the streams
      IOUtils.cleanup(LOG, input);

      // Re-throw
      throw ioe;
    }

  }
  
  private void shuffleToDisk(MapHost host, MapOutput mapOutput, 
                             InputStream input, 
                             long compressedLength) 
  throws IOException {
    // Copy data to local-disk
    OutputStream output = mapOutput.getDisk();
    long bytesLeft = compressedLength;
    try {
      final int BYTES_TO_READ = 64 * 1024;
      byte[] buf = new byte[BYTES_TO_READ];
      while (bytesLeft > 0) {
        int n = input.read(buf, 0, (int) Math.min(bytesLeft, BYTES_TO_READ));
        if (n < 0) {
          throw new IOException("read past end of stream reading " + 
                                mapOutput.getMapId());
        }
        output.write(buf, 0, n);
        bytesLeft -= n;
        metrics.inputBytes(n);
        reporter.progress();
      }

      LOG.info("Read " + (compressedLength - bytesLeft) + 
               " bytes from map-output for " +
               mapOutput.getMapId());

      output.close();
    } catch (IOException ioe) {
      // Close the streams
      IOUtils.cleanup(LOG, input, output);

      // Re-throw
      throw ioe;
    }

    // Sanity check
    if (bytesLeft != 0) {
      throw new IOException("Incomplete map output received for " +
                            mapOutput.getMapId() + " from " +
                            host.getHostName() + " (" + 
                            bytesLeft + " bytes missing of " + 
                            compressedLength + ")"
      );
    }
  }
}
