package org.folio.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.folio.search.domain.dto.ReindexRequest.ResourceNameEnum.AUTHORITY;
import static org.folio.search.domain.dto.ReindexRequest.ResourceNameEnum.LINKED_DATA_AUTHORITY;
import static org.folio.search.domain.dto.ReindexRequest.ResourceNameEnum.LINKED_DATA_WORK;
import static org.folio.search.domain.dto.ReindexRequest.ResourceNameEnum.LOCATION;
import static org.folio.search.utils.SearchResponseHelper.getSuccessFolioCreateIndexResponse;
import static org.folio.search.utils.SearchResponseHelper.getSuccessIndexOperationResponse;
import static org.folio.search.utils.SearchUtils.AUTHORITY_RESOURCE;
import static org.folio.search.utils.SearchUtils.CAMPUS_RESOURCE;
import static org.folio.search.utils.SearchUtils.INSTANCE_RESOURCE;
import static org.folio.search.utils.SearchUtils.INSTANCE_SUBJECT_RESOURCE;
import static org.folio.search.utils.SearchUtils.INSTITUTION_RESOURCE;
import static org.folio.search.utils.SearchUtils.LIBRARY_RESOURCE;
import static org.folio.search.utils.SearchUtils.LOCATION_RESOURCE;
import static org.folio.search.utils.SearchUtils.getIndexName;
import static org.folio.search.utils.TestConstants.CENTRAL_TENANT_ID;
import static org.folio.search.utils.TestConstants.EMPTY_JSON_OBJECT;
import static org.folio.search.utils.TestConstants.EMPTY_OBJECT;
import static org.folio.search.utils.TestConstants.INDEX_NAME;
import static org.folio.search.utils.TestConstants.MEMBER_TENANT_ID;
import static org.folio.search.utils.TestConstants.TENANT_ID;
import static org.folio.search.utils.TestUtils.randomId;
import static org.folio.search.utils.TestUtils.resourceDescription;
import static org.folio.search.utils.TestUtils.secondaryResourceDescription;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import org.folio.search.client.ResourceReindexClient;
import org.folio.search.domain.dto.IndexDynamicSettings;
import org.folio.search.domain.dto.IndexSettings;
import org.folio.search.domain.dto.ReindexJob;
import org.folio.search.domain.dto.ReindexRequest;
import org.folio.search.exception.RequestValidationException;
import org.folio.search.repository.IndexNameProvider;
import org.folio.search.repository.IndexRepository;
import org.folio.search.service.consortium.ConsortiumInstanceService;
import org.folio.search.service.consortium.TenantProvider;
import org.folio.search.service.es.SearchMappingsHelper;
import org.folio.search.service.es.SearchSettingsHelper;
import org.folio.search.service.metadata.ResourceDescriptionService;
import org.folio.spring.testing.type.UnitTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@UnitTest
@ExtendWith(MockitoExtension.class)
class IndexServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @InjectMocks
  private IndexService indexService;

  @Mock
  private IndexRepository indexRepository;
  @Mock
  private SearchMappingsHelper mappingsHelper;
  @Mock
  private SearchSettingsHelper settingsHelper;
  @Mock
  private ResourceReindexClient resourceReindexClient;
  @Mock
  private ResourceDescriptionService resourceDescriptionService;
  @Mock
  private IndexNameProvider indexNameProvider;
  @Mock
  private ConsortiumInstanceService consortiumInstanceService;
  @Mock
  private LocationService locationService;

  @Mock
  private TenantProvider tenantProvider;

  @BeforeEach
  void setUp() {
    lenient().when(tenantProvider.getTenant(TENANT_ID)).thenReturn(TENANT_ID);
    lenient().when(indexNameProvider.getIndexName(any(), any()))
      .thenAnswer(invocation -> getIndexName(invocation.getArgument(0), invocation.getArgument(1)));
  }

  @Test
  void createIndex_positive() {
    var expectedResponse = getSuccessFolioCreateIndexResponse(List.of(INDEX_NAME));

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(mappingsHelper.getMappings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(indexRepository.createIndex(INDEX_NAME, EMPTY_OBJECT, EMPTY_OBJECT)).thenReturn(expectedResponse);

    var indexResponse = indexService.createIndex(INSTANCE_RESOURCE, TENANT_ID);
    assertThat(indexResponse).isEqualTo(expectedResponse);
  }

  @ParameterizedTest
  @MethodSource("customSettingsTestData")
  @SneakyThrows
  void createIndex_positive_customSettings(Integer shards, Integer replicas, Integer refresh) {
    var expectedResponse = getSuccessFolioCreateIndexResponse(List.of(INDEX_NAME));

    var indexSettingsMock = MAPPER.readTree(getIndexSettingsJsonString(4, 2, "1s"));
    var indexSettingsRequest = new IndexSettings()
      .numberOfShards(shards)
      .numberOfReplicas(replicas)
      .refreshInterval(refresh);

    var expectedShards = shards == null ? 4 : shards;
    var expectedReplicas = replicas == null ? 2 : replicas;
    var expectedRefresh = refresh == null || refresh == 0 ? "1s"
                                                          : refresh < 0 ? String.valueOf(refresh) : refresh + "s";
    var expectedIndexSettings = getIndexSettingsJsonString(expectedShards, expectedReplicas, expectedRefresh);

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(mappingsHelper.getMappings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(INSTANCE_RESOURCE)).thenReturn(indexSettingsMock);
    when(indexRepository.createIndex(INDEX_NAME, expectedIndexSettings, EMPTY_OBJECT)).thenReturn(expectedResponse);

    var indexResponse = indexService.createIndex(INSTANCE_RESOURCE, TENANT_ID, indexSettingsRequest);
    assertThat(indexResponse).isEqualTo(expectedResponse);
  }

  @ParameterizedTest
  @MethodSource("customDynamicSettingsTestData")
  @SneakyThrows
  void updateIndexDynamicSettings_positive_customSettings(Integer replicas, Integer refresh) {
    var expectedResponse = getSuccessIndexOperationResponse();

    var indexSettingsMock = MAPPER.readTree(getIndexDynamicSettingsJsonString(2, "1s"));
    var indexSettingsRequest = new IndexDynamicSettings()
      .numberOfReplicas(replicas)
      .refreshInterval(refresh);

    var expectedReplicas = replicas == null ? 2 : replicas;
    var expectedRefresh = refresh == null || refresh == 0 ? "1s"
                                                          : refresh < 0 ? String.valueOf(refresh) : refresh + "s";
    var expectedIndexSettings = getIndexDynamicSettingsJsonString(expectedReplicas, expectedRefresh);

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(settingsHelper.getSettingsJson("dynamicSettings")).thenReturn(indexSettingsMock);
    when(indexRepository.updateIndexSettings(INDEX_NAME, expectedIndexSettings)).thenReturn(expectedResponse);

    var indexResponse = indexService.updateIndexSettings(INSTANCE_RESOURCE, TENANT_ID, indexSettingsRequest);
    assertThat(indexResponse).isEqualTo(expectedResponse);
  }

  @Test
  @SneakyThrows
  void updateIndexDynamicSettings_negative_customSettings() {
    var indexSettingsRequest = new IndexDynamicSettings()
      .numberOfReplicas(1)
      .refreshInterval(1);
    String invalidResourceName = "invalid_instance";

    when(resourceDescriptionService.find(invalidResourceName)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> indexService.updateIndexSettings(invalidResourceName, TENANT_ID, indexSettingsRequest))
      .hasMessage("Index Settings cannot be updated, resource name is invalid.");
  }

  @Test
  @SneakyThrows
  void createIndex_positive_customSettingsNull() {
    var expectedResponse = getSuccessFolioCreateIndexResponse(List.of(INDEX_NAME));

    var expectedIndexSettings = getIndexSettingsJsonString(4, 2, "1s");
    var indexSettingsMock = MAPPER.readTree(expectedIndexSettings);

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(mappingsHelper.getMappings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(INSTANCE_RESOURCE)).thenReturn(indexSettingsMock);
    when(indexRepository.createIndex(INDEX_NAME, expectedIndexSettings, EMPTY_OBJECT)).thenReturn(expectedResponse);

    var indexResponse = indexService.createIndex(INSTANCE_RESOURCE, TENANT_ID, null);
    assertThat(indexResponse).isEqualTo(expectedResponse);
  }

  @Test
  void createIndex_negative_resourceDescriptionNotFound() {
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> indexService.createIndex(INSTANCE_RESOURCE, TENANT_ID))
      .isInstanceOf(RequestValidationException.class)
      .hasMessage("Index cannot be created for the resource because resource description is not found.");
  }

  @Test
  void updateMappings() {
    var expectedResponse = getSuccessIndexOperationResponse();

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(mappingsHelper.getMappings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(indexRepository.updateMappings(INDEX_NAME, EMPTY_OBJECT)).thenReturn(expectedResponse);

    var indexResponse = indexService.updateMappings(INSTANCE_RESOURCE, TENANT_ID);
    assertThat(indexResponse).isEqualTo(expectedResponse);
  }

  @Test
  void updateMappings_negative_resourceDescriptionNotFound() {
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> indexService.updateMappings(INSTANCE_RESOURCE, TENANT_ID))
      .isInstanceOf(RequestValidationException.class)
      .hasMessage("Mappings cannot be updated, resource name is invalid.");
  }

  @Test
  void createIndexIfNotExist_shouldCreateIndex_indexNotExist() {
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    var indexName = getIndexName(INSTANCE_RESOURCE, TENANT_ID);

    indexService.createIndexIfNotExist(INSTANCE_RESOURCE, TENANT_ID);

    verify(indexRepository).createIndex(eq(indexName), any(), any());
  }

  @Test
  void createIndexIfNotExist_shouldNotCreateIndex_alreadyExist() {
    var indexName = getIndexName(INSTANCE_RESOURCE, TENANT_ID);
    when(indexRepository.indexExists(indexName)).thenReturn(true);

    indexService.createIndexIfNotExist(INSTANCE_RESOURCE, TENANT_ID);

    verify(indexRepository, times(0)).createIndex(eq(indexName), any(), any());
  }

  @Test
  void reindexInventory_positive_recreateIndexIsTrue() {
    var indexName = getIndexName(INSTANCE_RESOURCE, TENANT_ID);
    var createIndexResponse = getSuccessFolioCreateIndexResponse(List.of(indexName));
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(mappingsHelper.getMappings(INSTANCE_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(INSTANCE_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);
    when(indexRepository.indexExists(indexName)).thenReturn(true, false);
    when(indexRepository.createIndex(indexName, EMPTY_OBJECT, EMPTY_OBJECT)).thenReturn(createIndexResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().recreateIndex(true));

    assertThat(actual).isEqualTo(expectedResponse);
    verify(indexRepository).dropIndex(indexName);
    verify(consortiumInstanceService).deleteAll();
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_recreateIndexIsTrue_memberTenant() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(tenantProvider.getTenant(TENANT_ID)).thenReturn(CENTRAL_TENANT_ID);

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().recreateIndex(true));

    assertThat(actual).isEqualTo(expectedResponse);
    verifyNoInteractions(indexRepository);
    verifyNoInteractions(consortiumInstanceService);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_recreateIndexIsFalse() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest());
    assertThat(actual).isEqualTo(expectedResponse);
    verifyNoInteractions(locationService);
    verifyNoInteractions(consortiumInstanceService);
  }

  @Test
  void reindexInventory_positive_resourceNameIsNull() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(resourceDescriptionService.getSecondaryResourceNames(INSTANCE_RESOURCE)).thenReturn(List.of("secondary"));
    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().resourceName(null));
    assertThat(actual).isEqualTo(expectedResponse);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_resourceNameIsNullAndRecreateIndexIsTrue() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(resourceDescriptionService.find(INSTANCE_SUBJECT_RESOURCE))
      .thenReturn(Optional.of(secondaryResourceDescription(INSTANCE_SUBJECT_RESOURCE, INSTANCE_RESOURCE)));
    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(resourceDescriptionService.getSecondaryResourceNames(INSTANCE_RESOURCE))
      .thenReturn(List.of(INSTANCE_SUBJECT_RESOURCE));
    mockCreateIndexOperation(INSTANCE_RESOURCE);
    mockCreateIndexOperation(INSTANCE_SUBJECT_RESOURCE);

    var secondaryIndexName = getIndexName(INSTANCE_SUBJECT_RESOURCE, TENANT_ID);
    var instanceIndexName = getIndexName(INSTANCE_RESOURCE, TENANT_ID);
    when(indexRepository.indexExists(instanceIndexName)).thenReturn(true);
    when(indexRepository.indexExists(secondaryIndexName)).thenReturn(true);

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().resourceName(null).recreateIndex(true));
    assertThat(actual).isEqualTo(expectedResponse);

    verify(indexRepository).dropIndex(instanceIndexName);
    verify(indexRepository).dropIndex(secondaryIndexName);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_reindexRequestIsNull() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://instance-storage/reindex");

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(INSTANCE_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTANCE_RESOURCE)));
    when(resourceDescriptionService.getSecondaryResourceNames(INSTANCE_RESOURCE)).thenReturn(List.of("secondary"));

    var actual = indexService.reindexInventory(TENANT_ID, null);

    assertThat(actual).isEqualTo(expectedResponse);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_authorityRecord() {
    var expectedResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://authority-storage/reindex");
    var resourceName = AUTHORITY;

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(expectedResponse);
    when(resourceDescriptionService.find(resourceName.getValue()))
      .thenReturn(Optional.of(resourceDescription(resourceName.getValue())));

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().resourceName(resourceName));

    assertThat(actual).isEqualTo(expectedResponse);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_authorityRecordAndRecreateIndex() {
    var reindexResponse = new ReindexJob().id(randomId());
    var expectedUri = URI.create("http://authority-storage/reindex");
    var indexName = getIndexName(AUTHORITY_RESOURCE, TENANT_ID);

    when(resourceReindexClient.submitReindex(expectedUri)).thenReturn(reindexResponse);
    when(resourceDescriptionService.find(AUTHORITY_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(AUTHORITY_RESOURCE)));
    when(mappingsHelper.getMappings(AUTHORITY_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(AUTHORITY_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);
    when(indexRepository.createIndex(indexName, EMPTY_OBJECT, EMPTY_OBJECT))
      .thenReturn(getSuccessFolioCreateIndexResponse(List.of(indexName)));

    var reindexRequest = new ReindexRequest().resourceName(AUTHORITY).recreateIndex(true);
    var actual = indexService.reindexInventory(TENANT_ID, reindexRequest);

    assertThat(actual).isEqualTo(reindexResponse);
    verifyNoInteractions(locationService);
  }

  @Test
  void reindexInventory_positive_locations() {
    when(resourceDescriptionService.find(LOCATION_RESOURCE))
      .thenReturn(Optional.of(resourceDescription(LOCATION_RESOURCE)));
    when(resourceDescriptionService.getSecondaryResourceNames(LOCATION_RESOURCE))
      .thenReturn(List.of(CAMPUS_RESOURCE, LIBRARY_RESOURCE, INSTITUTION_RESOURCE));

    var actual = indexService.reindexInventory(TENANT_ID, new ReindexRequest().resourceName(LOCATION));

    assertThat(actual.getId()).isNotBlank();
    assertThat(actual.getSubmittedDate()).isNotBlank();
    assertThat(actual.getJobStatus()).isEqualTo("Completed");
    verify(locationService, atMostOnce()).reindex(TENANT_ID, LOCATION_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, CAMPUS_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, LIBRARY_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, INSTITUTION_RESOURCE);
    verifyNoInteractions(resourceReindexClient);
  }

  @Test
  void reindexInventory_positive_locationsAndRecreateIndex() {
    var locationIndex = getIndexName(LOCATION_RESOURCE, TENANT_ID);
    var campusIndex = getIndexName(CAMPUS_RESOURCE, TENANT_ID);
    var libraryIndex = getIndexName(LIBRARY_RESOURCE, TENANT_ID);
    var institutionIndex = getIndexName(INSTITUTION_RESOURCE, TENANT_ID);

    when(resourceDescriptionService.find(LOCATION_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(LOCATION_RESOURCE)));
    when(resourceDescriptionService.find(CAMPUS_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(CAMPUS_RESOURCE)));
    when(resourceDescriptionService.find(LIBRARY_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(LIBRARY_RESOURCE)));
    when(resourceDescriptionService.find(INSTITUTION_RESOURCE)).thenReturn(
      Optional.of(resourceDescription(INSTITUTION_RESOURCE)));

    when(resourceDescriptionService.getSecondaryResourceNames(LOCATION_RESOURCE))
      .thenReturn(List.of(CAMPUS_RESOURCE, LIBRARY_RESOURCE, INSTITUTION_RESOURCE));

    when(mappingsHelper.getMappings(LOCATION_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(LOCATION_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);
    when(mappingsHelper.getMappings(CAMPUS_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(CAMPUS_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);
    when(mappingsHelper.getMappings(LIBRARY_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(LIBRARY_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);
    when(mappingsHelper.getMappings(INSTITUTION_RESOURCE)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(INSTITUTION_RESOURCE)).thenReturn(EMPTY_JSON_OBJECT);

    when(indexRepository.createIndex(locationIndex, EMPTY_OBJECT, EMPTY_OBJECT))
      .thenReturn(getSuccessFolioCreateIndexResponse(List.of(locationIndex)));
    when(indexRepository.createIndex(campusIndex, EMPTY_OBJECT, EMPTY_OBJECT))
      .thenReturn(getSuccessFolioCreateIndexResponse(List.of(campusIndex)));
    when(indexRepository.createIndex(libraryIndex, EMPTY_OBJECT, EMPTY_OBJECT))
      .thenReturn(getSuccessFolioCreateIndexResponse(List.of(libraryIndex)));
    when(indexRepository.createIndex(institutionIndex, EMPTY_OBJECT, EMPTY_OBJECT))
      .thenReturn(getSuccessFolioCreateIndexResponse(List.of(institutionIndex)));

    var reindexRequest = new ReindexRequest().resourceName(LOCATION).recreateIndex(true);
    var actual = indexService.reindexInventory(TENANT_ID, reindexRequest);

    assertThat(actual.getId()).isNotBlank();
    assertThat(actual.getSubmittedDate()).isNotBlank();
    assertThat(actual.getJobStatus()).isEqualTo("Completed");
    verify(locationService, atMostOnce()).reindex(TENANT_ID, LOCATION_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, CAMPUS_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, LIBRARY_RESOURCE);
    verify(locationService, atMostOnce()).reindex(TENANT_ID, INSTITUTION_RESOURCE);
    verifyNoInteractions(resourceReindexClient);
  }

  @ParameterizedTest
  @EnumSource(value = ReindexRequest.ResourceNameEnum.class, names = {"LINKED_DATA_WORK", "LINKED_DATA_AUTHORITY"})
  void reindexInventory_shouldRecreate_linkedDataResourcesIndexes(ReindexRequest.ResourceNameEnum resourceNameEnum) {
    var resourceName = resourceNameEnum.getValue();
    var linkedDataResourceIndex = getIndexName(resourceName, TENANT_ID);
    when(resourceDescriptionService.find(resourceName)).thenReturn(
      Optional.of(resourceDescription(resourceName)));
    when(resourceDescriptionService.getSecondaryResourceNames(resourceName))
      .thenReturn(List.of());
    when(indexRepository.indexExists(linkedDataResourceIndex)).thenReturn(true);
    when(mappingsHelper.getMappings(resourceName)).thenReturn(EMPTY_OBJECT);
    when(settingsHelper.getSettingsJson(resourceName)).thenReturn(EMPTY_JSON_OBJECT);

    var reindexRequest = new ReindexRequest().resourceName(resourceNameEnum).recreateIndex(true);
    var actual = indexService.reindexInventory(TENANT_ID, reindexRequest);

    assertNotNull(actual);
    verify(indexRepository).dropIndex(linkedDataResourceIndex);
    verify(indexRepository).createIndex(linkedDataResourceIndex, EMPTY_JSON_OBJECT.toString(), EMPTY_OBJECT);
    verifyNoInteractions(consortiumInstanceService);
    verifyNoInteractions(locationService);
    verifyNoInteractions(resourceReindexClient);
  }

  @ParameterizedTest
  @MethodSource("tenantIdAndReindexRequestDataProvider")
  void reindexInventory_shouldNotRecreate_linkedDataResourcesIndexes(String tenantId, ReindexRequest reindexRequest) {
    var resourceName = reindexRequest.getResourceName().getValue();
    when(resourceDescriptionService.find(resourceName)).thenReturn(
      Optional.of(resourceDescription(resourceName)));
    when(resourceDescriptionService.getSecondaryResourceNames(resourceName))
      .thenReturn(List.of());

    var actual = indexService.reindexInventory(tenantId, reindexRequest);

    assertNotNull(actual);
    verifyNoInteractions(indexRepository);
    verifyNoInteractions(consortiumInstanceService);
    verifyNoInteractions(locationService);
    verifyNoInteractions(resourceReindexClient);
  }

  @Test
  void shouldDropIndexWhenExists() {
    when(indexRepository.indexExists(INDEX_NAME)).thenReturn(true);
    indexService.dropIndex(INSTANCE_RESOURCE, TENANT_ID);
    verify(indexRepository).dropIndex(INDEX_NAME);
  }

  @Test
  void shouldNotDropIndexWhenNotExist() {
    when(indexRepository.indexExists(INDEX_NAME)).thenReturn(false);
    indexService.dropIndex(INSTANCE_RESOURCE, TENANT_ID);
    verify(indexRepository, times(0)).dropIndex(INDEX_NAME);
  }

  @SneakyThrows
  private String getIndexSettingsJsonString(Integer shards, Integer replicas, String refresh) {
    return MAPPER.writeValueAsString(Map.of("index", Map.of(
      "number_of_shards", shards,
      "number_of_replicas", replicas,
      "refresh_interval", refresh)));
  }

  @SneakyThrows
  private String getIndexDynamicSettingsJsonString(Integer replicas, String refresh) {
    return MAPPER.writeValueAsString(Map.of("index", Map.of(
      "number_of_replicas", replicas,
      "refresh_interval", refresh)));
  }

  private void mockCreateIndexOperation(String resource) {
    var indexName = getIndexName(resource, TENANT_ID);
    doReturn(EMPTY_OBJECT).when(mappingsHelper).getMappings(resource);
    doReturn(EMPTY_JSON_OBJECT).when(settingsHelper).getSettingsJson(resource);
    doReturn(getSuccessFolioCreateIndexResponse(List.of(indexName))).when(indexRepository)
      .createIndex(indexName, EMPTY_OBJECT, EMPTY_OBJECT);
  }

  private static Stream<Arguments> customSettingsTestData() {
    return Stream.of(
      Arguments.of(1, 1, 2),
      Arguments.of(null, null, null),
      Arguments.of(null, 1, null),
      Arguments.of(null, null, -1),
      Arguments.of(null, null, 0)
    );
  }

  private static Stream<Arguments> customDynamicSettingsTestData() {
    return Stream.of(
      Arguments.of(1, 2),
      Arguments.of(null, null),
      Arguments.of(1, null),
      Arguments.of(null, -1),
      Arguments.of(null, 0)
    );
  }

  private static Stream<Arguments> tenantIdAndReindexRequestDataProvider() {
    return Stream.of(
      arguments(
        TENANT_ID,
        new ReindexRequest().resourceName(LINKED_DATA_WORK)
      ),
      arguments(
        TENANT_ID,
        new ReindexRequest().resourceName(LINKED_DATA_AUTHORITY)
      ),
      arguments(
        MEMBER_TENANT_ID,
        new ReindexRequest().resourceName(LINKED_DATA_WORK).recreateIndex(true)
      ),
      arguments(
        MEMBER_TENANT_ID,
        new ReindexRequest().resourceName(LINKED_DATA_AUTHORITY).recreateIndex(true)
      )
    );
  }
}
