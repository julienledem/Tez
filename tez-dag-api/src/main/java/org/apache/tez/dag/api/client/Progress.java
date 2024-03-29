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

import org.apache.tez.dag.api.records.DAGProtos.ProgressProtoOrBuilder;

public class Progress {
  
  ProgressProtoOrBuilder proxy = null;
  
  Progress(ProgressProtoOrBuilder proxy) {
    this.proxy = proxy;
  }
  
  public int getTotalTaskCount() {
    return proxy.getTotalTaskCount();
  }

  public int getSucceededTaskCount() {
    return proxy.getSucceededTaskCount();
  }

  public int getRunningTaskCount() {
    return proxy.getRunningTaskCount();
  }

  public int getFailedTaskCount() {
    return proxy.getFailedTaskCount();
  }

  public int getKilledTaskCount() {
    return proxy.getKilledTaskCount();
  }
  
  @Override
  public String toString() {
    return new String("Total: " + getTotalTaskCount() +
                       " Succeeded: " + getSucceededTaskCount() +
                       " Running: " + getRunningTaskCount() + 
                       " Failed: " + getFailedTaskCount() + 
                       " Killed: " + getKilledTaskCount());
  }

}
