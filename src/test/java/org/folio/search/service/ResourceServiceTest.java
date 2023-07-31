package org.folio.search.service;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.folio.search.domain.dto.ResourceEventType.CREATE;
import static org.folio.search.domain.dto.ResourceEventType.DELETE;
import static org.folio.search.domain.dto.ResourceEventType.UPDATE;
import static org.folio.search.utils.SearchResponseHelper.getErrorIndexOperationResponse;
import static org.folio.search.utils.SearchResponseHelper.getSuccessIndexOperationResponse;
import static org.folio.search.utils.SearchUtils.INSTANCE_RESOURCE;
import static org.folio.search.utils.TestConstants.RESOURCE_ID;
import static org.folio.search.utils.TestConstants.RESOURCE_ID_SECOND;
import static org.folio.search.utils.TestConstants.TENANT_ID;
import static org.folio.search.utils.TestConstants.indexName;
import static org.folio.search.utils.TestUtils.asJsonString;
import static org.folio.search.utils.TestUtils.mapOf;
import static org.folio.search.utils.TestUtils.randomId;
import static org.folio.search.utils.TestUtils.resourceDescription;
import static org.folio.search.utils.TestUtils.resourceEvent;
import static org.folio.search.utils.TestUtils.searchDocumentBody;
import static org.folio.search.utils.TestUtils.searchDocumentBodyToDelete;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.folio.search.domain.dto.FolioIndexOperationResponse;
import org.folio.search.integration.KafkaMessageProducer;
import org.folio.search.integration.ResourceFetchService;
import org.folio.search.model.index.SearchDocumentBody;
import org.folio.search.model.metadata.ResourceDescription;
import org.folio.search.model.metadata.ResourceIndexingConfiguration;
import org.folio.search.repository.IndexRepository;
import org.folio.search.repository.PrimaryResourceRepository;
import org.folio.search.repository.ResourceRepository;
import org.folio.search.service.consortium.ConsortiumInstanceService;
import org.folio.search.service.consortium.ConsortiumTenantService;
import org.folio.search.service.converter.MultiTenantSearchDocumentConverter;
import org.folio.search.service.metadata.ResourceDescriptionService;
import org.folio.search.support.base.TenantConfig;
import org.folio.spring.test.type.UnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;

@UnitTest
@ExtendWith(MockitoExtension.class)
@SpringBootTest(classes = TenantConfig.class)
class ResourceServiceTest {

  private static final String CUSTOM_REPOSITORY_NAME = "org.folio.search.service.ResourceServiceTest$TestRepository#0";

  @MockBean
  private IndexRepository indexRepository;
  @MockBean
  private ResourceFetchService resourceFetchService;
  @MockBean
  private PrimaryResourceRepository primaryResourceRepository;
  @MockBean
  private ResourceDescriptionService resourceDescriptionService;
  @MockBean
  private MultiTenantSearchDocumentConverter searchDocumentConverter;
  @MockBean
  private KafkaMessageProducer kafkaMessageProducer;
  @MockBean
  private TestRepository testRepository;
  @MockBean
  private ConsortiumTenantService consortiumTenantService;
  @MockBean
  private TenantScopedExecutionService tenantScopedExecutionService;
  @MockBean
  private ConsortiumInstanceService consortiumInstanceService;
  @SpyBean
  private ResourceService indexService;

  @Autowired
  private String centralTenant;

  @BeforeEach
  public void setUp(@Autowired String centralTenant) {
    when(consortiumTenantService.getCentralTenant(any())).thenReturn(Optional.of(centralTenant));
    lenient().when(consortiumInstanceService.saveInstances(anyList()))
      .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(consortiumInstanceService.deleteInstances(anyList()))
      .thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(tenantScopedExecutionService.executeTenantScoped(any(), any()))
      .thenAnswer(invocation -> ((Callable<?>) invocation.getArgument(1)).call());
  }

