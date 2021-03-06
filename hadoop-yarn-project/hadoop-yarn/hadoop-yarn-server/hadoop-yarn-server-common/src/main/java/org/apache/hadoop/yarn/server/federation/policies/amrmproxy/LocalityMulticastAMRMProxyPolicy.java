/*
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

package org.apache.hadoop.yarn.server.federation.policies.amrmproxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.server.federation.policies.FederationPolicyInitializationContext;
import org.apache.hadoop.yarn.server.federation.policies.dao.WeightedPolicyInfo;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.FederationPolicyInitializationException;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.NoActiveSubclustersException;
import org.apache.hadoop.yarn.server.federation.resolver.SubClusterResolver;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterId;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterIdInfo;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterInfo;
import org.apache.hadoop.yarn.server.federation.utils.FederationStateStoreFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * An implementation of the {@link FederationAMRMProxyPolicy} interface that
 * carefully multicasts the requests with the following behavior:
 *
 * <p>
 * Host localized {@link ResourceRequest}s are always forwarded to the RM that
 * owns the corresponding node, based on the feedback of a
 * {@link SubClusterResolver}. If the {@link SubClusterResolver} cannot resolve
 * this node we default to forwarding the {@link ResourceRequest} to the home
 * sub-cluster.
 * </p>
 *
 * <p>
 * Rack localized {@link ResourceRequest}s are forwarded to the RMs that owns
 * the corresponding rack. Note that in some deployments each rack could be
 * striped across multiple RMs. Thsi policy respects that. If the
 * {@link SubClusterResolver} cannot resolve this rack we default to forwarding
 * the {@link ResourceRequest} to the home sub-cluster.
 * </p>
 *
 * <p>
 * ANY requests corresponding to node/rack local requests are forwarded only to
 * the set of RMs that owns the corresponding localized requests. The number of
 * containers listed in each ANY is proportional to the number of localized
 * container requests (associated to this ANY via the same allocateRequestId).
 * </p>
 *
 * <p>
 * ANY that are not associated to node/rack local requests are split among RMs
 * based on the "weights" in the {@link WeightedPolicyInfo} configuration *and*
 * headroom information. The {@code headroomAlpha} parameter of the policy
 * configuration indicates how much headroom contributes to the splitting
 * choice. Value of 1.0f indicates the weights are interpreted only as 0/1
 * boolean but all splitting is based on the advertised headroom (fallback to
 * 1/N for RMs that we don't have headroom info from). An {@code headroomAlpha}
 * value of 0.0f means headroom is ignored and all splitting decisions are
 * proportional to the "weights" in the configuration of the policy.
 * </p>
 *
 * <p>
 * ANY of zero size are forwarded to all known subclusters (i.e., subclusters
 * where we scheduled containers before), as they may represent a user attempt
 * to cancel a previous request (and we are mostly stateless now, so should
 * forward to all known RMs).
 * </p>
 *
 * <p>
 * Invariants:
 * </p>
 *
 * <p>
 * The policy always excludes non-active RMs.
 * </p>
 *
 * <p>
 * The policy always excludes RMs that do not appear in the policy configuration
 * weights, or have a weight of 0 (even if localized resources explicit refer to
 * it).
 * </p>
 *
 * <p>
 * (Bar rounding to closest ceiling of fractional containers) The sum of
 * requests made to multiple RMs at the ANY level "adds-up" to the user request.
 * The maximum possible excess in a given request is a number of containers less
 * or equal to number of sub-clusters in the federation.
 * </p>
 */
public class LocalityMulticastAMRMProxyPolicy extends AbstractAMRMProxyPolicy {

  public static final Logger LOG =
      LoggerFactory.getLogger(LocalityMulticastAMRMProxyPolicy.class);

  private Map<SubClusterId, Float> weights;
  private SubClusterResolver resolver;

  private Map<SubClusterId, Resource> headroom;
  private float hrAlpha;
  private FederationStateStoreFacade federationFacade;
  private AllocationBookkeeper bookkeeper;
  private SubClusterId homeSubcluster;

