/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.workflow.impl;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.createNiceMock;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.opencastproject.util.persistence.PersistenceUtil.newTestEntityManagerFactory;
import static org.opencastproject.workflow.impl.SecurityServiceStub.DEFAULT_ORG_ADMIN;

import org.opencastproject.assetmanager.api.AssetManager;
import org.opencastproject.assetmanager.api.Property;
import org.opencastproject.assetmanager.api.PropertyId;
import org.opencastproject.assetmanager.api.Value;
import org.opencastproject.elasticsearch.api.SearchResult;
import org.opencastproject.elasticsearch.index.ElasticsearchIndex;
import org.opencastproject.elasticsearch.index.objects.event.EventSearchQuery;
import org.opencastproject.job.api.JobContext;
import org.opencastproject.mediapackage.DefaultMediaPackageSerializerImpl;
import org.opencastproject.mediapackage.MediaPackage;
import org.opencastproject.mediapackage.MediaPackageBuilder;
import org.opencastproject.mediapackage.MediaPackageBuilderFactory;
import org.opencastproject.metadata.api.MediaPackageMetadataService;
import org.opencastproject.security.api.AccessControlList;
import org.opencastproject.security.api.AclScope;
import org.opencastproject.security.api.AuthorizationService;
import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.Organization;
import org.opencastproject.security.api.OrganizationDirectoryService;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.serviceregistry.api.IncidentService;
import org.opencastproject.serviceregistry.api.ServiceRegistryInMemoryImpl;
import org.opencastproject.systems.OpencastConstants;
import org.opencastproject.util.data.Tuple;
import org.opencastproject.workflow.api.AbstractWorkflowOperationHandler;
import org.opencastproject.workflow.api.WorkflowDefinition;
import org.opencastproject.workflow.api.WorkflowInstance;
import org.opencastproject.workflow.api.WorkflowInstance.WorkflowState;
import org.opencastproject.workflow.api.WorkflowOperationException;
import org.opencastproject.workflow.api.WorkflowOperationInstance.OperationState;
import org.opencastproject.workflow.api.WorkflowOperationResult;
import org.opencastproject.workflow.api.WorkflowOperationResult.Action;
import org.opencastproject.workflow.api.WorkflowServiceDatabaseImpl;
import org.opencastproject.workflow.api.WorkflowStateListener;
import org.opencastproject.workflow.api.XmlWorkflowParser;
import org.opencastproject.workflow.impl.WorkflowServiceImpl.HandlerRegistration;
import org.opencastproject.workspace.api.Workspace;

import com.entwinemedia.fn.data.Opt;

