/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dromara.dynamictp.adapter.common;

import com.github.dadiyang.equator.Equator;
import com.github.dadiyang.equator.FieldInfo;
import com.github.dadiyang.equator.GetterBaseEquator;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.dromara.dynamictp.common.entity.NotifyPlatform;
import org.dromara.dynamictp.common.entity.ThreadPoolStats;
import org.dromara.dynamictp.common.entity.TpExecutorProps;
import org.dromara.dynamictp.common.entity.TpMainFields;
import org.dromara.dynamictp.common.properties.DtpProperties;
import org.dromara.dynamictp.common.spring.ApplicationContextHolder;
import org.dromara.dynamictp.common.spring.OnceApplicationContextEventListener;
import org.dromara.dynamictp.common.util.ReflectionUtil;
import org.dromara.dynamictp.common.util.StreamUtil;
import org.dromara.dynamictp.core.aware.AwareManager;
import org.dromara.dynamictp.core.converter.ExecutorConverter;
import org.dromara.dynamictp.core.notifier.manager.AlarmManager;
import org.dromara.dynamictp.core.notifier.manager.NoticeManager;
import org.dromara.dynamictp.core.support.ExecutorAdapter;
import org.dromara.dynamictp.core.support.ExecutorWrapper;
import org.dromara.dynamictp.core.support.ThreadPoolExecutorProxy;
import org.springframework.context.event.ContextRefreshedEvent;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.util.stream.Collectors.toList;
import static org.dromara.dynamictp.common.constant.DynamicTpConst.PROPERTIES_CHANGE_SHOW_STYLE;
import static org.dromara.dynamictp.core.notifier.manager.NotifyHelper.updateNotifyInfo;

/**
 * AbstractDtpAdapter related
 *
 * @author yanhom
 * @author dragon-zhang
 * @since 1.0.6
 */
@Slf4j
public abstract class AbstractDtpAdapter extends OnceApplicationContextEventListener implements DtpAdapter {

    private static final Equator EQUATOR = new GetterBaseEquator();

    protected final Map<String, ExecutorWrapper> executors = Maps.newHashMap();

    @Override
    protected void onContextRefreshedEvent(ContextRefreshedEvent event) {
        try {
            DtpProperties dtpProperties = ApplicationContextHolder.getBean(DtpProperties.class);
            initialize();
            afterInitialize();
            refresh(dtpProperties);
        } catch (Throwable e) {
            log.error("Initialize [{}] thread pool failed.", getAdapterPrefix(), e);
        }
    }

    protected void initialize() {
    }

    protected void afterInitialize() {
        getExecutorWrappers().forEach((k, v) -> AwareManager.register(v));
    }

    @Override
    public Map<String, ExecutorWrapper> getExecutorWrappers() {
        return executors;
    }

    /**
     * Get multi thread pool stats.
     *
     * @return thead pools stats
     */
    @Override
    public List<ThreadPoolStats> getMultiPoolStats() {
        val executorWrappers = getExecutorWrappers();
        if (MapUtils.isEmpty(executorWrappers)) {
            return Collections.emptyList();
        }

        List<ThreadPoolStats> threadPoolStats = Lists.newArrayList();
        executorWrappers.forEach((k, v) -> threadPoolStats.add(ExecutorConverter.toMetrics(v)));
        return threadPoolStats;
    }

    public void initNotifyItems(String poolName, ExecutorWrapper executorWrapper) {
        AlarmManager.initAlarm(poolName, executorWrapper.getNotifyItems());
    }

    public void refresh(List<TpExecutorProps> propsList, List<NotifyPlatform> platforms) {
        val executorWrappers = getExecutorWrappers();
        if (CollectionUtils.isEmpty(propsList) || MapUtils.isEmpty(executorWrappers)) {
            return;
        }

        val tmpMap = StreamUtil.toMap(propsList, TpExecutorProps::getThreadPoolName);
        executorWrappers.forEach((k, v) -> refresh(v, platforms, tmpMap.get(k)));
    }