  @Override
  public void reinitialize(
      FederationPolicyInitializationContext policyContext)
      throws FederationPolicyInitializationException {

    // save reference to old weights
    WeightedPolicyInfo tempPolicy = getPolicyInfo();

    super.reinitialize(policyContext);
    if (!getIsDirty()) {
      return;
    }

    Map<SubClusterId, Float> newWeightsConverted = new HashMap<>();
    boolean allInactive = true;
    WeightedPolicyInfo policy = getPolicyInfo();

    if (policy.getAMRMPolicyWeights() != null
        && policy.getAMRMPolicyWeights().size() > 0) {
      for (Map.Entry<SubClusterIdInfo, Float> e : policy.getAMRMPolicyWeights()
          .entrySet()) {
        if (e.getValue() > 0) {
          allInactive = false;
        }
        newWeightsConverted.put(e.getKey().toId(), e.getValue());
      }
    }
    if (allInactive) {
      // reset the policyInfo and throw
      setPolicyInfo(tempPolicy);
      throw new FederationPolicyInitializationException(
          "The weights used to configure "
              + "this policy are all set to zero! (no ResourceRequest could be "
              + "forwarded with this setting.)");
    }

    if (policyContext.getHomeSubcluster() == null) {
      setPolicyInfo(tempPolicy);
      throw new FederationPolicyInitializationException("The homeSubcluster "
          + "filed in the context must be initialized to use this policy");
    }

    weights = newWeightsConverted;
    resolver = policyContext.getFederationSubclusterResolver();

    if (headroom == null) {
      headroom = new ConcurrentHashMap<>();
    }
    hrAlpha = policy.getHeadroomAlpha();

    this.federationFacade =
        policyContext.getFederationStateStoreFacade();
    this.homeSubcluster = policyContext.getHomeSubcluster();

  }

  @Override
  public void notifyOfResponse(SubClusterId subClusterId,
      AllocateResponse response) throws YarnException {
    // stateless policy does not care about responses except tracking headroom
    headroom.put(subClusterId, response.getAvailableResources());
  }

  @Override
  public Map<SubClusterId, List<ResourceRequest>> splitResourceRequests(
      List<ResourceRequest> resourceRequests) throws YarnException {

    // object used to accumulate statistics about the answer, initialize with
    // active subclusters. Create a new instance per call because this method
    // can be called concurrently.
    bookkeeper = new AllocationBookkeeper();
    bookkeeper.reinitialize(federationFacade.getSubClusters(true));

    List<ResourceRequest> nonLocalizedRequests =
        new ArrayList<ResourceRequest>();

    SubClusterId targetId = null;
    Set<SubClusterId> targetIds = null;

    // if the RR is resolved to a local subcluster add it directly (node and
    // resolvable racks)
    for (ResourceRequest rr : resourceRequests) {
      targetId = null;
      targetIds = null;

      // Handle: ANY (accumulated for later)
      if (ResourceRequest.isAnyLocation(rr.getResourceName())) {
        nonLocalizedRequests.add(rr);
        continue;
      }

      // Handle "node" requests
      try {
        targetId = resolver.getSubClusterForNode(rr.getResourceName());
      } catch (YarnException e) {
        // this might happen as we can't differentiate node from rack names
        // we log altogether later
      }
      if (bookkeeper.isActiveAndEnabled(targetId)) {
        bookkeeper.addLocalizedNodeRR(targetId, rr);
        continue;
      }

      // Handle "rack" requests
      try {
        targetIds = resolver.getSubClustersForRack(rr.getResourceName());
      } catch (YarnException e) {
        // this might happen as we can't differentiate node from rack names
        // we log altogether later
      }
      if (targetIds != null && targetIds.size() > 0) {
        boolean hasActive = false;
        for (SubClusterId tid : targetIds) {
          if (bookkeeper.isActiveAndEnabled(tid)) {
            bookkeeper.addRackRR(tid, rr);
            hasActive = true;
          }
        }
        if (hasActive) {
          continue;
        }
      }

      // Handle node/rack requests that the SubClusterResolver cannot map to
      // any cluster. Defaulting to home subcluster.
      if (LOG.isDebugEnabled()) {
        LOG.debug("ERROR resolving sub-cluster for resourceName: "
            + rr.getResourceName() + " we are falling back to homeSubCluster:"
            + homeSubcluster);
      }

      // If home-subcluster is not active, ignore node/rack request
      if (bookkeeper.isActiveAndEnabled(homeSubcluster)) {
        if (targetIds != null && targetIds.size() > 0) {
          bookkeeper.addRackRR(homeSubcluster, rr);
        } else {
          bookkeeper.addLocalizedNodeRR(homeSubcluster, rr);
        }
      } else {
        if (LOG.isDebugEnabled()) {
          LOG.debug("The homeSubCluster (" + homeSubcluster + ") we are "
              + "defaulting to is not active, the ResourceRequest "
              + "will be ignored.");
        }
      }
    }

    // handle all non-localized requests (ANY)
    splitAnyRequests(nonLocalizedRequests, bookkeeper);

    return bookkeeper.getAnswer();
  }

