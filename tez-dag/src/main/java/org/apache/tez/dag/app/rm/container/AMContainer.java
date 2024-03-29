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

package org.apache.tez.dag.app.rm.container;

import java.util.List;

import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.tez.dag.records.TezTaskAttemptID;

public interface AMContainer extends EventHandler<AMContainerEvent>{
  
  public AMContainerState getState();
  public ContainerId getContainerId();
  public Container getContainer();
  //TODO Rename - CompletedTaskAttempts, ideally means FAILED / KILLED as well.
  public List<TezTaskAttemptID> getCompletedTaskAttempts();
  public TezTaskAttemptID getRunningTaskAttempt();
  public List<TezTaskAttemptID> getQueuedTaskAttempts();
  
  public int getShufflePort();
  
  // TODO Add a method to get the containers capabilities - to match taskAttempts.

}