    public void refresh(ExecutorWrapper executorWrapper, List<NotifyPlatform> platforms, TpExecutorProps props) {

        if (Objects.isNull(props) || Objects.isNull(executorWrapper) || containsInvalidParams(props, log)) {
            return;
        }

        TpMainFields oldFields = getTpMainFields(executorWrapper, props);
        doRefresh(executorWrapper, platforms, props);
        TpMainFields newFields = getTpMainFields(executorWrapper, props);
        if (oldFields.equals(newFields)) {
            log.debug("DynamicTp adapter refresh, main properties of [{}] have not changed.",
                    executorWrapper.getThreadPoolName());
            return;
        }

        List<FieldInfo> diffFields = EQUATOR.getDiffFields(oldFields, newFields);
        List<String> diffKeys = diffFields.stream().map(FieldInfo::getFieldName).collect(toList());
        NoticeManager.doNoticeAsync(executorWrapper, oldFields, diffKeys);
        log.info("DynamicTp {} adapter, [{}] refreshed end, changed keys: {}, corePoolSize: [{}], "
                        + "maxPoolSize: [{}], keepAliveTime: [{}]",
                getAdapterPrefix(), executorWrapper.getThreadPoolName(), diffKeys,
                String.format(PROPERTIES_CHANGE_SHOW_STYLE, oldFields.getCorePoolSize(), newFields.getCorePoolSize()),
                String.format(PROPERTIES_CHANGE_SHOW_STYLE, oldFields.getMaxPoolSize(), newFields.getMaxPoolSize()),
                String.format(PROPERTIES_CHANGE_SHOW_STYLE, oldFields.getKeepAliveTime(), newFields.getKeepAliveTime()));
    }

    protected TpMainFields getTpMainFields(ExecutorWrapper executorWrapper, TpExecutorProps props) {
        return ExecutorConverter.toMainFields(executorWrapper);
    }

    /**
     * Get adapter prefix.
     * @return adapter prefix
     */
    protected abstract String getAdapterPrefix();

    protected void enhanceOriginExecutor(ExecutorWrapper wrapper, String fieldName, Object targetObj) {
        ThreadPoolExecutorProxy threadPoolExecutorProxy = new ThreadPoolExecutorProxy(wrapper);
        try {
            ReflectionUtil.setFieldValue(fieldName, targetObj, threadPoolExecutorProxy);
        } catch (IllegalAccessException e) {
            log.error("DynamicTp {} adapter, enhance origin executor failed.", getAdapterPrefix(), e);
        }
    }

    protected void doRefresh(ExecutorWrapper executorWrapper,
                             List<NotifyPlatform> platforms,
                             TpExecutorProps props) {

        val executor = executorWrapper.getExecutor();
        doRefreshPoolSize(executor, props);
        if (!Objects.equals(executor.getKeepAliveTime(props.getUnit()), props.getKeepAliveTime())) {
            executor.setKeepAliveTime(props.getKeepAliveTime(), props.getUnit());
        }
        if (StringUtils.isNotBlank(props.getThreadPoolAliasName())) {
            executorWrapper.setThreadPoolAliasName(props.getThreadPoolAliasName());
        }

        // update notify items
        updateNotifyInfo(executorWrapper, props, platforms);
        // update aware related
        AwareManager.refresh(executorWrapper, props);
    }

    private void doRefreshPoolSize(ExecutorAdapter<?> executor, TpExecutorProps props) {
        if (props.getMaximumPoolSize() >= executor.getMaximumPoolSize()) {
            if (!Objects.equals(props.getMaximumPoolSize(), executor.getMaximumPoolSize())) {
                executor.setMaximumPoolSize(props.getMaximumPoolSize());
            }
            if (!Objects.equals(props.getCorePoolSize(), executor.getCorePoolSize())) {
                executor.setCorePoolSize(props.getCorePoolSize());
            }
            return;
        }

        if (!Objects.equals(props.getCorePoolSize(), executor.getCorePoolSize())) {
            executor.setCorePoolSize(props.getCorePoolSize());
        }
        if (!Objects.equals(props.getMaximumPoolSize(), executor.getMaximumPoolSize())) {
            executor.setMaximumPoolSize(props.getMaximumPoolSize());
        }
    }
}
