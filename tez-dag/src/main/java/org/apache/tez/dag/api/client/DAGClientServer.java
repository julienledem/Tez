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

package org.apache.tez.dag.api.client;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RPC.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezException;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolBlockingPB;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolBlockingPBServerImpl;
import org.apache.tez.dag.api.client.rpc.DAGClientAMProtocolRPC.DAGClientAMProtocol;

import com.google.protobuf.BlockingService;

public class DAGClientServer extends AbstractService {
  static final Log LOG = LogFactory.getLog(DAGClientServer.class);
      
  DAGClient realInstance;
  Server server;
  InetSocketAddress bindAddress;

  public DAGClientServer(DAGClient realInstance) {
    super("DAGClientRPCServer");
    this.realInstance = realInstance;
  }
  
  @Override
  public void start() {
    try {
      assert getConfig() instanceof TezConfiguration;
      TezConfiguration conf = (TezConfiguration) getConfig();
      InetSocketAddress addr = new InetSocketAddress(0);
      
      DAGClientAMProtocolBlockingPBServerImpl service = 
          new DAGClientAMProtocolBlockingPBServerImpl(realInstance);
      
      BlockingService blockingService = 
                DAGClientAMProtocol.newReflectiveBlockingService(service);
      
      int numHandlers = conf.getInt(TezConfiguration.DAG_CLIENT_AM_THREAD_COUNT, 
                          TezConfiguration.DAG_CLIENT_AM__THREAD_COUNT_DEFAULT);
      
      String portRange = conf.get(TezConfiguration.DAG_CLIENT_AM_PORT_RANGE);
      
      server = createServer(DAGClientAMProtocolBlockingPB.class, addr, conf, 
                            numHandlers, blockingService, portRange);
      server.start();
      bindAddress = NetUtils.getConnectAddress(server);
      LOG.info("Instantiated DAGClientRPCServer at " + bindAddress);
      super.start();
    } catch (Exception e) {
      LOG.error("Failed to start DAGClientServer: ", e);
      throw new TezException(e);
    }
  }
  
  @Override
  public void stop() {
    if(server != null) {
      server.stop();
    }
    super.stop();
  }
  
  public InetSocketAddress getBindAddress() {
    return bindAddress;
  }
  
  private Server createServer(Class<?> pbProtocol, InetSocketAddress addr, Configuration conf, 
      int numHandlers, 
      BlockingService blockingService, String portRangeConfig) throws IOException {
    RPC.setProtocolEngine(conf, pbProtocol, ProtobufRpcEngine.class);
    RPC.Server server = new RPC.Builder(conf).setProtocol(pbProtocol)
        .setInstance(blockingService).setBindAddress(addr.getHostName())
        .setPort(addr.getPort()).setNumHandlers(numHandlers).setVerbose(false)
        .setPortRangeConfig(portRangeConfig)
        .build();
    server.addProtocol(RPC.RpcKind.RPC_PROTOCOL_BUFFER, pbProtocol, blockingService);
    return server;
  }
}