  /**
   * It splits a list of non-localized resource requests among sub-clusters.
   */
  private void splitAnyRequests(List<ResourceRequest> originalResourceRequests,
      AllocationBookkeeper allocationBookkeeper) throws YarnException {

    for (ResourceRequest resourceRequest : originalResourceRequests) {

      // FIRST: pick the target set of subclusters (based on whether this RR
      // is associated with other localized requests via an allocationId)
      Long allocationId = resourceRequest.getAllocationRequestId();
      Set<SubClusterId> targetSubclusters;
      if (allocationBookkeeper.getSubClustersForId(allocationId) != null) {
        targetSubclusters =
            allocationBookkeeper.getSubClustersForId(allocationId);
      } else {
        targetSubclusters = allocationBookkeeper.getActiveAndEnabledSC();
      }

      // SECOND: pick how much to ask to each RM for each request
      splitIndividualAny(resourceRequest, targetSubclusters,
          allocationBookkeeper);
    }
  }

  /**
   * Return a projection of this ANY {@link ResourceRequest} that belongs to
   * this sub-cluster. This is done based on the "count" of the containers that
   * require locality in each sublcuster (if any) or based on the "weights" and
   * headroom.
   */
  private void splitIndividualAny(ResourceRequest originalResourceRequest,
      Set<SubClusterId> targetSubclusters,
      AllocationBookkeeper allocationBookkeeper) {

    long allocationId = originalResourceRequest.getAllocationRequestId();

    for (SubClusterId targetId : targetSubclusters) {
      float numContainer = originalResourceRequest.getNumContainers();

      // If the ANY request has 0 containers to begin with we must forward it to
      // any RM we have previously contacted (this might be the user way
      // to cancel a previous request).
      if (numContainer == 0 && headroom.containsKey(targetId)) {
        allocationBookkeeper.addAnyRR(targetId, originalResourceRequest);
      }

      // If ANY is associated with localized asks, split based on their ratio
      if (allocationBookkeeper.getSubClustersForId(allocationId) != null) {
        float localityBasedWeight = getLocalityBasedWeighting(allocationId,
            targetId, allocationBookkeeper);
        numContainer = numContainer * localityBasedWeight;
      } else {
        // split ANY based on load and policy configuration
        float headroomWeighting =
            getHeadroomWeighting(targetId, allocationBookkeeper);
        float policyWeighting =
            getPolicyConfigWeighting(targetId, allocationBookkeeper);
        // hrAlpha controls how much headroom influencing decision
        numContainer = numContainer
            * (hrAlpha * headroomWeighting + (1 - hrAlpha) * policyWeighting);
      }

      // if the calculated request is non-empty add it to the answer
      if (numContainer > 0) {
        ResourceRequest out =
            ResourceRequest.newInstance(originalResourceRequest.getPriority(),
                originalResourceRequest.getResourceName(),
                originalResourceRequest.getCapability(),
                originalResourceRequest.getNumContainers(),
                originalResourceRequest.getRelaxLocality(),
                originalResourceRequest.getNodeLabelExpression(),
                originalResourceRequest.getExecutionTypeRequest());
        out.setAllocationRequestId(allocationId);
        out.setNumContainers((int) Math.ceil(numContainer));
        if (ResourceRequest.isAnyLocation(out.getResourceName())) {
          allocationBookkeeper.addAnyRR(targetId, out);
        } else {
          allocationBookkeeper.addRackRR(targetId, out);
        }
      }
    }
  }

