package io.arex.storage.model.mocker.impl;

import io.arex.storage.model.annotations.FieldCompression;
import io.arex.storage.model.mocker.AbstractMocker;
import io.arex.storage.model.mocker.ConfigVersion;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * @author jmo
 * @since 2021/11/2
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ConfigFileMocker extends AbstractMocker implements ConfigVersion {
    private String key;
    private Integer recordVersion;
    private Long fileVersion;
    @FieldCompression
    private String content;
}