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

package org.apache.tez.dag.app.rm;

import java.io.IOException;
import java.util.Collection;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.service.Service;

@InterfaceAudience.Public
@InterfaceStability.Unstable
public interface AMRMClient<T extends AMRMClient.ContainerRequest> extends Service {

  /**
   * Object to represent container request for resources.
   * Resources may be localized to nodes and racks.
   * Resources may be assigned priorities.
   * Can ask for multiple containers of a given type.
   */
  public static class ContainerRequest {
    Resource capability;
    String[] hosts;
    String[] racks;
    Priority priority;
    int containerCount;
        
    public ContainerRequest(Resource capability, String[] hosts,
        String[] racks, Priority priority, int containerCount) {
      this.capability = capability;
      this.hosts = (hosts != null ? hosts.clone() : null);
      this.racks = (racks != null ? racks.clone() : null);
      this.priority = priority;
      this.containerCount = containerCount;
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("Capability[").append(capability).append("]");
      sb.append("Priority[").append(priority).append("]");
      sb.append("ContainerCount[").append(containerCount).append("]");
      return sb.toString();
    }
  }
  
  public static class StoredContainerRequest <T> extends ContainerRequest {
    T cookie;
    
    public StoredContainerRequest(Resource capability, String[] hosts,
        String[] racks, Priority priority) {
      super(capability, hosts, racks, priority, 1);
    }
    
    void setCookie(T cookie) {
      this.cookie = cookie;
    }
    
    T getCookie() {
      return cookie;
    }    
  }
  
  /**
   * Register the application master. This must be called before any 
   * other interaction
   * @param appHostName Name of the host on which master is running
   * @param appHostPort Port master is listening on
   * @param appTrackingUrl URL at which the master info can be seen
   * @return <code>RegisterApplicationMasterResponse</code>
   * @throws YarnRemoteException
   * @throws IOException
   */
  public RegisterApplicationMasterResponse 
               registerApplicationMaster(String appHostName,
                                         int appHostPort,
                                         String appTrackingUrl) 
               throws YarnRemoteException, IOException;
  
  /**
   * Request additional containers and receive new container allocations.
   * Requests made via <code>addContainerRequest</code> are sent to the 
   * <code>ResourceManager</code>. New containers assigned to the master are 
   * retrieved. Status of completed containers and node health updates are 
   * also retrieved.
   * This also doubles up as a heartbeat to the ResourceManager and must be 
   * made periodically.
   * The call may not always return any new allocations of containers.
   * App should not make concurrent allocate requests. May cause request loss.
   * @param progressIndicator Indicates progress made by the master
   * @return the response of the allocate request
   * @throws YarnRemoteException
   * @throws IOException
   */
  public AllocateResponse allocate(float progressIndicator) 
                           throws YarnRemoteException, IOException;
  
  /**
   * Unregister the application master. This must be called in the end.
   * @param appStatus Success/Failure status of the master
   * @param appMessage Diagnostics message on failure
   * @param appTrackingUrl New URL to get master info
   * @throws YarnRemoteException
   * @throws IOException
   */
  public void unregisterApplicationMaster(FinalApplicationStatus appStatus,
                                           String appMessage,
                                           String appTrackingUrl) 
               throws YarnRemoteException, IOException;
  
  /**
   * Request containers for resources before calling <code>allocate</code>
   * @param req Resource request
   */
  public void addContainerRequest(T req);
  
  /**
   * Remove previous container request. The previous container request may have 
   * already been sent to the ResourceManager. So even after the remove request 
   * the app must be prepared to receive an allocation for the previous request 
   * even after the remove request
   * @param req Resource request
   */
  public void removeContainerRequest(T req);
  
  /**
   * Release containers assigned by the Resource Manager. If the app cannot use
   * the container or wants to give up the container then it can release them.
   * The app needs to make new requests for the released resource capability if
   * it still needs it. eg. it released non-local resources
   * @param containerId
   */
  public void releaseAssignedContainer(ContainerId containerId);
  
  /**
   * Get the currently available resources in the cluster.
   * A valid value is available after a call to allocate has been made
   * @return Currently available resources
   */
  public Resource getClusterAvailableResources();
  
  /**
   * Get the current number of nodes in the cluster.
   * A valid values is available after a call to allocate has been made
   * @return Current number of nodes in the cluster
   */
  public int getClusterNodeCount();

  /**
   * Get outstanding <code>StoredContainerRequest</code>s matching the given 
   * parameters. These StoredContainerRequests should have been added via
   * <code>addContainerRequest</code> earlier in the lifecycle.
   */
  public Collection<T> getMatchingRequests(
                                     Priority priority, 
                                     String resourceName, 
                                     Resource capability);

}