import org.apache.commons.io.IOUtils;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WorkflowOperationSkippingTest {

  private WorkflowServiceImpl service = null;
  private WorkflowDefinitionScanner scanner = null;
  private WorkflowDefinition workingDefinition = null;
  private MediaPackage mediapackage1 = null;
  private SucceedingWorkflowOperationHandler succeedingOperationHandler = null;
  protected Set<HandlerRegistration> handlerRegistrations = null;
  private Workspace workspace = null;
  private Property property = null;

  private AccessControlList acl = new AccessControlList();

  @Before
  public void setUp() throws Exception {
    // create operation handlers for our workflows
    succeedingOperationHandler = new SucceedingWorkflowOperationHandler(mediapackage1);
    handlerRegistrations = new HashSet<HandlerRegistration>();
    handlerRegistrations.add(new HandlerRegistration("op1", succeedingOperationHandler));
    handlerRegistrations.add(new HandlerRegistration("op2", succeedingOperationHandler));

    // instantiate a service implementation and its DAO, overriding the methods that depend on the osgi runtime
    service = new WorkflowServiceImpl() {
      @Override
      public Set<HandlerRegistration> getRegisteredHandlers() {
        return handlerRegistrations;
      }
    };
    scanner = new WorkflowDefinitionScanner();
    service.addWorkflowDefinitionScanner(scanner);
    MediaPackageMetadataService mds = EasyMock.createNiceMock(MediaPackageMetadataService.class);
    EasyMock.replay(mds);
    service.addMetadataService(mds);

    workspace = EasyMock.createNiceMock(Workspace.class);
    EasyMock.expect(workspace.getCollectionContents((String) EasyMock.anyObject())).andReturn(new URI[0]);
    EasyMock.expect(workspace.read(anyObject()))
            .andAnswer(() -> getClass().getResourceAsStream("/dc-1.xml")).anyTimes();
    EasyMock.replay(workspace);
    service.setWorkspace(workspace);

    // security service
    SecurityService securityService = EasyMock.createNiceMock(SecurityService.class);
    EasyMock.expect(securityService.getUser()).andReturn(SecurityServiceStub.DEFAULT_ORG_ADMIN).anyTimes();
    EasyMock.expect(securityService.getOrganization()).andReturn(new DefaultOrganization()).anyTimes();
    EasyMock.replay(securityService);

    service.setSecurityService(securityService);

    UserDirectoryService userDirectoryService = EasyMock.createMock(UserDirectoryService.class);
    EasyMock.expect(userDirectoryService.loadUser((String) EasyMock.anyObject())).andReturn(DEFAULT_ORG_ADMIN)
            .anyTimes();
    EasyMock.replay(userDirectoryService);
    service.setUserDirectoryService(userDirectoryService);

    Organization organization = new DefaultOrganization();
    List<Organization> organizationList = new ArrayList<Organization>();
    organizationList.add(organization);
    OrganizationDirectoryService organizationDirectoryService = EasyMock.createMock(OrganizationDirectoryService.class);
    EasyMock.expect(organizationDirectoryService.getOrganizations()).andReturn(organizationList).anyTimes();
    EasyMock.expect(organizationDirectoryService.getOrganization((String) EasyMock.anyObject()))
            .andReturn(organization).anyTimes();
    EasyMock.replay(organizationDirectoryService);
    service.setOrganizationDirectoryService(organizationDirectoryService);

    ServiceRegistryInMemoryImpl serviceRegistry = new ServiceRegistryInMemoryImpl(service, securityService,
            userDirectoryService, organizationDirectoryService, EasyMock.createNiceMock(IncidentService.class));

    AuthorizationService authzService = EasyMock.createNiceMock(AuthorizationService.class);
    EasyMock.expect(authzService.getActiveAcl((MediaPackage) EasyMock.anyObject()))
            .andReturn(Tuple.tuple(acl, AclScope.Series)).anyTimes();
    EasyMock.replay(authzService);
    service.setAuthorizationService(authzService);

    {
      final AssetManager assetManager = createNiceMock(AssetManager.class);
      property = EasyMock.createMock(Property.class);
      EasyMock.expect(assetManager.selectProperties(EasyMock.anyString(), EasyMock.anyString()))
              .andReturn(Collections.singletonList(property))
              .anyTimes();
      EasyMock.expect(assetManager.getMediaPackage(EasyMock.anyString())).andReturn(Opt.none()).anyTimes();
      EasyMock.expect(assetManager.snapshotExists(EasyMock.anyString())).andReturn(true).anyTimes();
      EasyMock.replay(assetManager);
      service.setAssetManager(assetManager);
    }

    WorkflowServiceDatabaseImpl workflowDb = new WorkflowServiceDatabaseImpl();
    workflowDb.setEntityManagerFactory(newTestEntityManagerFactory(WorkflowServiceDatabaseImpl.PERSISTENCE_UNIT));
    workflowDb.setSecurityService(securityService);
    workflowDb.activate(null);
    service.setPersistence(workflowDb);

    service.setServiceRegistry(serviceRegistry);
    service.setUserDirectoryService(userDirectoryService);
    service.activate(null);

    InputStream is;
    try {
      is = getClass().getResourceAsStream("/workflow-definition-skipping.xml");
      workingDefinition = XmlWorkflowParser.parseWorkflowDefinition(is);
      IOUtils.closeQuietly(is);

      MediaPackageBuilder mediaPackageBuilder = MediaPackageBuilderFactory.newInstance().newMediaPackageBuilder();
      mediaPackageBuilder.setSerializer(new DefaultMediaPackageSerializerImpl(new File("target/test-classes")));
      is = WorkflowOperationSkippingTest.class.getResourceAsStream("/mediapackage-1.xml");
      mediapackage1 = mediaPackageBuilder.loadFromXml(is);
      IOUtils.closeQuietly(is);
      Assert.assertNotNull(mediapackage1.getIdentifier());
    } catch (Exception e) {
      e.printStackTrace();
      Assert.fail(e.getMessage());
    }

    SearchResult result = EasyMock.createNiceMock(SearchResult.class);

    final ElasticsearchIndex index = EasyMock.createNiceMock(ElasticsearchIndex.class);
    EasyMock.expect(index.getIndexName()).andReturn("index").anyTimes();
    EasyMock.expect(index.getByQuery(EasyMock.anyObject(EventSearchQuery.class))).andReturn(result).anyTimes();
    EasyMock.replay(result, index);

    service.setIndex(index);
  }

  @Test
  public void testIf() throws Exception {

    EasyMock.expect(property.getId()).andReturn(new PropertyId(mediapackage1.getIdentifier().toString(),
            OpencastConstants.WORKFLOW_PROPERTIES_NAMESPACE, "executecondition")).anyTimes();
    EasyMock.expect(property.getValue()).andReturn(new Value.StringValue("true")).once();
    EasyMock.expect(property.getValue()).andReturn(new Value.StringValue("false")).once();

    EasyMock.replay(property);

    Map<String, String> properties1 = new HashMap<String, String>();
    properties1.put("executecondition", "true");

    Map<String, String> properties2 = new HashMap<String, String>();
    properties2.put("executecondition", "false");

    WorkflowInstance instance = startAndWait(workingDefinition, mediapackage1, properties1, WorkflowState.SUCCEEDED);
    WorkflowInstance instance2 = startAndWait(workingDefinition, mediapackage1, properties2, WorkflowState.SUCCEEDED);
    WorkflowInstance instance3 = startAndWait(workingDefinition, mediapackage1, null, WorkflowState.SUCCEEDED);

    // See if the skip operation has been executed
    WorkflowInstance instanceFromDb = service.getWorkflowById(instance.getId());
    assertNotNull(instanceFromDb);
    assertEquals(OperationState.SUCCEEDED, instanceFromDb.getOperations().get(0).getState());

    // See if the skip operation has been skipped (skip value != "true")
    WorkflowInstance instance2FromDb = service.getWorkflowById(instance2.getId());
    assertNotNull(instance2FromDb);
    assertEquals(OperationState.SKIPPED, instance2FromDb.getOperations().get(0).getState());

    // See if the skip operation has been skipped (skip property is undefined)
    WorkflowInstance instance3FromDb = service.getWorkflowById(instance3.getId());
    assertNotNull(instance3FromDb);
    assertEquals(OperationState.SKIPPED, instance3FromDb.getOperations().get(0).getState());
  }

  protected WorkflowInstance startAndWait(WorkflowDefinition definition, MediaPackage mp,
          Map<String, String> properties, WorkflowState stateToWaitFor) throws Exception {

    WorkflowStateListener stateListener = new WorkflowStateListener(stateToWaitFor);
    service.addWorkflowListener(stateListener);
    WorkflowInstance instance = null;
    instance = service.start(definition, mp, properties);
    WorkflowTestSupport.poll(stateListener, 1);
    service.removeWorkflowListener(stateListener);

    return instance;
  }

  class SucceedingWorkflowOperationHandler extends AbstractWorkflowOperationHandler {

    private MediaPackage mp;

    SucceedingWorkflowOperationHandler(MediaPackage mp) {
      this.mp = mp;
    }

    @Override
    public String getId() {
      return this.getClass().getName();
    }

    @Override
    public String getDescription() {
      return "ContinuingWorkflowOperationHandler";
    }

    @Override
    public WorkflowOperationResult start(WorkflowInstance workflowInstance, JobContext context)
            throws WorkflowOperationException {
      return createResult(mp, Action.CONTINUE);
    }
  }

}
