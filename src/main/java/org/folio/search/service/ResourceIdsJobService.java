package org.folio.search.service;

import java.security.SecureRandom;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.RandomStringUtils;
import org.folio.search.converter.ResourceIdsJobMapper;
import org.folio.search.domain.dto.ResourceIdsJob;
import org.folio.search.model.types.StreamJobStatus;
import org.folio.search.repository.ResourceIdsJobRepository;
import org.folio.search.service.consortia.ResourceIdServiceDecorator;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@RequiredArgsConstructor
public class ResourceIdsJobService {

  private final TenantScopedExecutionService tenantExecutionService;
  private final ResourceIdsJobRepository jobRepository;
  private final ResourceIdsJobMapper resourceIdsJobMapper;
  private final ResourceIdServiceDecorator resourceIdService;

  public ResourceIdsJob getJobById(String id) {
    return resourceIdsJobMapper.convert(jobRepository.getReferenceById(id));
  }

  public ResourceIdsJob createStreamJob(ResourceIdsJob job, String tenantId) {
    log.debug("createStreamJob:: by [job: {}, tenantId: {}]", job, tenantId);
    var entity = resourceIdsJobMapper.convert(job);
    entity.setCreatedDate(new Date());
    entity.setStatus(StreamJobStatus.IN_PROGRESS);
    entity.setTemporaryTableName(generateTemporaryTableName());

    log.info("Attempts to create streamJob by [resourceIdsJob: {}]", entity);
    var savedJob = jobRepository.save(entity);

    Runnable asyncJob = () -> resourceIdService.streamResourceIdsForJob(savedJob, tenantId);
    tenantExecutionService.executeAsyncTenantScoped(tenantId, asyncJob);
    return resourceIdsJobMapper.convert(savedJob);
  }

  private String generateTemporaryTableName() {
    return RandomStringUtils
      .random(32, 0, 0, true, false, null, new SecureRandom())
      .toLowerCase();
  }
}
