// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.flink.autoci.e2e;

import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import org.apache.doris.flink.autoci.AbstractAutoCITestBase;
import org.apache.doris.flink.autoci.container.ContainerService;
import org.apache.doris.flink.autoci.container.MySQLContainerService;
import org.apache.doris.flink.exception.DorisRuntimeException;
import org.apache.doris.flink.tools.cdc.CdcTools;
import org.apache.doris.flink.tools.cdc.DatabaseSyncConfig;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractE2EService extends AbstractAutoCITestBase {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractE2EService.class);
    private static ContainerService mysqlContainerService;
    private static final CustomerSingleThreadExecutor singleThreadExecutor =
            new CustomerSingleThreadExecutor();
    protected static final String SINK_CONF = "--" + DatabaseSyncConfig.SINK_CONF;
    protected static final String DORIS_DATABASE = "--database";
    protected static final String HOSTNAME = "hostname";
    protected static final String PORT = "port";
    protected static final String USERNAME = "username";
    protected static final String PASSWORD = "password";
    protected static final String DATABASE_NAME = "database-name";
    protected static final String FENODES = "fenodes";
    protected static final String JDBC_URL = "jdbc-url";
    protected static final String SINK_LABEL_PREFIX = "sink.label-prefix";

    @BeforeClass
    public static void initE2EContainers() {
        LOG.info("Trying to Start init E2E containers.");
        initMySQLContainer();
    }

    private static void initMySQLContainer() {
        if (Objects.nonNull(mysqlContainerService)) {
            LOG.info("The MySQL container has been started and will be used directly.");
            return;
        }
        mysqlContainerService = new MySQLContainerService();
        mysqlContainerService.startContainer();
        LOG.info("Mysql container was started.");
    }

    protected String getMySQLInstanceHost() {
        return mysqlContainerService.getInstanceHost();
    }

    protected Integer getMySQLQueryPort() {
        return mysqlContainerService.getMappedPort(3306);
    }

    protected String getMySQLUsername() {
        return mysqlContainerService.getUsername();
    }

    protected String getMySQLPassword() {
        return mysqlContainerService.getPassword();
    }

    protected Connection getMySQLQueryConnection() {
        return mysqlContainerService.getQueryConnection();
    }

    protected void submitE2EJob(String jobName, String[] args) {
        singleThreadExecutor.submitJob(
                jobName,
                () -> {
                    try {
                        LOG.info("{} e2e job will submit to start.", jobName);
                        CdcTools.setStreamExecutionEnvironmentForTesting(configFlinkEnvironment());
                        CdcTools.main(args);
                        Thread.sleep(10000);
                    } catch (Exception e) {
                        throw new DorisRuntimeException(e);
                    }
                });
    }

    protected void cancelCurrentE2EJob(String jobName) {
        LOG.info("{} e2e job will cancel", jobName);
        singleThreadExecutor.cancelCurrentJob(jobName);
    }

    private StreamExecutionEnvironment configFlinkEnvironment() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        Map<String, String> flinkMap = new HashMap<>();
        flinkMap.put("execution.checkpointing.interval", "10s");
        flinkMap.put("pipeline.operator-chaining", "false");
        flinkMap.put("parallelism.default", "1");
        Configuration configuration = Configuration.fromMap(flinkMap);
        env.configure(configuration);
        env.setRestartStrategy(RestartStrategies.noRestart());
        return env;
    }

    protected void setSinkConfDefaultConfig(List<String> argList) {
        // set default doris sink config
        argList.add(SINK_CONF);
        argList.add(FENODES + "=" + getFenodes());
        argList.add(SINK_CONF);
        argList.add(USERNAME + "=" + getDorisUsername());
        argList.add(SINK_CONF);
        argList.add(PASSWORD + "=" + getDorisPassword());
        argList.add(SINK_CONF);
        argList.add(FENODES + "=" + getFenodes());
        argList.add(SINK_CONF);
        argList.add(JDBC_URL + "=" + getDorisQueryUrl());
        argList.add(SINK_CONF);
        argList.add(SINK_LABEL_PREFIX + "=" + "label");
    }

    public static void closeE2EContainers() {
        LOG.info("Starting to close E2E containers.");
        closeMySQLContainer();
    }

    private static void closeMySQLContainer() {
        if (Objects.isNull(mysqlContainerService)) {
            return;
        }
        singleThreadExecutor.shutdown();
        mysqlContainerService.close();
        LOG.info("Mysql container was closed.");
    }
}