  /**
   * Compute the weight to assign to a subcluster based on how many local
   * requests a subcluster is target of.
   */
  private float getLocalityBasedWeighting(long reqId, SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {
    float totWeight = allocationBookkeeper.getTotNumLocalizedContainers(reqId);
    float localWeight =
        allocationBookkeeper.getNumLocalizedContainers(reqId, targetId);
    return totWeight > 0 ? localWeight / totWeight : 0;
  }

  /**
   * Compute the "weighting" to give to a sublcuster based on the configured
   * policy weights (for the active subclusters).
   */
  private float getPolicyConfigWeighting(SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {
    float totWeight = allocationBookkeeper.totPolicyWeight;
    Float localWeight = allocationBookkeeper.policyWeights.get(targetId);
    return (localWeight != null && totWeight > 0) ? localWeight / totWeight : 0;
  }

  /**
   * Compute the weighting based on available headroom. This is proportional to
   * the available headroom memory announced by RM, or to 1/N for RMs we have
   * not seen yet. If all RMs report zero headroom, we fallback to 1/N again.
   */
  private float getHeadroomWeighting(SubClusterId targetId,
      AllocationBookkeeper allocationBookkeeper) {

    // baseline weight for all RMs
    float headroomWeighting =
        1 / (float) allocationBookkeeper.getActiveAndEnabledSC().size();

    // if we have headroom infomration for this sub-cluster (and we are safe
    // from /0 issues)
    if (headroom.containsKey(targetId)
        && allocationBookkeeper.totHeadroomMemory > 0) {
      // compute which portion of the RMs that are active/enabled have reported
      // their headroom (needed as adjustment factor)
      // (note: getActiveAndEnabledSC should never be null/zero)
      float ratioHeadroomKnown = allocationBookkeeper.totHeadRoomEnabledRMs
          / (float) allocationBookkeeper.getActiveAndEnabledSC().size();

      // headroomWeighting is the ratio of headroom memory in the targetId
      // cluster / total memory. The ratioHeadroomKnown factor is applied to
      // adjust for missing information and ensure sum of allocated containers
      // closely approximate what the user asked (small excess).
      headroomWeighting = (headroom.get(targetId).getMemorySize()
          / allocationBookkeeper.totHeadroomMemory) * (ratioHeadroomKnown);
    }
    return headroomWeighting;
  }

  /**
   * This helper class is used to book-keep the requests made to each
   * subcluster, and maintain useful statistics to split ANY requests.
   */
  private final class AllocationBookkeeper {

    // the answer being accumulated
    private Map<SubClusterId, List<ResourceRequest>> answer = new TreeMap<>();

    // stores how many containers we have allocated in each RM for localized
    // asks, used to correctly "spread" the corresponding ANY
    private Map<Long, Map<SubClusterId, AtomicLong>> countContainersPerRM =
        new HashMap<>();
    private Map<Long, AtomicLong> totNumLocalizedContainers = new HashMap<>();

    private Set<SubClusterId> activeAndEnabledSC = new HashSet<>();
    private float totHeadroomMemory = 0;
    private int totHeadRoomEnabledRMs = 0;
    private Map<SubClusterId, Float> policyWeights;
    private float totPolicyWeight = 0;

    private void reinitialize(
        Map<SubClusterId, SubClusterInfo> activeSubclusters)
        throws YarnException {
      if (activeSubclusters == null) {
        throw new YarnRuntimeException("null activeSubclusters received");
      }

      // reset data structures
      answer.clear();
      countContainersPerRM.clear();
      totNumLocalizedContainers.clear();
      activeAndEnabledSC.clear();
      totHeadroomMemory = 0;
      totHeadRoomEnabledRMs = 0;
      // save the reference locally in case the weights get reinitialized
      // concurrently
      policyWeights = weights;
      totPolicyWeight = 0;

      // pre-compute the set of subclusters that are both active and enabled by
      // the policy weights, and accumulate their total weight
      for (Map.Entry<SubClusterId, Float> entry : policyWeights.entrySet()) {
        if (entry.getValue() > 0
            && activeSubclusters.containsKey(entry.getKey())) {
          activeAndEnabledSC.add(entry.getKey());
          totPolicyWeight += entry.getValue();
        }
      }

      if (activeAndEnabledSC.size() < 1) {
        throw new NoActiveSubclustersException(
            "None of the subclusters enabled in this policy (weight>0) are "
                + "currently active we cannot forward the ResourceRequest(s)");
      }

      // pre-compute headroom-based weights for active/enabled subclusters
      for (Map.Entry<SubClusterId, Resource> r : headroom.entrySet()) {
        if (activeAndEnabledSC.contains(r.getKey())) {
          totHeadroomMemory += r.getValue().getMemorySize();
          totHeadRoomEnabledRMs++;
        }
      }
    }

    /**
     * Add to the answer a localized node request, and keeps track of statistics
     * on a per-allocation-id and per-subcluster bases.
     */
    private void addLocalizedNodeRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(!ResourceRequest.isAnyLocation(rr.getResourceName()));

      if (!countContainersPerRM.containsKey(rr.getAllocationRequestId())) {
        countContainersPerRM.put(rr.getAllocationRequestId(), new HashMap<>());
      }
      if (!countContainersPerRM.get(rr.getAllocationRequestId())
          .containsKey(targetId)) {
        countContainersPerRM.get(rr.getAllocationRequestId()).put(targetId,
            new AtomicLong(0));
      }
      countContainersPerRM.get(rr.getAllocationRequestId()).get(targetId)
          .addAndGet(rr.getNumContainers());

      if (!totNumLocalizedContainers.containsKey(rr.getAllocationRequestId())) {
        totNumLocalizedContainers.put(rr.getAllocationRequestId(),
            new AtomicLong(0));
      }
      totNumLocalizedContainers.get(rr.getAllocationRequestId())
          .addAndGet(rr.getNumContainers());

      internalAddToAnswer(targetId, rr);
    }

    /**
     * Add a rack-local request to the final asnwer.
     */
    public void addRackRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(!ResourceRequest.isAnyLocation(rr.getResourceName()));
      internalAddToAnswer(targetId, rr);
    }

    /**
     * Add an ANY request to the final answer.
     */
    private void addAnyRR(SubClusterId targetId, ResourceRequest rr) {
      Preconditions
          .checkArgument(ResourceRequest.isAnyLocation(rr.getResourceName()));
      internalAddToAnswer(targetId, rr);
    }

    private void internalAddToAnswer(SubClusterId targetId,
        ResourceRequest partialRR) {
      if (!answer.containsKey(targetId)) {
        answer.put(targetId, new ArrayList<ResourceRequest>());
      }
      answer.get(targetId).add(partialRR);
    }

    /**
     * Return all known subclusters associated with an allocation id.
     *
     * @param allocationId the allocation id considered
     *
     * @return the list of {@link SubClusterId}s associated with this allocation
     *         id
     */
    private Set<SubClusterId> getSubClustersForId(long allocationId) {
      if (countContainersPerRM.get(allocationId) == null) {
        return null;
      }
      return countContainersPerRM.get(allocationId).keySet();
    }

    /**
     * Return the answer accumulated so far.
     *
     * @return the answer
     */
    private Map<SubClusterId, List<ResourceRequest>> getAnswer() {
      return answer;
    }

    /**
     * Return the set of sub-clusters that are both active and allowed by our
     * policy (weight > 0).
     *
     * @return a set of active and enabled {@link SubClusterId}s
     */
    private Set<SubClusterId> getActiveAndEnabledSC() {
      return activeAndEnabledSC;
    }

    /**
     * Return the total number of container coming from localized requests
     * matching an allocation Id.
     */
    private long getTotNumLocalizedContainers(long allocationId) {
      AtomicLong c = totNumLocalizedContainers.get(allocationId);
      return c == null ? 0 : c.get();
    }

    /**
     * Returns the number of containers matching an allocation Id that are
     * localized in the targetId subcluster.
     */
    private long getNumLocalizedContainers(long allocationId,
        SubClusterId targetId) {
      AtomicLong c = countContainersPerRM.get(allocationId).get(targetId);
      return c == null ? 0 : c.get();
    }

    /**
     * Returns true is the subcluster request is both active and enabled.
     */
    private boolean isActiveAndEnabled(SubClusterId targetId) {
      if (targetId == null) {
        return false;
      } else {
        return getActiveAndEnabledSC().contains(targetId);
      }
    }

  }
}