package io.arex.storage.core.service;

import io.arex.storage.model.mocker.ConfigVersion;
import io.arex.storage.model.mocker.MockItem;
import io.arex.storage.model.enums.MockCategoryType;
import io.arex.storage.core.mock.MockResultProvider;
import io.arex.storage.core.repository.RepositoryProvider;
import io.arex.storage.core.repository.RepositoryProviderFactory;
import io.arex.storage.core.repository.RepositoryReader;
import io.arex.storage.core.serialization.ZstdJacksonSerializer;
import io.arex.storage.model.mocker.impl.ConfigVersionMocker;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.validation.constraints.NotNull;

/**
 * The agent working should be complete two things:
 * save the origin record and fetch the record result for mocked source when replaying
 *
 * @author jmo
 * @since 2021/11/11
 */
@Slf4j
@Service
public final class AgentWorkingService {
    @Resource
    private MockResultProvider mockResultProvider;
    @Resource
    private RepositoryProviderFactory repositoryProviderFactory;
    @Resource
    private ZstdJacksonSerializer zstdJacksonSerializer;
    @Resource
    private PrepareMockResultService prepareMockResultService;
    @Resource
    private RecordReplayMappingBuilder recordReplayMappingBuilder;

    /**
     * requested from AREX's agent hits to recording, we direct save to repository for next replay using
     *
     * @param category the resource type of requested
     * @param item     the instance of T
     * @param <T>      class type
     * @return true means success,else save failure
     */
    public <T extends MockItem> boolean saveRecord(@NotNull MockCategoryType category, @NotNull T item) {
        RepositoryProvider<T> repositoryWriter = repositoryProviderFactory.findProvider(category);
        return repositoryWriter != null && repositoryWriter.save(item);
    }

    /**
     * requested from AREX's agent replaying which should be fetch the mocked resource of dependency.
     * NOTE:
     * This is sequence query from the cached result.
     * if requested times overhead the size of the resource, return the last sequence item.
     * if the requested should be compared by scheduler,we put it to cache for performance goal.
     *
     * @param category   the resource type of requested
     * @param recordItem from AREX's recording
     * @return compress bytes with zstd from the cached which filled by scheduler's preload
     */
    public <T extends MockItem> byte[] queryMockResult(@NotNull MockCategoryType category, @NotNull T recordItem) {
        mockResultProvider.putReplayResult(category, recordItem.getReplayId(), recordItem);
        String recordId = recordItem.getRecordId();
        String replayId = recordItem.getReplayId();
        if (category.isMainEntry()) {
            LOGGER.info("skip main entry mock response,record id:{},replay id:{}", recordId, replayId);
            if (category == MockCategoryType.QMQ_CONSUMER) {
                recordReplayMappingBuilder.putLastReplayResultId(category, recordId, replayId);
            }
            return zstdJacksonSerializer.serialize(recordItem);
        }
        byte[] result = mockResultProvider.getRecordResult(category, recordItem);
        if (result == null) {
            LOGGER.info("fetch replay mock record empty from cache,record id:{},replay id:{}", recordId, replayId);
            boolean reloadResult = prepareMockResultService.preload(category, recordId);
            if (reloadResult) {
                result = mockResultProvider.getRecordResult(category, recordItem);
            }
            if (result == null) {
                LOGGER.info("reload fetch replay mock record empty from cache,record id:{},replay id:{}, " +
                                "reloadResult:{}",
                        recordId,
                        replayId, reloadResult);
                return ZstdJacksonSerializer.EMPTY_INSTANCE;
            }
        }
        LOGGER.info("agent query found result for category:{},record id: {},replay id: {}", category.getDisplayName(),
                recordId, replayId);
        return result;
    }

    public byte[] queryConfigVersion(MockCategoryType category, ConfigVersion version) {
        RepositoryReader<?> repositoryReader = repositoryProviderFactory.findProvider(category);
        Object value = null;
        if (repositoryReader != null) {
            value = repositoryReader.queryByVersion(version);
        }
        return zstdJacksonSerializer.serialize(value);
    }

    /**
     * build a key for all config files before agent recording,then save any config resources with the key, after
     * that, it used to replay restore as part of mock dependency.
     *
     * @param application the request app
     * @return version object of ConfigVersionMocker
     * @see ConfigVersionMocker
     */
    public byte[] queryConfigVersionKey(ConfigVersion application) {
        if (StringUtils.isEmpty(application.getAppId())) {
            LOGGER.warn("The appId is empty from request application");
            return ZstdJacksonSerializer.EMPTY_INSTANCE;
        }
        return queryConfigVersion(MockCategoryType.CONFIG_VERSION, application);
    }
}