  @Test
  void indexResources_positive() {
    var searchBody = searchDocumentBody();
    var resourceEvent = resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId()));
    var expectedResponse = getSuccessIndexOperationResponse();

    when(searchDocumentConverter.convert(List.of(resourceEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, List.of(searchBody)));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(primaryResourceRepository.indexResources(List.of(searchBody))).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(of(resourceDescription(INSTANCE_RESOURCE)));

    var response = indexService.indexResources(List.of(resourceEvent));
    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  void indexResources_negative_failedResponse() {
    var searchBody = searchDocumentBody();
    var resourceEvent = resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId()));
    var expectedResponse = getErrorIndexOperationResponse("Failed to save bulk");

    when(searchDocumentConverter.convert(List.of(resourceEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, List.of(searchBody)));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(primaryResourceRepository.indexResources(List.of(searchBody))).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(of(resourceDescription(INSTANCE_RESOURCE)));

    var response = indexService.indexResources(List.of(resourceEvent));
    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  void indexResources_positive_customResourceRepository() {
    var searchBody = searchDocumentBody();
    var resourceEvent = resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId())).tenant(centralTenant);
    var expectedResponse = getSuccessIndexOperationResponse();

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(of(resourceDescriptionWithCustomRepository()));
    when(searchDocumentConverter.convert(List.of(resourceEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, List.of(searchBody)));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(testRepository.indexResources(List.of(searchBody))).thenReturn(expectedResponse);
    when(primaryResourceRepository.indexResources(null)).thenReturn(getSuccessIndexOperationResponse());

    var response = indexService.indexResources(List.of(resourceEvent));
    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  void indexResources_negative() {
    var resourceEvents = List.of(resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId())));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(false);
    when(primaryResourceRepository.indexResources(null)).thenReturn(getSuccessIndexOperationResponse());
    when(searchDocumentConverter.convert(emptyList())).thenReturn(emptyMap());

    var actual = indexService.indexResources(resourceEvents);
    assertThat(actual).isEqualTo(getSuccessIndexOperationResponse());
  }

  @Test
  void indexResources_positive_emptyList() {
    var response = indexService.indexResources(Collections.emptyList());
    assertThat(response).isEqualTo(getSuccessIndexOperationResponse());
  }

  @Test
  void indexResourcesById_positive() {
    var resourceEvents = List.of(resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, CREATE, null, null));
    var resourceEvent = resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId()));
    var expectedResponse = getSuccessIndexOperationResponse();
    var expectedDocuments = List.of(searchDocumentBody());

    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(resourceFetchService.fetchInstancesByIds(resourceEvents, TENANT_ID)).thenReturn(List.of(resourceEvent));
    when(searchDocumentConverter.convert(List.of(resourceEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, expectedDocuments));
    when(searchDocumentConverter.convert(null)).thenReturn(emptyMap());
    when(primaryResourceRepository.indexResources(expectedDocuments)).thenReturn(expectedResponse);
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var actual = indexService.indexInstancesById(resourceEvents);
    assertThat(actual).isEqualTo(expectedResponse);
  }

  @Test
  void indexResourcesById_positive_updateEvent() {
    var newData = mapOf("id", RESOURCE_ID, "title", "new title");
    var oldData = mapOf("id", RESOURCE_ID, "title", "old title");
    var resourceEvent = resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, UPDATE, newData, oldData);
    var fetchedEvent = resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, CREATE, newData, null);
    var expectedResponse = getSuccessIndexOperationResponse();
    var searchBody = searchDocumentBody(asJsonString(newData));

    when(resourceFetchService.fetchInstancesByIds(List.of(resourceEvent), TENANT_ID)).thenReturn(List.of(fetchedEvent));
    when(searchDocumentConverter.convert(List.of(fetchedEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, List.of(searchBody)));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(primaryResourceRepository.indexResources(List.of(searchBody))).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(of(resourceDescription(INSTANCE_RESOURCE)));
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var response = indexService.indexInstancesById(List.of(resourceEvent));
    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  void indexResourcesById_positive_moveDataBetweenInstances() {
    var oldData = mapOf("instanceId", RESOURCE_ID_SECOND, "title", "old title");
    var newData = mapOf("instanceId", RESOURCE_ID, "title", "new title");
    var resourceEvent = resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, UPDATE, newData, oldData);
    var oldEvent = resourceEvent(RESOURCE_ID_SECOND, INSTANCE_RESOURCE, UPDATE, oldData, null);
    var newEvent = resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, UPDATE, newData, null);
    var fetchedEvents = List.of(resourceEvent(RESOURCE_ID_SECOND, INSTANCE_RESOURCE, CREATE, oldData, null),
      resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, CREATE, newData, null));
    var expectedResponse = getSuccessIndexOperationResponse();
    var searchBodies = List.of(searchDocumentBody(asJsonString(oldData)), searchDocumentBody(asJsonString(newData)));

    when(resourceFetchService.fetchInstancesByIds(List.of(oldEvent, newEvent), TENANT_ID)).thenReturn(fetchedEvents);
    when(searchDocumentConverter.convert(fetchedEvents)).thenReturn(mapOf(INSTANCE_RESOURCE, searchBodies));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(primaryResourceRepository.indexResources(searchBodies)).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(of(resourceDescription(INSTANCE_RESOURCE)));
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var response = indexService.indexInstancesById(List.of(resourceEvent));
    assertThat(response).isEqualTo(expectedResponse);
  }

  @Test
  void indexResourcesById_positive_deleteEvent() {
    var expectedDocuments = List.of(searchDocumentBodyToDelete());
    var resourceEvents = List.of(resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, DELETE));

    when(resourceFetchService.fetchInstancesByIds(emptyList(), TENANT_ID)).thenReturn(emptyList());
    when(searchDocumentConverter.convert(emptyList())).thenReturn(emptyMap());
    when(searchDocumentConverter.convert(resourceEvents)).thenReturn(mapOf(INSTANCE_RESOURCE, expectedDocuments));
    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var expectedResponse = getSuccessIndexOperationResponse();
    when(primaryResourceRepository.indexResources(expectedDocuments)).thenReturn(expectedResponse);

    var actual = indexService.indexInstancesById(resourceEvents);
    assertThat(actual).isEqualTo(expectedResponse);
  }

  @Test
  void indexResourcesById_negative_failedEvents() {
    var resourceEvents = List.of(resourceEvent(RESOURCE_ID, INSTANCE_RESOURCE, CREATE, null, null));
    var resourceEvent = resourceEvent(INSTANCE_RESOURCE, mapOf("id", randomId()));
    var expectedResponse = getErrorIndexOperationResponse("Bulk failed: errors: ['test-error']");
    var expectedDocuments = List.of(searchDocumentBody());

    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(true);
    when(resourceFetchService.fetchInstancesByIds(resourceEvents, TENANT_ID)).thenReturn(List.of(resourceEvent));
    when(searchDocumentConverter.convert(List.of(resourceEvent))).thenReturn(
      mapOf(INSTANCE_RESOURCE, expectedDocuments));
    when(searchDocumentConverter.convert(null)).thenReturn(emptyMap());
    when(primaryResourceRepository.indexResources(expectedDocuments)).thenReturn(expectedResponse);
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var actual = indexService.indexInstancesById(resourceEvents);
    assertThat(actual).isEqualTo(expectedResponse);
  }

  @Test
  void indexResourcesById_positive_emptyList() {
    var actual = indexService.indexInstancesById(emptyList());
    assertThat(actual).isEqualTo(getSuccessIndexOperationResponse());
  }

  @Test
  void indexResourcesById_positive_null() {
    var actual = indexService.indexInstancesById(null);
    assertThat(actual).isEqualTo(getSuccessIndexOperationResponse());
  }

  @Test
  void indexResourcesById_negative_indexNotExist() {
    var eventIds = List.of(resourceEvent(randomId(), INSTANCE_RESOURCE, CREATE));

    when(indexRepository.indexExists(indexName(centralTenant))).thenReturn(false);
    when(resourceFetchService.fetchInstancesByIds(emptyList(), TENANT_ID)).thenReturn(emptyList());
    when(searchDocumentConverter.convert(null)).thenReturn(emptyMap());
    when(searchDocumentConverter.convert(emptyList())).thenReturn(emptyMap());
    when(primaryResourceRepository.indexResources(null)).thenReturn(getSuccessIndexOperationResponse());
    doNothing().when(kafkaMessageProducer).prepareAndSendContributorEvents(anyList());

    var actual = indexService.indexInstancesById(eventIds);

    assertThat(actual).isEqualTo(getSuccessIndexOperationResponse());
  }

  private static ResourceDescription resourceDescriptionWithCustomRepository() {
    var resourceIndexingConfiguration = new ResourceIndexingConfiguration();
    resourceIndexingConfiguration.setResourceRepository(CUSTOM_REPOSITORY_NAME);

    var resourceDescription = resourceDescription(INSTANCE_RESOURCE);
    resourceDescription.setIndexingConfiguration(resourceIndexingConfiguration);

    return resourceDescription;
  }

  static class TestRepository implements ResourceRepository {
    @Override
    public FolioIndexOperationResponse indexResources(List<SearchDocumentBody> esDocumentBodies) {
      return null;
    }
  }
}
